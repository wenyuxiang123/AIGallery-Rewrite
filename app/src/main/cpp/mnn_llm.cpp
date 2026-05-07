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
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static std::vector<int> getBigCores() {
    std::vector<std::pair<long, int>> freqs;
    for (int i = 0; i < 8; i++) {
        std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq";
        std::ifstream ifs(path);
        if (ifs.is_open()) { long freq = 0; ifs >> freq; freqs.push_back({freq, i}); }
    }
    std::sort(freqs.begin(), freqs.end(), std::greater<>());
    std::vector<int> bigCores;
    for (int i = 0; i < std::min((int)freqs.size(), 6); i++) bigCores.push_back(freqs[i].second);
    return bigCores;
}

static void setCpuAffinity() {
    auto bigCores = getBigCores();
    if (bigCores.empty()) return;
    cpu_set_t cpuset; CPU_ZERO(&cpuset);
    for (int core : bigCores) CPU_SET(core, &cpuset);
    sched_setaffinity(0, sizeof(cpuset), &cpuset);
    LOGI("setCpuAffinity: bound to %zu big cores", bigCores.size());
}

namespace {
struct LlmSession {
    MNN::Transformer::Llm* llm = nullptr;
    int64_t prefill_ms = 0, decode_ms = 0;
    int prompt_tokens = 0, generated_tokens = 0;
    std::string loaded_model_name, last_error;
    std::atomic<bool> stop_flag{false};
    jobject javaCallback = nullptr;
    jmethodID onTokenMethod = nullptr;
    JavaVM* javaVM = nullptr;
};
std::atomic<LlmSession*> gSession{nullptr};
std::atomic<bool> gModelLoaded{false};
}

JavaVM* gJavaVM = nullptr;

class LlmStreamBuffer : public std::streambuf {
public:
    using CallBack = std::function<void(const char* str, size_t len)>;
    explicit LlmStreamBuffer(CallBack callback) : callback_(std::move(callback)) {}
protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        if (callback_) callback_(s, static_cast<size_t>(n)); return n;
    }
    int overflow(int c) override {
        if (c != EOF) { char ch = static_cast<char>(c); if (callback_) callback_(&ch, 1); } return c;
    }
private:
    CallBack callback_ = nullptr;
};

class Utf8StreamProcessor {
public:
    explicit Utf8StreamProcessor(std::function<void(const std::string&)> cb) : callback_(std::move(cb)) {}
    void processStream(const char* str, size_t len) {
        utf8Buffer_.append(str, len);
        size_t i = 0; std::string completeChars;
        while (i < utf8Buffer_.size()) {
            int length = utf8CharLength(static_cast<unsigned char>(utf8Buffer_[i]));
            if (length == 0 || i + length > utf8Buffer_.size()) break;
            completeChars.append(utf8Buffer_, i, length); i += length;
        }
        utf8Buffer_ = utf8Buffer_.substr(i);
        if (!completeChars.empty()) callback_(completeChars);
    }
    void flush() { if (!utf8Buffer_.empty()) { callback_(utf8Buffer_); utf8Buffer_.clear(); } }
    static int utf8CharLength(unsigned char b) {
        if ((b & 0x80) == 0) return 1; if ((b & 0xE0) == 0xC0) return 2;
        if ((b & 0xF0) == 0xE0) return 3; if ((b & 0xF8) == 0xF0) return 4; return 0;
    }
private:
    std::string utf8Buffer_;
    std::function<void(const std::string&)> callback_;
};

// Build ChatML prompt manually - bypasses MNN's ChatMessages/jinja requirement
static std::string buildChatPrompt(const std::string& userMessage) {
    std::string prompt;
    prompt += "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n";
    prompt += "<|im_start|>user\n" + userMessage + "<|im_end|>\n";
    prompt += "<|im_start|>assistant\n";
    return prompt;
}

// Call Java log method via JNI
static void jlog(JNIEnv* env, LlmSession* s, const std::string& msg) {
    if (!env || !s->javaCallback || !s->onTokenMethod) return;
    // We reuse the onToken callback for logging - prepend [NATIVE]
    // Actually, let's just use __android_log_print which gets captured by logcat
    LOGI("%s", msg.c_str());
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    LOGI("JNI_OnLoad: saving JavaVM pointer"); gJavaVM = vm; return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeStop(JNIEnv*, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s) s->stop_flag.store(true, std::memory_order_relaxed);
}

JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeLoadModel(JNIEnv* env, jclass,
    jstring jConfigPath, jint nCtx, jint nThreads) {
    const char* path = env->GetStringUTFChars(jConfigPath, nullptr);
    if (!path) return JNI_FALSE;
    std::string configPath(path); env->ReleaseStringUTFChars(jConfigPath, path);
    LOGI("nativeLoadModel: %s, nCtx=%d, nThreads=%d", configPath.c_str(), nCtx, nThreads);

    MNN::Transformer::Llm* llm = MNN::Transformer::Llm::createLLM(configPath);
    if (!llm) { LOGE("createLLM returned null"); return JNI_FALSE; }
    LOGI("createLLM success, setting config");

    std::string cfg = "{\"num_ctx\":" + std::to_string(nCtx)
        + ",\"num_threads\":" + std::to_string(nThreads) + ",\"mmap\":true}";
    llm->set_config(cfg);

    LOGI("calling llm->load()");
    auto t0 = std::chrono::steady_clock::now();
    bool ok = llm->load();
    auto t1 = std::chrono::steady_clock::now();
    int64_t ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("llm->load() returned %s in %lld ms", ok ? "true" : "false", (long long)ms);

    if (ok) {
        setCpuAffinity();
        auto* session = new LlmSession{llm}; session->javaVM = gJavaVM;
        gSession.store(session, std::memory_order_relaxed);
        gModelLoaded.store(true, std::memory_order_relaxed);
        size_t ls = configPath.find_last_of("/\\");
        std::string mn = (ls != std::string::npos) ? configPath.substr(ls + 1) : configPath;
        size_t dp = mn.find(".json");
        if (dp != std::string::npos) mn = mn.substr(0, dp);
        session->loaded_model_name = mn;

        // Log model context info
        auto* ctx = llm->getContext();
        if (ctx) {
            LOGI("Model context: prompt_len=%d, gen_seq_len=%d, all_seq_len=%d, status=%d",
                 ctx->prompt_len, ctx->gen_seq_len, ctx->all_seq_len, (int)ctx->status);
        }

        LOGI("nativeLoadModel: success, model=%s", mn.c_str());
        return JNI_TRUE;
    }
    MNN::Transformer::Llm::destroy(llm);
    LOGE("nativeLoadModel: failed");
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeUnloadModel(JNIEnv* env, jclass) {
    LOGI("nativeUnloadModel");
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s) {
        if (s->llm) MNN::Transformer::Llm::destroy(s->llm);
        if (s->javaCallback) { env->DeleteGlobalRef(s->javaCallback); s->javaCallback = nullptr; }
        delete s; gSession.store(nullptr, std::memory_order_relaxed);
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
    if (js) { env->CallVoidMethod(s->javaCallback, s->onTokenMethod, js); env->DeleteLocalRef(js); }
}

// Generate (sync)
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGenerate(JNIEnv* env, jclass,
    jstring jPrompt, jint maxTokens, jfloat temperature, jint topK, jfloat topP) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s || !s->llm) return env->NewStringUTF("");

    const char* pc = env->GetStringUTFChars(jPrompt, nullptr);
    if (!pc) return env->NewStringUTF("");
    std::string prompt(pc); env->ReleaseStringUTFChars(jPrompt, pc);

    // use_template=false: we build ChatML prompt ourselves
    std::string cfg = "{\"temperature\":" + std::to_string(temperature)
        + ",\"top_k\":" + std::to_string(topK)
        + ",\"top_p\":" + std::to_string(topP)
        + ",\"repetition_penalty\":1.05,\"thinking\":false,\"use_template\":false}";
    s->llm->set_config(cfg);

    std::string chatPrompt = buildChatPrompt(prompt);
    LOGI("nativeGenerate: user_prompt='%s' (len=%zu), chat_prompt len=%zu, maxTokens=%d",
         prompt.substr(0, 50).c_str(), prompt.length(), chatPrompt.length(), maxTokens);
    LOGI("nativeGenerate: chat_prompt first 200 chars: %.200s", chatPrompt.c_str());

    std::ostringstream oss;
    auto t0 = std::chrono::steady_clock::now();
    s->llm->response(chatPrompt, &oss, "<|im_end|>", static_cast<int>(maxTokens));
    auto t1 = std::chrono::steady_clock::now();

    int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    std::string result = oss.str();

    auto* ctx = s->llm->getContext();
    LOGI("nativeGenerate: completed in %lld ms, result len=%zu, gen_seq_len=%d, status=%d",
         (long long)total_ms, result.length(),
         ctx ? ctx->gen_seq_len : -1, ctx ? (int)ctx->status : -1);
    LOGI("nativeGenerate: result first 200 chars: %.200s", result.c_str());

    s->generated_tokens = ctx ? ctx->gen_seq_len : result.length() / 4;
    s->prefill_ms = ctx ? ctx->prefill_us / 1000 : total_ms * 30 / 100;
    s->decode_ms = ctx ? ctx->decode_us / 1000 : total_ms * 70 / 100;
    return env->NewStringUTF(result.c_str());
}

// Generate (streaming)
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGenerateStream(JNIEnv* env, jclass,
    jstring jPrompt, jint maxTokens, jfloat temperature, jint topK, jfloat topP) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s || !s->llm) { LOGE("nativeGenerateStream: model not loaded"); return env->NewStringUTF(""); }

    const char* pc = env->GetStringUTFChars(jPrompt, nullptr);
    if (!pc) return env->NewStringUTF("");
    std::string prompt(pc); env->ReleaseStringUTFChars(jPrompt, pc);

    // CRITICAL: use_template=false to prevent MNN from applying its own template
    // We build the ChatML prompt manually because llm_config.json lacks jinja field
    std::string cfg = "{\"temperature\":" + std::to_string(temperature)
        + ",\"top_k\":" + std::to_string(topK)
        + ",\"top_p\":" + std::to_string(topP)
        + ",\"repetition_penalty\":1.05,\"thinking\":false,\"use_template\":false}";
    s->llm->set_config(cfg);

    std::string chatPrompt = buildChatPrompt(prompt);
    LOGI("nativeGenerateStream: user_prompt='%s' (len=%zu), chat_prompt len=%zu",
         prompt.substr(0, 80).c_str(), prompt.length(), chatPrompt.length());
    LOGI("nativeGenerateStream: chat_prompt content: %.300s", chatPrompt.c_str());

    s->stop_flag.store(false, std::memory_order_relaxed);

    JNIEnv* callbackEnv = nullptr; bool threadAttached = false;
    if (s->javaCallback && s->javaVM) {
        int ret = s->javaVM->GetEnv(reinterpret_cast<void**>(&callbackEnv), JNI_VERSION_1_6);
        if (ret == JNI_EDETACHED) {
            ret = s->javaVM->AttachCurrentThread(&callbackEnv, nullptr);
            threadAttached = (ret == JNI_OK);
            LOGI("nativeGenerateStream: attached thread=%d", threadAttached);
        }
    }

    std::string accumulated;
    int tokenCount = 0;
    JNIEnv* cbEnv = callbackEnv;

    Utf8StreamProcessor utf8Processor([&](const std::string& chars) {
        std::string filtered = chars;
        // Filter <|im_end|> from output
        size_t pos = filtered.find("<|im_end|>");
        if (pos != std::string::npos) filtered = filtered.substr(0, pos);
        if (!filtered.empty()) {
            callJavaTokenCallbackWithEnv(cbEnv, s, filtered);
            accumulated.append(filtered);
            tokenCount++;
            if (tokenCount <= 5 || tokenCount % 50 == 0) {
                LOGI("nativeGenerateStream: token #%d: '%s' (accumulated len=%zu)",
                     tokenCount, filtered.substr(0, 30).c_str(), accumulated.length());
            }
        }
    });

    LlmStreamBuffer stream_buffer([&](const char* str, size_t len) {
        utf8Processor.processStream(str, len);
    });
    std::ostream output_ostream(&stream_buffer);

    LOGI("nativeGenerateStream: calling response() with chatPrompt, end_with=<|im_end|>");
    auto t0 = std::chrono::steady_clock::now();

    // Use response(string) - NOT ChatMessages - with manually built ChatML
    s->llm->response(chatPrompt, &output_ostream, "<|im_end|>", static_cast<int>(maxTokens));

    utf8Processor.flush();
    if (threadAttached && s->javaVM) s->javaVM->DetachCurrentThread();

    auto t1 = std::chrono::steady_clock::now();
    int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    auto* context = s->llm->getContext();
    s->generated_tokens = context ? context->gen_seq_len : tokenCount;
    s->prefill_ms = context ? context->prefill_us / 1000 : total_ms * 30 / 100;
    s->decode_ms = context ? context->decode_us / 1000 : total_ms * 70 / 100;

    LOGI("nativeGenerateStream: completed in %lld ms, tokens=%d, gen_seq_len=%d, accumulated=%zu, status=%d",
         (long long)total_ms, tokenCount,
         context ? context->gen_seq_len : -1, accumulated.length(),
         context ? (int)context->status : -1);
    LOGI("nativeGenerateStream: result first 300 chars: %.300s", accumulated.c_str());

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
Java_com_localai_server_engine_LlamaEngine_nativeSetSystemPrompt(JNIEnv* env, jclass, jstring jSP) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s || !s->llm) return JNI_FALSE;
    const char* sp = env->GetStringUTFChars(jSP, nullptr);
    if (!sp) return JNI_FALSE;
    std::string cfg = "{\"system_prompt\":\"" + std::string(sp) + "\"}";
    env->ReleaseStringUTFChars(jSP, sp);
    s->llm->set_config(cfg); return JNI_TRUE;
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
Java_com_localai_server_engine_LlamaEngine_initNativeCallback(JNIEnv* env, jobject, jobject callback) {
    LOGI("initNativeCallback");
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s) { LOGE("initNativeCallback: no session"); return; }
    if (s->javaCallback) { env->DeleteGlobalRef(s->javaCallback); s->javaCallback = nullptr; }
    if (callback) {
        s->javaCallback = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        s->onTokenMethod = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (!s->onTokenMethod) { LOGE("initNativeCallback: onToken method not found"); env->DeleteGlobalRef(s->javaCallback); s->javaCallback = nullptr; }
        else LOGI("initNativeCallback: callback set up successfully");
    }
}

} // extern "C"
