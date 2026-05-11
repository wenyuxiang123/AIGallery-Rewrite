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
 * - 极速：0.5B INT4，最快响应，~25-30 tok/s（778G实测）
 * - 日常：1.5B INT4，日常聊天，~15-22 tok/s（778G实测）
 * - 推荐⭐：3B INT4，速度+质量甜点，~10-15 tok/s（778G实测）
 * - 质量：3B INT4 单独使用，好回答不追求速度，~10-15 tok/s
 * - 极限：3B INT4（设备最优），当前设备能运行的最大模型
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
        targetSpeed = "~25-30 tok/s",
        requiresSpeculativeDecoding = false
    ),
    DAILY(
        displayName = "日常",
        description = "日常聊天和轻度推理",
        targetSpeed = "~15-22 tok/s",
        requiresSpeculativeDecoding = false
    ),
    RECOMMENDED(
        displayName = "推荐⭐",
        description = "速度+质量甜点，配合0.5B推测解码可达~15-20 tok/s，质量零损失",
        targetSpeed = "~15-20 tok/s",
        requiresSpeculativeDecoding = true,
        draftModelId = "qwen2.5-0.5b-int4"
    ),
    QUALITY(
        displayName = "质量",
        description = "好回答不追求速度",
        targetSpeed = "~10-15 tok/s",
        requiresSpeculativeDecoding = false
    ),
    MAXIMUM(
        displayName = "极限",
        description = "当前设备最优模型，~10-15 tok/s",
        targetSpeed = "~10-15 tok/s",
        requiresSpeculativeDecoding = false
    )
}

/**
 * Predicted model catalog with MNN INT4 optimized models
 * 
 * PH0 硬件加速优化：
 * - Qwen2.5-0.5B：INT4量化版，ModelScope MNN/Qwen2.5-0.5B-Instruct-MNN
 * - Qwen2.5-1.5B：INT4量化版，ModelScope MNN/Qwen2.5-1.5B-Instruct-MNN
 * - Qwen2.5-3B：INT4量化版，ModelScope MNN/Qwen2.5-3B-Instruct-MNN
 * ⚠️ Qwen2.5-3B 有 Research License 限制，仅限研究使用
 */
object ModelCatalog {
    val supportedModels = listOf(
        // ===================== 极速档 =====================
        AIModel(
            id = "qwen2.5-0.5b-int4",
            name = "极速 · Qwen2.5-0.5B",
            description = "最快响应，简单问答和工具调用草稿，~25-30 tok/s",
            provider = ModelProvider.MODELSCOPE,
            size = "400MB",
            sizeBytes = 400_000_000,
            quantization = "INT4 + KV-INT8",
            parameters = "0.5B",
            downloadUrl = "MNN://Qwen2.5-0.5B-Instruct-MNN",
            mirrorUrl = "MNN://Qwen2.5-0.5B-Instruct-MNN",
            hash = null,
            isMultimodal = false,
            minMemory = 800_000_000,
            isMnnModel = true,
            tier = ModelTier.SPEED
        ),
        // ===================== 日常档 =====================
        AIModel(
            id = "qwen2.5-1.5b-int4",
            name = "日常 · Qwen2.5-1.5B",
            description = "日常聊天和轻度推理，~15-22 tok/s",
            provider = ModelProvider.MODELSCOPE,
            size = "1.1GB",
            sizeBytes = 1_100_000_000,
            quantization = "INT4 + KV-INT8",
            parameters = "1.5B",
            downloadUrl = "MNN://Qwen2.5-1.5B-Instruct-MNN",
            mirrorUrl = "MNN://Qwen2.5-1.5B-Instruct-MNN",
            hash = null,
            isMultimodal = false,
            minMemory = 1_800_000_000,
            isMnnModel = true,
            tier = ModelTier.DAILY
        ),
        // ===================== 推荐档 =====================
        AIModel(
            id = "qwen2.5-3b-int4",
            name = "推荐⭐ · Qwen2.5-3B",
            description = "速度+质量甜点，配合0.5B推测解码可达~15-20 tok/s，质量零损失",
            provider = ModelProvider.MODELSCOPE,
            size = "2.0GB",
            sizeBytes = 2_000_000_000,
            quantization = "INT4 + KV-INT8",
            parameters = "3B",
            downloadUrl = "MNN://Qwen2.5-3B-Instruct-MNN",
            mirrorUrl = "MNN://Qwen2.5-3B-Instruct-MNN",
            hash = null,
            isMultimodal = false,
            minMemory = 3_200_000_000,
            isMnnModel = true,
            tier = ModelTier.RECOMMENDED
        ),
        // ===================== 极限档（保守方案，同3B模型） =====================
        AIModel(
            id = "qwen2.5-3b-int4-optimal",
            name = "设备最优 · Qwen2.5-3B",
            description = "当前设备最优模型，7B模型可能导致内存不足，~10-15 tok/s",
            provider = ModelProvider.MODELSCOPE,
            size = "2.0GB",
            sizeBytes = 2_000_000_000,
            quantization = "INT4 + KV-INT8",
            parameters = "3B",
            downloadUrl = "MNN://Qwen2.5-3B-Instruct-MNN",
            mirrorUrl = "MNN://Qwen2.5-3B-Instruct-MNN",
            hash = null,
            isMultimodal = false,
            minMemory = 3_200_000_000,
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
     * 获取多模态模型
     * MNN 目前不支持多模态模型
     */
    fun getMultimodalModels(): List<AIModel> {
        return emptyList()  // MNN 不支持多模态模型
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
     * Get small models suitable for mobile devices (极速档+日常档)
     */
    fun getSmallModels(): List<AIModel> {
        return supportedModels.filter {
            it.tier == ModelTier.SPEED || it.tier == ModelTier.DAILY
        }
    }

    /**
     * 获取推荐模型
     */
    fun getRecommendedModel(): AIModel? {
        return supportedModels.find { it.tier == ModelTier.RECOMMENDED }
    }

    /**
     * 根据内存推荐档位
     */
    fun recommendTierByMemory(totalMemoryMB: Long): ModelTier {
        return when {
            totalMemoryMB >= 6000 -> ModelTier.RECOMMENDED   // 8GB RAM 推荐3B
            totalMemoryMB >= 4000 -> ModelTier.DAILY   // 6GB RAM 1.5B
            else -> ModelTier.SPEED    // 4GB及以下 0.5B
        }
    }
}
