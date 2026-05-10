package com.aigallery.rewrite.inference

import android.content.Context
import com.aigallery.rewrite.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 推理管理器 - 统一管理所有 AI 推理引擎
 * 提供单一入口点供整个应用使用
 */
@Singleton
class InferenceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "InferenceManager"
    
    private var currentEngine: InferenceEngine? = null
    private var currentModelId: String? = null
    
    /**
     * 检查是否有活动的推理引擎
     */
    fun hasActiveEngine(): Boolean = currentEngine?.isInitialized == true
    
    /**
     * 获取当前活动的引擎
     */
    fun getCurrentEngine(): InferenceEngine? = currentEngine
    
    /**
     * 获取当前加载的模型 ID
     */
    fun getCurrentModelId(): String? = currentModelId
    
    /**
     * 加载指定模型
     * @param modelId 模型 ID
     * @param engineType 引擎类型
     * @param config 推理配置
     * @return 是否加载成功
     */
    suspend fun loadModel(
        modelId: String,
        engineType: EngineType = EngineType.MNN,
        config: InferenceConfig = InferenceConfig()
    ): Boolean {
        FileLogger.d(TAG, "Loading model: $modelId with engine: $engineType")
        
        // 先释放旧引擎
        release()
        
        // 获取模型路径
        val modelPath = getModelPath(modelId)
        if (modelPath == null) {
            FileLogger.e(TAG, "Model not found: $modelId")
            return false
        }
        
        // 创建并初始化引擎
        val engine = InferenceEngineFactory.createEngine(engineType, context)
        val success = engine.initialize(modelPath, config)
        
        if (success) {
            currentEngine = engine
            currentModelId = modelId
            InferenceEngineFactory.setActiveEngine(engine)
            FileLogger.d(TAG, "Model loaded successfully: $modelId")
        } else {
            FileLogger.e(TAG, "Failed to load model: $modelId")
        }
        
        return success
    }
    
    /**
     * 执行推理
     */
    suspend fun infer(prompt: String, config: InferenceConfig = InferenceConfig()): InferenceResult {
        val engine = currentEngine
        if (engine == null || !engine.isInitialized) {
            FileLogger.e(TAG, "infer: no active engine available")
            return InferenceResult(
                text = "模型尚未加载，请先在模型管理中加载模型",
                inferenceTimeMs = 0,
                tokenCount = 0,
                tokensPerSecond = 0f,
                success = false,
                errorMessage = "No active inference engine"
            )
        }
        return engine.infer(prompt, config)
    }
    
    /**
     * 执行流式推理
     */
    suspend fun inferStream(
        prompt: String,
        config: InferenceConfig = InferenceConfig()
    ): Flow<String> {
        val engine = currentEngine
        if (engine == null || !engine.isInitialized) {
            FileLogger.e(TAG, "inferStream: no active engine available")
            return flow { emit("模型尚未加载，请先在模型管理中加载模型") }
        }
        return engine.inferStream(prompt, config)
    }
    
    /**
     * 生成嵌入向量
     */
    suspend fun generateEmbedding(text: String): FloatArray {
        val engine = currentEngine
        if (engine == null || !engine.isInitialized) {
            FileLogger.w(TAG, "generateEmbedding: no active engine")
            return FloatArray(0)
        }
        return engine.generateEmbedding(text)
    }
    
    /**
     * 释放当前引擎
     */
    suspend fun release() {
        currentEngine?.release()
        currentEngine = null
        currentModelId = null
        InferenceEngineFactory.releaseAll()
        FileLogger.d(TAG, "Inference engine released")
    }
    
    /**
     * 获取所有可用引擎类型
     */
    fun getAvailableEngineTypes(): List<EngineType> {
        return EngineType.values().toList()
    }
    
    /**
     * 获取支持的模型类型
     */
    fun getSupportedModelTypes(engineType: EngineType): List<String> {
        val engine = InferenceEngineFactory.createEngine(engineType, context)
        return engine.getSupportedModelTypes()
    }
    
    /**
     * 获取模型文件路径
     * 优先从应用私有目录查找，不存在则返回 null（需要先下载）
     */
    private fun getModelPath(modelId: String): String? {
        val modelDir = context.getDir("models", Context.MODE_PRIVATE)
        
        // Bug3修复: MNN 模型是目录结构，不是 .mnn 文件
        // 检查 models/{modelId}/ 目录是否存在
        val mnnModelDir = java.io.File(modelDir, modelId)
        if (mnnModelDir.exists() && mnnModelDir.isDirectory) {
            // 检查目录中是否包含必要的 MNN 文件
            val llmMnn = java.io.File(mnnModelDir, "llm.mnn")
            val config = java.io.File(mnnModelDir, "config.json")
            if (llmMnn.exists() || config.exists()) {
                return mnnModelDir.absolutePath
            }
        }
        
        // 检查是否有 gguf 格式
        val ggufFile = java.io.File(modelDir, "$modelId.gguf")
        if (ggufFile.exists()) {
            return ggufFile.absolutePath
        }
        
        return null
    }
    
    /**
     * 检查模型是否已下载
     */
    fun isModelDownloaded(modelId: String): Boolean {
        return getModelPath(modelId) != null
    }
    
    /**
     * 获取模型存储目录
     */
    fun getModelsDirectory(): String {
        val modelDir = context.getDir("models", Context.MODE_PRIVATE)
        return modelDir.absolutePath
    }
    
    /**
     * 估算模型推理速度（用于性能测试）
     */
    suspend fun estimatePerformance(modelId: String): Float {
        if (!isModelDownloaded(modelId)) return -1f
        
        // 简单性能测试
        val startTime = System.nanoTime()
        repeat(10) {
            generateEmbedding("test prompt $it")
        }
        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
        
        // 返回估算的 tokens/second
        return 100f / elapsed
    }
}
