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
import android.system.Os
import android.system.OsConstants

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
         * @return 是否加载成功
         */
        fun loadLibraries(): Boolean {
            if (librariesLoaded) {
                return true
            }
            
            try {
                FileLogger.d(TAG, "loadLibraries: starting library load")
                
                // 按正确顺序加载库（依赖库必须先加载）
                // localai-jni 依赖 llm -> MNN_Express -> MNN -> c++_shared
                
                // 加载基础库
                // 关键修复：Android的System.loadLibrary使用RTLD_LOCAL，
                // 导致so之间C++符号（vtable/虚函数）不可见，MNN推理输出乱码/循环。
                // 解决方案：对MNN相关的so使用dlopen(RTLD_GLOBAL)加载，
                // 让符号全局可见，这样liblocalai-jni.so才能正确解析libllm.so的符号。
                
                System.loadLibrary("c++_shared")
                FileLogger.d(TAG, "loadLibraries: c++_shared loaded")
                
                // 用dlopen RTLD_GLOBAL加载MNN相关so，使符号全局可见
                loadLibraryGlobal("libMNN.so")
                FileLogger.d(TAG, "loadLibraries: MNN loaded (RTLD_GLOBAL)")
                
                loadLibraryGlobal("libMNN_Express.so")
                FileLogger.d(TAG, "loadLibraries: MNN_Express loaded (RTLD_GLOBAL)")
                
                loadLibraryGlobal("libllm.so")
                FileLogger.d(TAG, "loadLibraries: llm loaded (RTLD_GLOBAL)")
                
                // 可选库
                try {
                    loadLibraryGlobal("libMNNOpenCV.so")
                    FileLogger.d(TAG, "loadLibraries: MNNOpenCV loaded (optional, RTLD_GLOBAL)")
                } catch (e: UnsatisfiedLinkError) {
                    FileLogger.w(TAG, "loadLibraries: MNNOpenCV not available (optional)")
                }
                
                // JNI 桥接库最后加载（依赖所有其他库）
                System.loadLibrary("localai-jni")
                FileLogger.d(TAG, "loadLibraries: localai-jni loaded")
                
                
                librariesLoaded = true
                // 库加载成功后立即设置native层日志路径，确保后续所有native调用都有日志
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
        
        /**
         * 使用dlopen RTLD_GLOBAL加载so库，使符号全局可见。
         * 
         * Android的System.loadLibrary内部使用RTLD_LOCAL，导致：
         * - libllm.so导出的C++符号（createLLM/response/set_config等）对其他so不可见
         * - liblocalai-jni.so调用这些符号时，虚函数表(vtable)解析失败
         * - 结果：MNN推理输出乱码或token循环
         * 
         * RTLD_GLOBAL让所有符号加入全局符号表，后续加载的so可以正确解析。
         */
        private fun loadLibraryGlobal(libraryName: String) {
            try {
                // 尝试从应用nativeLibraryDir加载
                val libDir = "/data/app/~~"
                // 方法1：使用System.loadLibrary先加载（确保so文件找到）
                // 然后用dlopen RTLD_GLOBAL重新打开使符号全局可见
                val path = findLibraryPath(libraryName)
                if (path != null) {
                    Os.dlopen(path, OsConstants.RTLD_NOW or OsConstants.RTLD_GLOBAL)
                    FileLogger.d(TAG, "loadLibraryGlobal: $libraryName loaded from $path (RTLD_GLOBAL)")
                } else {
                    // fallback: 直接用System.loadLibrary
                    val libName = libraryName.removePrefix("lib").removeSuffix(".so")
                    System.loadLibrary(libName)
                    FileLogger.w(TAG, "loadLibraryGlobal: fallback to System.loadLibrary for $libraryName")
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "loadLibraryGlobal: failed to load $libraryName: ${e.message}", e)
                throw UnsatisfiedLinkError("Failed to load $libraryName with RTLD_GLOBAL: ${e.message}")
            }
        }
        
        /**
         * 查找so库的绝对路径
         */
        private fun findLibraryPath(libraryName: String): String? {
            try {
                // 读取/proc/self/maps查找已加载的库路径
                val maps = java.io.File("/proc/self/maps").readText()
                for (line in maps.lines()) {
                    if (line.contains(libraryName) && line.contains(".so")) {
                        // 提取路径（maps格式：地址 权限 偏移 设备 inode 路径）
                        val parts = line.trim().split("\s+".toRegex())
                        if (parts.size >= 6) {
                            val path = parts.last()
                            if (path.endsWith(libraryName) || path.endsWith("/$libraryName")) {
                                return path
                            }
                        }
                    }
                }
                
                // 如果maps中找不到（库还没加载），尝试nativeLibraryDir
                // 先用System.loadLibrary触发加载
                val libName = libraryName.removePrefix("lib").removeSuffix(".so")
                try {
                    System.loadLibrary(libName)
                } catch (e: UnsatisfiedLinkError) {
                    return null
                }
                
                // 再查一次maps
                val maps2 = java.io.File("/proc/self/maps").readText()
                for (line in maps2.lines()) {
                    if (line.contains(libraryName) && line.contains(".so")) {
                        val parts = line.trim().split("\s+".toRegex())
                        if (parts.size >= 6) {
                            val path = parts.last()
                            if (path.endsWith(libraryName) || path.endsWith("/$libraryName")) {
                                return path
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "findLibraryPath: error: ${e.message}")
            }
            return null
        }
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
    external fun nativeLoadModel(configPath: String, nCtx: Int, nThreads: Int): Boolean
    
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
    external fun nativeGenerateStream(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float
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
    fun loadModel(path: String, nCtx: Int = 2048, nThreads: Int = 6): Boolean {
        FileLogger.d(TAG, "loadModel: path=$path, nCtx=$nCtx, nThreads=$nThreads")
        
        if (!librariesLoaded) {
            FileLogger.e(TAG, "loadModel: libraries not loaded")
            _lastError.value = "Native libraries not loaded"
            return false
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
            val success = nativeLoadModel(nativePath, nCtx, nThreads)
            
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
    fun generateStream(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.9f
    ): Flow<String> {
        FileLogger.d(TAG, "generateStream: prompt len=${prompt.length}")
        val startTime = System.currentTimeMillis()
        
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
                    val streamResult = nativeGenerateStream(prompt, maxTokens, temperature, topK, topP)
                    
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
