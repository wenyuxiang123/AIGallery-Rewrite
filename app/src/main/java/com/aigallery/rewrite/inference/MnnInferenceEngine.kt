package com.aigallery.rewrite.inference

import android.content.Context
import android.util.Log
import com.aigallery.rewrite.util.FileLogger
import com.localai.server.engine.InferenceStats
import com.localai.server.engine.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * MNN 推理引擎实现
 * 
 * 基于 Mobile AI Server v4.0-MNN 的 liblocalai-jni.so native 库
 * 提供本地 LLM 推理能力
 */
class MnnInferenceEngine(
    private val context: Context
) : InferenceEngine {
    
    companion object {
        private const val TAG = "MnnInferenceEngine"
        
        // 默认配置
        const val DEFAULT_MAX_TOKENS = 512
        const val DEFAULT_CONTEXT_LENGTH = 2048
        const val DEFAULT_THREADS = 4  // 骁龙778G+ 8核，4线程最优
    }
    
    // MNN 引擎实例
    private var llamaEngine: LlamaEngine? = null
    
    // 库加载状态
    private var librariesLoaded = false
    private var libraryLoadAttempted = false
    
    override val name: String = "MNN Inference Engine"
    override val version: String = "4.0.0"
    
    override val isInitialized: Boolean
        get() = librariesLoaded && llamaEngine?.isModelLoaded?.value == true
    
    /**
     * 尝试加载 native 库
     * @return 是否加载成功
     */
    private fun ensureLibrariesLoaded(): Boolean {
        if (libraryLoadAttempted) {
            return librariesLoaded
        }
        
        libraryLoadAttempted = true
        
        FileLogger.d(TAG, "ensureLibrariesLoaded: loading MNN libraries...")
        
        // 检查 native 库文件是否存在
        val jniDir = context.getDir("jniLibs", Context.MODE_PRIVATE)
        val arm64Dir = java.io.File(jniDir, "arm64-v8a")
        if (!arm64Dir.exists()) {
            // 使用 APK 内置的库
            FileLogger.d(TAG, "ensureLibrariesLoaded: using APK bundled libraries")
        }
        
        val success = LlamaEngine.loadLibraries()
        librariesLoaded = success
        
        if (success) {
            FileLogger.i(TAG, "ensureLibrariesLoaded: success")
        } else {
            val error = LlamaEngine.getLibraryLoadError()
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
                
                // 2. 初始化 LlamaEngine
                llamaEngine = LlamaEngine.getInstance(context)
                
                // 3. 计算最优线程数
                val nThreads = config.numThreads.takeIf { it > 0 } ?: DEFAULT_THREADS
                val nCtx = config.contextWindow.takeIf { it > 0 } ?: DEFAULT_CONTEXT_LENGTH
                
                FileLogger.d(TAG, "initialize: nThreads=$nThreads, nCtx=$nCtx")
                
                // 4. 加载模型
                val success = llamaEngine?.loadModel(
                    path = modelPath,
                    nCtx = nCtx,
                    nThreads = nThreads
                ) ?: false
                
                if (success) {
                    val modelName = llamaEngine?.loadedModelName?.value ?: "unknown"
                    val memoryMB = llamaEngine?.getMemoryUsageMB() ?: 0f
                    FileLogger.i(TAG, "initialize: success, model=$modelName, memory=${memoryMB}MB")
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
                
                engine.generateStream(
                    prompt = prompt,
                    maxTokens = config.maxLength,
                    temperature = config.temperature,
                    topK = config.topK,
                    topP = config.topP
                ).collect { token ->
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
            // MNN 当前版本可能不支持 embedding
            // 返回零向量或抛出异常
            FileLogger.w(TAG, "generateEmbedding: not supported by MNN LLM")
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
        return listOf(
            "Qwen", "Qwen2", "Qwen2.5", "Qwen3",
            "Llama", "Llama2", "Llama3",
            "Mistral", "Gemma", "Phi", "Baichuan", "ChatGLM"
        )
    }
    
    /**
     * 获取当前推理统计
     */
    fun getInferenceStats(): InferenceStats {
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
}
