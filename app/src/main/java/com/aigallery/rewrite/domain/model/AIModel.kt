package com.aigallery.rewrite.domain.model

/**
 * AI Model domain model
 */
data class AIModel(
    val id: String,
    val name: String,
    val description: String,
    val provider: ModelProvider,
    val size: String,
    val sizeBytes: Long,
    val quantization: String,
    val parameters: String,
    val downloadUrl: String,
    val mirrorUrl: String,
    val hash: String?,
    val isMultimodal: Boolean,
    val minMemory: Long,
    val status: ModelStatus = ModelStatus.NOT_DOWNLOADED,
    val downloadProgress: Float = 0f,
    val isMnnModel: Boolean = false  // MNN 模型为目录结构
)

enum class ModelProvider(val displayName: String, val mirrorName: String) {
    MODELSCOPE("ModelScope", "ModelScope")
}

enum class ModelStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    PAUSED,
    DOWNLOADED,
    DOWNLOAD_FAILED,
    INITIALIZING,
    READY,
    FAILED
}

/**
 * Predefined model catalog with MNN optimized models
 * All models use ModelScope as primary source for Chinese users
 * 
 * Note: GGUF models (mistral-7b, phi3-3.8b, gemma2-2b, llama3-8b, qwen2-7b, qwen2.5-7b)
 * have been removed as they do not have MNN format versions available on ModelScope
 */
object ModelCatalog {
    val supportedModels = listOf(
        // ============ Qwen 3.5 系列 MNN 模型 ============
        AIModel(
            id = "qwen3.5-0.8b-mnn",
            name = "Qwen3.5 0.8B (MNN)",
            description = "通义千问3.5最小版，混合注意力架构，超低内存",
            provider = ModelProvider.MODELSCOPE,
            size = "650MB",
            sizeBytes = 650_000_000,
            quantization = "MNN-INT4",
            parameters = "0.8B",
            downloadUrl = "MNN://Qwen3.5-0.8B-MNN",
            mirrorUrl = "MNN://Qwen3.5-0.8B-MNN",
            hash = null,
            isMultimodal = true,
            minMemory = 1_200_000_000,
            isMnnModel = true
        ),
        AIModel(
            id = "qwen3.5-2b-mnn",
            name = "Qwen3.5 2B (MNN)",
            description = "通义千问3.5轻量版，75%线性注意力，省内存",
            provider = ModelProvider.MODELSCOPE,
            size = "1.5GB",
            sizeBytes = 1_500_000_000,
            quantization = "MNN-INT4",
            parameters = "2B",
            downloadUrl = "MNN://Qwen3.5-2B-MNN",
            mirrorUrl = "MNN://Qwen3.5-2B-MNN",
            hash = null,
            isMultimodal = true,
            minMemory = 2_500_000_000,
            isMnnModel = true
        ),
        AIModel(
            id = "qwen3.5-4b-mnn",
            name = "Qwen3.5 4B (MNN)",
            description = "通义千问3.5均衡版，混合注意力，最强中文能力",
            provider = ModelProvider.MODELSCOPE,
            size = "2.8GB",
            sizeBytes = 2_800_000_000,
            quantization = "MNN-INT4",
            parameters = "4B",
            downloadUrl = "MNN://Qwen3.5-4B-MNN",
            mirrorUrl = "MNN://Qwen3.5-4B-MNN",
            hash = null,
            isMultimodal = true,
            minMemory = 4_500_000_000,
            isMnnModel = true
        ),
        AIModel(
            id = "qwen3.5-9b-mnn",
            name = "Qwen3.5 9B (MNN)",
            description = "通义千问3.5性能版，高精度，支持256K超长上下文",
            provider = ModelProvider.MODELSCOPE,
            size = "5.2GB",
            sizeBytes = 5_200_000_000,
            quantization = "MNN-INT4",
            parameters = "9B",
            downloadUrl = "MNN://Qwen3.5-9B-MNN",
            mirrorUrl = "MNN://Qwen3.5-9B-MNN",
            hash = null,
            isMultimodal = true,
            minMemory = 8_000_000_000,
            isMnnModel = true
        )
    )


    /**
     * Get model by ID
     */
    fun getModelById(modelId: String): AIModel? {
        return supportedModels.find { it.id == modelId }
    }

    /**
     * Get models by provider
     */
    fun getModelsByProvider(provider: ModelProvider): List<AIModel> {
        return supportedModels.filter { it.provider == provider }
    }

    /**
     * Get MNN models only
     */
    fun getMnnModels(): List<AIModel> {
        return supportedModels.filter { it.isMnnModel }
    }

    /**
     * Get small models suitable for mobile devices
     */
    fun getSmallModels(): List<AIModel> {
        return supportedModels.filter { 
            it.parameters.contains("0.5") || 
            it.parameters.contains("0.8") ||
            it.parameters.contains("1.5") || 
            it.parameters.contains("2") || 
            it.parameters.contains("3") 
        }
    }

    /**
     * Get multimodal models
     */
    fun getMultimodalModels(): List<AIModel> {
        return supportedModels.filter { it.isMultimodal }
    }

    /**
     * Get text-only models
     */
    fun getTextModels(): List<AIModel> {
        return supportedModels.filter { !it.isMultimodal }
    }
}
