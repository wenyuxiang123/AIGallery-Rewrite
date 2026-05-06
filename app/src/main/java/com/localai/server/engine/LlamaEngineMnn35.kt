package com.localai.server.engine

import android.content.Context
import android.util.Log
import com.aigallery.rewrite.util.FileLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * MNN LLM 推理引擎 JNI 桥接类 - MNN 3.5.0 兼容版本
 * 
 * 基于 MNN Android SDK (https://github.com/AdribMahmud101/mnn-android-sdk)
 * 此类封装了 libllm.so 中的 native 方法调用
 * 
 * 重要：此类需要与新的 liblocalai-jni-v35.so 配套使用
 * 编译JNI需要使用 MNN Android SDK 的 mnn_llm.cpp 源码
 */
class LlamaEngineMnn35 private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "LlamaEngineMnn35"
        
        @Volatile
        private var instance: LlamaEngineMnn35? = null
        
        // 库加载状态
        private var librariesLoaded = false
        private var libraryLoadError: String? = null
        
        /**
         * 获取单例实例
         */
        fun getInstance(context: Context): LlamaEngineMnn35 {
            return instance ?: synchronized(this) {
                instance ?: LlamaEngineMnn35(context.applicationContext).also { instance = it }
            }
        }
        
        /**
         * 初始化引擎（用于 Hilt 注入前的初始化）
         */
        fun initialize(context: Context): LlamaEngineMnn35 {
            return getInstance(context)
        }
        
        /**
         * 加载 MNN 3.5.0 native 库
         * 
         * 加载顺序（MNN 3.5.0 SDK版本）：
         * 1. libmnncore.so - 核心桥接
         * 2. libMNN.so - MNN核心
         * 3. libMNN_CL.so - OpenCL后端（可选）
         * 4. libMNN_Vulkan.so - Vulkan后端（可选）
         * 5. libMNN_Express.so - Express子图
         * 6. libllm.so - LLM核心
         * 7. libc++_shared.so - C++运行时
         * 
         * @return 是否加载成功
         */
        fun loadLibraries(): Boolean {
            if (librariesLoaded) {
                return true
            }
            
            try {
                FileLogger.d(TAG, "loadLibraries: starting MNN 3.5.0 library load")
                
                // MNN 3.5.0 SDK 加载顺序
                System.loadLibrary("mnncore")
                FileLogger.d(TAG, "loadLibraries: mnncore loaded")
                
                System.loadLibrary("MNN")
                FileLogger.d(TAG, "loadLibraries: MNN loaded")
                
                // 可选后端
                try {
                    System.loadLibrary("MNN_CL")
                    FileLogger.d(TAG, "loadLibraries: MNN_CL loaded (optional)")
                } catch (e: UnsatisfiedLinkError) {
                    FileLogger.w(TAG, "loadLibraries: MNN_CL not available (optional)")
                }
                
                try {
                    System.loadLibrary("MNN_Vulkan")
                    FileLogger.d(TAG, "loadLibraries: MNN_Vulkan loaded (optional)")
                } catch (e: UnsatisfiedLinkError) {
                    FileLogger.w(TAG, "loadLibraries: MNN_Vulkan not available (optional)")
                }
                
                System.loadLibrary("MNN_Express")
                FileLogger.d(TAG, "loadLibraries: MNN_Express loaded")
                
                System.loadLibrary("llm")
                FileLogger.d(TAG, "loadLibraries: llm loaded")
                
                // 加载 C++ 共享库
                System.loadLibrary("c++_shared")
                FileLogger.d(TAG, "loadLibraries: c++_shared loaded")
                
                librariesLoaded = true
                FileLogger.i(TAG, "loadLibraries: all MNN 3.5.0 libraries loaded successfully")
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
    
    // ==================== Native 句柄 ====================
    
    // MNN 3.5.0 使用句柄模式，不同于旧版本的单例模式
    private var llmHandle: Long = 0
    
    // 推理状态
    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()
    
    private val _loadedModelName = MutableStateFlow<String?>(null)
    val loadedModelName: StateFlow<String?> = _loadedModelName.asStateFlow()
    
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    
    private val _inferenceStats = MutableStateFlow(InferenceStats())
    val inferenceStats: StateFlow<InferenceStats> = _inferenceStats.asStateFlow()
    
    // Token 回调（用于流式输出）
    private var tokenCallback: TokenCallback? = null
    
    /**
     * 设置 token 回调（用于流式输出）
     */
    fun setTokenCallback(callback: TokenCallback?) {
        this.tokenCallback = callback
    }
    
    // ==================== MNN 3.5.0 Native 方法声明 ====================
    
    /**
     * 创建 LLM 实例
     * @param configPath llm_config.json 路径
     * @return 句柄，0表示失败
     */
    external fun nativeCreate(configPath: String): Long
    
    /**
     * 加载模型
     * @param handle LLM句柄
     * @return 是否加载成功
     */
    external fun nativeLoad(handle: Long): Boolean
    
    /**
     * 同步推理
     * @param handle LLM句柄
     * @param prompt 输入提示词
     * @param maxNewTokens 最大生成长度
     * @param stopString 停止字符串（可选）
     * @return 生成结果
     */
    external fun nativeResponse(
        handle: Long,
        prompt: String,
        maxNewTokens: Int,
        stopString: String?
    ): String
    
    /**
     * 流式推理
     * @param handle LLM句柄
     * @param prompt 输入提示词
     * @param maxNewTokens 最大生成长度
     * @param stopString 停止字符串（可选）
     */
    external fun nativeResponseStreaming(
        handle: Long,
        prompt: String,
        maxNewTokens: Int,
        stopString: String?
    )
    
    /**
     * 重置对话上下文
     * @param handle LLM句柄
     */
    external fun nativeReset(handle: Long)
    
    /**
     * 销毁 LLM 实例
     * @param handle LLM句柄
     */
    external fun nativeDestroy(handle: Long)
    
    /**
     * 设置配置（JSON格式）
     * @param handle LLM句柄
     * @param configJson 配置JSON
     */
    external fun nativeSetConfig(handle: Long, configJson: String)
    
    /**
     * 计算token数
     * @param handle LLM句柄
     * @param text 文本
     * @return token数
     */
    external fun nativeCountTokens(handle: Long, text: String): Int
    
    /**
     * 获取预填充时间（毫秒）
     * @param handle LLM句柄
     */
    external fun nativeGetPrefillMs(handle: Long): Long
    
    /**
     * 获取解码时间（毫秒）
     * @param handle LLM句柄
     */
    external fun nativeGetDecodeMs(handle: Long): Long
    
    /**
     * 获取生成的token数
     * @param handle LLM句柄
     */
    external fun nativeGetGeneratedTokens(handle: Long): Int
    
    /**
     * 停止推理
     * @param handle LLM句柄
     */
    external fun nativeStop(handle: Long)
    
    // ==================== Kotlin 封装方法 ====================
    
    /**
     * 加载模型
     * 
     * @param path 模型目录路径
     * @param nCtx 上下文窗口大小（不用于MNN 3.5.0，保留兼容性）
     * @param nThreads 推理线程数（通过set_config设置）
     * @return 是否加载成功
     */
    fun loadModel(path: String, nCtx: Int = 2048, nThreads: Int = 4): Boolean {
        FileLogger.d(TAG, "loadModel: path=$path, nThreads=$nThreads")
        
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
            
            // 查找 llm_config.json
            val configFile = File(modelDir, "llm_config.json")
            if (!configFile.exists()) {
                FileLogger.e(TAG, "loadModel: llm_config.json not found")
                _lastError.value = "llm_config.json not found"
                return false
            }
            
            // 创建 LLM 实例
            llmHandle = nativeCreate(configFile.absolutePath)
            if (llmHandle == 0L) {
                FileLogger.e(TAG, "loadModel: nativeCreate failed")
                _lastError.value = "Failed to create LLM instance"
                return false
            }
            
            // 设置线程数
            nativeSetConfig(llmHandle, """{"num_threads":$nThreads}""")
            
            // 加载模型
            val success = nativeLoad(llmHandle)
            
            if (success) {
                _isModelLoaded.value = true
                _loadedModelName.value = modelDir.name
                FileLogger.i(TAG, "loadModel: success, model=${_loadedModelName.value}")
            } else {
                _lastError.value = "Model load failed"
                FileLogger.e(TAG, "loadModel: failed")
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
            if (llmHandle != 0L) {
                nativeDestroy(llmHandle)
                llmHandle = 0L
            }
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
        FileLogger.d(TAG, "generate: prompt len=${prompt.length}, maxTokens=$maxTokens")
        
        if (llmHandle == 0L) {
            FileLogger.e(TAG, "generate: LLM not loaded")
            return ""
        }
        
        val startTime = System.currentTimeMillis()
        
        try {
            // MNN 3.5.0 不直接支持 temperature/topK/topP 参数
            // 需要通过 set_config 设置采样参数
            val configJson = """
                {
                    "temperature": $temperature,
                    "top_k": $topK,
                    "top_p": $topP
                }
            """.trimIndent()
            nativeSetConfig(llmHandle, configJson)
            
            val result = nativeResponse(llmHandle, prompt, maxTokens, null)
            
            val elapsed = System.currentTimeMillis() - startTime
            val generatedTokens = nativeGetGeneratedTokens(llmHandle)
            val tokPerSec = if (elapsed > 0) generatedTokens * 1000f / elapsed else 0f
            
            _inferenceStats.value = InferenceStats(
                lastInferenceTimeMs = elapsed,
                tokensGenerated = generatedTokens,
                tokensPerSecond = tokPerSec
            )
            
            FileLogger.d(TAG, "generate: completed in ${elapsed}ms, $generatedTokens tokens, $tokPerSec tok/s")
            return result
            
        } catch (e: Throwable) {
            FileLogger.e(TAG, "generate failed", e)
            _lastError.value = e.message
            return ""
        }
    }
    
    /**
     * 生成文本（流式）
     */
    fun generateStream(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.9f
    ): Flow<String> {
        FileLogger.d(TAG, "generateStream: prompt len=${prompt.length}")
        
        return kotlinx.coroutines.flow.flow {
            if (llmHandle == 0L) {
                FileLogger.e(TAG, "generateStream: LLM not loaded")
                emit("")
                return@flow
            }
            
            val startTime = System.currentTimeMillis()
            var totalTokens = 0
            
            try {
                // 设置采样参数
                val configJson = """
                    {
                        "temperature": $temperature,
                        "top_k": $topK,
                        "top_p": $topP
                    }
                """.trimIndent()
                nativeSetConfig(llmHandle, configJson)
                
                // 调用流式推理
                nativeResponseStreaming(llmHandle, prompt, maxTokens, null)
                
                // 流式结果通过 tokenCallback 回调
                // 注意：实际实现需要修改 nativeResponseStreaming 来调用 Kotlin 回调
                
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
     * 设置系统提示词（MNN 3.5.0 通过配置JSON设置）
     */
    fun setSystemPrompt(systemPrompt: String): Boolean {
        return try {
            if (llmHandle != 0L) {
                nativeSetConfig(llmHandle, """{"system_prompt":"${systemPrompt.replace("\"", "\\\"")}"}""")
                true
            } else {
                false
            }
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
            if (llmHandle != 0L) {
                nativeReset(llmHandle)
                FileLogger.d(TAG, "resetConversation: success")
            }
        } catch (e: Throwable) {
            FileLogger.e(TAG, "resetConversation failed", e)
        }
    }
    
    /**
     * 获取内存使用（MB）
     * 注意：MNN 3.5.0 可能不直接支持此方法
     */
    fun getMemoryUsageMB(): Float {
        // MNN 3.5.0 SDK 版本可能需要其他方式获取内存使用
        return try {
            0f // 占位
        } catch (e: Throwable) {
            0f
        }
    }
    
    /**
     * 获取上下文大小
     */
    fun getContextSize(): Int {
        // MNN 3.5.0 可能从配置读取
        return try {
            0 // 占位
        } catch (e: Throwable) {
            0
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        unloadModel()
    }
}
