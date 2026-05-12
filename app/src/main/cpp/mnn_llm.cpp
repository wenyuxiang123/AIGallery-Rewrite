#include <jni.h>
#include <string>
#include <sstream>
#include <chrono>
#include <atomic>
#include <exception>
#include <android/log.h>
#include <signal.h>
#include <fcntl.h>
#include <unistd.h>
#include <sched.h>
#include <fstream>
#include <vector>
#include <algorithm>
#include <functional>
#include <ctime>
#include <sys/stat.h>
#include <cstdarg>
#include <dlfcn.h>
#include "llm/llm.hpp"
#include "MNN/expr/ExecutorScope.hpp"
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
    // Use POSIX open()/write() instead of std::ofstream - more reliable on Android
    int fd = open(g_logFilePath.c_str(), O_WRONLY | O_APPEND | O_CREAT, 0644);
    if (fd >= 0) {
        size_t len = 0;
        while (line[len] != '\0') len++;
        write(fd, line, len);
        close(fd);
    }
    // 同时输出到logcat
    LOGI("%s", buf);
}
// JNI: 设置日志文件路径（由Kotlin层调用，传入FileLogger的日志路径）
extern "C" JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeSetLogFilePath(JNIEnv* env, jclass, jstring jPath) {
    const char* p = env->GetStringUTFChars(jPath, nullptr);
    if (p) {
        g_logFilePath = std::string(p);
        env->ReleaseStringUTFChars(jPath, p);
        fileLog("nativeSetLogFilePath: log file set to %s", g_logFilePath.c_str());
    }
}
// ====== CPU亲和性 ======
// CPU拓扑检测：大核+小核
struct CpuTopology {
    std::vector<int> bigCores;   // 高频大核
    std::vector<int> smallCores; // 低频小核
};
static CpuTopology getCpuTopology() {
    CpuTopology topo;
    std::vector<std::pair<long, int>> freqs;
    for (int i = 0; i < 8; i++) {
        std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq";
        std::ifstream ifs(path);
        if (ifs.is_open()) { long freq = 0; ifs >> freq; freqs.push_back({freq, i}); }
    }
    if (freqs.empty()) {
        topo.bigCores = {0, 1, 2, 3};
        return topo;
    }
    std::sort(freqs.begin(), freqs.end(), std::greater<>());
    
    // 频率中位数分割大小核
    size_t mid = freqs.size() / 2;
    long medianFreq = freqs[mid].first;
    
    for (const auto& pair : freqs) {
        if (pair.first > medianFreq) {
            topo.bigCores.push_back(pair.second);
        } else {
            topo.smallCores.push_back(pair.second);
        }
    }
    fileLog("getCpuTopology: %zu big cores + %zu small cores (median=%ld)",
         topo.bigCores.size(), topo.smallCores.size(), medianFreq);
    for (int c : topo.bigCores) fileLog("  big: cpu%d", c);
    for (int c : topo.smallCores) fileLog("  small: cpu%d", c);
    return topo;
}
static std::vector<int> getBigCores() {
    return getCpuTopology().bigCores;
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
// Native crash signal handler - uses raw write() which is async-signal-safe
static void nativeCrashHandler(int sig) {
    const char* signame = "UNKNOWN";
    switch(sig) {
        case SIGSEGV: signame = "SIGSEGV"; break;
        case SIGABRT: signame = "SIGABRT"; break;
        case SIGBUS:  signame = "SIGBUS"; break;
        case SIGFPE:  signame = "SIGFPE"; break;
        case SIGILL:  signame = "SIGILL"; break;
    }
    // Use raw write() - async-signal-safe, no buffering, no locks
    int fd = open(g_logFilePath.c_str(), O_WRONLY|O_APPEND|O_CREAT, 0644);
    if (fd >= 0) {
        char crashbuf[256];
        int len = snprintf(crashbuf, sizeof(crashbuf),
            "[FATAL] Native crash! signal=%d (%s) - process will die\n", sig, signame);
        write(fd, crashbuf, len);
        close(fd);
    }
    __android_log_print(ANDROID_LOG_FATAL, "MNN-Native", "FATAL: native crash signal=%d (%s)", sig, signame);
    signal(sig, SIG_DFL);
    raise(sig);
}
static void installCrashHandler() {
    signal(SIGSEGV, nativeCrashHandler);
    signal(SIGABRT, nativeCrashHandler);
    signal(SIGBUS, nativeCrashHandler);
    signal(SIGFPE, nativeCrashHandler);
    signal(SIGILL, nativeCrashHandler);
}
extern "C" {
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    gJavaVM = vm;
    installCrashHandler();
    fileLog("JNI_OnLoad: saved JavaVM, installed crash handler");
    return JNI_VERSION_1_6;
}
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeStop(JNIEnv*, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s) s->stop_flag.store(true, std::memory_order_relaxed);
    fileLog("nativeStop called");
}
JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeLoadModel(JNIEnv* env, jclass,
    jstring jConfigPath, jint nCtx, jint nThreads, jstring jCacheDir, jboolean jUseQnn) {
    __android_log_print(ANDROID_LOG_INFO, "MNN-Native", "nativeLoadModel: ENTRY (logcat)");
    fileLog("nativeLoadModel: ENTRY - about to read config path");
    
    // QNN safety: force useQnn=false if QNN libs not loaded
    // QNN requires libcdsprpc.so + libQnnHtpV68Skel.so; if missing, backend_type=5 will crash
    bool useQnn = jUseQnn == JNI_TRUE;
    if (useQnn) {
        // Check if QNN V68Skel exists in cache (deployed by tryLoadQNN)
        // If not found, QNN was never initialized properly
        const char* home = getenv("HOME");
        bool qnnReady = false;
        // Try to dlopen libcdsprpc.so - if it fails, QNN is not available
        void* hCdsp = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_LOCAL);
        if (hCdsp) {
            dlclose(hCdsp);
            qnnReady = true;
        }
        if (!qnnReady) {
            fileLog("nativeLoadModel: QNN requested but libcdsprpc.so not available - forcing CPU backend");
            useQnn = false;
        }
    }
    fileLog("nativeLoadModel: useQnn=%s (requested=%s)", useQnn ? "true" : "false", jUseQnn == JNI_TRUE ? "true" : "false");
    
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
    // Create MNN Executor with ExecutorScope (required by MNN 3.4+)
    MNN::BackendConfig backendConfig;
    auto executor = MNN::Express::Executor::newExecutor(MNN_FORWARD_CPU, backendConfig, 1);
    MNN::Express::ExecutorScope executorScope(executor);
    fileLog("nativeLoadModel: Executor and ExecutorScope created");
    fileLog("nativeLoadModel: calling createLLM with path=%s ...", configPath.c_str());
    MNN::Transformer::Llm* llm = nullptr;
    try {
        llm = MNN::Transformer::Llm::createLLM(configPath);
    } catch (const std::exception& e) {
        fileLog("nativeLoadModel: createLLM threw std::exception: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        fileLog("nativeLoadModel: createLLM threw unknown exception");
        return JNI_FALSE;
    }
    if (!llm) {
        fileLog("nativeLoadModel: ERROR - createLLM returned null!");
        return JNI_FALSE;
    }
    fileLog("nativeLoadModel: createLLM success, setting config...");
    // 线程自动调优：所有大核 + 2个小核，充分利用778G Plus算力
    // 大核做重计算（prefill/decode），小核做轻任务（tokenizer/调度）
    CpuTopology topo = getCpuTopology();
    int bigCount = (int)topo.bigCores.size();
    int smallCount = (int)topo.smallCores.size();
    if (bigCount <= 0) bigCount = 4; // fallback
    int smallToUse = std::min(smallCount, 2);
    int optimalThreads = std::min(bigCount + smallToUse, 8);
    if (nThreads <= 0 || nThreads > optimalThreads) {
        nThreads = optimalThreads;
    }
    fileLog("nativeLoadModel: thread config: %d big + %d small = %d threads", bigCount, smallToUse, nThreads);
    
    // CPU亲和性：绑定所有大核+2个小核（不设亲和性让OS自由调度反而更差）
    {
        cpu_set_t cpuset;
        CPU_ZERO(&cpuset);
        for (int core : topo.bigCores) CPU_SET(core, &cpuset);
        for (int i = 0; i < smallToUse && i < (int)topo.smallCores.size(); i++) {
            CPU_SET(topo.smallCores[i], &cpuset);
        }
        sched_setaffinity(0, sizeof(cpuset), &cpuset);
        fileLog("nativeLoadModel: CPU affinity set to %d big + %d small cores", bigCount, smallToUse);
    }
    // mmap优化 + QNN/NPU加速（KV-INT8和lookahead暂时禁用，778G Plus纯CPU上开销大于收益）
    // 根据QNN库是否完整加载决定后端
    // QNN完整: backend_type=5 (MNN_FORWARD_NN = QNN/NPU)，换8Gen2+手机可加速
    // QNN不完整: 不设backend_type，默认CPU后端，避免native crash
    std::string cfg;
    if (useQnn) {
        cfg = std::string("{\"backend_type\":5")
            + ",\"attention_mode\":14"
            + ",\"num_ctx\":" + std::to_string(nCtx)
            + ",\"num_threads\":" + std::to_string(nThreads)
            + ",\"mmap\":true"
            + ",\"use_mmap\":true"
            + ",\"kvcache_mmap\":true}";
        fileLog("nativeLoadModel: QNN available, using NPU backend (backend_type=5)");
    } else {
        // CPU路径不设attention_mode: KV-TQ4(attention_mode=14)在纯CPU上dequant开销>内存带宽节省
        cfg = std::string("{\"num_ctx\":") + std::to_string(nCtx)
            + ",\"num_threads\":" + std::to_string(nThreads)
            + ",\"mmap\":true"
            + ",\"use_mmap\":true"
            + ",\"kvcache_mmap\":true}";
        fileLog("nativeLoadModel: QNN not available, using CPU backend");
    }
    fileLog("nativeLoadModel: set_config: %s", cfg.c_str());
    llm->set_config(cfg);
    std::string cacheDirStr;
    if (jCacheDir) {
        const char* cd = env->GetStringUTFChars(jCacheDir, nullptr);
        if (cd) {
            cacheDirStr = std::string(cd);
            env->ReleaseStringUTFChars(jCacheDir, cd);
        }
    }
    if (cacheDirStr.empty()) {
        cacheDirStr = "/data/data/com.aigallery.rewrite/cache/llm_cache";
    }
    std::string tmpCfg = "{\"tmp_path\":\"" + cacheDirStr + "\"}";
    fileLog("nativeLoadModel: set_config tmp_path: %s", tmpCfg.c_str());
    llm->set_config(tmpCfg);
    // QNN/NPU加速: 仅在Kotlin层检测到QNN库完整加载时启用backend_type=5
    // 778G Plus上libcdsprpc.so不可访问，QNN不完整，自动使用CPU后端
    fileLog("nativeLoadModel: calling llm->load()...");
    auto t0 = std::chrono::steady_clock::now();
    bool ok = false;
    try {
        ok = llm->load();
    } catch (const std::exception& e) {
        fileLog("nativeLoadModel: llm->load() threw exception: %s", e.what());
        ok = false;
    } catch (...) {
        fileLog("nativeLoadModel: llm->load() threw unknown exception");
        ok = false;
    }
    auto t1 = std::chrono::steady_clock::now();
    int64_t ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    fileLog("nativeLoadModel: llm->load() returned %s in %lld ms", ok ? "true" : "false", (long long)ms);
    if (ok) {
        // Lookahead 投机解码暂时禁用 - 778G Plus纯CPU上draft命中率低，反而拖慢
        // std::string speculativeCfg = "{\"speculative_type\":\"lookahead\",\"draft_token_num\":4}";
        // llm->set_config(speculativeCfg);
        fileLog("nativeLoadModel: lookahead disabled (CPU overhead > benefit on this device)");
        
        // CPU亲和性已在load()前设置
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
        // 直接 tokenize + response(vector<int>)，Kotlin 层已处理 prompt 格式化
        fileLog("nativeLoadModel: direct tokenize mode (no MNN template)");
        fileLog("nativeLoadModel: SUCCESS - model=%s", mn.c_str());
        return JNI_TRUE;
    }
    MNN::Transformer::Llm::destroy(llm);
    fileLog("nativeLoadModel: FAILED - llm->load() returned false");
    return JNI_FALSE;
}
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeUnloadModel(JNIEnv* env, jclass) {
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
Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeIsModelLoaded(JNIEnv*, jclass) {
    return gModelLoaded.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}

// Generate (sync) - 直接 tokenize + response(vector<int>)
// Kotlin 层已通过 GSSC prompt builder 格式化完整的 prompt（含系统提示、历史等）
// C++ 层不再套任何模板，直接 tokenize 后推理
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeGenerate(JNIEnv* env, jclass,
    jstring jPrompt, jint maxTokens, jfloat temperature, jint topK, jfloat topP) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s || !s->llm) { fileLog("nativeGenerate: ERROR - model not loaded"); return env->NewStringUTF(""); }
    const char* pc = env->GetStringUTFChars(jPrompt, nullptr);
    if (!pc) return env->NewStringUTF("");
    std::string prompt(pc); env->ReleaseStringUTFChars(jPrompt, pc);
    // 设置采样参数
    std::string cfg = "{\"temperature\":" + std::to_string(temperature)
        + ",\"top_k\":" + std::to_string(topK)
        + ",\"top_p\":" + std::to_string(topP)
        + ",\"thinking\":false"
        + ",\"repetition_penalty\":1.05}";
    s->llm->set_config(cfg);

    // ===== 直接 tokenize + response(vector<int>) =====
    // Kotlin 层已通过 GSSC prompt builder 格式化完整的 prompt（含系统提示、历史等）
    // C++ 层不再套任何模板，直接 tokenize 后推理
    fileLog("nativeGenerate: direct tokenize + response(vector<int>)");

    std::vector<int> input_ids = s->llm->tokenizer_encode(prompt);
    fileLog("nativeGenerate: tokenized to %zu tokens", input_ids.size());
    if (!input_ids.empty()) {
        fileLog("nativeGenerate: first 20 token IDs: %s", 
            [&]() { std::string ids; for (size_t i = 0; i < std::min(input_ids.size(), (size_t)20); i++) ids += std::to_string(input_ids[i]) + ","; return ids; }().c_str());
    }

    std::ostringstream result_oss;
    auto t0 = std::chrono::steady_clock::now();

    s->llm->response(input_ids, &result_oss, "<|im_end|>", static_cast<int>(maxTokens));

    auto t1 = std::chrono::steady_clock::now();
    auto total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

    std::string result = result_oss.str();
    fileLog("nativeGenerate: completed in %lld ms, result length=%zu", (long long)total_ms, result.length());
    fileLog("nativeGenerate: result first 200 chars: '%.200s'", result.c_str());

    auto* ctx = s->llm->getContext();
    s->generated_tokens = ctx ? ctx->gen_seq_len : result.length() / 4;
    s->prefill_ms = ctx ? ctx->prefill_us / 1000 : total_ms * 30 / 100;
    s->decode_ms = ctx ? ctx->decode_us / 1000 : total_ms * 70 / 100;
    return env->NewStringUTF(result.c_str());
}

// Generate (streaming) - 直接 tokenize + response(vector<int>)
// Kotlin 层已通过 GSSC prompt builder 格式化完整的 prompt（含系统提示、历史等）
// C++ 层不再套任何模板，直接 tokenize 后推理
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeGenerateStream(JNIEnv* env, jclass,
    jstring jPrompt, jint maxTokens, jfloat temperature, jint topK, jfloat topP, jboolean useGPU) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s || !s->llm) { fileLog("nativeGenerateStream: ERROR - model not loaded"); return env->NewStringUTF(""); }
    const char* pc = env->GetStringUTFChars(jPrompt, nullptr);
    if (!pc) return env->NewStringUTF("");
    std::string prompt(pc); env->ReleaseStringUTFChars(jPrompt, pc);
    // 设置采样参数
    std::string cfg = "{\"temperature\":" + std::to_string(temperature)
        + ",\"top_k\":" + std::to_string(topK)
        + ",\"top_p\":" + std::to_string(topP)
        + ",\"thinking\":false"
        + ",\"repetition_penalty\":1.05}";
    s->llm->set_config(cfg);

    // ===== 直接 tokenize + response(vector<int>) =====
    // Kotlin 层已通过 GSSC prompt builder 格式化完整的 prompt（含系统提示、历史等）
    // C++ 层不再套任何模板，直接 tokenize 后推理
    fileLog("nativeGenerateStream: direct tokenize + response(vector<int>)");

    std::vector<int> input_ids = s->llm->tokenizer_encode(prompt);
    fileLog("nativeGenerateStream: tokenized to %zu tokens", input_ids.size());
    if (!input_ids.empty()) {
        fileLog("nativeGenerateStream: first 20 token IDs: %s",
            [&]() { std::string ids; for (size_t i = 0; i < std::min(input_ids.size(), (size_t)20); i++) ids += std::to_string(input_ids[i]) + ","; return ids; }().c_str());
    }

    // 重要：end_with 必须设为 "<|im_end|>" 而非 nullptr
    // MNN 内部 nullptr 默认用 "\n" 作为停止符，会导致提前终止
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
    // 添加thinking过滤状态变量
    bool inThinkingBlock = false;
    std::string thinkingBuffer;
    
    Utf8StreamProcessor utf8Processor([&](const std::string& chars) {
        std::string filtered = chars;
        
        // 过滤<|im_end|>
        size_t pos = filtered.find("<|im_end|>");
        if (pos != std::string::npos) filtered = filtered.substr(0, pos);
        
        // 过滤thinking标签
        // 处理<think>开始标签
        size_t thinkStart = filtered.find("<think>");
        while (thinkStart != std::string::npos) {
            inThinkingBlock = true;
            // 输出<think>之前的内容
            if (thinkStart > 0) {
                std::string beforeThink = filtered.substr(0, thinkStart);
                if (!beforeThink.empty()) {
                    callJavaTokenCallbackWithEnv(cbEnv, s, beforeThink);
                    accumulated.append(beforeThink);
                    tokenCount++;
                }
            }
            // 找到下一个<think>或</think>
            size_t nextStart = filtered.find("<think>", thinkStart + 7);
            size_t thinkEnd = filtered.find("</think>", thinkStart);
            if (thinkEnd != std::string::npos) {
                // 先结束再开始的情况: </think>后面还有<think>
                if (nextStart != std::string::npos && nextStart < thinkEnd) {
                    // 多个<think>连续
                    filtered = filtered.substr(nextStart);
                    thinkStart = 0;
                    nextStart = filtered.find("<think>", 7);
                    thinkEnd = filtered.find("</think>");
                } else {
                    // 正常情况: 找到</think>
                    inThinkingBlock = false;
                    filtered = filtered.substr(thinkEnd + 8);  // 跳过</think>
                    thinkStart = filtered.find("<think>");
                    if (thinkStart == std::string::npos && !filtered.empty()) {
                        // </think>之后没有新的<think>，输出剩余内容
                        callJavaTokenCallbackWithEnv(cbEnv, s, filtered);
                        accumulated.append(filtered);
                        tokenCount++;
                        filtered.clear();
                    }
                }
            } else {
                // 没有</think>，记录剩余内容用于下次处理
                if (thinkStart + 7 < filtered.length()) {
                    thinkingBuffer = filtered.substr(thinkStart + 7);
                } else {
                    thinkingBuffer = "";
                }
                filtered.clear();
                break;
            }
        }
        
        // 如果还在thinking block中，追加到buffer并跳过
        if (inThinkingBlock) {
            thinkingBuffer += filtered;
            size_t thinkEnd = thinkingBuffer.find("</think>");
            if (thinkEnd != std::string::npos) {
                // thinking结束
                inThinkingBlock = false;
                filtered = thinkingBuffer.substr(thinkEnd + 8);
                thinkingBuffer.clear();
            } else {
                filtered.clear();
            }
        }
        
        // 过滤英文思考输出
        if (!filtered.empty()) {
            // 检查是否包含英文思考前缀
            std::vector<std::string> thinking_prefixes = {
                "Thinking Process:",
                "Here's a thinking process",
                "Let me think about this",
                "I need to reason through"
            };
            bool skipLine = false;
            for (const auto& prefix : thinking_prefixes) {
                if (filtered.find(prefix) != std::string::npos) {
                    skipLine = true;
                    break;
                }
            }
            
            if (!skipLine) {
                callJavaTokenCallbackWithEnv(cbEnv, s, filtered);
                accumulated.append(filtered);
                tokenCount++;
                if (tokenCount <= 10 || tokenCount % 50 == 0) {
                    fileLog("nativeGenerateStream: token #%d: '%s' (accumulated len=%zu)",
                         tokenCount, filtered.substr(0, 30).c_str(), accumulated.length());
                }
            }
        }
    });

    LlmStreamBuffer stream_buffer([&](const char* str, size_t len) {
        utf8Processor.processStream(str, len);
    });
    std::ostream output_ostream(&stream_buffer);
    
    auto t0 = std::chrono::steady_clock::now();
    fileLog("nativeGenerateStream: calling response(vector<int>) with end_with=\"<|im_end|>\"");
    s->llm->response(input_ids, &output_ostream, "<|im_end|>", static_cast<int>(maxTokens));
    utf8Processor.flush();

    if (threadAttached && s->javaVM) s->javaVM->DetachCurrentThread();
    auto t1 = std::chrono::steady_clock::now();
    auto total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
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
Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeGetLoadedModelName(JNIEnv* env, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    return s ? env->NewStringUTF(s->loaded_model_name.c_str()) : env->NewStringUTF("");
}
JNIEXPORT jint JNICALL
Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeGetContextSize(JNIEnv*, jclass) { return 2048; }
JNIEXPORT jlong JNICALL
Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeGetMemoryUsage(JNIEnv*, jclass) { return 0; }
JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeSetSystemPrompt(JNIEnv* env, jclass, jstring jSP) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (!s || !s->llm) return JNI_FALSE;
    const char* sp = env->GetStringUTFChars(jSP, nullptr);
    if (!sp) return JNI_FALSE;
    std::string cfg = "{\"system_prompt\":\"" + std::string(sp) + "\"}";
    env->ReleaseStringUTFChars(jSP, sp);
    s->llm->set_config(cfg); return JNI_TRUE;
}
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeResetConversation(JNIEnv*, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    if (s && s->llm) { fileLog("nativeResetConversation"); s->llm->reset(); }
}
JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeGetLastError(JNIEnv* env, jclass) {
    LlmSession* s = gSession.load(std::memory_order_relaxed);
    return s ? env->NewStringUTF(s->last_error.c_str()) : env->NewStringUTF("");
}
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeFree(JNIEnv*, jclass) {
    fileLog("nativeFree called");
    LlmSession* oldSession = gSession.exchange(nullptr, std::memory_order_acq_rel);
    if (oldSession) {
        if (oldSession->llm) MNN::Transformer::Llm::destroy(oldSession->llm);
        delete oldSession;
        gModelLoaded.store(false, std::memory_order_relaxed);
    }
}
JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_00024Companion_initNativeCallback(JNIEnv* env, jobject, jobject callback) {
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
// ====== @JvmStatic 别名 ======
// Kotlin 中带 @JvmStatic 注解的 companion object 方法，JVM 查找时不带 _00024Companion 后缀
// 因此需要提供不带后缀的 JNI 入口，转发到带后缀的实际实现
extern "C" JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeSetLogFilePath(JNIEnv* env, jclass cls, jstring jPath) {
    Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeSetLogFilePath(env, cls, jPath);
}
extern "C" JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeStop(JNIEnv* env, jclass cls) {
    Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeStop(env, cls);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGenerate(JNIEnv* env, jclass cls,
    jstring jPrompt, jint maxTokens, jfloat temperature, jint topK, jfloat topP) {
    return Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeGenerate(env, cls, jPrompt, maxTokens, temperature, topK, topP);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGenerateStream(JNIEnv* env, jclass cls,
    jstring jPrompt, jint maxTokens, jfloat temperature, jint topK, jfloat topP, jboolean useGPU) {
    return Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeGenerateStream(env, cls, jPrompt, maxTokens, temperature, topK, topP, useGPU);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetLoadedModelName(JNIEnv* env, jclass cls) {
    return Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeGetLoadedModelName(env, cls);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetContextSize(JNIEnv* env, jclass cls) {
    return Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeGetContextSize(env, cls);
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetMemoryUsage(JNIEnv* env, jclass cls) {
    return Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeGetMemoryUsage(env, cls);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeSetSystemPrompt(JNIEnv* env, jclass cls, jstring jSP) {
    return Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeSetSystemPrompt(env, cls, jSP);
}
extern "C" JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeResetConversation(JNIEnv* env, jclass cls) {
    Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeResetConversation(env, cls);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeGetLastError(JNIEnv* env, jclass cls) {
    return Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeGetLastError(env, cls);
}
extern "C" JNIEXPORT void JNICALL
Java_com_localai_server_engine_LlamaEngine_nativeFree(JNIEnv* env, jclass cls) {
    Java_com_localai_server_engine_LlamaEngine_00024Companion_nativeFree(env, cls);
}
