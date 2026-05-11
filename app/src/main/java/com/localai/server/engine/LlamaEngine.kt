package com.localai.server.engine

import android.content.Context
import com.aigallery.rewrite.util.FileLogger
import com.aigallery.rewrite.inference.InferenceStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject

/**
 * Token 回调接口，用于流式输出
 */
interface TokenCallback {
    fun onToken(token: String)
}

/**
 * MNN LLM 引擎 JNI 封装
 * 
 * 使用 liblocalai-jni.so 封装 MNN LLM 的 native 接口，
 * JNI 层位于 com/localai/server/engine/LlamaEngine
 * 
 * JNI 采用 liblocalai-jni 导出：
 * - DDT_NEEDED pulls in libMNN.so
 * - LLM_Express 4.7B (MNN_Express + LLaMA)
 * - 模型需要通过 config.json 传给 MNN
 * - 使用 Android NDK 的 CMake 构建，配合 C++ MNN LLM 实现
 * 
 * @return 返回模型路径
 */
class LlamaEngine private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "LlamaEngine"
    
        @Volatile
        private var instance: LlamaEngine? = null
        
        /**
         * 获取单例实例（线程安全）
         */
        fun getInstance(context: Context): LlamaEngine {
            return instance ?: synchronized(this) {
                instance ?: LlamaEngine(context.applicationContext).also { instance = it }
            }
        }
        
        /**
         * 初始化 LlamaEngine（线程安全）
         */
        fun initialize(context: Context): LlamaEngine {
            return getInstance(context)
        }
        
        /**
         * 加载 MNN native 库
         */
        fun loadLibraries(): Boolean {
            FileLogger.d(TAG, "loadLibraries: starting library load")
            
            try {
                // MNN_SO_BUILD=OFF: 使用预编译 MNN 库 (推荐生产使用)
                // 加载 liblocalai-jni.so，它会 pull in libMNN.so
                System.loadLibrary("localai-jni")
                FileLogger.d(TAG, "loadLibraries: localai-jni loaded (DDT_NEEDED pulls in libMNN.so)")
                
                // 如需调试，加载 dlopen MNN 加载器
                System.loadLibrary("MNN")
                FileLogger.d(TAG, "loadLibraries: MNN loaded (includes Express+LLaMA, SET_BUILD=OFF)")
                
            } catch (e: UnsatisfiedLinkError) {
                FileLogger.e(TAG, "loadLibraries: native library not found or linking failed", e)
                return false
            }
            
            FileLogger.i(TAG, "loadLibraries: all MNN libraries loaded successfully")
            return true
        }
        
        /**
         * 预加载 QNN/NPU 支持
         */
        private fun tryLoadQNN(appContext: android.content.Context) {
            // FastRPC + QNN (QNN native libs pulled by liblocalai-jni)
            // Step 1: FastRPC (required for QNN/NPU)
            try {
                System.loadLibrary("cdspgrpc")
                FileLogger.d(TAG, "loadLibraries: cdspgrpc loaded (FastRPC for QNN)")
            } catch (e: UnsatisfiedLinkError) {
                FileLogger.w(TAG, "loadLibraries: cdspgrpc not available, QNN disabled")
            }
            
            // Step 2: QNN/NPU (requires QNN native libs from liblocalai-jni)
            // Load QNN libs after FastRPC (liblocalai-jni pulls them in via RTLD_NOW)
            if (qnnAvailable) {
                try {
                    System.loadLibrary("QnnSystem")
                    FileLogger.d(TAG, "loadLibraries: QnnSystem loaded (QNN/NPU)")
                } catch (e: UnsatisfiedLinkError) {
                    FileLogger.w(TAG, "loadLibraries: QNN not available (NPU disabled)")
                    qnnAvailable = false
                }
            }
            
            // Step 3: Deploy QNN V68Skel from assets to cache (DSP6 bin, cannot use System.loadLibrary)
            if (qnnAvailable) {
                try {
                    val skelDir = File(appContext.cacheDir, "qnn")
                    skelDir.mkdirs()
                    val skelFile = File(skelDir, "libQnnHtpV68Skel.so")
                    if (!skelFile.exists()) {
                        appContext.assets.open("qnn/libQnnHtpV68Skel.so").use { input ->
                            skelFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    System.getenv("ADSPRP_LIBRARY_PATH")?.let { /* set if needed */ }
                    FileLogger.d(TAG, "loadLibraries: QnnHtpV68Skel deployed to ${skelFile.absolutePath}")
                } catch (e: Exception) {
                    FileLogger.w(TAG, "loadLibraries: QnnHtpV68Skel deploy failed")
                }
            }
            
            // Disable QNN for now: LLaMA inference with backend_type=5 hangs silently -
            // NPU graph compile fails for LLaMA ops. Re-enable only on devices with V7c+ (8Gen2+).
            qnnAvailable = false
            FileLogger.w(TAG, "loadLibraries: QNN disabled - NPU cannot handle LLaMA inference (needs V7c+ devices)")
        }
        
        private var qnnAvailable = false
        
        // 预加载 JNI 库（QNN 检测由 tryLoadQNN 处理，qnnAvailable 默认 false）
        init {
            try {
                System.loadLibrary("localai-jni")
            } catch (e: UnsatisfiedLinkError) {
                // library will be loaded again in loadLibraries()
            }
        }
        
        /**
         * 设置 native 日志文件路径
         */
        @JvmStatic
        private external fun nativeSetLogFilePath(path: String)
        
        // =============== Native 声明 (由 C++ 实现) ===============
        
        /**
         * 加载 MNN 模型（通过 config.json）
         */
        external fun nativeLoadModel(
            configPath: String,
            nCtx: Int,
            nThreads: Int,
            cacheDir: String,
            useQnn: Boolean
        ): Boolean
        
        /**
         * 卸载模型
         */
        external fun nativeUnloadModel()
        
        /**
         * 模型是否已加载
         */
        external fun nativeIsModelLoaded(): Boolean
        
        /**
         * 同步生成文本（短文本）
         */
        @JvmStatic
        external fun nativeGenerate(
            prompt: String,
            maxTokens: Int,
            temperature: Float,
            topK: Int,
            topP: Float
        ): String
        
        /**
         * 生成文本流（长文本）
         */
        @JvmStatic
        external fun nativeGenerateStream(
            prompt: String,
            maxTokens: Int,
            temperature: Float,
            topK: Int,
            topP: Float,
            useGPU: Boolean
        ): String
        
        /**
         * 获取已加载模型名称
         */
        @JvmStatic
        external fun nativeGetLoadedModelName(): String
        
        /**
         * 获取上下文大小
         */
        @JvmStatic
        external fun nativeGetContextSize(): Int
        
        /**
         * 获取已使用内存
         */
        @JvmStatic
        external fun nativeGetMemoryUsage(): Long
        
        /**
         * 设置系统提示词
         */
        @JvmStatic
        external fun nativeSetSystemPrompt(systemPrompt: String): Boolean
        
        /**
         * 重置会话
         */
        @JvmStatic
        external fun nativeResetConversation()
        
        /**
         * 卸载模型并释放资源
         */
        @JvmStatic
        external fun nativeFree()
        
        /**
         * 获取最后错误信息
         */
        @JvmStatic
        external fun nativeGetLastError(): String
    }
    
    // =============== Kotlin 实现 ===============
    
    // 模型是否已加载
    private var _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()
    
    private var _loadedModelName = MutableStateFlow<String?>(null)
    val loadedModelName: StateFlow<String?> = _loadedModelName.asStateFlow()
    
    private var _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    
    // 推理统计
    private var totalInferences = 0L
    private var totalTokensGenerated = 0L
    private var lastInferenceTimeMs = 0L
    
    // Token 回调
    private var tokenCallback: TokenCallback? = null
    
    /**
     * 设置 token 回调（流式输出）
     */
    fun setTokenCallback(callback: TokenCallback?) {
        this.tokenCallback = callback
        // Note: native callback initialization would be handled here if supported
    }
    
    // =============== Native 回调 ===============
    @Volatile private var librariesLoaded = false
    @Volatile private var libraryLoadError: String? = null
    
    /**
     * 加载 native 库并初始化回调
     */
    private fun loadNativeLibraries() {
        if (librariesLoaded) return
        synchronized(this) {
            if (librariesLoaded) return
            try {
                if (!loadLibraries()) {
                    libraryLoadError = "Native libraries failed to load"
                    return
                }
                librariesLoaded = true
            } catch (e: Throwable) {
                libraryLoadError = e.message
            }
        }
    }
    
    // 推理统计状态
    private val _inferenceStats = MutableStateFlow(InferenceStats())
    val inferenceStats: StateFlow<InferenceStats> = _inferenceStats.asStateFlow()
    
    /**
     * 运行时配置数据类
     */
    data class RuntimeConfig(
        val backend: String,
        val attentionMode: Int,
        val precision: String,
        val openclCachePath: String?
    )
    
    // 保存运行时配置，下次 loadModel 时应用
    private var pendingRuntimeConfig: RuntimeConfig? = null
    
    /**
     * 应用运行时配置（通过修改 config.json 实现）
     * 注意：MNN LLM 通过 config.json 加载配置，没有直接的 native API
     * 因此我们修改 config.json 然后重新加载模型
     */
    fun applyRuntimeConfig(backend: String, attentionMode: Int, precision: String, openclCachePath: String?) {
        FileLogger.d(TAG, "applyRuntimeConfig: backend=$backend, attentionMode=$attentionMode, precision=$precision")
        pendingRuntimeConfig = RuntimeConfig(backend, attentionMode, precision, openclCachePath)
        // If model is already loaded, we need to reload with new config
        // (MNN doesn't support changing runtime config on a loaded model)
        if (_isModelLoaded.value && pendingRuntimeConfig != null) {
            FileLogger.i(TAG, "applyRuntimeConfig: config saved, will apply on next model load")
        }
    }
    
    /**
     * 加载模型
     */
    fun loadModel(
        path: String, 
        nCtx: Int = 2048, 
        nThreads: Int = 0,
        backend: String = "cpu",
        attentionMode: Int = 10,
        precision: String = "low",
        openclCachePath: String? = null
    ): Boolean {

        // 确保 native 库已加载
        loadNativeLibraries()
        if (libraryLoadError != null) {
            FileLogger.e(TAG, "loadModel: native libraries not loaded - $libraryLoadError")
            _lastError.value = "Native libraries not loaded: $libraryLoadError"
            return false
        }
        FileLogger.d(TAG, "loadModel: path=$path, nCtx=$nCtx, nThreads=$nThreads, backend=$backend, attentionMode=$attentionMode, precision=$precision")
        
        // 应用待处理的运行时配置
        val effectiveBackend = pendingRuntimeConfig?.backend ?: backend
        val effectiveAttentionMode = pendingRuntimeConfig?.attentionMode ?: attentionMode
        val effectivePrecision = pendingRuntimeConfig?.precision ?: precision
        val effectiveOpenclCache = pendingRuntimeConfig?.openclCachePath ?: openclCachePath
        
        // 清空待处理配置
        pendingRuntimeConfig = null
        
        if (_isModelLoaded.value) {
            FileLogger.d(TAG, "loadModel: unloading previous model before loading new one")
            unloadModel()
        }
        
        try {
            // 定位模型目录
            val modelDir = File(path)
            if (!modelDir.exists() || !modelDir.isDirectory) {
                FileLogger.e(TAG, "loadModel: model directory not found: $path")
                _lastError.value = "Model directory not found: $path"
                return false
            }
            
            // 检查必需文件
            val requiredFiles = listOf("config.json", "llm.mnn", "tokenizer.txt")
            for (fileName in requiredFiles) {
                if (!File(modelDir, fileName).exists()) {
                    FileLogger.w(TAG, "loadModel: missing optional file: $fileName")
                }
            }
            
            // WORKAROUND: native 日志路径设置到 workdir，
            // 因为 qwen2.5-0.5B-mnn 用 config.json 传给 MNN
            // 设置 native 日志路径到 workdir
            val nativePath = File(modelDir, "config.json").absolutePath
            FileLogger.d(TAG, "loadModel: nativePath=$nativePath (config.json workaround)")
            
            // 创建 cache 目录
            val cacheDir = File("/data/data/com.localai.server/cache/llm_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
                FileLogger.d(TAG, "loadModel: created cache dir ${cacheDir.absolutePath}")
            }
            // 创建 app cache 目录
            val appCacheDir = File(context.cacheDir, "llm_cache")
            if (!appCacheDir.exists()) {
                appCacheDir.mkdirs()
                FileLogger.d(TAG, "loadModel: created app cache dir ${appCacheDir.absolutePath}")
            }
            
            // 获取 cache 目录路径
            val cacheDirPath = appCacheDir.absolutePath
            
            // NOTE: 不再 patch config.json — MNN 的 createLLM() 读取原始 config.json 获取模型结构信息
            // 运行时配置（backend/threads/precision/attention_mode）由 C++ 层 set_config() 独立管理
            // 之前 Kotlin 写入 backend_type="cpu"（字符串），MNN 期望整数，导致 createLLM() 崩溃
            FileLogger.d(TAG, "loadModel: using original config.json, runtime config handled by C++ layer")
            
            // 设置 C++ 层日志路径，让 fileLog() 输出到同一个日志文件
            // 必须用 getExternalFilesDir（与 FileLogger 同一目录），否则 C++ 日志写入内部存储但用户看到的是外部存储
            try {
                val logDir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
                if (!logDir.exists()) logDir.mkdirs()
                val logFile = File(logDir, "aigallery_${java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())}.log")
                nativeSetLogFilePath(logFile.absolutePath)
                FileLogger.d(TAG, "loadModel: set native log path to ${logFile.absolutePath}")
            } catch (e: Throwable) {
                FileLogger.w(TAG, "loadModel: failed to set native log path: ${e.javaClass.simpleName}: ${e.message}")
            }
            
            // 调用 native 加载模型
            // Log memory state before native load
            val runtime = Runtime.getRuntime()
            val maxMem = runtime.maxMemory() / 1024 / 1024
            val totalMem = runtime.totalMemory() / 1024 / 1024
            val freeMem = runtime.freeMemory() / 1024 / 1024
            val usedMem = totalMem - freeMem
            FileLogger.d(TAG, "loadModel: memory before native - max=${maxMem}MB, used=${usedMem}MB, free=${freeMem}MB, total=${totalMem}MB")
            FileLogger.d(TAG, "loadModel: calling nativeLoadModel(path=$nativePath, nCtx=$nCtx, nThreads=$nThreads, cacheDir=$cacheDirPath, qnn=$qnnAvailable)")
            val success = nativeLoadModel(nativePath, nCtx, nThreads, cacheDirPath, qnnAvailable)
            FileLogger.d(TAG, "loadModel: nativeLoadModel returned $success")
            // Dump logcat for any native crash info
            try {
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "50", "-s", "DEBUG:F", "libc:F", "MNN-Native:*"))
                val logcatOutput = process.inputStream.bufferedReader().readText()
                if (logcatOutput.isNotBlank()) {
                    FileLogger.d(TAG, "loadModel: logcat crash info:\n$logcatOutput")
                }
            } catch (e: Throwable) {
                FileLogger.w(TAG, "loadModel: failed to read logcat: ${e.message}")
            }
            
            if (success) {
                _isModelLoaded.value = true
                _loadedModelName.value = nativeGetLoadedModelName()
                FileLogger.i(TAG, "loadModel: success, model=${_loadedModelName.value}")
            } else {
                _lastError.value = nativeGetLastError()
                FileLogger.e(TAG, "loadModel: failed, error=${_lastError.value}")
            }
            
            return success
            
        } catch (e: Throwable) {
            val errorMsg = "loadModel exception: ${e.message}"
            FileLogger.e(TAG, errorMsg)
            _lastError.value = errorMsg
            return false
        }
    }
    
    /**
     * 卸载模型
     */
    fun unloadModel() {
        FileLogger.d(TAG, "unloadModel")
        try {
            nativeUnloadModel()
            _isModelLoaded.value = false
            _loadedModelName.value = null
            FileLogger.i(TAG, "unloadModel: success")
        } catch (e: Throwable) {
            FileLogger.e(TAG, "unloadModel failed")
        }
    }
    
    /**
     * 生成文本（同步）
     */
    fun generate(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.9f
    ): String {
        FileLogger.d(TAG, "generate: prompt len=${prompt.length}, maxTokens=$maxTokens, temp=$temperature")
        val startTime = System.currentTimeMillis()
        
        try {
            val result = nativeGenerate(prompt, maxTokens, temperature, topK, topP)
            val elapsed = System.currentTimeMillis() - startTime
            val tokens = result.length / 4  // 粗略估计
            
            updateInferenceStateInternal(elapsed, tokens)
            
            FileLogger.d(TAG, "generate: completed in ${elapsed}ms, ~$tokens tokens")
            return result
            
        } catch (e: Throwable) {
            FileLogger.e(TAG, "generate failed")
            return ""
        }
    }
    
    /**
     * 生成文本流
     */
    suspend fun generateStream(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.9f
    ): Flow<String> = callbackFlow {
        FileLogger.d(TAG, "generateStream: prompt len=${prompt.length}")
        
        // 使用字符级别的流输出
        val fullText = StringBuilder()
        val callback = object : TokenCallback {
            override fun onToken(token: String) {
                fullText.append(token)
                trySend(token)
            }
        }
        
        setTokenCallback(callback)
        
        try {
            // 同步生成
            val result = withContext(Dispatchers.IO) {
                nativeGenerateStream(prompt, maxTokens, temperature, topK, topP, true)
            }
            
            // 清理回调
            setTokenCallback(null)
            
            // 发送完成信号
            close()
            
        } catch (e: Throwable) {
            FileLogger.e(TAG, "generateStream failed")
            setTokenCallback(null)
            close(e)
        }
        
        awaitClose { setTokenCallback(null) }
    }.flowOn(Dispatchers.IO)
    
    /**
     * 获取已加载模型名称
     */
    fun getLoadedModelName(): String? = _loadedModelName.value
    
    /**
     * 获取上下文大小
     */
    fun getContextSize(): Int = try { nativeGetContextSize() } catch (e: Throwable) { 0 }
    
    /**
     * 获取已使用内存 (MB)
     */
    fun getMemoryUsageMB(): Float {
        return try {
            nativeGetMemoryUsage() / (1024f * 1024f)
        } catch (e: Exception) {
            0f
        }
    }
    
    /**
     * 获取设备总内存 (MB)
     */
    fun getDeviceTotalMemoryMB(): Long {
        return try {
            val stat = java.io.File("/proc/meminfo").readLines()
            val memTotal = stat.firstOrNull { it.startsWith("MemTotal:") }
            memTotal?.split(Regex("\\s+"))?.get(1)?.toLongOrNull()?.div(1024) ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    /**
     * 更新推理统计信息
     */
    private fun updateInferenceStateInternal(durationMs: Long, tokensGenerated: Int) {
        totalInferences++
        totalTokensGenerated += tokensGenerated
        lastInferenceTimeMs = durationMs
        _inferenceStats.value = InferenceStats(
            lastInferenceTimeMs = lastInferenceTimeMs,
            tokensPerSecond = if (durationMs > 0) tokensGenerated * 1000f / durationMs else 0f,
            totalInferences = totalInferences,
            totalTokensGenerated = totalTokensGenerated,
            avgTokensPerSecond = if (totalInferences > 0) totalTokensGenerated * 1000f / (totalInferences * durationMs) else 0f
        )
    }
    
    // =============== P3: Enhanced interface methods implementations ===============
    
    fun estimateTokens(text: String): Int {
        var tokens = 0
        for (char in text) {
            tokens += if (char.code > 0x4E00) 1 else 0  // CJK = 1 token each
        }
        val asciiChars = text.count { it.code <= 0x4E00 }
        tokens += (asciiChars + 3) / 4
        return tokens
    }
    
    fun supportsToolCalling(): Boolean = false   // MNN parses tool calls from text
    
    fun supportsThinking(): Boolean = true   // Qwen 3.5 supports thinking mode
    
    fun getInferenceStats(): InferenceStats {
        return _inferenceStats.value
    }
}

