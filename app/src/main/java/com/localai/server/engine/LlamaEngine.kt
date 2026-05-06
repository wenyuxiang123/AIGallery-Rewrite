package com.localai.server.engine

import android.content.Context
import android.util.Log
import com.aigallery.rewrite.util.FileLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        private var libraryLoadError: String? = null
        
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
                
                // 按正确顺序加载库
                // localai-jni 依赖 llm -> MNN_Express -> MNN -> MNNOpenCV
                
                // 加载基础库
                System.loadLibrary("localai-jni")
                FileLogger.d(TAG, "loadLibraries: localai-jni loaded")
                
                System.loadLibrary("llm")
                FileLogger.d(TAG, "loadLibraries: llm loaded")
                
                System.loadLibrary("MNN_Express")
                FileLogger.d(TAG, "loadLibraries: MNN_Express loaded")
                
                System.loadLibrary("MNN")
                FileLogger.d(TAG, "loadLibraries: MNN loaded")
                
                // 可选库
                try {
                    System.loadLibrary("MNNOpenCV")
                    FileLogger.d(TAG, "loadLibraries: MNNOpenCV loaded (optional)")
                } catch (e: UnsatisfiedLinkError) {
                    FileLogger.w(TAG, "loadLibraries: MNNOpenCV not available (optional)")
                }
                
                // 加载 C++ 共享库
                System.loadLibrary("c++_shared")
                FileLogger.d(TAG, "loadLibraries: c++_shared loaded")
                
                librariesLoaded = true
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
    external fun nativeInitNative(): Boolean
    
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
    fun loadModel(path: String, nCtx: Int = 2048, nThreads: Int = 4): Boolean {
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
            
            // 调用 native 加载
            val success = nativeLoadModel(path, nCtx, nThreads)
            
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
     * 注意：当前实现为模拟流式，实际流式需要 native 层支持
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
        var totalTokens = 0
        
        return kotlinx.coroutines.flow.flow {
            try {
                // 调用 native 流式生成
                val streamResult = nativeGenerateStream(prompt, maxTokens, temperature, topK, topP)
                
                // 解析流式结果（按换行分隔 tokens）
                val tokens = streamResult.split("\n").filter { it.isNotEmpty() }
                for (token in tokens) {
                    emit(token)
                    totalTokens++
                    // 调用回调
                    tokenCallback?.onToken(token)
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
                emit("生成失败: ${e.message}")
            }
        }
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
