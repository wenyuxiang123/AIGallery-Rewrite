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
    val isMnnModel: Boolean = false,
    val tier: ModelTier = ModelTier.DAILY  // 模型所属档位
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
 * 模型档位 — PH0 硬件加速优化
 * 
 * 5个档位对应不同的速度/质量权衡：
 * - 极速：0.8B INT4，最快响应，~30-40 tok/s
 * - 日常：1.5B INT4，日常聊天，~15-25 tok/s
 * - 推荐⭐：3B INT4 + 0.8B 推测解码，速度+质量甜点，~15-25 tok/s
 * - 质量：3B INT4 单独使用，好回答不追求速度，~8-12 tok/s
 * - 极限：4B INT4 + KV-TQ4，最大模型，~6-9 tok/s
 */
enum class ModelTier(
    val displayName: String,
    val description: String,
    val targetSpeed: String,
    val requiresSpeculativeDecoding: Boolean,
    val draftModelId: String? = null
) {
    SPEED(
        displayName = "极速",
        description = "最快响应，简单问答和工具调用草稿",
        targetSpeed = "~30-40 tok/s",
        requiresSpeculativeDecoding = false
    ),
    DAILY(
        displayName = "日常",
        description = "日常聊天，轻度推理",
        targetSpeed = "~15-25 tok/s",
        requiresSpeculativeDecoding = false
    ),
    RECOMMENDED(
        displayName = "推荐⭐",
        description = "速度+质量甜点（推测解码）",
        targetSpeed = "~15-25 tok/s",
        requiresSpeculativeDecoding = true,
        draftModelId = "qwen3.5-0.8b-int4"
    ),
    QUALITY(
        displayName = "质量",
        description = "好回答，不追求速度",
        targetSpeed = "~8-12 tok/s",
        requiresSpeculativeDecoding = false
    ),
    MAXIMUM(
        displayName = "极限",
        description = "最大模型，质量最好",
        targetSpeed = "~6-9 tok/s",
        requiresSpeculativeDecoding = false
    )
}

/**
 * Predefined model catalog with MNN INT4 optimized models
 * 
 * PH0 模型清单：5档位制，统一 INT4 量化
 * 所有模型均从 ModelScope 下载 MNN 预导出的 INT4 版本
 */
object ModelCatalog {
    val supportedModels = listOf(
        // ============ 极速档位 ============
        AIModel(
            id = "qwen3.5-0.8b-int4",
            name = "极速 · Qwen3.5-0.8B",
            description = "最快响应，简单问答和工具调用草稿，~30-40 tok/s",
            provider = ModelProvider.MODELSCOPE,
            size = "400MB",
            sizeBytes = 400_000_000,
            quantization = "INT4 + KV-INT8",
            parameters = "0.8B",
            downloadUrl = "MNN://Qwen3.5-0.8B-Instruct-MNN",
            mirrorUrl = "MNN://Qwen3.5-0.8B-Instruct-MNN",
            hash = null,
            isMultimodal = false,
            minMemory = 800_000_000,
            isMnnModel = true,
            tier = ModelTier.SPEED
        ),
        // ============ 日常档位 ============
        AIModel(
            id = "qwen3.5-1.5b-int4",
            name = "日常 · Qwen3.5-1.5B",
            description = "日常聊天和轻度推理，~15-25 tok/s",
            provider = ModelProvider.MODELSCOPE,
            size = "700MB",
            sizeBytes = 700_000_000,
            quantization = "INT4 + KV-INT8",
            parameters = "1.5B",
            downloadUrl = "MNN://Qwen3.5-1.5B-Instruct-MNN",
            mirrorUrl = "MNN://Qwen3.5-1.5B-Instruct-MNN",
            hash = null,
            isMultimodal = false,
            minMemory = 1_500_000_000,
            isMnnModel = true,
            tier = ModelTier.DAILY
        ),
        // ============ 推荐⭐档位（也用作质量档位） ============
        AIModel(
            id = "qwen3.5-3b-int4",
            name = "推荐⭐ · Qwen3.5-3B",
            description = "速度+质量甜点，配合0.8B推测解码可达~15-25 tok/s，质量零损失",
            provider = ModelProvider.MODELSCOPE,
            size = "1.3GB",
            sizeBytes = 1_300_000_000,
            quantization = "INT4 + KV-INT8",
            parameters = "3B",
            downloadUrl = "MNN://Qwen3.5-3B-Instruct-MNN",
            mirrorUrl = "MNN://Qwen3.5-3B-Instruct-MNN",
            hash = null,
            isMultimodal = false,
            minMemory = 2_100_000_000,
            isMnnModel = true,
            tier = ModelTier.RECOMMENDED
        ),
        // ============ 极限档位 ============
        AIModel(
            id = "qwen3.5-4b-int4",
            name = "极限 · Qwen3.5-4B",
            description = "最大模型，质量最好，需开启KV-TQ4，~6-9 tok/s",
            provider = ModelProvider.MODELSCOPE,
            size = "2.0GB",
            sizeBytes = 2_000_000_000,
            quantization = "INT4 + KV-TQ4",
            parameters = "4B",
            downloadUrl = "MNN://Qwen3.5-4B-Instruct-MNN",
            mirrorUrl = "MNN://Qwen3.5-4B-Instruct-MNN",
            hash = null,
            isMultimodal = false,
            minMemory = 2_500_000_000,
            isMnnModel = true,
            tier = ModelTier.MAXIMUM
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
     * 多模态模型（当前不支持）
     * MNN 推理框架暂不支持多模态模型，此方法始终返回空列表
     */
    fun getMultimodalModels(): List<AIModel> {
        return emptyList()  // MNN 暂不支持多模态
    }

    /**
     * Get text-only models
     */
    fun getTextModels(): List<AIModel> {
        return supportedModels.filter { !it.isMultimodal }
    }

    /**
     * Get MNN models only
     */
    fun getMnnModels(): List<AIModel> {
        return supportedModels.filter { it.isMnnModel }
    }

    /**
     * Get small models suitable for mobile devices (极速/日常档位)
     */
    fun getSmallModels(): List<AIModel> {
        return supportedModels.filter {
            it.tier == ModelTier.SPEED || it.tier == ModelTier.DAILY
        }
    }

    /**
     * 获取推荐档位模型
     */
    fun getRecommendedModel(): AIModel? {
        return supportedModels.find { it.tier == ModelTier.RECOMMENDED }
    }

    /**
     * 根据设备内存推荐档位
     */
    fun recommendTierByMemory(totalMemoryMB: Long): ModelTier {
        return when {
            totalMemoryMB >= 6000 -> ModelTier.RECOMMENDED  // 8GB RAM → 推荐⭐档
            totalMemoryMB >= 4000 -> ModelTier.DAILY         // 6GB → 日常档
            else -> ModelTier.SPEED                           // 4GB以下 → 极速档
        }
    }
}
