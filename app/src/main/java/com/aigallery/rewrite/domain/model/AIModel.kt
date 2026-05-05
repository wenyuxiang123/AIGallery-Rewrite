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
 */
object ModelCatalog {
    val supportedModels = listOf(
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
            mirrorUrl = "https://modelscope.cn/models/meta-llama/Meta-Llama-3-8B-Instruct-GGUF/resolve/main/meta-llama-3-8b-instruct-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 6_000_000_000
        ),
        AIModel(
            id = "llama3-70b",
            name = "Llama 3 70B",
            description = "Meta开源最强模型，700亿参数，需要较大内存",
            provider = ModelProvider.HUGGING_FACE,
            size = "39GB",
            sizeBytes = 39_000_000_000,
            quantization = "Q4_K_M",
            parameters = "70B",
            downloadUrl = "https://huggingface.co/meta-llama/Meta-Llama-3-70B-Instruct-GGUF/resolve/main/meta-llama-3-70b-instruct-q4_k_m.gguf",
            mirrorUrl = "https://modelscope.cn/models/meta-llama/Meta-Llama-3-70B-Instruct-GGUF/resolve/main/meta-llama-3-70b-instruct-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 32_000_000_000
        ),
        // Qwen 2 series
        AIModel(
            id = "qwen2-7b",
            name = "Qwen 2 7B",
            description = "阿里通义千问2代，7B参数，中文能力强",
            provider = ModelProvider.ALI_QWEN,
            size = "4.4GB",
            sizeBytes = 4_400_000_000,
            quantization = "Q4_K_M",
            parameters = "7B",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2-7B-Instruct-GGUF/resolve/main/qwen2-7b-instruct-q4_k_m.gguf",
            mirrorUrl = "https://modelscope.cn/models/Qwen/Qwen2-7B-Instruct-GGUF/resolve/main/qwen2-7b-instruct-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 6_000_000_000
        ),
        AIModel(
            id = "qwen2-14b",
            name = "Qwen 2 14B",
            description = "阿里通义千问2代，14B参数，更强能力",
            provider = ModelProvider.ALI_QWEN,
            size = "8.8GB",
            sizeBytes = 8_800_000_000,
            quantization = "Q4_K_M",
            parameters = "14B",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2-14B-Instruct-GGUF/resolve/main/qwen2-14b-instruct-q4_k_m.gguf",
            mirrorUrl = "https://modelscope.cn/models/Qwen/Qwen2-14B-Instruct-GGUF/resolve/main/qwen2-14b-instruct-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 12_000_000_000
        ),
        // Gemma 2 series
        AIModel(
            id = "gemma2-9b",
            name = "Gemma 2 9B",
            description = "Google最新开源模型，90亿参数",
            provider = ModelProvider.GOOGLE,
            size = "5.5GB",
            sizeBytes = 5_500_000_000,
            quantization = "Q4_K_M",
            parameters = "9B",
            downloadUrl = "https://huggingface.co/google/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-q4_k_m.gguf",
            mirrorUrl = "https://modelscope.cn/models/google/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 8_000_000_000
        ),
        AIModel(
            id = "gemma2-27b",
            name = "Gemma 2 27B",
            description = "Google开源最强模型，270亿参数",
            provider = ModelProvider.GOOGLE,
            size = "16GB",
            sizeBytes = 16_000_000_000,
            quantization = "Q4_K_M",
            parameters = "27B",
            downloadUrl = "https://huggingface.co/google/gemma-2-27b-it-GGUF/resolve/main/gemma-2-27b-it-q4_k_m.gguf",
            mirrorUrl = "https://modelscope.cn/models/google/gemma-2-27b-it-GGUF/resolve/main/gemma-2-27b-it-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 20_000_000_000
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
            mirrorUrl = "https://modelscope.cn/models/AI-ModelScope/Mistral-7B-Instruct-v0.2-GGUF/resolve/main/mistral-7b-instruct-v0.2-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 6_000_000_000
        ),
        AIModel(
            id = "mistral-8x7b",
            name = "Mixtral 8x7B",
            description = "Mistral MoE模型，8x7B稀疏专家",
            provider = ModelProvider.MISTRAL,
            size = "24GB",
            sizeBytes = 24_000_000_000,
            quantization = "Q4_K_M",
            parameters = "8x7B",
            downloadUrl = "https://huggingface.co/TheBloke/Mixtral-8x7B-Instruct-v0.1-GGUF/resolve/main/mixtral-8x7b-instruct-v0.1-q4_k_m.gguf",
            mirrorUrl = "https://modelscope.cn/models/AI-ModelScope/Mixtral-8x7B-Instruct-v0.1-GGUF/resolve/main/mixtral-8x7b-instruct-v0.1-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 18_000_000_000
        ),
        // Phi-3 series
        AIModel(
            id = "phi3-7b",
            name = "Phi-3 Mini 7B",
            description = "Microsoft小而强模型，7B参数",
            provider = ModelProvider.MICROSOFT,
            size = "4.1GB",
            sizeBytes = 4_100_000_000,
            quantization = "Q4_K_M",
            parameters = "7B",
            downloadUrl = "https://huggingface.co/Microsoft/Phi-3-mini-128k-instruct-gguf/resolve/main/phi-3-mini-128k-instruct-q4_k_m.gguf",
            mirrorUrl = "https://modelscope.cn/models/AI-ModelScope/Phi-3-mini-128k-instruct-gguf/resolve/main/phi-3-mini-128k-instruct-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 6_000_000_000
        ),
        AIModel(
            id = "phi3-14b",
            name = "Phi-3 Small 14B",
            description = "Microsoft中等规模模型，14B参数",
            provider = ModelProvider.MICROSOFT,
            size = "8.1GB",
            sizeBytes = 8_100_000_000,
            quantization = "Q4_K_M",
            parameters = "14B",
            downloadUrl = "https://huggingface.co/Microsoft/Phi-3-small-128k-instruct-gguf/resolve/main/phi-3-small-128k-instruct-q4_k_m.gguf",
            mirrorUrl = "https://modelscope.cn/models/AI-ModelScope/Phi-3-small-128k-instruct-gguf/resolve/main/phi-3-small-128k-instruct-q4_k_m.gguf",
            hash = null,
            isMultimodal = false,
            minMemory = 10_000_000_000
        )
    )
}
