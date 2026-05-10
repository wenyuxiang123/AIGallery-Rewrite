package com.aigallery.rewrite.inference

import com.aigallery.rewrite.util.FileLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * ONNX Runtime 推理引擎 - 未实现
 * 
 * 此引擎为预留接口，当前不支持 ONNX 模型格式。
 * 尝试使用将返回初始化失败。
 */
class OnnxInferenceEngine : InferenceEngine {
    override val name: String = "ONNX Runtime Engine"
    override val version: String = "0.0.0-not-implemented"
    override var isInitialized: Boolean = false
        private set

    override suspend fun initialize(modelPath: String, config: InferenceConfig): Boolean {
        FileLogger.w(name, "ONNX Runtime engine is not implemented yet")
        isInitialized = false
        return false
    }

    override suspend fun infer(prompt: String, config: InferenceConfig): InferenceResult {
        return InferenceResult(
            text = "",
            inferenceTimeMs = 0,
            tokenCount = 0,
            tokensPerSecond = 0f,
            success = false,
            errorMessage = "ONNX Runtime engine is not implemented"
        )
    }

    override suspend fun inferStream(prompt: String, config: InferenceConfig): Flow<String> {
        return flow { emit("[ONNX引擎未实现]") }
    }

    override suspend fun generateEmbedding(text: String): FloatArray {
        FileLogger.w(name, "generateEmbedding not available - engine not implemented")
        return FloatArray(0)
    }

    override suspend fun release() {
        isInitialized = false
    }

    override fun getSupportedModelTypes(): List<String> {
        return emptyList()
    }
}

/**
 * TensorFlow Lite 推理引擎 - 未实现
 * 
 * 此引擎为预留接口，当前不支持 TFLite 模型格式。
 */
class TFLiteInferenceEngine : InferenceEngine {
    override val name: String = "TensorFlow Lite Engine"
    override val version: String = "0.0.0-not-implemented"
    override var isInitialized: Boolean = false
        private set

    override suspend fun initialize(modelPath: String, config: InferenceConfig): Boolean {
        FileLogger.w(name, "TFLite engine is not implemented yet")
        isInitialized = false
        return false
    }

    override suspend fun infer(prompt: String, config: InferenceConfig): InferenceResult {
        return InferenceResult(
            text = "",
            inferenceTimeMs = 0,
            tokenCount = 0,
            tokensPerSecond = 0f,
            success = false,
            errorMessage = "TFLite engine is not implemented"
        )
    }

    override suspend fun inferStream(prompt: String, config: InferenceConfig): Flow<String> {
        return flow { emit("[TFLite引擎未实现]") }
    }

    override suspend fun generateEmbedding(text: String): FloatArray {
        FileLogger.w(name, "generateEmbedding not available - engine not implemented")
        return FloatArray(0)
    }

    override suspend fun release() {
        isInitialized = false
    }

    override fun getSupportedModelTypes(): List<String> {
        return emptyList()
    }
}

/**
 * GGML (llama.cpp) 推理引擎 - 未实现
 * 
 * 此引擎为预留接口，当前不支持 GGML 模型格式。
 */
class GgmlInferenceEngine : InferenceEngine {
    override val name: String = "GGML (llama.cpp) Engine"
    override val version: String = "0.0.0-not-implemented"
    override var isInitialized: Boolean = false
        private set

    override suspend fun initialize(modelPath: String, config: InferenceConfig): Boolean {
        FileLogger.w(name, "GGML engine is not implemented yet")
        isInitialized = false
        return false
    }

    override suspend fun infer(prompt: String, config: InferenceConfig): InferenceResult {
        return InferenceResult(
            text = "",
            inferenceTimeMs = 0,
            tokenCount = 0,
            tokensPerSecond = 0f,
            success = false,
            errorMessage = "GGML engine is not implemented"
        )
    }

    override suspend fun inferStream(prompt: String, config: InferenceConfig): Flow<String> {
        return flow { emit("[GGML引擎未实现]") }
    }

    override suspend fun generateEmbedding(text: String): FloatArray {
        FileLogger.w(name, "generateEmbedding not available - engine not implemented")
        return FloatArray(0)
    }

    override suspend fun release() {
        isInitialized = false
    }

    override fun getSupportedModelTypes(): List<String> {
        return emptyList()
    }
}
