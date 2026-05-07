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
    // Atomic flag: set to true by nativeStop() to interrupt streaming generation.
    std::atomic<bool> stop_flag{false};
    // Callback for streaming token output
    jobject javaCallback = nullptr;
    jmethodID onTokenMethod = nullptr;
};

inline LlmSession* toSession(jlong handle) {
    return reinterpret_cast<LlmSession*>(static_cast<intptr_t>(handle));
}

} // anonymous namespace

// Custom streambuf that fires a Java TokenCallback.onToken(String) for each chunk
// written by MNN's response() — enabling token-by-token streaming from C++ to Kotlin.
class CallbackStreambuf : public std::streambuf {
public:
    CallbackStreambuf(JNIEnv* e, jobject cb, jmethodID mid, std::atomic<bool>& stopFlag)
        : mEnv(e), mCallback(cb), mMethod(mid), mStopFlag(stopFlag) {}

protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        // Return 0 to signal the ostream is done; MNN will observe !os->good() and stop.
        if (mStopFlag.load(std::memory_order_relaxed)) return 0;
        // MNN writes one decoded token per xsputn call; copy to get null-termination.
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

// Create an LLM from a llm_config.json path. Returns 0 on failure.
// JNI function name matches: com.localai.server.engine.LlamaEngineMnn35
JNIEXPORT jlong JNICALL
Java_com_localai_server_engine_LlamaEngineMnn35_nativeCreate(JNIEnv* env, jclass /*cls*/, jstring jConfigPath) {
    const char* path = env->GetStringUTFChars(jConfigPath, nullptr);
    if (!path) {
        LOGE("createLLM: null config path");
        return 0;
    }
    
    std::string configPath(path);
    env->ReleaseStringUTFChars(jConfigPath, path);

    LOGI("nativeCreate: %s", configPath.c_str());
    
    MNN::Transformer::Llm* llm = MNN::Transformer::Llm::createLLM(configPath);
    if (!llm) {
        LOGE("createLLM returned null for path: %s", configPath.c_str());
        return 0;
    }

    auto* session = new LlmSession{llm};
    LOGI("nativeCreate: success, session=%p", session);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

// Load (initialise) the model. Returns true on success.
JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngineMnn35_nativeLoad(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    LlmSession* s = toSession(handle);
    if (!s) {
        LOGE("nativeLoad: invalid handle");
        return JNI_FALSE;
    }
    if (!s->llm) {
        LOGE("nativeLoad: llm is null");
        return JNI_FALSE;
    }

    LOGI("nativeLoad: Loading LLM model...");
    auto t0 = std::chrono::steady_clock::now();
    bool ok = s->llm->load();
    auto t1 = std::chrono::steady_clock::now();
    int64_t ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("nativeLoad: finished in %lld ms, result=%d", (long long)ms, ok);
    
    if (ok) {
        // Get context info after successful load
        auto ctx = s->llm->getContext();
        if (ctx) {
            LOGI("nativeLoad: context status=%d", (int)ctx->status);
        }
    }
    
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Run inference and return the generated text.
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngineMnn35_nativeResponse(JNIEnv* env, jclass /*cls*/,
                                        jlong handle, jstring jPrompt, jint maxNewTokens,
                                        jstring jStopString) {
    LlmSession* s = toSession(handle);
    if (!s || !s->llm) {
        LOGE("nativeResponse: invalid session or llm");
        return env->NewStringUTF("");
    }

    const char* promptCStr = env->GetStringUTFChars(jPrompt, nullptr);
    if (!promptCStr) {
        LOGE("nativeResponse: null prompt");
        return env->NewStringUTF("");
    }
    std::string prompt(promptCStr);
    env->ReleaseStringUTFChars(jPrompt, promptCStr);

    // Resolve optional stop string (null = rely on model's own EOS token)
    const char* stopStr = nullptr;
    std::string stopStringStorage;
    if (jStopString != nullptr) {
        const char* s2 = env->GetStringUTFChars(jStopString, nullptr);
        if (s2) {
            stopStringStorage = s2;
            env->ReleaseStringUTFChars(jStopString, s2);
            stopStr = stopStringStorage.c_str();
        }
    }

    LOGI("nativeResponse: prompt len=%zu, maxTokens=%d", prompt.length(), maxNewTokens);
    
    std::ostringstream oss;
    auto t0 = std::chrono::steady_clock::now();
    
    // Check context status before inference
    auto ctx = s->llm->getContext();
    if (ctx) {
        LOGI("nativeResponse: context status before=%d", (int)ctx->status);
    }
    
    // Call MNN response
    s->llm->response(prompt, &oss, stopStr, maxNewTokens);
    
    auto t1 = std::chrono::steady_clock::now();
    int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

    std::string result = oss.str();
    
    // Count generated tokens
    s->generated_tokens = result.length() / 4; // Rough estimate for Chinese
    s->prefill_ms = total_ms * 30 / 100;
    s->decode_ms = total_ms * 70 / 100;

    LOGI("nativeResponse: completed in %lld ms, result len=%zu, estimated tokens=%d", 
         (long long)total_ms, result.length(), s->generated_tokens);

    return env->NewStringUTF(result.c_str());
}

// Reset conversation context (clears KV cache).
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngineMnn35_nativeReset(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    LlmSession* s = toSession(handle);
    if (s && s->llm) {
        LOGI("nativeReset: resetting conversation");
        s->llm->reset();
    }
}

// Merge a JSON string into the model config (e.g. set jinja context for thinking mode).
// Example: nativeSetConfig(handle, "{\"num_threads\":4}")
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngineMnn35_nativeSetConfig(JNIEnv* env, jclass /*cls*/, jlong handle,
                                         jstring jConfigJson) {
    LlmSession* s = toSession(handle);
    if (!s || !s->llm) return;
    
    const char* cfg = env->GetStringUTFChars(jConfigJson, nullptr);
    if (!cfg) return;
    
    LOGI("nativeSetConfig: %s", cfg);
    bool ok = s->llm->set_config(std::string(cfg));
    LOGI("nativeSetConfig: result=%d", ok);
    
    env->ReleaseStringUTFChars(jConfigJson, cfg);
}

// Destroy the LLM and free memory.
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngineMnn35_nativeDestroy(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    LlmSession* s = toSession(handle);
    if (!s) return;
    
    LOGI("nativeDestroy: destroying session");
    
    if (s->llm) {
        MNN::Transformer::Llm::destroy(s->llm);
        s->llm = nullptr;
    }
    delete s;
    LOGI("nativeDestroy: done");
}

// ---- Metric accessors ----
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngineMnn35_nativeSetPromptTokens(JNIEnv*, jclass, jlong handle, jint count) {
    LlmSession* s = toSession(handle);
    if (s) {
        s->prompt_tokens = static_cast<int>(count);
        LOGI("setPromptTokens: %d", count);
    }
}

JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngineMnn35_nativeSetGeneratedTokens(JNIEnv*, jclass, jlong handle, jint count) {
    LlmSession* s = toSession(handle);
    if (s) {
        s->generated_tokens = static_cast<int>(count);
        LOGI("setGeneratedTokens: %d", count);
    }
}

JNIEXPORT jint JNICALL
Java_com_localai_server_engine_LlamaEngineMnn35_nativeCountTokens(JNIEnv* env, jclass, jlong handle, jstring jText) {
    LlmSession* s = toSession(handle);
    if (!s || !s->llm || jText == nullptr) return -1;

    const char* textCStr = env->GetStringUTFChars(jText, nullptr);
    if (!textCStr) return -1;
    
    std::string text(textCStr);
    env->ReleaseStringUTFChars(jText, textCStr);

    try {
        auto ids = s->llm->tokenizer_encode(text);
        int count = static_cast<jint>(ids.size());
        LOGI("nativeCountTokens: text len=%zu, tokens=%d", text.length(), count);
        return count;
    } catch (const std::exception& e) {
        LOGE("nativeCountTokens exception: %s", e.what());
        return -1;
    } catch (...) {
        LOGE("nativeCountTokens unknown exception");
        return -1;
    }
}

JNIEXPORT jlong JNICALL
Java_com_localai_server_engine_LlamaEngineMnn35_nativeGetPrefillMs(JNIEnv*, jclass, jlong handle) {
    LlmSession* s = toSession(handle);
    return s ? static_cast<jlong>(s->prefill_ms) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_localai_server_engine_LlamaEngineMnn35_nativeGetDecodeMs(JNIEnv*, jclass, jlong handle) {
    LlmSession* s = toSession(handle);
    return s ? static_cast<jlong>(s->decode_ms) : 0;
}

JNIEXPORT jint JNICALL
Java_com_localai_server_engine_LlamaEngineMnn35_nativeGetPromptTokens(JNIEnv*, jclass, jlong handle) {
    LlmSession* s = toSession(handle);
    return s ? static_cast<jint>(s->prompt_tokens) : 0;
}

JNIEXPORT jint JNICALL
Java_com_localai_server_engine_LlamaEngineMnn35_nativeGetGeneratedTokens(JNIEnv*, jclass, jlong handle) {
    LlmSession* s = toSession(handle);
    return s ? static_cast<jint>(s->generated_tokens) : 0;
}

// Streaming inference: calls callback.onToken(String) for each decoded chunk.
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngineMnn35_nativeResponseStreaming(JNIEnv* env, jclass /*cls*/,
                                                  jlong handle, jstring jPrompt,
                                                  jint maxNewTokens, jstring jStopString,
                                                  jobject callback) {
    LlmSession* s = toSession(handle);
    if (!s || !s->llm) {
        LOGE("nativeResponseStreaming: invalid session");
        return;
    }

    // Get callback method
    jclass cls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);
    if (!onToken) {
        LOGE("nativeResponseStreaming: could not find onToken(String) method");
        return;
    }

    const char* promptCStr = env->GetStringUTFChars(jPrompt, nullptr);
    if (!promptCStr) {
        LOGE("nativeResponseStreaming: null prompt");
        return;
    }
    std::string prompt(promptCStr);
    env->ReleaseStringUTFChars(jPrompt, promptCStr);

    const char* stopStr = nullptr;
    std::string stopStorage;
    if (jStopString != nullptr) {
        const char* s2 = env->GetStringUTFChars(jStopString, nullptr);
        if (s2) {
            stopStorage = s2;
            env->ReleaseStringUTFChars(jStopString, s2);
            stopStr = stopStorage.c_str();
        }
    }

    LOGI("nativeResponseStreaming: start, prompt len=%zu", prompt.length());
    
    s->stop_flag.store(false, std::memory_order_relaxed);
    CallbackStreambuf cbBuf(env, callback, onToken, s->stop_flag);
    std::ostream cbStream(&cbBuf);

    auto t0 = std::chrono::steady_clock::now();
    s->llm->response(prompt, &cbStream, stopStr, static_cast<int>(maxNewTokens));
    auto t1 = std::chrono::steady_clock::now();

    int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    s->prefill_ms = total_ms * 30 / 100;
    s->decode_ms = total_ms * 70 / 100;
    LOGI("nativeResponseStreaming: completed in %lld ms", (long long)total_ms);
}

// Interrupt an in-progress nativeResponseStreaming() call.
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngineMnn35_nativeStop(JNIEnv*, jclass, jlong handle) {
    LlmSession* s = toSession(handle);
    if (s) {
        LOGI("nativeStop: stopping");
        s->stop_flag.store(true, std::memory_order_relaxed);
    }
}

} // extern "C"
