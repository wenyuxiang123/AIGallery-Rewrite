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
    val downloadProgress: Float = 0f
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
 * Predefined model catalog with mirror URLs for Chinese users
 * Using ModelScope as primary source for Chinese users
 */
object ModelCatalog {
    val supportedModels = listOf(
        // Qwen 2.5 series (small models, ideal for mobile)
        AIModel(
            id = "qwen2.5-0.5b",
            name = "Qwen 2.5 0.5B",
            description = "阿里通义千问2.5，0.5B参数，极小体积，适合手机运行",
            provider = ModelProvider.ALI_QWEN,
            size = "390MB",
            sizeBytes = 390_000_000,
            quantization = "Q4_K_M",
            parameters = "0.5B",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            mirrorUrl = "https://www.modelscope.cn/models/qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/master/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 1_000_000_000
        ),
        AIModel(
            id = "qwen2.5-1.5b",
            name = "Qwen 2.5 1.5B",
            description = "阿里通义千问2.5，1.5B参数，轻量高效，中文能力强",
            provider = ModelProvider.ALI_QWEN,
            size = "990MB",
            sizeBytes = 990_000_000,
            quantization = "Q4_K_M",
            parameters = "1.5B",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            mirrorUrl = "https://www.modelscope.cn/models/qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/master/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 2_000_000_000
        ),
        AIModel(
            id = "qwen2.5-3b",
            name = "Qwen 2.5 3B",
            description = "阿里通义千问2.5，3B参数，性能均衡，适合大多数手机",
            provider = ModelProvider.ALI_QWEN,
            size = "1.9GB",
            sizeBytes = 1_900_000_000,
            quantization = "Q4_K_M",
            parameters = "3B",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            mirrorUrl = "https://www.modelscope.cn/models/qwen/Qwen2.5-3B-Instruct-GGUF/resolve/master/qwen2.5-3b-instruct-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 4_000_000_000
        ),
        AIModel(
            id = "qwen2.5-7b",
            name = "Qwen 2.5 7B",
            description = "阿里通义千问2.5，7B参数，中文能力强，性能优秀",
            provider = ModelProvider.ALI_QWEN,
            size = "4.4GB",
            sizeBytes = 4_400_000_000,
            quantization = "Q4_K_M",
            parameters = "7B",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_k_m.gguf",
            mirrorUrl = "https://www.modelscope.cn/models/qwen/Qwen2.5-7B-Instruct-GGUF/resolve/master/qwen2.5-7b-instruct-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 6_000_000_000
        ),
        // Qwen 2 series (legacy, kept for compatibility)
        AIModel(
            id = "qwen2-7b",
            name = "Qwen 2 7B (Legacy)",
            description = "阿里通义千问2代，7B参数，中文能力强",
            provider = ModelProvider.ALI_QWEN,
            size = "4.4GB",
            sizeBytes = 4_400_000_000,
            quantization = "Q4_K_M",
            parameters = "7B",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2-7B-Instruct-GGUF/resolve/main/qwen2-7b-instruct-q4_k_m.gguf",
            mirrorUrl = "https://www.modelscope.cn/models/qwen/Qwen2-7B-Instruct-GGUF/resolve/master/qwen2-7b-instruct-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 6_000_000_000
        ),
        // Llama 3 series
        AIModel(
            id = "llama3-8b",
            name = "Llama 3 8B",
            description = "Meta最新开源大模型，80亿参数，性能优秀",
            provider = ModelProvider.HUGGING_FACE,
            size = "4.9GB",
            sizeBytes = 4_900_000_000,
            quantization = "Q4_K_M",
            parameters = "8B",
            downloadUrl = "https://huggingface.co/meta-llama/Meta-Llama-3-8B-Instruct-GGUF/resolve/main/meta-llama-3-8b-instruct-q4_k_m.gguf",
            mirrorUrl = "https://www.modelscope.cn/models/meta-llama/Meta-Llama-3-8B-Instruct-GGUF/resolve/master/meta-llama-3-8b-instruct-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 6_000_000_000
        ),
        // Gemma 2 series
        AIModel(
            id = "gemma2-2b",
            name = "Gemma 2 2B",
            description = "Google轻量开源模型，20亿参数，适合移动设备",
            provider = ModelProvider.GOOGLE,
            size = "1.5GB",
            sizeBytes = 1_500_000_000,
            quantization = "Q4_K_M",
            parameters = "2B",
            downloadUrl = "https://huggingface.co/google/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-q4_k_m.gguf",
            mirrorUrl = "https://www.modelscope.cn/models/google/gemma-2-2b-it-GGUF/resolve/master/gemma-2-2b-it-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 3_000_000_000
        ),
        // Phi-3 series
        AIModel(
            id = "phi3-3.8b",
            name = "Phi-3 Mini 3.8B",
            description = "Microsoft小而强模型，3.8B参数，优秀推理能力",
            provider = ModelProvider.MICROSOFT,
            size = "2.3GB",
            sizeBytes = 2_300_000_000,
            quantization = "Q4_K_M",
            parameters = "3.8B",
            downloadUrl = "https://huggingface.co/Microsoft/Phi-3-mini-128k-instruct-gguf/resolve/main/phi-3-mini-128k-instruct-q4_k_m.gguf",
            mirrorUrl = "https://www.modelscope.cn/models/AI-ModelScope/Phi-3-mini-128k-instruct-gguf/resolve/master/phi-3-mini-128k-instruct-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 4_000_000_000
        ),
        // Mistral series
        AIModel(
            id = "mistral-7b",
            name = "Mistral 7B",
            description = "Mistral经典开源模型，7B参数",
            provider = ModelProvider.MISTRAL,
            size = "4.1GB",
            sizeBytes = 4_100_000_000,
            quantization = "Q4_K_M",
            parameters = "7B",
            downloadUrl = "https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.2-GGUF/resolve/main/mistral-7b-instruct-v0.2-q4_k_m.gguf",
            mirrorUrl = "https://www.modelscope.cn/models/AI-ModelScope/Mistral-7B-Instruct-v0.2-GGUF/resolve/master/mistral-7b-instruct-v0.2-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 6_000_000_000
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
     * Get small models suitable for mobile devices
     */
    fun getSmallModels(): List<AIModel> {
        return supportedModels.filter { it.parameters.contains("0.5") || it.parameters.contains("1.5") || it.parameters.contains("2") || it.parameters.contains("3") }
    }
}
