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
    HUGGING_FACE("HuggingFace", "ModelScope"),
    MODELSCOPE("ModelScope", "ModelScope"),
    ALI_QWEN("阿里通义千问", "镜像"),
    GOOGLE("Google", "镜像"),
    MISTRAL("Mistral", "镜像"),
    MICROSOFT("Microsoft", "镜像")
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
        // ============ Qwen 2.5 系列 MNN 模型 ============
        AIModel(
            id = "qwen2.5-0.5b-mnn",
            name = "Qwen 2.5 0.5B (MNN)",
            description = "MNN优化版，骁龙778G+流畅运行，极低内存占用",
            provider = ModelProvider.MODELSCOPE,
            size = "800MB",
            sizeBytes = 800_000_000,
            quantization = "MNN-INT4",
            parameters = "0.5B",
            downloadUrl = "MNN://Qwen2.5-0.5B-Instruct-MNN",
            mirrorUrl = "MNN://Qwen2.5-0.5B-Instruct-MNN",
            hash = null,
            isMultimodal = false,
            minMemory = 1_500_000_000,
            isMnnModel = true
        ),
        AIModel(
            id = "qwen2.5-1.5b-mnn",
            name = "Qwen 2.5 1.5B (MNN)",
            description = "MNN优化版，中文能力强，推荐骁龙778G+",
            provider = ModelProvider.MODELSCOPE,
            size = "1.6GB",
            sizeBytes = 1_600_000_000,
            quantization = "MNN-INT4",
            parameters = "1.5B",
            downloadUrl = "MNN://Qwen2.5-1.5B-Instruct-MNN",
            mirrorUrl = "MNN://Qwen2.5-1.5B-Instruct-MNN",
            hash = null,
            isMultimodal = false,
            minMemory = 2_500_000_000,
            isMnnModel = true
        ),
        AIModel(
            id = "qwen2.5-3b-mnn",
            name = "Qwen 2.5 3B (MNN)",
            description = "MNN优化版，性能均衡，大内存手机推荐",
            provider = ModelProvider.MODELSCOPE,
            size = "2.8GB",
            sizeBytes = 2_800_000_000,
            quantization = "MNN-INT4",
            parameters = "3B",
            downloadUrl = "MNN://Qwen2.5-3B-Instruct-MNN",
            mirrorUrl = "MNN://Qwen2.5-3B-Instruct-MNN",
            hash = null,
            isMultimodal = false,
            minMemory = 4_500_000_000,
            isMnnModel = true
        ),
        AIModel(
            id = "qwen2.5-7b-mnn",
            name = "Qwen 2.5 7B (MNN)",
            description = "MNN优化版，强劲性能，需要8GB以上内存",
            provider = ModelProvider.MODELSCOPE,
            size = "4.2GB",
            sizeBytes = 4_200_000_000,
            quantization = "MNN-INT4",
            parameters = "7B",
            downloadUrl = "MNN://Qwen2.5-7B-Instruct-MNN",
            mirrorUrl = "MNN://Qwen2.5-7B-Instruct-MNN",
            hash = null,
            isMultimodal = false,
            minMemory = 6_500_000_000,
            isMnnModel = true
        ),
        
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
        ),
        
        // ============ Qwen2-VL 多模态系列 MNN ============
        AIModel(
            id = "qwen2-vl-2b-mnn",
            name = "Qwen2-VL 2B (MNN)",
            description = "Qwen2视觉版，支持图片理解，骁龙8系推荐",
            provider = ModelProvider.MODELSCOPE,
            size = "2.2GB",
            sizeBytes = 2_200_000_000,
            quantization = "MNN-INT4",
            parameters = "2B",
            downloadUrl = "MNN://Qwen2-VL-2B-Instruct-MNN",
            mirrorUrl = "MNN://Qwen2-VL-2B-Instruct-MNN",
            hash = null,
            isMultimodal = true,
            minMemory = 3_500_000_000,
            isMnnModel = true
        ),
        
        // ============ Qwen2.5-Omni 多模态系列 MNN ============
        AIModel(
            id = "qwen2.5-omni-3b-mnn",
            name = "Qwen2.5-Omni 3B (MNN)",
            description = "Qwen2.5全模态版，支持语音+视觉+文本",
            provider = ModelProvider.MODELSCOPE,
            size = "3.0GB",
            sizeBytes = 3_000_000_000,
            quantization = "MNN-INT4",
            parameters = "3B",
            downloadUrl = "MNN://Qwen2.5-Omni-3B-MNN",
            mirrorUrl = "MNN://Qwen2.5-Omni-3B-MNN",
            hash = null,
            isMultimodal = true,
            minMemory = 5_000_000_000,
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
