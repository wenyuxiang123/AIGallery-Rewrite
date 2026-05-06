package com.aigallery.rewrite.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
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
 * 使用 Android DownloadManager 进行后台下载
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
    
    // 模型目录
    private val modelDir: File by lazy {
        context.getDir("models", Context.MODE_PRIVATE).also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    /**
     * 下载完成广播接收器
     */
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (downloadId != -1L) {
                    Log.d(TAG, "Download completed: $downloadId")
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
        ContextCompat.registerReceiver(context, downloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * 下载模型
     * @param modelId 模型 ID
     * @param modelName 模型名称
     * @param source 下载源
     * @param downloadUrl 下载 URL（如果为空则根据 source 和 modelId 自动生成）
     * @return 下载 ID，-1 表示失败
     */
    fun downloadModel(
        modelId: String,
        modelName: String,
        source: ModelSource = ModelSource.MODEL_SCOPE,
        downloadUrl: String? = null
    ): Long {
        val url = downloadUrl ?: generateDownloadUrl(modelId, source)
        val fileName = "$modelId.gguf" // 使用 gguf 扩展名

        Log.d(TAG, "Starting download: modelId=$modelId, url=$url, fileName=$fileName")

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
            Log.d(TAG, "Download started with ID: $downloadId")
        } else {
            Log.e(TAG, "Failed to start download")
        }

        return downloadId
    }

    /**
     * 使用镜像 URL 下载模型
     */
    fun downloadModelWithMirrorUrl(
        modelId: String,
        modelName: String,
        mirrorUrl: String
    ): Long {
        return downloadModel(modelId, modelName, ModelSource.MODEL_SCOPE, mirrorUrl)
    }

    /**
     * 暂停下载
     * 注意：DownloadManager 不支持直接暂停，需要取消后重新开始
     */
    fun pauseDownload(downloadId: Long): Boolean {
        val modelId = downloadIdToModel[downloadId] ?: return false
        
        // DownloadManager API 不直接支持暂停，这里标记为暂停状态
        updateDownloadState(modelId, DownloadState(
            modelId = modelId,
            modelName = "",
            status = DownloadStatus.PAUSED,
            downloadId = downloadId
        ))
        return true
    }

    /**
     * 取消下载并删除部分文件
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
            Log.d(TAG, "Download cancelled: modelId=$modelId")
        }
    }

    /**
     * 重试下载
     */
    fun retryDownload(modelId: String, modelName: String, source: ModelSource, downloadUrl: String? = null) {
        val oldDownloadId = getDownloadIdByModelId(modelId)
        if (oldDownloadId != -1L) {
            cancelDownload(oldDownloadId)
        }
        downloadModel(modelId, modelName, source, downloadUrl)
    }

    /**
     * 根据模型 ID 获取下载 ID
     */
    fun getDownloadIdByModelId(modelId: String): Long {
        return downloadIdToModel.entries.find { it.value == modelId }?.key ?: -1L
    }

    /**
     * 更新下载进度
     */
    fun updateProgress() {
        val currentDownloadIds = downloadIdToModel.keys.toList()
        
        currentDownloadIds.forEach { downloadId ->
            val modelId = downloadIdToModel[downloadId] ?: return@forEach

            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager?.query(query) ?: return@forEach

            if (cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

                val status = cursor.getInt(statusIndex)
                val bytesDownloaded = cursor.getLong(bytesDownloadedIndex)
                val bytesTotal = cursor.getLong(bytesTotalIndex)
                val localUri = cursor.getString(localUriIndex)

                val downloadStatus = when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                    DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                    DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                    DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                    DownloadManager.STATUS_RUNNING -> DownloadStatus.DOWNLOADING
                    else -> DownloadStatus.UNKNOWN
                }

                val progress = if (bytesTotal > 0) bytesDownloaded.toFloat() / bytesTotal.toFloat() else 0f

                Log.d(TAG, "Update progress: modelId=$modelId, status=$downloadStatus, progress=$progress, downloaded=$bytesDownloaded, total=$bytesTotal")

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
                if (downloadStatus == DownloadStatus.COMPLETED && localUri != null) {
                    moveModelToAppDir(modelId, localUri)
                    downloadIdToModel.remove(downloadId)
                }
            } else {
                // Cursor 为空，可能下载已从系统移除
                Log.w(TAG, "Cursor empty for downloadId=$downloadId")
                downloadIdToModel.remove(downloadId)
            }

            cursor.close()
        }
    }

    /**
     * 将下载的模型移动到应用私有目录
     */
    private fun moveModelToAppDir(modelId: String, localUri: String) {
        try {
            // 从 URI 解析实际文件路径
            val sourceFile = if (localUri.startsWith("file://")) {
                File(localUri.removePrefix("file://"))
            } else {
                // 尝试在 Downloads 目录中查找
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$modelId.gguf")
            }

            if (sourceFile.exists()) {
                val targetFile = File(modelDir, "$modelId.gguf")

                // 确保目标目录存在
                if (!modelDir.exists()) {
                    modelDir.mkdirs()
                }

                // 复制文件到目标位置
                sourceFile.copyTo(targetFile, overwrite = true)
                
                // 删除原始文件
                if (sourceFile.delete()) {
                    Log.d(TAG, "Model $modelId moved to app directory: ${targetFile.absolutePath}")
                } else {
                    Log.w(TAG, "Failed to delete source file: ${sourceFile.absolutePath}")
                }
            } else {
                Log.w(TAG, "Source file not found: ${sourceFile.absolutePath}")
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
                // 尝试从 ModelCatalog 获取 HuggingFace URL
                com.aigallery.rewrite.domain.model.ModelCatalog.getModelById(modelId)?.downloadUrl
                    ?: "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_k_m.gguf"
            }
            ModelSource.MODEL_SCOPE -> {
                // 优先使用 ModelCatalog 中定义的镜像 URL
                com.aigallery.rewrite.domain.model.ModelCatalog.getModelById(modelId)?.mirrorUrl
                    ?: "https://www.modelscope.cn/models/qwen/Qwen2.5-7B-Instruct-GGUF/resolve/master/qwen2.5-7b-instruct-q4_k_m.gguf"
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
        return modelDir.listFiles { file -> 
            file.extension == "gguf" || file.extension == "mnn" || file.extension == "bin"
        }?.map { it.nameWithoutExtension } ?: emptyList()
    }

    /**
     * 检查模型是否已下载
     */
    fun isModelDownloaded(modelId: String): Boolean {
        val ggufFile = File(modelDir, "$modelId.gguf")
        val mnnFile = File(modelDir, "$modelId.mnn")
        val binFile = File(modelDir, "$modelId.bin")
        return ggufFile.exists() || mnnFile.exists() || binFile.exists()
    }

    /**
     * 获取模型文件路径
     */
    fun getModelFilePath(modelId: String): String? {
        val ggufFile = File(modelDir, "$modelId.gguf")
        val mnnFile = File(modelDir, "$modelId.mnn")
        val binFile = File(modelDir, "$modelId.bin")
        
        return when {
            ggufFile.exists() -> ggufFile.absolutePath
            mnnFile.exists() -> mnnFile.absolutePath
            binFile.exists() -> binFile.absolutePath
            else -> null
        }
    }

    /**
     * 删除已下载模型
     */
    fun deleteModel(modelId: String): Boolean {
        var deleted = false
        
        listOf("gguf", "mnn", "bin").forEach { ext ->
            val file = File(modelDir, "$modelId.$ext")
            if (file.exists()) {
                deleted = file.delete() || deleted
                Log.d(TAG, "Deleted model file: ${file.absolutePath}")
            }
        }
        
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
    NOT_DOWNLOADED, // 未下载
    DOWNLOADED,      // 已下载
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
            DownloadStatus.NOT_DOWNLOADED -> ""
            DownloadStatus.DOWNLOADED -> ""
        }
    }
}
