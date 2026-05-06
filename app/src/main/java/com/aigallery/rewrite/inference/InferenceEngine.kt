package com.aigallery.rewrite.inference

import kotlinx.coroutines.flow.Flow

/**
 * AI 推理引擎核心接口
 * 定义所有推理引擎必须实现的标准接口
 */
interface InferenceEngine {

    /**
     * 引擎名称
     */
    val name: String

    /**
     * 引擎版本
     */
    val version: String

    /**
     * 是否已经初始化
     */
    val isInitialized: Boolean

    /**
     * 初始化引擎
     * @param modelPath 模型文件路径
     * @param config 推理配置
     * @return 是否初始化成功
     */
    suspend fun initialize(modelPath: String, config: InferenceConfig): Boolean

    /**
     * 执行同步推理
     * @param prompt 输入提示词
     * @param config 推理参数配置
     * @return 推理结果
     */
    suspend fun infer(prompt: String, config: InferenceConfig = InferenceConfig()): InferenceResult

    /**
     * 执行流式推理
     * @param prompt 输入提示词
     * @param config 推理参数配置
     * @return 流式输出 Flow
     */
    suspend fun inferStream(prompt: String, config: InferenceConfig = InferenceConfig()): Flow<String>

    /**
     * 生成嵌入向量
     * @param text 输入文本
     * @return 向量数组
     */
    suspend fun generateEmbedding(text: String): FloatArray

    /**
     * 释放资源
     */
    suspend fun release()

    /**
     * 获取支持的模型类型
     */
    fun getSupportedModelTypes(): List<String>
}

/**
 * 推理配置参数
 */
data class InferenceConfig(
    /** 最大生成长度 */
    val maxLength: Int = 2048,
    /** 温度参数 (0-1) */
    val temperature: Float = 0.7f,
    /** Top-P 采样 (0-1) */
    val topP: Float = 0.9f,
    /** Top-K 采样 */
    val topK: Int = 50,
    /** 重复惩罚 */
    val repeatPenalty: Float = 1.1f,
    /** 使用 GPU 加速 */
    val useGPU: Boolean = true,
    /** 线程数 */
    val numThreads: Int = 4,
    /** 上下文窗口大小 */
    val contextWindow: Int = 4096,
    /** 停止词列表 */
    val stopWords: List<String> = emptyList()
)

/**
 * 推理结果封装
 */
data class InferenceResult(
    /** 输出文本 */
    val text: String,
    /** 推理耗时（毫秒） */
    val inferenceTimeMs: Long,
    /** Token 生成数量 */
    val tokenCount: Int,
    /** 生成速度 (tokens/s) */
    val tokensPerSecond: Float,
    /** 是否成功 */
    val success: Boolean,
    /** 错误信息 */
    val errorMessage: String? = null
)

/**
 * 推理引擎类型枚举
 */
enum class EngineType {
    MNN,
    ONNX,
    TFLITE,
    GGML
}

/**
 * 推理引擎工厂
 */
object InferenceEngineFactory {

    private var activeEngine: InferenceEngine? = null

    /**
     * 创建指定类型的推理引擎
     */
    fun createEngine(type: EngineType, context: Context): InferenceEngine {
        return when (type) {
            EngineType.MNN -> MnnInferenceEngine(context)
            EngineType.ONNX -> OnnxInferenceEngine()
            EngineType.TFLITE -> TFLiteInferenceEngine()
            EngineType.GGML -> GgmlInferenceEngine()
        }
    }

    /**
     * 获取当前活动的引擎
     */
    fun getActiveEngine(): InferenceEngine? = activeEngine

    /**
     * 设置活动引擎
     */
    suspend fun setActiveEngine(engine: InferenceEngine) {
        activeEngine?.release()
        activeEngine = engine
    }

    /**
     * 释放所有资源
     */
    suspend fun releaseAll() {
        activeEngine?.release()
        activeEngine = null
    }
}
