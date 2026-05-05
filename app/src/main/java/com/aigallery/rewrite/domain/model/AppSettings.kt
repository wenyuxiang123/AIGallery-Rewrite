package com.aigallery.rewrite.domain.model

/**
 * Application settings domain model
 */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: String = "zh-CN",
    val defaultModelId: String? = null,
    val enableMemorySystem: Boolean = true,
    val memoryConfig: MemoryConfig = MemoryConfig(),
    val autoDownloadOnWifi: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val streamingEnabled: Boolean = true,
    val maxHistorySessions: Int = 50,
    val clearCacheOnExit: Boolean = false
)

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

/**
 * Download state
 */
data class DownloadState(
    val modelId: String,
    val progress: Float,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val status: DownloadStatus,
    val error: String? = null
)

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Runtime configuration
 */
data class RuntimeConfig(
    val serverUrl: String = "http://localhost:8080",
    val apiKey: String? = null,
    val timeoutSeconds: Int = 60,
    val maxRetries: Int = 3
)
