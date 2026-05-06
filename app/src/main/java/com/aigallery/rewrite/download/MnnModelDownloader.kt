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
 * 可选文件（视觉模型需要）：
 * - visual.mnn
 * - visual.mnn.weight
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
        
        // MNN 模型必需文件（核心文件）
        private val REQUIRED_FILES = listOf(
            "config.json",
            "llm_config.json", 
            "llm.mnn",
            "llm.mnn.weight",
            "llm.mnn.json",
            "tokenizer.txt"
        )
        
        // 可选文件（仅视觉模型需要）
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
    // ModelScope 路径格式: MNN/ModelName
    private val mnnModelPaths = mapOf(
        // Qwen 2.5 系列
        "qwen2.5-0.5b-mnn" to "MNN/Qwen2.5-0.5B-Instruct-MNN",
        "qwen2.5-1.5b-mnn" to "MNN/Qwen2.5-1.5B-Instruct-MNN",
        "qwen2.5-3b-mnn" to "MNN/Qwen2.5-3B-Instruct-MNN",
        "qwen2.5-7b-mnn" to "MNN/Qwen2.5-7B-Instruct-MNN",
        
        // Qwen 3.5 系列
        "qwen3.5-0.8b-mnn" to "MNN/Qwen3.5-0.8B-MNN",
        "qwen3.5-2b-mnn" to "MNN/Qwen3.5-2B-MNN",
        "qwen3.5-4b-mnn" to "MNN/Qwen3.5-4B-MNN",
        "qwen3.5-9b-mnn" to "MNN/Qwen3.5-9B-MNN",
        
        // Qwen2-VL 多模态系列
        "qwen2-vl-2b-mnn" to "MNN/Qwen2-VL-2B-Instruct-MNN",
        
        // Qwen2.5-Omni 全模态系列
        "qwen2.5-omni-3b-mnn" to "MNN/Qwen2.5-Omni-3B-MNN"
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
        
        // 获取文件列表 - 只下载必需文件，可选文件按需下载
        val requiredCount = REQUIRED_FILES.size
        val totalFiles = requiredCount + OPTIONAL_FILES.size  // 进度显示总文件数但只验证必需文件
        
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
        val totalEstimatedSize = REQUIRED_FILES.sumOf { estimatedSizes[it] ?: 1_000_000L }
        
        try {
            // 更新状态 - 初始化时传入总字节数
            updateState(modelId, MnnDownloadState(
                modelId = modelId,
                status = MnnDownloadStatus.DOWNLOADING,
                totalFiles = requiredCount,
                totalBytes = totalEstimatedSize,
                downloadedBytes = 0
            ))
            
            var completedSize = 0L
            var completedFileIndex = 0
            
            // 先下载所有必需文件
            for ((fileIndex, fileName) in REQUIRED_FILES.withIndex()) {
                FileLogger.d(TAG, "downloadModel: downloading required file $fileName")
                
                val fileSize = estimatedSizes[fileName] ?: 1_000_000L
                
                updateState(modelId, MnnDownloadState(
                    modelId = modelId,
                    status = MnnDownloadStatus.DOWNLOADING,
                    currentFile = fileName,
                    completedFiles = completedFileIndex,
                    totalFiles = requiredCount,
                    totalBytes = totalEstimatedSize,
                    downloadedBytes = completedSize
                ))
                
                val success = downloadRequiredFile(modelPath, fileName, targetDir, onFileProgress = { fileProgress: Float ->
                    val currentFileBytes = (fileSize * fileProgress).toLong()
                    val overallBytes = completedSize + currentFileBytes
                    val overallProgress = overallBytes.toFloat() / totalEstimatedSize
                    onByteProgress(overallProgress.coerceAtMost(1f))
                    // 更新状态中的字节进度
                    updateState(modelId, MnnDownloadState(
                        modelId = modelId,
                        status = MnnDownloadStatus.DOWNLOADING,
                        currentFile = fileName,
                        completedFiles = completedFileIndex,
                        totalFiles = requiredCount,
                        totalBytes = totalEstimatedSize,
                        downloadedBytes = overallBytes
                    ))
                })
                
                if (success) {
                    completedSize += fileSize
                    completedFileIndex++
                    onByteProgress(completedSize.toFloat() / totalEstimatedSize)
                    FileLogger.d(TAG, "downloadModel: $fileName completed")
                } else {
                    FileLogger.e(TAG, "downloadModel: required file $fileName failed")
                    updateState(modelId, MnnDownloadState(
                        modelId = modelId,
                        status = MnnDownloadStatus.FAILED,
                        errorMessage = "Failed to download required file: $fileName",
                        totalBytes = totalEstimatedSize,
                        downloadedBytes = completedSize
                    ))
                    return@withContext Result.failure(
                        IllegalStateException("Failed to download required file: $fileName")
                    )
                }
            }
            
            // 可选文件下载（视觉模型需要）- 不影响必需文件进度
            // 可选文件跳过时，保持当前进度不变
            for (fileName in OPTIONAL_FILES) {
                FileLogger.d(TAG, "downloadModel: downloading optional file $fileName")
                
                // 更新状态显示正在尝试下载可选文件（进度不变）
                updateState(modelId, MnnDownloadState(
                    modelId = modelId,
                    status = MnnDownloadStatus.DOWNLOADING,
                    currentFile = fileName,
                    completedFiles = completedFileIndex,
                    totalFiles = requiredCount,
                    totalBytes = totalEstimatedSize,
                    downloadedBytes = completedSize
                ))
                
                // 可选文件：404 时跳过，不重试
                val success = downloadOptionalFile(modelPath, fileName, targetDir)
                if (success) {
                    FileLogger.i(TAG, "downloadModel: optional file $fileName completed")
                }
                // 无论成功与否都继续，因为可选文件不是必须的
                // 可选文件不计入下载进度
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
                    errorMessage = "Missing required files: $missingRequired",
                    totalBytes = totalEstimatedSize,
                    downloadedBytes = completedSize
                ))
                return@withContext Result.failure(
                    IllegalStateException("Missing required files: $missingRequired")
                )
            }
            
            // 下载完成
            updateState(modelId, MnnDownloadState(
                modelId = modelId,
                status = MnnDownloadStatus.COMPLETED,
                completedFiles = requiredCount,
                totalFiles = requiredCount,
                totalBytes = totalEstimatedSize,
                downloadedBytes = totalEstimatedSize
            ))
            
            FileLogger.i(TAG, "downloadModel: $modelId completed successfully")
            Result.success(Unit)
            
        } catch (e: Exception) {
            FileLogger.e(TAG, "downloadModel: $modelId failed", e)
            updateState(modelId, MnnDownloadState(
                modelId = modelId,
                status = MnnDownloadStatus.FAILED,
                errorMessage = e.message,
                totalBytes = totalEstimatedSize,
                downloadedBytes = completedSize
            ))
            Result.failure(e)
        }
    }
    
    /**
     * 下载必需文件（无限重试直到成功或明确失败）
     * 网络中断后持续重试，每次从头重新下载
     */
    private fun downloadRequiredFile(
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
                FileLogger.d(TAG, "downloadRequiredFile: [$fileName] attempt $attempt - deleting incomplete file (${targetFile.length()} bytes)")
                targetFile.delete()
            }
            
            FileLogger.i(TAG, "downloadRequiredFile: [$fileName] starting download attempt $attempt")
            
            val result = downloadFileOnce(modelPath, fileName, targetDir, onFileProgress)
            
            when (result) {
                is DownloadResult.Success -> {
                    FileLogger.i(TAG, "downloadRequiredFile: [$fileName] download succeeded on attempt $attempt")
                    return true
                }
                is DownloadResult.NotFound -> {
                    // 必需文件不应该返回404，但万一返回则失败
                    FileLogger.e(TAG, "downloadRequiredFile: [$fileName] file not found (404), this is unexpected for required file")
                    return false
                }
                is DownloadResult.RetryableError -> {
                    // 网络错误，重试
                    FileLogger.w(TAG, "downloadRequiredFile: [$fileName] attempt $attempt failed (${result.message}), retrying in 2 seconds...")
                    try {
                        Thread.sleep(2000L)
                    } catch (e: InterruptedException) {
                        FileLogger.e(TAG, "downloadRequiredFile: [$fileName] retry interrupted", e)
                        return false
                    }
                }
            }
        }
    }
    
    /**
     * 下载可选文件（404时跳过，不重试）
     * 用于下载视觉模型的 visual.mnn 等可选文件
     */
    private fun downloadOptionalFile(
        modelPath: String,
        fileName: String,
        targetDir: File
    ): Boolean {
        val targetFile = File(targetDir, fileName)
        
        // 如果文件已存在，认为已下载成功
        if (targetFile.exists()) {
            FileLogger.i(TAG, "downloadOptionalFile: [$fileName] already exists, skipping download")
            return true
        }
        
        FileLogger.i(TAG, "downloadOptionalFile: [$fileName] attempting download")
        
        val result = downloadFileOnce(modelPath, fileName, targetDir, {})
        
        return when (result) {
            is DownloadResult.Success -> {
                FileLogger.i(TAG, "downloadOptionalFile: [$fileName] downloaded successfully")
                true
            }
            is DownloadResult.NotFound -> {
                // 404 表示该模型没有这个可选文件（如纯文本模型没有 visual.mnn）
                // 记录日志但不重试，直接跳过
                FileLogger.i(TAG, "downloadOptionalFile: [$fileName] not found (404), skipping (optional file)")
                true  // 返回 true 表示"允许继续"，因为这是可选文件
            }
            is DownloadResult.RetryableError -> {
                // 网络错误，可选文件也跳过
                FileLogger.w(TAG, "downloadOptionalFile: [$fileName] download failed (${result.message}), skipping (optional file)")
                true  // 返回 true 表示"允许继续"
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
    ): DownloadResult {
        // ModelScope 下载 URL 格式
        // https://modelscope.cn/models/{owner}/{repo}/resolve/{revision}/{file_path}
        val urlStr = "https://modelscope.cn/models/$modelPath/resolve/master/$fileName"
        
        FileLogger.d(TAG, "downloadFileOnce: URL=$urlStr")
        FileLogger.d(TAG, "downloadFileOnce: targetDir=${targetDir.absolutePath}")
        
        var connection: HttpURLConnection? = null
        var inputStream: java.io.InputStream? = null
        var outputStream: FileOutputStream? = null
        
        try {
            FileLogger.d(TAG, "downloadFileOnce: connecting to $urlStr")
            
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 300000  // 5 分钟，大文件需要更长
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Connection", "keep-alive")
            
            FileLogger.d(TAG, "downloadFileOnce: waiting for response...")
            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage
            
            FileLogger.d(TAG, "downloadFileOnce: HTTP $responseCode $responseMessage for $fileName")
            
            when (responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    // 下载成功，继续处理
                }
                HttpURLConnection.HTTP_NOT_FOUND -> {
                    FileLogger.w(TAG, "downloadFileOnce: [$fileName] file not found (404)")
                    return DownloadResult.NotFound
                }
                else -> {
                    FileLogger.e(TAG, "downloadFileOnce: HTTP error $responseCode for $fileName, URL=$urlStr")
                    FileLogger.e(TAG, "downloadFileOnce: responseHeaders=${connection.headerFields}")
                    return DownloadResult.RetryableError("HTTP $responseCode: $responseMessage")
                }
            }
            
            val contentLength = connection.contentLength
            FileLogger.d(TAG, "downloadFileOnce: $fileName contentLength=$contentLength bytes")
            
            if (contentLength <= 0) {
                FileLogger.w(TAG, "downloadFileOnce: $fileName has invalid contentLength=$contentLength")
            }
            
            val targetFile = File(targetDir, fileName)
            FileLogger.d(TAG, "downloadFileOnce: creating file ${targetFile.absolutePath}")
            
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
                    FileLogger.d(TAG, "downloadFileOnce: $fileName ${totalBytesRead}/${contentLength}bytes ($percent%)")
                    onFileProgress(totalBytesRead.toFloat() / contentLength)
                    lastLogTime = now
                }
            }
            
            outputStream.flush()
            outputStream.fd.sync()
            
            val actualSize = targetFile.length()
            FileLogger.i(TAG, "downloadFileOnce: $fileName completed, expected=$contentLength, actual=$actualSize bytes")
            
            // 验证文件大小
            if (contentLength > 0 && actualSize != contentLength.toLong()) {
                FileLogger.w(TAG, "downloadFileOnce: $fileName size mismatch, expected=$contentLength actual=$actualSize")
            }
            
            return DownloadResult.Success
            
        } catch (e: Exception) {
            FileLogger.e(TAG, "downloadFileOnce: $fileName failed with exception", e)
            FileLogger.e(TAG, "downloadFileOnce: error message=${e.message}, class=${e.javaClass.simpleName}")
            return DownloadResult.RetryableError(e.message ?: "Unknown error")
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
 * 下载结果
 */
sealed class DownloadResult {
    data object Success : DownloadResult()
    data object NotFound : DownloadResult()  // 404 - 文件不存在
    data class RetryableError(val message: String) : DownloadResult()  // 可重试的错误
}

/**
 * MNN 下载状态
 * 进度基于实际下载字节数计算（downloadedBytes/totalBytes）
 */
data class MnnDownloadState(
    val modelId: String,
    val status: MnnDownloadStatus = MnnDownloadStatus.IDLE,
    val currentFile: String? = null,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val errorMessage: String? = null
) {
    /**
     * 进度百分比 (0.0 - 1.0)
     * 基于实际下载字节数计算
     */
    val progress: Float
        get() = if (totalBytes > 0 && downloadedBytes > 0) {
            (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            0f
        }
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
