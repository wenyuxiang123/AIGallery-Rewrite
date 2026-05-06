package com.aigallery.rewrite.download

import android.content.Context
import android.util.Log
import com.aigallery.rewrite.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MNN 模型下载管理器
 * 
 * MNN 模型为目录结构，包含：
 * - config.json
 * - llm_config.json
 * - llm.mnn
 * - llm.mnn.weight (主要权重文件)
 * - llm.mnn.json
 * - tokenizer.txt
 * 
 * 需要从 ModelScope 下载完整的目录结构
 */
@Singleton
class MnnModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "MnnModelDownloader"
        
        // ModelScope API 配置
        private const val MODEL_SCOPE_API = "https://modelscope.cn/api/v1/models"
        private const val MODEL_SCOPE_FILE_API = "https://modelscope.cn/api/v1/models/{model_id}/repo/files"
        
        // MNN 模型必需文件
        private val REQUIRED_FILES = listOf(
            "config.json",
            "llm_config.json", 
            "llm.mnn",
            "llm.mnn.weight",
            "llm.mnn.json",
            "tokenizer.txt"
        )
        
        // 可选文件
        private val OPTIONAL_FILES = listOf(
            "visual.mnn",
            "visual.mnn.weight"
        )
    }
    
    // 模型目录
    private val modelDir: File by lazy {
        File(context.filesDir, "models").also { dir ->
            if (!dir.exists()) dir.mkdirs()
        }
    }
    
    // 下载状态
    private val _downloadStates = MutableStateFlow<Map<String, MnnDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, MnnDownloadState>> = _downloadStates.asStateFlow()
    
    // MNN 模型 ID 映射（modelId -> ModelScope 路径）
    private val mnnModelPaths = mapOf(
        "qwen2.5-0.5b-mnn" to "MNN/Qwen2.5-0.5B-Instruct-MNN",
        "qwen2.5-1.5b-mnn" to "MNN/Qwen2.5-1.5B-Instruct-MNN",
        "qwen2.5-3b-mnn" to "MNN/Qwen2.5-3B-Instruct-MNN",
        "qwen3-4b-mnn" to "MNN/Qwen3.5-4B-MNN"
    )
    
    /**
     * 检查模型是否已下载
     */
    fun isModelDownloaded(modelId: String): Boolean {
        val modelPath = mnnModelPaths[modelId] ?: return false
        val modelDir = File(this.modelDir, modelId)
        
        if (!modelDir.exists() || !modelDir.isDirectory) return false
        
        // 检查必需文件是否存在
        return REQUIRED_FILES.all { File(modelDir, it).exists() }
    }
    
    /**
     * 获取模型目录路径
     */
    fun getModelDir(modelId: String): String {
        return File(modelDir, modelId).absolutePath
    }
    
    /**
     * 获取已下载的 MNN 模型列表
     */
    fun getDownloadedModels(): List<String> {
        return modelDir.listFiles { file -> 
            file.isDirectory && mnnModelPaths.containsKey(file.name)
        }?.map { it.name } ?: emptyList()
    }
    
    /**
     * 下载 MNN 模型
     * 
     * @param modelId 模型 ID
     * @param onProgress 进度回调 (current, total, fileName)
     */
    suspend fun downloadModel(
        modelId: String,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit,
        onByteProgress: (Float) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val modelPath = mnnModelPaths[modelId] 
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown MNN model: $modelId"))
        
        FileLogger.d(TAG, "downloadModel: $modelId from $modelPath")
        
        // 创建模型目录
        val targetDir = File(modelDir, modelId)
        if (!targetDir.exists()) targetDir.mkdirs()
        
        // 获取文件列表
        val allFiles = REQUIRED_FILES + OPTIONAL_FILES
        val totalFiles = allFiles.size
        
        // 预估各文件大小（基于已知模型的大小比例）
        val estimatedSizes = mapOf(
            "config.json" to 1_000L,
            "llm_config.json" to 10_000L,
            "llm.mnn" to 1_000_000L,
            "llm.mnn.weight" to 278_000_000L,  // 占99%以上
            "llm.mnn.json" to 10_000_000L,
            "tokenizer.txt" to 5_000_000L,
            "visual.mnn" to 1_000_000L,
            "visual.mnn.weight" to 200_000_000L
        )
        val totalEstimatedSize = allFiles.sumOf { estimatedSizes[it] ?: 1_000_000L }
        
        try {
            // 更新状态
            updateState(modelId, MnnDownloadState(
                modelId = modelId,
                status = MnnDownloadStatus.DOWNLOADING,
                totalFiles = totalFiles
            ))
            
            var completedSize = 0L
            
            for ((fileIndex, fileName) in allFiles.withIndex()) {
                FileLogger.d(TAG, "downloadModel: downloading $fileName")
                
                updateState(modelId, MnnDownloadState(
                    modelId = modelId,
                    status = MnnDownloadStatus.DOWNLOADING,
                    currentFile = fileName,
                    completedFiles = fileIndex,
                    totalFiles = totalFiles
                ))
                
                val fileSize = estimatedSizes[fileName] ?: 1_000_000L
                
                try {
                    val success = downloadFile(modelPath, fileName, targetDir, onFileProgress = { fileProgress: Float ->
                        // 基于文件大小的进度计算
                        val fileBase = completedSize.toFloat() / totalEstimatedSize
                        val fileWeight = fileSize.toFloat() / totalEstimatedSize
                        val overallProgress = fileBase + fileWeight * fileProgress
                        onByteProgress(overallProgress.coerceAtMost(1f))
                    })
                    if (success) {
                        completedSize += fileSize
                        onByteProgress(completedSize.toFloat() / totalEstimatedSize)
                        FileLogger.d(TAG, "downloadModel: $fileName completed")
                    } else {
                        // 可选文件下载失败可以忽略
                        if (REQUIRED_FILES.contains(fileName)) {
                            FileLogger.e(TAG, "downloadModel: required file $fileName failed")
                        } else {
                            FileLogger.w(TAG, "downloadModel: optional file $fileName failed, skipping")
                        }
                    }
                } catch (e: Exception) {
                    if (REQUIRED_FILES.contains(fileName)) {
                        throw e
                    } else {
                        FileLogger.w(TAG, "downloadModel: optional file $fileName failed: ${e.message}")
                    }
                }
            }
            
            // 验证必需文件
            val missingRequired = REQUIRED_FILES.filter { 
                !File(targetDir, it).exists() 
            }
            
            if (missingRequired.isNotEmpty()) {
                FileLogger.e(TAG, "downloadModel: missing required files: $missingRequired")
                updateState(modelId, MnnDownloadState(
                    modelId = modelId,
                    status = MnnDownloadStatus.FAILED,
                    errorMessage = "Missing required files: $missingRequired"
                ))
                return@withContext Result.failure(
                    IllegalStateException("Missing required files: $missingRequired")
                )
            }
            
            updateState(modelId, MnnDownloadState(
                modelId = modelId,
                status = MnnDownloadStatus.COMPLETED,
                completedFiles = totalFiles,
                totalFiles = totalFiles
            ))
            
            FileLogger.i(TAG, "downloadModel: $modelId completed successfully")
            Result.success(Unit)
            
        } catch (e: Exception) {
            FileLogger.e(TAG, "downloadModel: $modelId failed", e)
            updateState(modelId, MnnDownloadState(
                modelId = modelId,
                status = MnnDownloadStatus.FAILED,
                errorMessage = e.message
            ))
            Result.failure(e)
        }
    }
    
    /**
     * 下载单个文件（无限重试直到成功）
     * 网络中断后持续重试，每次从头重新下载，直到成功
     */
    private fun downloadFile(
        modelPath: String,
        fileName: String,
        targetDir: File,
        onFileProgress: (Float) -> Unit = {}
    ): Boolean {
        var attempt = 0
        while (true) {
            attempt++
            
            // 删除可能存在的部分文件，确保从头开始干净下载
            val targetFile = File(targetDir, fileName)
            if (targetFile.exists()) {
                FileLogger.d(TAG, "downloadFile: [$fileName] attempt $attempt - deleting incomplete file (${targetFile.length()} bytes)")
                targetFile.delete()
            }
            
            FileLogger.i(TAG, "downloadFile: [$fileName] starting download attempt $attempt")
            
            val success = downloadFileOnce(modelPath, fileName, targetDir, onFileProgress)
            
            if (success) {
                FileLogger.i(TAG, "downloadFile: [$fileName] download succeeded on attempt $attempt")
                return true
            }
            
            // 下载失败，记录原因并继续重试
            FileLogger.w(TAG, "downloadFile: [$fileName] attempt $attempt failed, retrying in 2 seconds...")
            
            // 等待2秒后再重试，避免立即重试导致服务器拒绝
            try {
                Thread.sleep(2000L)
            } catch (e: InterruptedException) {
                FileLogger.e(TAG, "downloadFile: [$fileName] retry interrupted", e)
                return false
            }
        }
    }

    /**
     * 下载单个文件（单次尝试）
     */
    private fun downloadFileOnce(
        modelPath: String,
        fileName: String,
        targetDir: File,
        onFileProgress: (Float) -> Unit = {}
    ): Boolean {
        // ModelScope 下载 URL 格式
        // https://modelscope.cn/models/{owner}/{repo}/resolve/{revision}/{file_path}
        val urlStr = "https://modelscope.cn/models/$modelPath/resolve/master/$fileName"
        
        FileLogger.d(TAG, "downloadFile: URL=$urlStr")
        FileLogger.d(TAG, "downloadFile: targetDir=${targetDir.absolutePath}")
        
        var connection: HttpURLConnection? = null
        var inputStream: java.io.InputStream? = null
        var outputStream: FileOutputStream? = null
        
        try {
            FileLogger.d(TAG, "downloadFile: connecting to $urlStr")
            
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 300000  // 5 分钟，大文件需要更长
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Connection", "keep-alive")
            
            FileLogger.d(TAG, "downloadFile: waiting for response...")
            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage
            
            FileLogger.d(TAG, "downloadFile: HTTP $responseCode $responseMessage for $fileName")
            
            if (responseCode != HttpURLConnection.HTTP_OK) {
                FileLogger.e(TAG, "downloadFile: HTTP error $responseCode for $fileName, URL=$urlStr")
                // 尝试打印响应头
                FileLogger.e(TAG, "downloadFile: responseHeaders=${connection.headerFields}")
                return false
            }
            
            val contentLength = connection.contentLength
            FileLogger.d(TAG, "downloadFile: $fileName contentLength=$contentLength bytes")
            
            if (contentLength <= 0) {
                FileLogger.w(TAG, "downloadFile: $fileName has invalid contentLength=$contentLength")
            }
            
            val targetFile = File(targetDir, fileName)
            FileLogger.d(TAG, "downloadFile: creating file ${targetFile.absolutePath}")
            
            inputStream = connection.inputStream
            outputStream = FileOutputStream(targetFile)
            
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L
            var lastLogTime = System.currentTimeMillis()
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                
                // 每秒打印一次进度并回调
                val now = System.currentTimeMillis()
                if (now - lastLogTime >= 1000 && contentLength > 0) {
                    val percent = (totalBytesRead * 100 / contentLength).toInt()
                    FileLogger.d(TAG, "downloadFile: $fileName ${totalBytesRead}/${contentLength}bytes ($percent%)")
                    onFileProgress(totalBytesRead.toFloat() / contentLength)
                    lastLogTime = now
                }
            }
            
            outputStream.flush()
            outputStream.fd.sync()
            
            val actualSize = targetFile.length()
            FileLogger.i(TAG, "downloadFile: $fileName completed, expected=$contentLength, actual=$actualSize bytes")
            
            // 验证文件大小
            if (contentLength > 0 && actualSize != contentLength.toLong()) {
                FileLogger.w(TAG, "downloadFile: $fileName size mismatch, expected=$contentLength actual=$actualSize")
            }
            
            return true
            
        } catch (e: Exception) {
            FileLogger.e(TAG, "downloadFile: $fileName failed with exception", e)
            FileLogger.e(TAG, "downloadFile: error message=${e.message}, class=${e.javaClass.simpleName}")
            return false
        } finally {
            try { inputStream?.close() } catch (e: Exception) {}
            try { outputStream?.close() } catch (e: Exception) {}
            try { connection?.disconnect() } catch (e: Exception) {}
        }
    }
    
    /**
     * 删除已下载模型
     */
    fun deleteModel(modelId: String): Boolean {
        val modelDir = File(this.modelDir, modelId)
        return if (modelDir.exists()) {
            modelDir.deleteRecursively()
        } else {
            true
        }
    }
    
    private fun updateState(modelId: String, state: MnnDownloadState) {
        _downloadStates.value = _downloadStates.value.toMutableMap().apply {
            put(modelId, state)
        }
    }
}

/**
 * MNN 下载状态
 */
data class MnnDownloadState(
    val modelId: String,
    val status: MnnDownloadStatus = MnnDownloadStatus.IDLE,
    val currentFile: String? = null,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val errorMessage: String? = null
) {
    val progress: Float
        get() = if (totalFiles > 0) completedFiles.toFloat() / totalFiles else 0f
}

/**
 * MNN 下载状态枚举
 */
enum class MnnDownloadStatus {
    IDLE,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}
