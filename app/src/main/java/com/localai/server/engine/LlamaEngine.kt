package com.localai.server.engine

import android.content.Context
import com.aigallery.rewrite.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MNN LLM 推理引擎 JNI 桥接类
 * 
 * 此类封装了 liblocalai-jni.so 中的 native 方法调用
 * JNI 方法签名绑定到 com/localai/server/engine/LlamaEngine
 */
class LlamaEngine private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "LlamaEngine"
        
        @Volatile
        private var instance: LlamaEngine? = null
        
        // 库加载状态
        private var librariesLoaded = false
        private var qnnAvailable = false
        private var libraryLoadError: String? = null
        
        // Native日志路径设置（静态方法，库加载后立即可用）
        @JvmStatic
        private external fun nativeSetLogFilePath(path: String)
        
        /**
         * 获取单例实例
         */
        fun getInstance(context: Context): LlamaEngine {
            return instance ?: synchronized(this) {
                instance ?: LlamaEngine(context.applicationContext).also { instance = it }
            }
        }
        
        /**
         * 初始化引擎（用于 Hilt 注入前的初始化）
         */
        fun initialize(context: Context): LlamaEngine {
            return getInstance(context)
        }
        
        /**
         * 加载 MNN native 库
         * 必须在调用其他 native 方法前调用
         * 
         * 关键：加载顺序参考 local-ai-server v3 项目
         * 先加载 localai-jni，它的 DT_NEEDED 会自动拉起 llm → MNN_Express → MNN
         * 这样所有库在同一个 dlopen 作用域内，C++ 虚函数表可以正确解析
         * 
         * 如果反过来先加载 MNN，再加载 localai-jni，每个 System.loadLibrary 
         * 创建独立的 RTLD_LOCAL 作用域，跨 so 的 C++ 符号不可见，导致推理输出乱码/循环
         * 
         * @return 是否加载成功
         */
        fun loadLibraries(): Boolean {
            if (librariesLoaded) {
                return true
            }
            
            try {
                FileLogger.d(TAG, "loadLibraries: starting library load")
                
                // MNN_SEP_BUILD=OFF: 所有代码（核心+Express+LLM）编译进一个libMNN.so
                // 先加载 localai-jni，其 DT_NEEDED 自动拉起 libMNN.so
                System.loadLibrary("localai-jni")
                FileLogger.d(TAG, "loadLibraries: localai-jni loaded (DT_NEEDED pulls in libMNN.so)")
                
                // 冗余加载（已是同一个 dlopen 作用域，不会重复加载）
                System.loadLibrary("MNN")
                FileLogger.d(TAG, "loadLibraries: MNN loaded (includes Express+LLM, SEP_BUILD=OFF)")
                
                // 可选库
                try {
                    System.loadLibrary("MNNOpenCV")
                    FileLogger.d(TAG, "loadLibraries: MNNOpenCV loaded (optional)")
                } catch (e: UnsatisfiedLinkError) {
                    FileLogger.w(TAG, "loadLibraries: MNNOpenCV not available (optional)")
                }
                
                // FastRPC库 - libQnnHtpV68Stub.so的DT_NEEDED依赖
                // Android 14 namespace隔离导致app无法访问/system/lib64/libcdsprpc.so
                // 从高通官方源码编译并打包进APK，必须在V68Stub之前加载
                try {
                    System.loadLibrary("cdsprpc")
                    FileLogger.d(TAG, "loadLibraries: cdsprpc loaded (FastRPC for QNN)")
                } catch (e: UnsatisfiedLinkError) {
                    FileLogger.w(TAG, "loadLibraries: cdsprpc not available, QNN disabled: ${e.message}")
                    qnnAvailable = false
                }

                // QNN/NPU运行时库（必须在MNN load()之前加载，否则dlopen找不到）
                // MNN QNN后端通过dlopen("libQnnHtp.so")等动态加载这些库
                // 必须全部加载成功才启用QNN，否则会native crash
                if (qnnAvailable) {
                    try {
                        System.loadLibrary("QnnSystem")
                        FileLogger.d(TAG, "loadLibraries: QnnSystem loaded (QNN/NPU)")
                        System.loadLibrary("QnnHtp")
                        FileLogger.d(TAG, "loadLibraries: QnnHtp loaded (QNN/NPU)")
                        System.loadLibrary("QnnHtpPrepare")
                        FileLogger.d(TAG, "loadLibraries: QnnHtpPrepare loaded (QNN/NPU)")
                        System.loadLibrary("QnnHtpV68Stub")
                        FileLogger.d(TAG, "loadLibraries: QnnHtpV68Stub loaded (QNN/NPU Hexagon V68)")
                        System.loadLibrary("QnnHtpV68Skel")
                        FileLogger.d(TAG, "loadLibraries: QnnHtpV68Skel loaded (QNN/NPU Hexagon V68 Skel)")
                        FileLogger.i(TAG, "loadLibraries: QNN/NPU fully available, will use NPU backend")
                    } catch (e: UnsatisfiedLinkError) {
                        FileLogger.w(TAG, "loadLibraries: QNN libraries not available (NPU disabled): ${e.message}")
                        qnnAvailable = false
                    }
                }
                
                librariesLoaded = true
                // 库加载成功后立即设置native层日志路径
                try {
                    nativeSetLogFilePath(FileLogger.getLogFilePath())
                    FileLogger.d(TAG, "loadLibraries: native log path set")
                } catch (e: Exception) {
                    FileLogger.w(TAG, "loadLibraries: failed to set native log path: ${e.message}")
                }
                FileLogger.i(TAG, "loadLibraries: all MNN libraries loaded successfully")
                return true
                
            } catch (e: Throwable) {
                val errorMsg = "loadLibraries failed: ${e.message}"
                FileLogger.e(TAG, errorMsg, e)
                libraryLoadError = errorMsg
                librariesLoaded = false
                return false
            }
        }
        
        /**
         * 获取库加载错误信息
         */
        fun getLibraryLoadError(): String? = libraryLoadError
        
        /**
         * 检查库是否已加载
         */
        fun isLibrariesLoaded(): Boolean = librariesLoaded
    }
    
    // 推理状态
    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()
    
    private val _loadedModelName = MutableStateFlow<String?>(null)
    val loadedModelName: StateFlow<String?> = _loadedModelName.asStateFlow()
    
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    
    private val _inferenceStats = MutableStateFlow(InferenceStats())
    val inferenceStats: StateFlow<InferenceStats> = _inferenceStats.asStateFlow()
    
    // Token 回调
    private var tokenCallback: TokenCallback? = null
    
    /**
     * 设置 token 回调（用于流式输出）
     */
    fun setTokenCallback(callback: TokenCallback?) {
        this.tokenCallback = callback
        initNativeCallback(callback)
    }
    
    /**
     * 初始化 native 回调
     */
    private external fun initNativeCallback(callback: TokenCallback?)
    
    // ==================== Native 方法声明 ====================
    
    /**
     * 初始化 native 层
     */
    
    /**
     * 加载模型
     * 
     * @param configPath 模型配置目录路径
     * @param nCtx 上下文窗口大小
     * @param nThreads 推理线程数
     * @return 是否加载成功
     */
    external fun nativeLoadModel(configPath: String, nCtx: Int, nThreads: Int, cacheDir: String, useQnn: Boolean): Boolean
    
    /**
     * 卸载模型
     */
    external fun nativeUnloadModel()
    
    /**
     * 检查模型是否已加载
     */
    external fun nativeIsModelLoaded(): Boolean
    
    /**
     * 生成文本（同步）
     * 
     * @param prompt 输入提示词
     * @param maxTokens 最大生成长度
     * @param temperature 温度参数
     * @param topK Top-K 采样
     * @param topP Top-P 采样
     * @return 生成结果
     */
    external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float
    ): String
    
    /**
     * 生成文本（流式）
     * 
     * @param prompt 输入提示词
     * @param maxTokens 最大生成长度
     * @param temperature 温度参数
     * @param topK Top-K 采样
     * @param topP Top-P 采样
     * @return 流式结果（每个 token 用换行分隔）
     */
    // Bug7修复: 添加useGPU参数
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
    external fun nativeGetLoadedModelName(): String
    
    /**
     * 获取上下文大小
     */
    external fun nativeGetContextSize(): Int
    
    /**
     * 获取内存使用量（字节）
     */
    external fun nativeGetMemoryUsage(): Long
    
    /**
     * 设置系统提示词
     */
    external fun nativeSetSystemPrompt(systemPrompt: String): Boolean
    
    /**
     * 重置对话上下文
     */
    external fun nativeResetConversation()
    
    /**
     * 获取最后错误信息
     */


    external fun nativeGetLastError(): String
    
    // ==================== Kotlin 封装方法 ====================
    
    /**
     * 加载模型
     * 
     * @param path 模型目录路径
     * @param nCtx 上下文窗口大小，默认 2048
     * @param nThreads 推理线程数，默认 4
     * @return 是否加载成功
     */
    fun loadModel(path: String, nCtx: Int = 2048, nThreads: Int = 0): Boolean {
        FileLogger.d(TAG, "loadModel: path=$path, nCtx=$nCtx, nThreads=$nThreads")
        
        if (!librariesLoaded) {
            FileLogger.e(TAG, "loadModel: libraries not loaded")
            _lastError.value = "Native libraries not loaded"
            return false
        }
        
        // 先卸载旧模型，避免切换模型时仍使用旧的推理引擎
        if (_isModelLoaded.value) {
            FileLogger.d(TAG, "loadModel: unloading previous model before loading new one")
            unloadModel()
        }
        
        try {
            // 验证模型目录存在
            val modelDir = File(path)
            if (!modelDir.exists() || !modelDir.isDirectory) {
                FileLogger.e(TAG, "loadModel: model directory not found: $path")
                _lastError.value = "Model directory not found: $path"
                return false
            }
            
            // 检查必要文件
            val requiredFiles = listOf("config.json", "llm.mnn", "tokenizer.txt")
            for (fileName in requiredFiles) {
                if (!File(modelDir, fileName).exists()) {
                    FileLogger.w(TAG, "loadModel: missing optional file: $fileName")
                }
            }
            
            // WORKAROUND: native层的路径解析逻辑会把目录名中含"."的截掉
            // (如 qwen2.5-0.5b-mnn 会被当成文件名)
            // 解决方案：传入config.json文件路径，让native层正确截取到模型目录
            val nativePath = File(modelDir, "config.json").absolutePath
            FileLogger.d(TAG, "loadModel: nativePath=$nativePath (config.json workaround)")
            
            // 创建缓存目录（native层硬编码了路径，需要确保存在）
            val cacheDir = File("/data/data/com.localai.server/cache/llm_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
                FileLogger.d(TAG, "loadModel: created cache dir ${cacheDir.absolutePath}")
            }
            // 也创建本应用包名的缓存目录
            val appCacheDir = File(context.cacheDir, "llm_cache")
            if (!appCacheDir.exists()) {
                appCacheDir.mkdirs()
                FileLogger.d(TAG, "loadModel: created app cache dir ${appCacheDir.absolutePath}")
            }
            
            // 调用 native 加载
            val cacheDirPath = appCacheDir.absolutePath
            val success = nativeLoadModel(nativePath, nCtx, nThreads, cacheDirPath, qnnAvailable)
            
            if (success) {
                _isModelLoaded.value = true
                _loadedModelName.value = nativeGetLoadedModelName()
                FileLogger.i(TAG, "loadModel: success, model=${_loadedModelName.value}")
            } else {
                val error = nativeGetLastError()
                _lastError.value = error
                FileLogger.e(TAG, "loadModel: failed, error=$error")
            }
            
            return success
            
        } catch (e: Throwable) {
            val errorMsg = "loadModel exception: ${e.message}"
            FileLogger.e(TAG, errorMsg, e)
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
            FileLogger.e(TAG, "unloadModel failed", e)
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
            val tokens = result.length / 4 // 估算
            val tokPerSec = if (elapsed > 0) tokens * 1000f / elapsed else 0f
            
            _inferenceStats.value = InferenceStats(
                lastInferenceTimeMs = elapsed,
                tokensGenerated = tokens,
                tokensPerSecond = tokPerSec
            )
            
            FileLogger.d(TAG, "generate: completed in ${elapsed}ms, ~$tokens tokens, $tokPerSec tok/s")
            return result
            
        } catch (e: Throwable) {
            FileLogger.e(TAG, "generate failed", e)
            _lastError.value = e.message
            throw e
        }
    }
    
    /**
     * 生成文本（流式）
     * 
     * 使用 callbackFlow 实现真正的流式输出
     * native 层通过 JNI 回调将每个 token 传递给 Kotlin 层
     */
    // Bug7修复: 添加useGPU参数，默认为false（避免GPU Vulkan性能问题）
    fun generateStream(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.9f,
        useGPU: Boolean = false  // Bug7修复: 默认false，避免GPU offload开销
    ): Flow<String> {
        FileLogger.d(TAG, "generateStream: prompt len=${prompt.length}")
        val startTime = System.currentTimeMillis()
        // Bug1修复: 每次推理前reset()清理KV cache，避免前几次推理乱码
        resetConversation()
        
        return callbackFlow {
            // 大buffer防止JNI回调线程上trySend因buffer满导致StackOverflow
            // 默认Channel.BUFFERED=64可能不够，显式设256
            var totalTokens = 0
            
            // 注册 token 回调，将每个 token 发送到 Flow
            val callback = object : TokenCallback {
                @Volatile private var inCallback = false
                override fun onToken(token: String) {
                    // 防止JNI回调重入导致StackOverflow
                    if (inCallback) return
                    inCallback = true
                    try {
                        trySend(token)
                        totalTokens++
                    } finally {
                        inCallback = false
                    }
                }
            }
            
            try {
                withContext(Dispatchers.IO) {
                    // 设置回调
                    setTokenCallback(callback)
                    
                    // 调用 native 流式生成
                    // Bug7修复: 传入useGPU参数
                    val streamResult = nativeGenerateStream(prompt, maxTokens, temperature, topK, topP, useGPU)
                    
                    // 如果 native 层没有通过回调返回（兼容旧实现），
                    // 则按换行分隔后逐个 emit
                    if (totalTokens == 0 && streamResult.isNotEmpty()) {
                        val tokens = streamResult.split("\n").filter { it.isNotEmpty() }
                        for (token in tokens) {
                            trySend(token)
                            totalTokens++
                        }
                    }
                    
                    // 清除回调
                    setTokenCallback(null)
                }
                
                val elapsed = System.currentTimeMillis() - startTime
                val tokPerSec = if (elapsed > 0) totalTokens * 1000f / elapsed else 0f
                
                _inferenceStats.value = InferenceStats(
                    lastInferenceTimeMs = elapsed,
                    tokensGenerated = totalTokens,
                    tokensPerSecond = tokPerSec
                )
                
                FileLogger.d(TAG, "generateStream: completed in ${elapsed}ms, $totalTokens tokens, $tokPerSec tok/s")
                
            } catch (e: Throwable) {
                FileLogger.e(TAG, "generateStream failed", e)
                _lastError.value = e.message
                trySend("生成失败: ${e.message}")
            } finally {
                setTokenCallback(null)
            }
            
            close()
        }.buffer(256)
    }
    
    /**
     * 设置系统提示词
     */
    fun setSystemPrompt(systemPrompt: String): Boolean {
        return try {
            nativeSetSystemPrompt(systemPrompt)
        } catch (e: Throwable) {
            FileLogger.e(TAG, "setSystemPrompt failed", e)
            false
        }
    }
    
    /**
     * 重置对话
     */
    fun resetConversation() {
        try {
            nativeResetConversation()
            FileLogger.d(TAG, "resetConversation: success")
        } catch (e: Throwable) {
            FileLogger.e(TAG, "resetConversation failed", e)
        }
    }
    
    /**
     * 获取内存使用（MB）
     */
    fun getMemoryUsageMB(): Float {
        return try {
            nativeGetMemoryUsage() / (1024f * 1024f)
        } catch (e: Throwable) {
            0f
        }
    }
    
    /**
     * 获取上下文大小
     */
    fun getContextSize(): Int {
        return try {
            nativeGetContextSize()
        } catch (e: Throwable) {
            0
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        FileLogger.d(TAG, "release")
        unloadModel()
        tokenCallback = null
        instance = null
    }
}

/**
 * Token 回调接口
 */
interface TokenCallback {
    /**
     * 当生成一个 token 时调用
     */
    fun onToken(token: String)
}

/**
 * 推理统计信息
 */
data class InferenceStats(
    val lastInferenceTimeMs: Long = 0,
    val tokensGenerated: Int = 0,
    val tokensPerSecond: Float = 0f
)
