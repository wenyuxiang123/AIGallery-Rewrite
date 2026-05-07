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

JavaVM* gJavaVM = nullptr;

// ==================== LlmStreamBuffer ====================
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
            if (length == 0 || i + length > utf8Buffer_.size()) break;
            completeChars.append(utf8Buffer_, i, length);
            i += length;
        }
        utf8Buffer_ = utf8Buffer_.substr(i);
        if (!completeChars.empty()) callback_(completeChars);
    }

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

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad: saving JavaVM pointer");
    gJavaVM = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeStop(JNIEnv*, jclass) {
    LOGI("nativeStop");
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s) s->stop_flag.store(true, std::memory_order_relaxed);
}

JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeLoadModel(JNIEnv* env, jclass,
                                                            jstring jConfigPath,
                                                            jint nCtx, jint nThreads) {
    const char* path = env->GetStringUTFChars(jConfigPath, nullptr);
    if (!path) { LOGE("nativeLoadModel: null config path"); return JNI_FALSE; }
    std::string configPath(path);
    env->ReleaseStringUTFChars(jConfigPath, path);
    LOGI("nativeLoadModel: %s, nCtx=%d, nThreads=%d", configPath.c_str(), nCtx, nThreads);

    MNN::Transformer::Llm* llm = MNN::Transformer::Llm::createLLM(configPath);
    if (!llm) { LOGE("nativeLoadModel: createLLM returned null"); return JNI_FALSE; }

    std::string configJson = "{\"num_ctx\":" + std::to_string(nCtx)
        + ",\"num_threads\":" + std::to_string(nThreads) + ",\"mmap\":true}";
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
        if (dotPos != std::string::npos) modelName = modelName.substr(0, dotPos);
        session->loaded_model_name = modelName;
        LOGI("nativeLoadModel: success in %lld ms, model=%s", (long long)ms, modelName.c_str());
        return JNI_TRUE;
    } else {
        MNN::Transformer::Llm::destroy(llm);
        LOGE("nativeLoadModel: load failed after %lld ms", (long long)ms);
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeUnloadModel(JNIEnv* env, jclass) {
    LOGI("nativeUnloadModel");
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s) {
        if (s->llm) MNN::Transformer::Llm::destroy(s->llm);
        if (s->javaCallback) { env->DeleteGlobalRef(s->javaCallback); s->javaCallback = nullptr; }
        delete s;
        gSession.store(nullptr, std::memory_order_relaxed);
    }
    gModelLoaded.store(false, std::memory_order_relaxed);
}

JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeIsModelLoaded(JNIEnv*, jclass) {
    return gModelLoaded.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}

static void callJavaTokenCallbackWithEnv(JNIEnv* env, LlmSession* s, const std::string& token) {
    if (!env || !s->javaCallback || !s->onTokenMethod) return;
    jstring js = env->NewStringUTF(token.c_str());
    if (js) {
        env->CallVoidMethod(s->javaCallback, s->onTokenMethod, js);
        env->DeleteLocalRef(js);
    }
}

// Generate (sync)
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGenerate(JNIEnv* env, jclass,
                                                          jstring jPrompt, jint maxTokens,
                                                          jfloat temperature, jint topK, jfloat topP) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s || !s->llm) { LOGE("nativeGenerate: model not loaded"); return env->NewStringUTF(""); }

    const char* promptCStr = env->GetStringUTFChars(jPrompt, nullptr);
    if (!promptCStr) return env->NewStringUTF("");
    std::string prompt(promptCStr);
    env->ReleaseStringUTFChars(jPrompt, promptCStr);

    std::string configJson = "{\"temperature\":" + std::to_string(temperature)
        + ",\"top_k\":" + std::to_string(topK)
        + ",\"top_p\":" + std::to_string(topP)
        + ",\"repetition_penalty\":1.05,\"thinking\":false}";
    s->llm->set_config(configJson);
    LOGI("nativeGenerate: prompt len=%zu, maxTokens=%d", prompt.length(), maxTokens);

    std::vector<std::pair<std::string, std::string>> history;
    history.emplace_back("system", "You are a helpful assistant.");
    history.emplace_back("user", prompt);

    std::ostringstream oss;
    auto t0 = std::chrono::steady_clock::now();
    s->llm->response(history, &oss, "<eop>", static_cast<int>(maxTokens));
    auto t1 = std::chrono::steady_clock::now();

    int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    std::string result = oss.str();

    // Filter out <eop> from result
    size_t eopPos = result.find("<eop>");
    if (eopPos != std::string::npos) result = result.substr(0, eopPos);

    s->generated_tokens = result.length() / 4;
    s->prefill_ms = total_ms * 30 / 100;
    s->decode_ms = total_ms * 70 / 100;
    LOGI("nativeGenerate: completed in %lld ms, result len=%zu", (long long)total_ms, result.length());
    return env->NewStringUTF(result.c_str());
}

// Generate (streaming) - follows MNN official MnnLlmChat pattern:
// 1. response(history, ostream, "<eop>", 0) for prefill
// 2. generate(1) loop for decode
// 3. Utf8StreamProcessor for clean UTF-8
// 4. <eop> detection for proper termination
// 5. restoreAndroidSteppingStatusIfNeeded for status reset
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGenerateStream(JNIEnv* env, jclass,
                                                                  jstring jPrompt, jint maxTokens,
                                                                  jfloat temperature, jint topK, jfloat topP) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s || !s->llm) { LOGE("nativeGenerateStream: model not loaded"); return env->NewStringUTF(""); }

    const char* promptCStr = env->GetStringUTFChars(jPrompt, nullptr);
    if (!promptCStr) return env->NewStringUTF("");
    std::string prompt(promptCStr);
    env->ReleaseStringUTFChars(jPrompt, promptCStr);

    std::string configJson = "{\"temperature\":" + std::to_string(temperature)
        + ",\"top_k\":" + std::to_string(topK)
        + ",\"top_p\":" + std::to_string(topP)
        + ",\"repetition_penalty\":1.05,\"thinking\":false}";
    s->llm->set_config(configJson);
    LOGI("nativeGenerateStream: prompt len=%zu, maxTokens=%d", prompt.length(), maxTokens);

    s->stop_flag.store(false, std::memory_order_relaxed);

    // ChatMessages: MNN will apply chat template automatically
    std::vector<std::pair<std::string, std::string>> history;
    history.emplace_back("system", "You are a helpful assistant.");
    history.emplace_back("user", prompt);

    // Attach thread to JVM once
    JNIEnv* callbackEnv = nullptr;
    bool threadAttached = false;
    if (s->javaCallback && s->javaVM) {
        int ret = s->javaVM->GetEnv(reinterpret_cast<void**>(&callbackEnv), JNI_VERSION_1_6);
        if (ret == JNI_EDETACHED) {
            ret = s->javaVM->AttachCurrentThread(&callbackEnv, nullptr);
            threadAttached = (ret == JNI_OK);
        }
    }

    std::string accumulated;
    bool generate_end = false;
    const std::string eopMarker = "<eop>";
    std::string pendingChars;

    // Utf8StreamProcessor + <eop> detection
    JNIEnv* cbEnv = callbackEnv;
    Utf8StreamProcessor utf8Processor([&](const std::string& chars) {
        if (generate_end) return;

        std::string toProcess = pendingChars + chars;
        pendingChars.clear();

        // Check for <eop> in the text
        size_t eopPos = toProcess.find(eopMarker);
        if (eopPos != std::string::npos) {
            std::string before = toProcess.substr(0, eopPos);
            if (!before.empty()) {
                callJavaTokenCallbackWithEnv(cbEnv, s, before);
                accumulated.append(before);
            }
            generate_end = true;
            LOGI("nativeGenerateStream: detected <eop>, stopping");
            return;
        }

        // Check for partial <eop> at end: "<", "<e", "<eo", "<eop"
        for (int len = 1; len <= 4 && len <= (int)toProcess.size(); len++) {
            std::string tail = toProcess.substr(toProcess.size() - len, len);
            if (eopMarker.substr(0, len) == tail) {
                std::string toSend = toProcess.substr(0, toProcess.size() - len);
                if (!toSend.empty()) {
                    callJavaTokenCallbackWithEnv(cbEnv, s, toSend);
                    accumulated.append(toSend);
                }
                pendingChars = tail;
                return;
            }
        }

        if (!toProcess.empty()) {
            callJavaTokenCallbackWithEnv(cbEnv, s, toProcess);
            accumulated.append(toProcess);
        }
    });

    LlmStreamBuffer stream_buffer([&](const char* str, size_t len) {
        utf8Processor.processStream(str, len);
    });
    std::ostream output_ostream(&stream_buffer);

    auto t0 = std::chrono::steady_clock::now();

    // Step 1: Prefill (max_tokens=0)
    s->llm->response(history, &output_ostream, "<eop>", 0);

    // Restore status after prefill
    restoreAndroidSteppingStatusIfNeeded(s->llm);

    // Step 2: Decode loop
    int generated = 0;
    while (!s->stop_flag.load(std::memory_order_relaxed) &&
           !generate_end &&
           generated < maxTokens) {
        s->llm->generate(1);
        generated++;

        if (s->llm->stoped()) {
            auto* context = s->llm->getContext();
            LOGI("nativeGenerateStream: model stopped at token %d, status=%d",
                 generated, context ? static_cast<int>(context->status) : -1);
            if (!accumulated.empty()) break;
            restoreAndroidSteppingStatusIfNeeded(s->llm);
        }
    }

    utf8Processor.flush();
    if (!pendingChars.empty()) {
        callJavaTokenCallbackWithEnv(callbackEnv, s, pendingChars);
        accumulated.append(pendingChars);
        pendingChars.clear();
    }

    if (threadAttached && s->javaVM) s->javaVM->DetachCurrentThread();

    auto t1 = std::chrono::steady_clock::now();
    int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    s->generated_tokens = generated;
    s->prefill_ms = total_ms * 30 / 100;
    s->decode_ms = total_ms * 70 / 100;
    LOGI("nativeGenerateStream: completed in %lld ms, generated=%d, len=%zu, eop=%s",
         (long long)total_ms, generated, accumulated.length(), generate_end ? "yes" : "no");
    return env->NewStringUTF(accumulated.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetLoadedModelName(JNIEnv* env, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    return s ? env->NewStringUTF(s->loaded_model_name.c_str()) : env->NewStringUTF("");
}

JNIEXPORT jint JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetContextSize(JNIEnv*, jclass) { return 2048; }

JNIEXPORT jlong JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetMemoryUsage(JNIEnv*, jclass) { return 0; }

JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeSetSystemPrompt(JNIEnv* env, jclass, jstring jSystemPrompt) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s || !s->llm) return JNI_FALSE;
    const char* sysPrompt = env->GetStringUTFChars(jSystemPrompt, nullptr);
    if (!sysPrompt) return JNI_FALSE;
    std::string configJson = "{\"system_prompt\":\"" + std::string(sysPrompt) + "\"}";
    env->ReleaseStringUTFChars(jSystemPrompt, sysPrompt);
    s->llm->set_config(configJson);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeResetConversation(JNIEnv*, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s && s->llm) { LOGI("nativeResetConversation"); s->llm->reset(); }
}

JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetLastError(JNIEnv* env, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    return s ? env->NewStringUTF(s->last_error.c_str()) : env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_initNativeCallback(JNIEnv* env, jobject thiz, jobject callback) {
    LOGI("initNativeCallback: setting up callback");
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s) { LOGE("initNativeCallback: no session"); return; }
    if (s->javaCallback) { env->DeleteGlobalRef(s->javaCallback); s->javaCallback = nullptr; }
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
