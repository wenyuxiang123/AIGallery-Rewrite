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
#include <ctime>
#include <sys/stat.h>

#include "llm/llm.hpp"

#define TAG "MNNLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ====== 全局文件日志 - 写入与FileLogger同一个文件 ======
static std::string g_logFilePath;
static std::mutex g_logMutex;

static void fileLog(const char* fmt, ...) {
    if (g_logFilePath.empty()) return;
    char buf[2048];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    // 获取时间戳
    auto now = std::chrono::system_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()) % 1000;
    auto t = std::chrono::system_clock::to_time_t(now);
    struct tm tm_buf;
    localtime_r(&t, &tm_buf);
    char timebuf[64];
    strftime(timebuf, sizeof(timebuf), "%m-%d %H:%M:%S", &tm_buf);

    char line[2304];
    snprintf(line, sizeof(line), "%s.%03lld [MNN-Native] %s\n", timebuf, (long long)ms.count(), buf);

    std::lock_guard<std::mutex> lock(g_logMutex);
    try {
        std::ofstream ofs(g_logFilePath, std::ios::app);
        if (ofs.is_open()) {
            ofs << line;
            ofs.flush();
        }
    } catch (...) {}

    // 同时输出到logcat
    LOGI("%s", buf);
}

// JNI: 设置日志文件路径（由Kotlin层调用，传入FileLogger的日志路径）
extern "C" JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeSetLogFilePath(JNIEnv* env, jclass, jstring jPath) {
    const char* p = env->GetStringUTFChars(jPath, nullptr);
    if (p) {
        g_logFilePath = std::string(p);
        env->ReleaseStringUTFChars(jPath, p);
        fileLog("nativeSetLogFilePath: log file set to %s", g_logFilePath.c_str());
    }
}

// ====== CPU亲和性 ======
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
    fileLog("setCpuAffinity: bound to %zu big cores", bigCores.size());
}

// ====== Session管理 ======
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

// ====== Stream Buffer ======
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

// ====== UTF8 Processor ======
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

// ====== JNI回调 ======
static void callJavaTokenCallbackWithEnv(JNIEnv* env, LlmSession* s, const std::string& token) {
    if (!env || !s->javaCallback || !s->onTokenMethod) return;
    jstring js = env->NewStringUTF(token.c_str());
    if (js) { env->CallVoidMethod(s->javaCallback, s->onTokenMethod, js); env->DeleteLocalRef(js); }
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    fileLog("JNI_OnLoad: saving JavaVM pointer");
    gJavaVM = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeStop(JNIEnv*, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s) s->stop_flag.store(true, std::memory_order_relaxed);
    fileLog("nativeStop called");
}

JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeLoadModel(JNIEnv* env, jclass,
    jstring jConfigPath, jint nCtx, jint nThreads) {
    const char* path = env->GetStringUTFChars(jConfigPath, nullptr);
    if (!path) { fileLog("nativeLoadModel: ERROR - null config path"); return JNI_FALSE; }
    std::string configPath(path); env->ReleaseStringUTFChars(jConfigPath, path);

    fileLog("nativeLoadModel: START - configPath=%s, nCtx=%d, nThreads=%d", configPath.c_str(), nCtx, nThreads);

    // 先释放旧session，避免内存泄漏和残留状态
    {
        LlmSession* oldSession = gSession.exchange(nullptr, std::memory_order_acq_rel);
        if (oldSession) {
            fileLog("nativeLoadModel: destroying previous session");
            if (oldSession->llm) MNN::Transformer::Llm::destroy(oldSession->llm);
            if (oldSession->javaCallback) { env->DeleteGlobalRef(oldSession->javaCallback); }
            delete oldSession;
            gModelLoaded.store(false, std::memory_order_relaxed);
        }
    }

    fileLog("nativeLoadModel: calling createLLM...");
    MNN::Transformer::Llm* llm = MNN::Transformer::Llm::createLLM(configPath);
    if (!llm) {
        fileLog("nativeLoadModel: ERROR - createLLM returned null!");
        return JNI_FALSE;
    }
    fileLog("nativeLoadModel: createLLM success, setting config...");

    std::string cfg = "{\"num_ctx\":" + std::to_string(nCtx)
        + ",\"num_threads\":" + std::to_string(nThreads) + ",\"mmap\":true}";
    fileLog("nativeLoadModel: set_config: %s", cfg.c_str());
    llm->set_config(cfg);

    fileLog("nativeLoadModel: calling llm->load()...");
    auto t0 = std::chrono::steady_clock::now();
    bool ok = llm->load();
    auto t1 = std::chrono::steady_clock::now();
    int64_t ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    fileLog("nativeLoadModel: llm->load() returned %s in %lld ms", ok ? "true" : "false", (long long)ms);

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

        auto* ctx = llm->getContext();
        if (ctx) {
            fileLog("nativeLoadModel: context info - prompt_len=%d, gen_seq_len=%d, all_seq_len=%d, status=%d",
                 ctx->prompt_len, ctx->gen_seq_len, ctx->all_seq_len, (int)ctx->status);
        }

        fileLog("nativeLoadModel: SUCCESS - model=%s", mn.c_str());
        return JNI_TRUE;
    }
    MNN::Transformer::Llm::destroy(llm);
    fileLog("nativeLoadModel: FAILED - llm->load() returned false");
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeUnloadModel(JNIEnv* env, jclass) {
    fileLog("nativeUnloadModel: called");
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s) {
        if (s->llm) MNN::Transformer::Llm::destroy(s->llm);
        if (s->javaCallback) { env->DeleteGlobalRef(s->javaCallback); s->javaCallback = nullptr; }
        delete s; gSession.store(nullptr, std::memory_order_relaxed);
    }
    gModelLoaded.store(false, std::memory_order_relaxed);
    fileLog("nativeUnloadModel: done");
}

JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeIsModelLoaded(JNIEnv*, jclass) {
    return gModelLoaded.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}

// Generate (sync)
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGenerate(JNIEnv* env, jclass,
    jstring jPrompt, jint maxTokens, jfloat temperature, jint topK, jfloat topP) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s || !s->llm) { fileLog("nativeGenerate: ERROR - model not loaded"); return env->NewStringUTF(""); }

    const char* pc = env->GetStringUTFChars(jPrompt, nullptr);
    if (!pc) return env->NewStringUTF("");
    std::string prompt(pc); env->ReleaseStringUTFChars(jPrompt, pc);

    // Fix Bug 1: 删除use_template:false，让MNN使用默认的use_template:true自动处理chat template
    // 删除repetition_penalty:1.05，使用MNN默认值
    std::string cfg = "{\"temperature\":" + std::to_string(temperature)
        + ",\"top_k\":" + std::to_string(topK)
        + ",\"top_p\":" + std::to_string(topP)
        + ",\"thinking\":false}";
    s->llm->set_config(cfg);

    // 直接传原始prompt，让MNN的use_template:true自动套模板
    fileLog("nativeGenerate: prompt (first 100 chars): '%.100s', len=%zu, maxTokens=%d",
         prompt.c_str(), prompt.length(), maxTokens);

    std::ostringstream oss;
    auto t0 = std::chrono::steady_clock::now();
    s->llm->response(prompt, &oss, "<|im_end|>", static_cast<int>(maxTokens));
    auto t1 = std::chrono::steady_clock::now();

    int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    std::string result = oss.str();

    auto* ctx = s->llm->getContext();
    fileLog("nativeGenerate: completed in %lld ms, result len=%zu, gen_seq_len=%d, status=%d",
         (long long)total_ms, result.length(),
         ctx ? ctx->gen_seq_len : -1, ctx ? (int)ctx->status : -1);
    fileLog("nativeGenerate: result first 100 chars: '%.100s'", result.c_str());

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
    if (!s || !s->llm) { fileLog("nativeGenerateStream: ERROR - model not loaded"); return env->NewStringUTF(""); }

    const char* pc = env->GetStringUTFChars(jPrompt, nullptr);
    if (!pc) return env->NewStringUTF("");
    std::string prompt(pc); env->ReleaseStringUTFChars(jPrompt, pc);

    // Fix Bug 1: 删除use_template:false，让MNN使用默认的use_template:true自动处理chat template
    // 删除repetition_penalty:1.05，使用MNN默认值
    std::string cfg = "{\"temperature\":" + std::to_string(temperature)
        + ",\"top_k\":" + std::to_string(topK)
        + ",\"top_p\":" + std::to_string(topP)
        + ",\"thinking\":false}";
    s->llm->set_config(cfg);

    // 直接传原始prompt，让MNN的use_template:true自动套模板
    fileLog("nativeGenerateStream: prompt (first 100 chars): '%.100s', len=%zu", prompt.c_str(), prompt.length());

    s->stop_flag.store(false, std::memory_order_relaxed);

    JNIEnv* callbackEnv = nullptr; bool threadAttached = false;
    if (s->javaCallback && s->javaVM) {
        int ret = s->javaVM->GetEnv(reinterpret_cast<void**>(&callbackEnv), JNI_VERSION_1_6);
        if (ret == JNI_EDETACHED) {
            ret = s->javaVM->AttachCurrentThread(&callbackEnv, nullptr);
            threadAttached = (ret == JNI_OK);
            fileLog("nativeGenerateStream: attached thread=%d", threadAttached);
        }
    }

    std::string accumulated;
    int tokenCount = 0;
    JNIEnv* cbEnv = callbackEnv;

    Utf8StreamProcessor utf8Processor([&](const std::string& chars) {
        std::string filtered = chars;
        size_t pos = filtered.find("<|im_end|>");
        if (pos != std::string::npos) filtered = filtered.substr(0, pos);
        if (!filtered.empty()) {
            callJavaTokenCallbackWithEnv(cbEnv, s, filtered);
            accumulated.append(filtered);
            tokenCount++;
            if (tokenCount <= 10 || tokenCount % 50 == 0) {
                fileLog("nativeGenerateStream: token #%d: '%s' (accumulated len=%zu)",
                     tokenCount, filtered.substr(0, 30).c_str(), accumulated.length());
            }
        }
    });

    LlmStreamBuffer stream_buffer([&](const char* str, size_t len) {
        utf8Processor.processStream(str, len);
    });
    std::ostream output_ostream(&stream_buffer);

    fileLog("nativeGenerateStream: calling response() with prompt, end_with=<|im_end|>");
    auto t0 = std::chrono::steady_clock::now();

    s->llm->response(prompt, &output_ostream, "<|im_end|>", static_cast<int>(maxTokens));

    utf8Processor.flush();
    if (threadAttached && s->javaVM) s->javaVM->DetachCurrentThread();

    auto t1 = std::chrono::steady_clock::now();
    int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    auto* context = s->llm->getContext();
    s->generated_tokens = context ? context->gen_seq_len : tokenCount;
    s->prefill_ms = context ? context->prefill_us / 1000 : total_ms * 30 / 100;
    s->decode_ms = context ? context->decode_us / 1000 : total_ms * 70 / 100;

    fileLog("nativeGenerateStream: completed in %lld ms, tokens=%d, gen_seq_len=%d, accumulated=%zu, status=%d",
         (long long)total_ms, tokenCount,
         context ? context->gen_seq_len : -1, accumulated.length(),
         context ? (int)context->status : -1);
    fileLog("nativeGenerateStream: result first 100 chars: '%.100s'", accumulated.c_str());

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
    if (s && s->llm) { fileLog("nativeResetConversation"); s->llm->reset(); }
}

JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetLastError(JNIEnv* env, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    return s ? env->NewStringUTF(s->last_error.c_str()) : env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_initNativeCallback(JNIEnv* env, jobject, jobject callback) {
    fileLog("initNativeCallback: called");
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s) { fileLog("initNativeCallback: ERROR - no session"); return; }
    if (s->javaCallback) { env->DeleteGlobalRef(s->javaCallback); s->javaCallback = nullptr; }
    if (callback) {
        s->javaCallback = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        s->onTokenMethod = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (!s->onTokenMethod) { fileLog("initNativeCallback: ERROR - onToken method not found"); env->DeleteGlobalRef(s->javaCallback); s->javaCallback = nullptr; }
        else fileLog("initNativeCallback: callback set up successfully");
    }
}

} // extern "C"
