package com.aigallery.rewrite.inference

import android.content.Context
import com.aigallery.rewrite.util.FileLogger
import com.localai.server.engine.LlamaEngineMnn35
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * MNN 推理引擎实现 - MNN 3.5.0 版本
 * 
 * 基于 MNN Android SDK (https://github.com/AdribMahmud101/mnn-android-sdk)
 * 提供本地 LLM 推理能力
 * 
 * 与旧版 MnnInferenceEngine 的主要区别：
 * 1. 使用 LlamaEngineMnn35 而非 LlamaEngine
 * 2. 支持 Thinking 模式
 * 3. 支持精确 token 计数
 * 4. 使用 stopString 而非 temperature/topK/topP
 */
class MnnInferenceEngineV35(
    private val context: Context
) : InferenceEngine {
    
    companion object {
        private const val TAG = "MnnInferenceEngineV35"
        
        // 默认配置
        const val DEFAULT_MAX_TOKENS = 512
        const val DEFAULT_CONTEXT_LENGTH = 2048
        const val DEFAULT_THREADS = 0
    }
    
    // MNN 3.5.0 引擎实例
    private var llamaEngine: LlamaEngineMnn35? = null
    
    // 库加载状态
    private var librariesLoaded = false
    private var libraryLoadAttempted = false
    
    override val name: String = "MNN 3.5.0 Inference Engine"
    override val version: String = "3.5.0"
    
    override val isInitialized: Boolean
        get() = librariesLoaded && llamaEngine?.isModelLoaded?.value == true
    
    // Thinking 模式支持
    var enableThinking: Boolean = false
    
    /**
     * 尝试加载 native 库
     * @return 是否加载成功
     */
    private fun ensureLibrariesLoaded(): Boolean {
        if (libraryLoadAttempted) {
            return librariesLoaded
        }
        
        libraryLoadAttempted = true
        
        FileLogger.d(TAG, "ensureLibrariesLoaded: loading MNN 3.5.0 libraries...")
        
        // 使用 MNN 3.5.0 的库加载方法
        val success = LlamaEngineMnn35.loadLibraries()
        librariesLoaded = success
        
        if (success) {
            FileLogger.i(TAG, "ensureLibrariesLoaded: success")
        } else {
            val error = LlamaEngineMnn35.getLibraryLoadError()
            FileLogger.e(TAG, "ensureLibrariesLoaded: failed - $error")
        }
        
        return success
    }
    
    override suspend fun initialize(modelPath: String, config: InferenceConfig): Boolean {
        FileLogger.d(TAG, "initialize: modelPath=$modelPath, config=$config")
        
        return withContext(Dispatchers.IO) {
            try {
                // 1. 加载 native 库
                if (!ensureLibrariesLoaded()) {
                    FileLogger.e(TAG, "initialize: failed to load native libraries")
                    return@withContext false
                }
                
                // 2. 初始化 LlamaEngineMnn35
                llamaEngine = LlamaEngineMnn35.getInstance(context)
                
                // 3. 计算最优线程数
                val nThreads = config.numThreads.takeIf { it > 0 } ?: DEFAULT_THREADS
                
                FileLogger.d(TAG, "initialize: nThreads=$nThreads")
                
                // 4. 加载模型
                val success = llamaEngine?.loadModel(
                    path = modelPath,
                    nCtx = DEFAULT_CONTEXT_LENGTH,
                    nThreads = nThreads
                ) ?: false
                
                if (success) {
                    val modelName = llamaEngine?.loadedModelName?.value ?: "unknown"
                    FileLogger.i(TAG, "initialize: success, model=$modelName")
                    
                    // 5. 设置 Thinking 模式
                    enableThinking = config.enableThinking
                    
                } else {
                    val error = llamaEngine?.lastError?.value ?: "unknown"
                    FileLogger.e(TAG, "initialize: model load failed, error=$error")
                }
                
                return@withContext success
                
            } catch (e: Throwable) {
                FileLogger.e(TAG, "initialize: exception", e)
                return@withContext false
            }
        }
    }
    
    override suspend fun infer(prompt: String, config: InferenceConfig): InferenceResult {
        val startTime = System.currentTimeMillis()
        FileLogger.d(TAG, "infer: prompt length=${prompt.length}")
        
        return withContext(Dispatchers.IO) {
            try {
                val engine = llamaEngine
                    ?: throw IllegalStateException("Engine not initialized - call initialize() first")
                
                if (!engine.isModelLoaded.value) {
                    throw IllegalStateException("Model not loaded - call initialize() with valid model path")
                }
                
                // 如果需要Thinking模式，修改系统提示
                if (config.enableThinking && !enableThinking) {
                    enableThinking = true
                    engine.setSystemPrompt(
                        "You are a helpful assistant. When appropriate, show your reasoning process " +
                        "inside <think>...</think> tags before providing your final answer."
                    )
                }
                
                val result = engine.generate(
                    prompt = prompt,
                    maxTokens = config.maxLength,
                    temperature = config.temperature,
                    topK = config.topK,
                    topP = config.topP
                )
                
                val elapsed = System.currentTimeMillis() - startTime
                val stats = engine.inferenceStats.value
                
                FileLogger.d(TAG, "infer: completed in ${elapsed}ms, output length=${result.length}")
                
                InferenceResult(
                    text = result,
                    inferenceTimeMs = elapsed,
                    tokenCount = stats.tokensGenerated,
                    tokensPerSecond = stats.tokensPerSecond,
                    success = true
                )
                
            } catch (e: Throwable) {
                FileLogger.e(TAG, "infer: failed", e)
                InferenceResult(
                    text = "",
                    inferenceTimeMs = System.currentTimeMillis() - startTime,
                    tokenCount = 0,
                    tokensPerSecond = 0f,
                    success = false,
                    errorMessage = e.message
                )
            }
        }
    }
    
    override suspend fun inferStream(prompt: String, config: InferenceConfig): Flow<String> {
        FileLogger.d(TAG, "inferStream: prompt length=${prompt.length}")
        
        return flow {
            try {
                val engine = llamaEngine
                    ?: throw IllegalStateException("Engine not initialized - call initialize() first")
                
                if (!engine.isModelLoaded.value) {
                    throw IllegalStateException("Model not loaded - call initialize() with valid model path")
                }
                
                // 如果需要Thinking模式
                if (config.enableThinking && !enableThinking) {
                    enableThinking = true
                    engine.setSystemPrompt(
                        "You are a helpful assistant. When appropriate, show your reasoning process " +
                        "inside <think>...</think> tags before providing your final answer."
                    )
                }
                
                engine.generateStream(
                    prompt = prompt,
                    maxTokens = config.maxLength,
                    temperature = config.temperature,
                    topK = config.topK,
                    topP = config.topP
                ).collect { token: String ->
                    emit(token)
                }
                
            } catch (e: Throwable) {
                FileLogger.e(TAG, "inferStream: failed", e)
                emit("推理失败: ${e.message}")
            }
        }.flowOn(Dispatchers.IO)
    }
    
    override suspend fun generateEmbedding(text: String): FloatArray {
        FileLogger.d(TAG, "generateEmbedding: text length=${text.length}")
        
        return withContext(Dispatchers.IO) {
            // MNN 3.5.0 当前版本可能不支持 embedding
            FileLogger.w(TAG, "generateEmbedding: not supported by MNN 3.5.0 LLM")
            FloatArray(768) { 0f }
        }
    }
    
    override suspend fun release() {
        FileLogger.d(TAG, "release")
        
        withContext(Dispatchers.IO) {
            try {
                llamaEngine?.release()
                llamaEngine = null
                librariesLoaded = false
                libraryLoadAttempted = false
                FileLogger.i(TAG, "release: success")
            } catch (e: Throwable) {
                FileLogger.e(TAG, "release: failed", e)
            }
        }
    }
    
    override fun getSupportedModelTypes(): List<String> {
        // MNN 3.5.0 支持的模型类型
        return listOf(
            "Qwen", "Qwen2", "Qwen2.5", "Qwen3", "Qwen3.5",
            "Llama", "Llama2", "Llama3",
            "Mistral", "Gemma", "Phi", "Baichuan", "ChatGLM",
            "DeepSeek", "InternLM", "Yi"
        )
    }
    
    /**
     * 获取当前推理统计
     */
    override fun getInferenceStats(): InferenceStats {
        return llamaEngine?.inferenceStats?.value ?: InferenceStats()
    }
    
    /**
     * 获取内存使用（MB）
     */
    fun getMemoryUsageMB(): Float {
        return llamaEngine?.getMemoryUsageMB() ?: 0f
    }
    
    /**
     * 获取已加载模型名称
     */
    fun getLoadedModelName(): String? {
        return llamaEngine?.loadedModelName?.value
    }
    
    /**
     * 获取上下文大小
     */
    fun getContextSize(): Int {
        return llamaEngine?.getContextSize() ?: 0
    }
    
    /**
     * 设置系统提示词
     */
    fun setSystemPrompt(systemPrompt: String): Boolean {
        return llamaEngine?.setSystemPrompt(systemPrompt) ?: false
    }
    
    /**
     * 重置对话上下文
     */
    fun resetConversation() {
        llamaEngine?.resetConversation()
    }
    
    /**
     * 启用/禁用 Thinking 模式
     */
    fun setThinkingEnabled(enabled: Boolean) {
        enableThinking = enabled
    }
}
