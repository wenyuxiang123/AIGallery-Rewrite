package com.aigallery.rewrite.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型下载管理器
 * 支持从 Hugging Face 和 ModelScope 下载 AI 模型
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    private val context: Context
) {

    private val TAG = "ModelDownloadManager"

    private val downloadManager: DownloadManager? = context.getSystemService()

    // 下载任务状态映射
    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    // downloadId -> modelId 映射
    private val downloadIdToModel = mutableMapOf<Long, String>()

    /**
     * 下载完成广播接收器
     */
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (downloadId != -1L) {
                    updateProgress()
                }
            }
        }
    }

    /**
     * 初始化下载接收器
     */
    init {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        context.registerReceiver(downloadReceiver, filter)
    }

    /**
     * 下载模型
     * @param modelId 模型 ID
     * @param modelName 模型名称
     * @param source 下载源
     * @param downloadUrl 下载 URL（如果为空则根据 source 和 modelId 自动生成）
     */
    fun downloadModel(
        modelId: String,
        modelName: String,
        source: ModelSource = ModelSource.HUGGING_FACE,
        downloadUrl: String? = null
    ): Long {
        val url = downloadUrl ?: generateDownloadUrl(modelId, source)
        val fileName = "$modelId.mnn"

        // 创建下载请求
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("下载模型: $modelName")
            .setDescription("正在下载 $modelName")
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

        // 开始下载
        val downloadId = downloadManager?.enqueue(request) ?: -1L

        if (downloadId != -1L) {
            downloadIdToModel[downloadId] = modelId
            updateDownloadState(modelId, DownloadState(
                modelId = modelId,
                modelName = modelName,
                status = DownloadStatus.PENDING,
                downloadId = downloadId
            ))
        }

        return downloadId
    }

    /**
     * 暂停下载
     */
    fun pauseDownload(downloadId: Long) {
        val modelId = downloadIdToModel[downloadId] ?: return
        updateDownloadState(modelId, DownloadState(
            modelId = modelId,
            modelName = "",
            status = DownloadStatus.PAUSED,
            downloadId = downloadId
        ))
    }

    /**
     * 取消下载
     */
    fun cancelDownload(downloadId: Long) {
        downloadManager?.remove(downloadId)
        val modelId = downloadIdToModel.remove(downloadId)
        if (modelId != null) {
            updateDownloadState(modelId, DownloadState(
                modelId = modelId,
                modelName = "",
                status = DownloadStatus.CANCELLED,
                downloadId = downloadId
            ))
        }
    }

    /**
     * 重试下载
     */
    fun retryDownload(modelId: String, modelName: String, source: ModelSource) {
        cancelDownload(getDownloadIdByModelId(modelId))
        downloadModel(modelId, modelName, source)
    }

    /**
     * 根据模型 ID 获取下载 ID
     */
    private fun getDownloadIdByModelId(modelId: String): Long {
        return downloadIdToModel.entries.find { it.value == modelId }?.key ?: -1L
    }

    /**
     * 更新下载进度
     */
    private fun updateProgress() {
        downloadIdToModel.keys.toList().forEach { downloadId ->
            val modelId = downloadIdToModel[downloadId] ?: return@forEach

            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager?.query(query) ?: return@forEach

            if (cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                val status = cursor.getInt(statusIndex)
                val bytesDownloaded = cursor.getLong(bytesDownloadedIndex)
                val bytesTotal = cursor.getLong(bytesTotalIndex)

                val downloadStatus = when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                    DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                    DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                    DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                    DownloadManager.STATUS_RUNNING -> DownloadStatus.DOWNLOADING
                    else -> DownloadStatus.UNKNOWN
                }

                val progress = if (bytesTotal > 0) bytesDownloaded.toFloat() / bytesTotal.toFloat() else 0f

                updateDownloadState(modelId, DownloadState(
                    modelId = modelId,
                    modelName = "",
                    status = downloadStatus,
                    progress = progress,
                    downloadedSize = bytesDownloaded,
                    totalSize = bytesTotal,
                    downloadId = downloadId
                ))

                // 下载完成后移动模型文件到应用私有目录
                if (downloadStatus == DownloadStatus.COMPLETED) {
                    moveModelToAppDir(modelId)
                    downloadIdToModel.remove(downloadId)
                }
            }

            cursor.close()
        }
    }

    /**
     * 将下载的模型移动到应用私有目录
     */
    private fun moveModelToAppDir(modelId: String) {
        try {
            val sourceDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val sourceFile = File(sourceDir, "models/$modelId.bin")

            if (sourceFile.exists()) {
                val targetDir = context.getDir("models", Context.MODE_PRIVATE)
                val targetFile = File(targetDir, "$modelId.mnn")

                sourceFile.copyTo(targetFile, overwrite = true)
                sourceFile.delete()

                Log.d(TAG, "Model $modelId moved to app directory")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move model to app directory", e)
        }
    }

    /**
     * 根据模型 ID 和下载源生成下载 URL
     */
    private fun generateDownloadUrl(modelId: String, source: ModelSource): String {
        return when (source) {
            ModelSource.HUGGING_FACE -> {
                val (author, model) = modelId.split("/").let {
                    if (it.size >= 2) it[0] to it[1] else "Qwen" to modelId
                }
                "https://huggingface.co/$author/$model/resolve/main/model.mnn"
            }
            ModelSource.MODEL_SCOPE -> {
                val (author, model) = modelId.split("/").let {
                    if (it.size >= 2) it[0] to it[1] else "qwen" to modelId
                }
                "https://www.modelscope.cn/api/v1/models/$author/$model/repo?Revision=master&FilePath=model.mnn"
            }
        }
    }

    /**
     * 更新下载状态
     */
    private fun updateDownloadState(modelId: String, state: DownloadState) {
        _downloadStates.value = _downloadStates.value.toMutableMap().apply {
            put(modelId, state)
        }
    }

    /**
     * 获取已下载模型列表
     */
    fun getDownloadedModels(): List<String> {
        val modelDir = context.getDir("models", Context.MODE_PRIVATE)
        return modelDir.listFiles { file -> file.extension == "mnn" || file.extension == "gguf" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    /**
     * 删除已下载模型
     */
    fun deleteModel(modelId: String): Boolean {
        val modelDir = context.getDir("models", Context.MODE_PRIVATE)
        val mnnFile = File(modelDir, "$modelId.mnn")
        val ggufFile = File(modelDir, "$modelId.gguf")

        var deleted = false
        if (mnnFile.exists()) deleted = mnnFile.delete()
        if (ggufFile.exists()) deleted = ggufFile.delete() || deleted

        return deleted
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            context.unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // 忽略未注册错误
        }
    }
}

/**
 * 下载源枚举
 */
enum class ModelSource {
    HUGGING_FACE,
    MODEL_SCOPE
}

/**
 * 下载状态枚举
 */
enum class DownloadStatus {
    PENDING,      // 等待中
    DOWNLOADING,  // 下载中
    PAUSED,       // 已暂停
    COMPLETED,    // 已完成
    FAILED,       // 失败
    CANCELLED,    // 已取消
    UNKNOWN       // 未知
}

/**
 * 下载状态数据类
 */
data class DownloadState(
    val modelId: String,
    val modelName: String,
    val status: DownloadStatus,
    val progress: Float = 0f,
    val downloadedSize: Long = 0L,
    val totalSize: Long = 0L,
    val downloadId: Long = -1L,
    val errorMessage: String? = null
) {
    /**
     * 获取格式化的进度文本
     */
    fun getProgressText(): String {
        return when (status) {
            DownloadStatus.DOWNLOADING -> {
                val percent = (progress * 100).toInt()
                val downloadedMB = downloadedSize / (1024 * 1024)
                val totalMB = totalSize / (1024 * 1024)
                "$percent% ($downloadedMB / $totalMB MB)"
            }
            DownloadStatus.COMPLETED -> "下载完成"
            DownloadStatus.FAILED -> "下载失败: $errorMessage"
            DownloadStatus.PAUSED -> "已暂停"
            DownloadStatus.CANCELLED -> "已取消"
            DownloadStatus.PENDING -> "等待中..."
            DownloadStatus.UNKNOWN -> "未知状态"
        }
    }
}
