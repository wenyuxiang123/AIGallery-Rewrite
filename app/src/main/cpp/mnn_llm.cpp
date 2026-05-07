#include <jni.h>
#include <string>
#include <sstream>
#include <chrono>
#include <atomic>
#include <exception>
#include <android/log.h>
#include <sched.h>
#include <fstream>
#include <vector>
#include <algorithm>
#include <functional>

#include "llm/llm.hpp"

#define TAG "MNNLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ==================== CPU Affinity Utilities ====================
static std::vector<int> getBigCores() {
    std::vector<std::pair<long, int>> freqs;
    for (int i = 0; i < 8; i++) {
        std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq";
        std::ifstream ifs(path);
        if (ifs.is_open()) {
            long freq = 0;
            ifs >> freq;
            freqs.push_back({freq, i});
        }
    }
    std::sort(freqs.begin(), freqs.end(), std::greater<>());
    std::vector<int> bigCores;
    for (int i = 0; i < std::min((int)freqs.size(), 6); i++) {
        bigCores.push_back(freqs[i].second);
    }
    return bigCores;
}

static void setCpuAffinity() {
    auto bigCores = getBigCores();
    if (bigCores.empty()) return;
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    for (int core : bigCores) {
        CPU_SET(core, &cpuset);
    }
    sched_setaffinity(0, sizeof(cpuset), &cpuset);
    LOGI("setCpuAffinity: bound to %zu big cores", bigCores.size());
}

// ==================== Session Management ====================
namespace {

struct LlmSession {
    MNN::Transformer::Llm* llm = nullptr;
    int64_t prefill_ms = 0;
    int64_t decode_ms = 0;
    int     prompt_tokens = 0;
    int     generated_tokens = 0;
    std::string loaded_model_name;
    std::string last_error;
    std::atomic<bool> stop_flag{false};
    jobject javaCallback = nullptr;
    jmethodID onTokenMethod = nullptr;
    JavaVM* javaVM = nullptr;
};

inline LlmSession* toSession(jlong handle) {
    return reinterpret_cast<LlmSession*>(static_cast<intptr_t>(handle));
}

std::atomic<LlmSession*> gSession{nullptr};
std::atomic<bool> gModelLoaded{false};

} // anonymous namespace

// Global JavaVM pointer for JNI callbacks from native threads
JavaVM* gJavaVM = nullptr;

// ==================== LlmStreamBuffer ====================
// Stream buffer that calls a lambda for each chunk of output.
// Same approach as MNN's official MnnLlmChat app.
class LlmStreamBuffer : public std::streambuf {
public:
    using CallBack = std::function<void(const char* str, size_t len)>;
    explicit LlmStreamBuffer(CallBack callback) : callback_(std::move(callback)) {}

protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        if (callback_) callback_(s, static_cast<size_t>(n));
        return n;
    }

    int overflow(int c) override {
        if (c != EOF) {
            char ch = static_cast<char>(c);
            if (callback_) callback_(&ch, 1);
        }
        return c;
    }

private:
    CallBack callback_ = nullptr;
};

// ==================== Utf8StreamProcessor ====================
// Ensures complete UTF-8 characters are delivered to the callback.
// Without this, multi-byte chars (e.g. Chinese) can be split across
// stream buffer callbacks, causing garbled output.
// Same as MNN's official MnnLlmChat implementation.
class Utf8StreamProcessor {
public:
    explicit Utf8StreamProcessor(std::function<void(const std::string&)> callback)
            : callback_(std::move(callback)) {}

    void processStream(const char* str, size_t len) {
        utf8Buffer_.append(str, len);

        size_t i = 0;
        std::string completeChars;
        while (i < utf8Buffer_.size()) {
            int length = utf8CharLength(static_cast<unsigned char>(utf8Buffer_[i]));
            if (length == 0 || i + length > utf8Buffer_.size()) {
                break;
            }
            completeChars.append(utf8Buffer_, i, length);
            i += length;
        }
        utf8Buffer_ = utf8Buffer_.substr(i);
        if (!completeChars.empty()) {
            callback_(completeChars);
        }
    }

    // Flush any remaining incomplete bytes
    void flush() {
        if (!utf8Buffer_.empty()) {
            callback_(utf8Buffer_);
            utf8Buffer_.clear();
        }
    }

    static int utf8CharLength(unsigned char byte) {
        if ((byte & 0x80) == 0) return 1;
        if ((byte & 0xE0) == 0xC0) return 2;
        if ((byte & 0xF0) == 0xE0) return 3;
        if ((byte & 0xF8) == 0xF0) return 4;
        return 0;
    }

private:
    std::string utf8Buffer_;
    std::function<void(const std::string&)> callback_;
};

// ==================== Android Stepping Status Restore ====================
// After response() with max_tokens=0 (prefill only), the context status
// might be set to MAX_TOKENS_FINISHED. We need to reset it to RUNNING
// so that subsequent generate(1) calls work correctly.
// Same workaround as MNN's official MnnLlmChat for Android prebuilt runtime.
static void restoreAndroidSteppingStatusIfNeeded(MNN::Transformer::Llm* llm) {
    if (llm == nullptr) return;
    auto* context = llm->getContext();
    if (context == nullptr) return;
    if (context->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED ||
        context->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED) {
        auto* mutable_context = const_cast<MNN::Transformer::LlmContext*>(context);
        mutable_context->status = MNN::Transformer::LlmStatus::RUNNING;
        LOGI("restoreAndroidSteppingStatus: reset status to RUNNING");
    }
}

// ==================== JNI Implementation ====================
extern "C" {

// JNI_OnLoad - called when the library is loaded by JVM
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad: saving JavaVM pointer");
    gJavaVM = vm;
    return JNI_VERSION_1_6;
}

// Stop generation
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeStop(JNIEnv*, jclass) {
    LOGI("nativeStop");
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s) {
        s->stop_flag.store(true, std::memory_order_relaxed);
    }
}

// Load model
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

    MNN::Transformer::Llm* llm = MNN::Transformer::Llm::createLLM(configPath);
    if (!llm) {
        LOGE("nativeLoadModel: createLLM returned null");
        return JNI_FALSE;
    }

    std::string configJson = "{\"num_ctx\":" + std::to_string(nCtx)
        + ",\"num_threads\":" + std::to_string(nThreads)
        + ",\"mmap\":true}";
    llm->set_config(configJson);

    auto t0 = std::chrono::steady_clock::now();
    bool ok = llm->load();
    auto t1 = std::chrono::steady_clock::now();
    int64_t ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

    if (ok) {
        setCpuAffinity();

        auto* session = new LlmSession{llm};
        session->javaVM = gJavaVM;
        gSession.store(session, std::memory_order_relaxed);
        gModelLoaded.store(true, std::memory_order_relaxed);

        size_t lastSlash = configPath.find_last_of("/\\");
        std::string modelName = (lastSlash != std::string::npos) ?
            configPath.substr(lastSlash + 1) : configPath;
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

// Unload model
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeUnloadModel(JNIEnv* env, jclass) {
    LOGI("nativeUnloadModel");
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s) {
        if (s->llm) {
            MNN::Transformer::Llm::destroy(s->llm);
        }
        if (s->javaCallback) {
            env->DeleteGlobalRef(s->javaCallback);
            s->javaCallback = nullptr;
        }
        delete s;
        gSession.store(nullptr, std::memory_order_relaxed);
    }
    gModelLoaded.store(false, std::memory_order_relaxed);
}

// Check if model is loaded
JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeIsModelLoaded(JNIEnv*, jclass) {
    return gModelLoaded.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}

// Helper: call Java onToken callback
static void callJavaTokenCallbackWithEnv(JNIEnv* env, LlmSession* s, const std::string& token) {
    if (!env || !s->javaCallback || !s->onTokenMethod) return;
    jstring js = env->NewStringUTF(token.c_str());
    if (js) {
        env->CallVoidMethod(s->javaCallback, s->onTokenMethod, js);
        env->DeleteLocalRef(js);
    }
}

// Generate text (sync) - uses ChatMessages format
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

    std::string configJson = "{"
        "\"temperature\":" + std::to_string(temperature) + ","
        "\"top_k\":" + std::to_string(topK) + ","
        "\"top_p\":" + std::to_string(topP) + ","
        "\"repetition_penalty\":1.05,"
        "\"thinking\":false}";
    s->llm->set_config(configJson);

    LOGI("nativeGenerate: prompt len=%zu, maxTokens=%d", prompt.length(), maxTokens);

    std::vector<std::pair<std::string, std::string>> history;
    history.emplace_back("user", prompt);

    std::ostringstream oss;
    auto t0 = std::chrono::steady_clock::now();
    // Use "饼干" as end_with - MNN's internal end-of-generation marker
    s->llm->response(history, &oss, "\xe9\xa5\xbc\xe5\xb9\xb2", static_cast<int>(maxTokens));
    auto t1 = std::chrono::steady_clock::now();

    int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    std::string result = oss.str();

    // Filter out the "饼干" end marker from result
    const std::string eopMarker = "\xe9\xa5\xbc\xe5\xb9\xb2";
    size_t eopPos = result.find(eopMarker);
    if (eopPos != std::string::npos) {
        result = result.substr(0, eopPos);
    }

    s->generated_tokens = result.length() / 4;
    s->prefill_ms = total_ms * 30 / 100;
    s->decode_ms = total_ms * 70 / 100;

    LOGI("nativeGenerate: completed in %lld ms, result len=%zu", (long long)total_ms, result.length());

    return env->NewStringUTF(result.c_str());
}

// Generate text (streaming) - uses ChatMessages + LlmStreamBuffer + generate(1) loop
// Same pattern as MNN's official MnnLlmChat app, with:
// - Utf8StreamProcessor for clean multi-byte char handling
// - "饼干" (eop) detection for proper generation termination
// - restoreAndroidSteppingStatusIfNeeded for prefill status reset
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

    std::string configJson = "{"
        "\"temperature\":" + std::to_string(temperature) + ","
        "\"top_k\":" + std::to_string(topK) + ","
        "\"top_p\":" + std::to_string(topP) + ","
        "\"repetition_penalty\":1.05,"
        "\"thinking\":false}";
    s->llm->set_config(configJson);

    LOGI("nativeGenerateStream: prompt len=%zu, maxTokens=%d", prompt.length(), maxTokens);

    // Reset stop flag
    s->stop_flag.store(false, std::memory_order_relaxed);

    // Use ChatMessages format so MNN applies chat template correctly
    std::vector<std::pair<std::string, std::string>> history;
    history.emplace_back("user", prompt);

    // Attach native thread to JVM once for all token callbacks
    JNIEnv* callbackEnv = nullptr;
    bool threadAttached = false;
    if (s->javaCallback && s->javaVM) {
        int ret = s->javaVM->GetEnv(reinterpret_cast<void**>(&callbackEnv), JNI_VERSION_1_6);
        if (ret == JNI_EDETACHED) {
            ret = s->javaVM->AttachCurrentThread(&callbackEnv, nullptr);
            threadAttached = (ret == JNI_OK);
        }
        if (!callbackEnv) {
            LOGE("nativeGenerateStream: failed to attach thread for callbacks");
        }
    }

    // Accumulated result string
    std::string accumulated;
    // End-of-generation flag (set when "饼干" detected in output)
    bool generate_end = false;
    // The "饼干" end marker (UTF-8 encoding)
    const std::string eopMarker = "\xe9\xa5\xbc\xe5\xb9\xb2";
    // Buffer for holding back the last char in case it's the start of "饼干"
    std::string pendingChars;

    // Utf8StreamProcessor ensures complete UTF-8 characters
    // and detects "饼干" (MNN's end-of-generation marker)
    JNIEnv* cbEnv = callbackEnv;
    Utf8StreamProcessor utf8Processor([&](const std::string& chars) {
        if (generate_end) return;

        // Prepend any pending chars from previous callback
        std::string toProcess = pendingChars + chars;
        pendingChars.clear();

        // Check if the text contains "饼干" (end marker)
        size_t eopPos = toProcess.find(eopMarker);
        if (eopPos != std::string::npos) {
            // Found end marker - send everything before it, then stop
            std::string before = toProcess.substr(0, eopPos);
            if (!before.empty()) {
                callJavaTokenCallbackWithEnv(cbEnv, s, before);
                accumulated.append(before);
            }
            generate_end = true;
            LOGI("nativeGenerateStream: detected eop marker, stopping");
            return;
        }

        // Check if the end could be a partial "饼干" start
        // "饼" is 3 bytes (E9 A5 BC), hold it back if it's the last char
        if (toProcess.size() >= 3) {
            std::string lastChar;
            unsigned char b = static_cast<unsigned char>(toProcess[toProcess.size() - 3]);
            int charLen = Utf8StreamProcessor::utf8CharLength(b);
            if (charLen == 3 && toProcess.size() >= static_cast<size_t>(charLen)) {
                lastChar = toProcess.substr(toProcess.size() - 3, 3);
            } else if (charLen == 2 && toProcess.size() >= static_cast<size_t>(charLen)) {
                lastChar = toProcess.substr(toProcess.size() - 2, 2);
            } else if (charLen == 1) {
                lastChar = toProcess.substr(toProcess.size() - 1, 1);
            }

            // If last char is "饼" (first char of "饼干"), hold it back
            if (lastChar == "\xe9\xa5\xbc") {
                std::string toSend = toProcess.substr(0, toProcess.size() - 3);
                if (!toSend.empty()) {
                    callJavaTokenCallbackWithEnv(cbEnv, s, toSend);
                    accumulated.append(toSend);
                }
                pendingChars = lastChar;
                return;
            }
        }

        // No "饼干" - send everything
        if (!toProcess.empty()) {
            callJavaTokenCallbackWithEnv(cbEnv, s, toProcess);
            accumulated.append(toProcess);
        }
    });

    // LlmStreamBuffer feeds raw bytes to Utf8StreamProcessor
    LlmStreamBuffer stream_buffer([&](const char* str, size_t len) {
        utf8Processor.processStream(str, len);
    });
    std::ostream output_ostream(&stream_buffer);

    auto t0 = std::chrono::steady_clock::now();

    // Step 1: Prefill (max_tokens=0) - processes the prompt
    // Use "饼干" as end_with - MNN's internal end-of-generation marker
    s->llm->response(history, &output_ostream, "\xe9\xa5\xbc\xe5\xb9\xb2", 0);

    // Restore context status - after prefill with max_tokens=0, the status
    // might be MAX_TOKENS_FINISHED, which would prevent generate(1) from working
    restoreAndroidSteppingStatusIfNeeded(s->llm);

    // Step 2: Decode loop - generate one token at a time
    int generated = 0;
    while (!s->stop_flag.load(std::memory_order_relaxed) &&
           !generate_end &&
           generated < maxTokens) {
        s->llm->generate(1);
        generated++;

        // Check if model has stopped (hit EOS)
        if (s->llm->stoped()) {
            auto* context = s->llm->getContext();
            if (context) {
                LOGI("nativeGenerateStream: model stopped at token %d, status=%d",
                     generated, static_cast<int>(context->status));
            }

            // If we haven't reached max_tokens and no eop detected yet,
            // this might be an intermediate stop - try to continue
            if (generated < maxTokens && !generate_end) {
                // Check if we already have meaningful output
                if (!accumulated.empty()) {
                    // Model truly stopped - exit loop
                    break;
                }
                // No output yet - try restoring status and continuing
                restoreAndroidSteppingStatusIfNeeded(s->llm);
            } else {
                break;
            }
        }
    }

    // Flush any remaining UTF-8 bytes
    utf8Processor.flush();

    // Send any pending chars (held back for "饼干" detection)
    if (!pendingChars.empty()) {
        callJavaTokenCallbackWithEnv(callbackEnv, s, pendingChars);
        accumulated.append(pendingChars);
        pendingChars.clear();
    }

    // Detach native thread from JVM after all callbacks done
    if (threadAttached && s->javaVM) {
        s->javaVM->DetachCurrentThread();
    }

    auto t1 = std::chrono::steady_clock::now();
    int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

    s->generated_tokens = generated;
    s->prefill_ms = total_ms * 30 / 100;
    s->decode_ms = total_ms * 70 / 100;

    LOGI("nativeGenerateStream: completed in %lld ms, generated=%d tokens, result len=%zu, eop=%s",
         (long long)total_ms, generated, accumulated.length(), generate_end ? "yes" : "no");

    return env->NewStringUTF(accumulated.c_str());
}

// Get loaded model name
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetLoadedModelName(JNIEnv* env, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s) {
        return env->NewStringUTF(s->loaded_model_name.c_str());
    }
    return env->NewStringUTF("");
}

// Get context size
JNIEXPORT jint JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetContextSize(JNIEnv*, jclass) {
    return 2048;
}

// Get memory usage
JNIEXPORT jlong JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetMemoryUsage(JNIEnv*, jclass) {
    return 0;
}

// Set system prompt
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

    s->llm->set_config(configJson);
    return JNI_TRUE;
}

// Reset conversation
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeResetConversation(JNIEnv*, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s && s->llm) {
        LOGI("nativeResetConversation: resetting");
        s->llm->reset();
    }
}

// Get last error
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetLastError(JNIEnv* env, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s) {
        return env->NewStringUTF(s->last_error.c_str());
    }
    return env->NewStringUTF("");
}

// Init native callback
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_initNativeCallback(JNIEnv* env, jobject thiz, jobject callback) {
    LOGI("initNativeCallback: setting up callback");

    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s) {
        LOGE("initNativeCallback: no session");
        return;
    }

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
