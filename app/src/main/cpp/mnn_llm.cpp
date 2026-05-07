#include <jni.h>
#include <string>
#include <sstream>
#include <chrono>
#include <atomic>
#include <exception>
#include <android/log.h>

#include "llm/llm.hpp"

#define TAG "MNNLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Per-session metrics stored alongside the Llm handle.
struct LlmSession {
    MNN::Transformer::Llm* llm = nullptr;
    int64_t prefill_ms = 0;
    int64_t decode_ms = 0;
    int     prompt_tokens = 0;
    int     generated_tokens = 0;
    std::string loaded_model_name;
    std::string last_error;
    // Atomic flag: set to true by nativeStop() to interrupt streaming generation.
    std::atomic<bool> stop_flag{false};
    // Callback for streaming token output
    jobject javaCallback = nullptr;
    jmethodID onTokenMethod = nullptr;
};

inline LlmSession* toSession(jlong handle) {
    return reinterpret_cast<LlmSession*>(static_cast<intptr_t>(handle));
}

// Global singleton session for simple API (LlamaEngine.kt)
std::atomic<LlmSession*> gSession{nullptr};
std::atomic<bool> gModelLoaded{false};

} // anonymous namespace

// Custom streambuf that fires a Java TokenCallback.onToken(String) for each chunk
// written by MNN's response() — enabling token-by-token streaming from C++ to Kotlin.
class CallbackStreambuf : public std::streambuf {
public:
    CallbackStreambuf(JNIEnv* e, jobject cb, jmethodID mid, std::atomic<bool>& stopFlag)
        : mEnv(e), mCallback(cb), mMethod(mid), mStopFlag(stopFlag) {}

protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        if (mStopFlag.load(std::memory_order_relaxed)) return 0;
        std::string token(s, static_cast<size_t>(n));
        jstring js = mEnv->NewStringUTF(token.c_str());
        if (js) {
            mEnv->CallVoidMethod(mCallback, mMethod, js);
            mEnv->DeleteLocalRef(js);
        }
        return n;
    }

    int overflow(int c) override {
        if (mStopFlag.load(std::memory_order_relaxed)) return EOF;
        if (c != EOF) {
            char ch = static_cast<char>(c);
            std::string token(&ch, 1);
            jstring js = mEnv->NewStringUTF(token.c_str());
            if (js) {
                mEnv->CallVoidMethod(mCallback, mMethod, js);
                mEnv->DeleteLocalRef(js);
            }
        }
        return c;
    }

private:
    JNIEnv* mEnv;
    jobject mCallback;
    jmethodID mMethod;
    std::atomic<bool>& mStopFlag;
};

extern "C" {

// ==================== LlamaEngine.kt Native Methods ====================

// Initialize native layer
JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeInitNative(JNIEnv* env, jclass) {
    LOGI("nativeInitNative: initializing");
    // Ensure cache directory exists
    return JNI_TRUE;
}

// Load model - matches: external fun nativeLoadModel(configPath: String, nCtx: Int, nThreads: Int): Boolean
JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeLoadModel(JNIEnv* env, jclass, 
                                                            jstring jConfigPath, 
                                                            jint nCtx, 
                                                            jint nThreads) {
    const char* path = env->GetStringUTFChars(jConfigPath, nullptr);
    if (!path) {
        LOGE("nativeLoadModel: null config path");
        return JNI_FALSE;
    }
    
    std::string configPath(path);
    env->ReleaseStringUTFChars(jConfigPath, path);

    LOGI("nativeLoadModel: %s, nCtx=%d, nThreads=%d", configPath.c_str(), nCtx, nThreads);
    
    // Create LLM instance
    MNN::Transformer::Llm* llm = MNN::Transformer::Llm::createLLM(configPath);
    if (!llm) {
        LOGE("nativeLoadModel: createLLM returned null");
        return JNI_FALSE;
    }
    
    // Set config via JSON
    std::string configJson = "{\"num_ctx\":" + std::to_string(nCtx) + ",\"num_threads\":" + std::to_string(nThreads) + "}";
    llm->set_config(configJson);
    
    // Load the model
    auto t0 = std::chrono::steady_clock::now();
    bool ok = llm->load();
    auto t1 = std::chrono::steady_clock::now();
    int64_t ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    
    if (ok) {
        // Create session
        auto* session = new LlmSession{llm};
        gSession.store(session, std::memory_order_relaxed);
        gModelLoaded.store(true, std::memory_order_relaxed);
        
        // Extract model name from path
        size_t lastSlash = configPath.find_last_of("/\\");
        std::string modelName = (lastSlash != std::string::npos) ? 
            configPath.substr(lastSlash + 1) : configPath;
        // Remove file extension if present
        size_t dotPos = modelName.find(".json");
        if (dotPos != std::string::npos) {
            modelName = modelName.substr(0, dotPos);
        }
        session->loaded_model_name = modelName;
        
        LOGI("nativeLoadModel: success in %lld ms, model=%s", (long long)ms, modelName.c_str());
        return JNI_TRUE;
    } else {
        MNN::Transformer::Llm::destroy(llm);
        LOGE("nativeLoadModel: load failed after %lld ms", (long long)ms);
        return JNI_FALSE;
    }
}

// Unload model - matches: external fun nativeUnloadModel()
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeUnloadModel(JNIEnv*, jclass) {
    LOGI("nativeUnloadModel");
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s) {
        if (s->llm) {
            MNN::Transformer::Llm::destroy(s->llm);
        }
        if (s->javaCallback) {
            // Already deleted via DeleteLocalRef in native code
            s->javaCallback = nullptr;
        }
        delete s;
        gSession.store(nullptr, std::memory_order_relaxed);
    }
    gModelLoaded.store(false, std::memory_order_relaxed);
}

// Check if model is loaded - matches: external fun nativeIsModelLoaded(): Boolean
JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeIsModelLoaded(JNIEnv*, jclass) {
    return gModelLoaded.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}

// Generate text (sync) - matches: external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float, topK: Int, topP: Float): String
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGenerate(JNIEnv* env, jclass,
                                                          jstring jPrompt,
                                                          jint maxTokens,
                                                          jfloat temperature,
                                                          jint topK,
                                                          jfloat topP) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s || !s->llm) {
        LOGE("nativeGenerate: model not loaded");
        return env->NewStringUTF("");
    }

    const char* promptCStr = env->GetStringUTFChars(jPrompt, nullptr);
    if (!promptCStr) {
        LOGE("nativeGenerate: null prompt");
        return env->NewStringUTF("");
    }
    std::string prompt(promptCStr);
    env->ReleaseStringUTFChars(jPrompt, promptCStr);

    // Set sampling config
    std::string configJson = "{";
    configJson += "\"temperature\":" + std::to_string(temperature) + ",";
    configJson += "\"top_k\":" + std::to_string(topK) + ",";
    configJson += "\"top_p\":" + std::to_string(topP);
    configJson += "}";
    s->llm->set_config(configJson);

    LOGI("nativeGenerate: prompt len=%zu, maxTokens=%d", prompt.length(), maxTokens);
    
    std::ostringstream oss;
    auto t0 = std::chrono::steady_clock::now();
    s->llm->response(prompt, &oss, nullptr, static_cast<int>(maxTokens));
    auto t1 = std::chrono::steady_clock::now();
    
    int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    std::string result = oss.str();
    
    // Estimate tokens
    s->generated_tokens = result.length() / 4;
    s->prefill_ms = total_ms * 30 / 100;
    s->decode_ms = total_ms * 70 / 100;
    
    LOGI("nativeGenerate: completed in %lld ms, result len=%zu", (long long)total_ms, result.length());
    
    return env->NewStringUTF(result.c_str());
}

// Generate text (streaming) - matches: external fun nativeGenerateStream(prompt: String, maxTokens: Int, temperature: Float, topK: Int, topP: Float): String
// Note: Returns concatenated tokens separated by newline
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGenerateStream(JNIEnv* env, jclass,
                                                                  jstring jPrompt,
                                                                  jint maxTokens,
                                                                  jfloat temperature,
                                                                  jint topK,
                                                                  jfloat topP) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s || !s->llm) {
        LOGE("nativeGenerateStream: model not loaded");
        return env->NewStringUTF("");
    }

    const char* promptCStr = env->GetStringUTFChars(jPrompt, nullptr);
    if (!promptCStr) {
        LOGE("nativeGenerateStream: null prompt");
        return env->NewStringUTF("");
    }
    std::string prompt(promptCStr);
    env->ReleaseStringUTFChars(jPrompt, promptCStr);

    // Set sampling config
    std::string configJson = "{";
    configJson += "\"temperature\":" + std::to_string(temperature) + ",";
    configJson += "\"top_k\":" + std::to_string(topK) + ",";
    configJson += "\"top_p\":" + std::to_string(topP);
    configJson += "}";
    s->llm->set_config(configJson);

    LOGI("nativeGenerateStream: prompt len=%zu, maxTokens=%d", prompt.length(), maxTokens);
    
    // For streaming, we collect tokens and join with newlines
    std::vector<std::string> tokens;
    auto t0 = std::chrono::steady_clock::now();
    
    // Simple streaming: collect result as string with token boundaries marked
    std::ostringstream oss;
    s->llm->response(prompt, &oss, nullptr, static_cast<int>(maxTokens));
    
    auto t1 = std::chrono::steady_clock::now();
    int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    std::string result = oss.str();
    
    // Split result into "tokens" (simplified - real implementation would track actual token boundaries)
    // For now, return the full result - Kotlin layer will handle streaming display
    s->generated_tokens = result.length() / 4;
    s->prefill_ms = total_ms * 30 / 100;
    s->decode_ms = total_ms * 70 / 100;
    
    LOGI("nativeGenerateStream: completed in %lld ms, result len=%zu", (long long)total_ms, result.length());
    
    return env->NewStringUTF(result.c_str());
}

// Get loaded model name - matches: external fun nativeGetLoadedModelName(): String
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetLoadedModelName(JNIEnv* env, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s) {
        return env->NewStringUTF(s->loaded_model_name.c_str());
    }
    return env->NewStringUTF("");
}

// Get context size - matches: external fun nativeGetContextSize(): Int
JNIEXPORT jint JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetContextSize(JNIEnv*, jclass) {
    // Default context size, actual may vary by model
    return 2048;
}

// Get memory usage - matches: external fun nativeGetMemoryUsage(): Long
JNIEXPORT jlong JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetMemoryUsage(JNIEnv*, jclass) {
    // Memory tracking not directly available, return 0
    return 0;
}

// Set system prompt - matches: external fun nativeSetSystemPrompt(systemPrompt: String): Boolean
JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeSetSystemPrompt(JNIEnv* env, jclass, jstring jSystemPrompt) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s || !s->llm) {
        return JNI_FALSE;
    }
    
    const char* sysPrompt = env->GetStringUTFChars(jSystemPrompt, nullptr);
    if (!sysPrompt) {
        return JNI_FALSE;
    }
    
    std::string configJson = "{\"system_prompt\":\"" + std::string(sysPrompt) + "\"}";
    env->ReleaseStringUTFChars(jSystemPrompt, sysPrompt);
    
    bool ok = s->llm->set_config(configJson);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Reset conversation - matches: external fun nativeResetConversation()
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeResetConversation(JNIEnv*, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s && s->llm) {
        LOGI("nativeResetConversation: resetting");
        s->llm->reset();
    }
}

// Get last error - matches: external fun nativeGetLastError(): String
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetLastError(JNIEnv* env, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s) {
        return env->NewStringUTF(s->last_error.c_str());
    }
    return env->NewStringUTF("");
}

// Init native callback (instance method) - matches: private external fun initNativeCallback(callback: TokenCallback?)
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_initNativeCallback(JNIEnv* env, jobject thiz, jobject callback) {
    LOGI("initNativeCallback: setting up callback");
    
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s) {
        LOGE("initNativeCallback: no session");
        return;
    }
    
    // Delete old global ref if exists
    if (s->javaCallback) {
        env->DeleteGlobalRef(s->javaCallback);
        s->javaCallback = nullptr;
    }
    
    if (callback) {
        s->javaCallback = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        s->onTokenMethod = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
        
        if (!s->onTokenMethod) {
            LOGE("initNativeCallback: could not find onToken method");
            env->DeleteGlobalRef(s->javaCallback);
            s->javaCallback = nullptr;
        }
    }
}

} // extern "C"
