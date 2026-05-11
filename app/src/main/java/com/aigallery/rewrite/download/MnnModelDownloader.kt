package com.aigallery.rewrite.download

import android.content.Context
import com.aigallery.rewrite.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * 
 * 设计原则：
 * 1. 必需文件全部下载成功后，立即标记模型为READY
 * 2. 可选文件在后台异步继续下载，不阻塞模型可用性
 * 3. 进度基于实际下载字节数计算
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
        
        // 必需文件预估大小（用于初始进度计算）
        private val REQUIRED_FILE_SIZES = mapOf(
            "config.json" to 1_000L,
            "llm_config.json" to 10_000L,
            "llm.mnn" to 1_000_000L,
            "llm.mnn.weight" to 278_000_000L,
            "llm.mnn.json" to 10_000_000L,
            "tokenizer.txt" to 5_000_000L
        )
        
        // 可选文件预估大小
        private val OPTIONAL_FILE_SIZES = mapOf(
            "visual.mnn" to 1_000_000L,
            "visual.mnn.weight" to 63_000_000L
        )
        
        // 必需文件总大小（预估）
        private val REQUIRED_TOTAL_SIZE = REQUIRED_FILE_SIZES.values.sum()
        
        // 预估可选文件总大小
        private val OPTIONAL_TOTAL_SIZE = OPTIONAL_FILE_SIZES.values.sum()
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
        // ============ Qwen2.5-MNN 模型 ============
        "qwen2.5-0.5b-int4" to "MNN/Qwen2.5-0.5B-Instruct-MNN",
        "qwen2.5-1.5b-int4" to "MNN/Qwen2.5-1.5B-Instruct-MNN",
        "qwen2.5-3b-int4" to "MNN/Qwen2.5-3B-Instruct-MNN",
        "qwen2.5-3b-int4-optimal" to "MNN/Qwen2.5-3B-Instruct-MNN"
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
     * 检查可选文件是否已下载
     */
    fun isOptionalFilesDownloaded(modelId: String): Boolean {
        val modelDir = File(this.modelDir, modelId)
        if (!modelDir.exists()) return false
        return OPTIONAL_FILES.any { File(modelDir, it).exists() }
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
    /**
     * 获取已下载的模型列表
     * 修复：使用 isModelDownloaded() 验证文件完整性，而非仅检查目录存在
     */
    fun getDownloadedModels(): List<String> {
        return mnnModelPaths.keys.filter { modelId ->
            isModelDownloaded(modelId)
        }
    }
    
    /**
     * 下载 MNN 模型
     * 
     * @param modelId 模型 ID
     * @param onProgress 进度回调 (current, total, fileName)
     */
    // Bug5修复: 添加isMultimodal参数，只有多模态模型才下载visual相关文件
    suspend fun downloadModel(
        modelId: String,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit,
        onByteProgress: (Float) -> Unit = {},
        isMultimodal: Boolean = false  // Bug5修复: 默认为false，不下载visual文件
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val modelPath = mnnModelPaths[modelId] 
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown MNN model: $modelId"))
        
        FileLogger.d(TAG, "downloadModel: $modelId from $modelPath")
        
        // 创建模型目录
        val targetDir = File(modelDir, modelId)
        if (!targetDir.exists()) targetDir.mkdirs()
        
        // 只下载必需文件，可选文件按需下载
        val requiredCount = REQUIRED_FILES.size
        
        try {
            // 更新状态 - 开始下载必需文件
            updateState(modelId, MnnDownloadState(
                modelId = modelId,
                status = MnnDownloadStatus.DOWNLOADING_REQUIRED,
                totalFiles = requiredCount,
                totalBytes = REQUIRED_TOTAL_SIZE,
                downloadedBytes = 0
            ))
            
            var completedRequiredBytes = 0L
            var completedFileIndex = 0
            val actualFileSizes = mutableMapOf<String, Long>()
            
            // 第一阶段：下载所有必需文件
            for ((fileIndex, fileName) in REQUIRED_FILES.withIndex()) {
                FileLogger.d(TAG, "downloadModel: downloading required file $fileName")
                
                val estimatedSize = REQUIRED_FILE_SIZES[fileName] ?: 1_000_000L
                
                updateState(modelId, MnnDownloadState(
                    modelId = modelId,
                    status = MnnDownloadStatus.DOWNLOADING_REQUIRED,
                    currentFile = fileName,
                    completedFiles = completedFileIndex,
                    totalFiles = requiredCount,
                    totalBytes = REQUIRED_TOTAL_SIZE,
                    downloadedBytes = completedRequiredBytes
                ))
                
                val result = downloadRequiredFile(modelPath, fileName, targetDir, 
                    onFileProgress = { fileProgress: Float ->
                        val currentFileBytes = (estimatedSize * fileProgress).toLong()
                        val overallBytes = completedRequiredBytes + currentFileBytes
                        val overallProgress = overallBytes.toFloat() / REQUIRED_TOTAL_SIZE
                        onByteProgress(overallProgress.coerceAtMost(1f))
                        updateState(modelId, MnnDownloadState(
                            modelId = modelId,
                            status = MnnDownloadStatus.DOWNLOADING_REQUIRED,
                            currentFile = fileName,
                            completedFiles = completedFileIndex,
                            totalFiles = requiredCount,
                            totalBytes = REQUIRED_TOTAL_SIZE,
                            downloadedBytes = overallBytes
                        ))
                    },
                    onActualSizeRetrieved = { actualSize ->
                        actualFileSizes[fileName] = actualSize
                    }
                )
                
                if (result is DownloadResult.Success) {
                    val fileSize = actualFileSizes[fileName] ?: estimatedSize
                    completedRequiredBytes += fileSize
                    completedFileIndex++
                    onByteProgress(completedRequiredBytes.toFloat() / REQUIRED_TOTAL_SIZE)
                    FileLogger.d(TAG, "downloadModel: $fileName completed (${fileSize} bytes)")
                } else if (result is DownloadResult.NotFound) {
                    FileLogger.e(TAG, "downloadModel: required file $fileName not found (404)")
                    updateState(modelId, MnnDownloadState(
                        modelId = modelId,
                        status = MnnDownloadStatus.FAILED,
                        errorMessage = "Required file not found: $fileName",
                        totalBytes = REQUIRED_TOTAL_SIZE,
                        downloadedBytes = completedRequiredBytes
                    ))
                    return@withContext Result.failure(
                        IllegalStateException("Required file not found: $fileName")
                    )
                } else {
                    FileLogger.e(TAG, "downloadModel: required file $fileName failed")
                    updateState(modelId, MnnDownloadState(
                        modelId = modelId,
                        status = MnnDownloadStatus.FAILED,
                        errorMessage = "Failed to download required file: $fileName",
                        totalBytes = REQUIRED_TOTAL_SIZE,
                        downloadedBytes = completedRequiredBytes
                    ))
                    return@withContext Result.failure(
                        IllegalStateException("Failed to download required file: $fileName")
                    )
                }
            }
            
            // 必需文件全部下载完成，验证
            val missingRequired = REQUIRED_FILES.filter { 
                !File(targetDir, it).exists() 
            }
            
            if (missingRequired.isNotEmpty()) {
                FileLogger.e(TAG, "downloadModel: missing required files: $missingRequired")
                updateState(modelId, MnnDownloadState(
                    modelId = modelId,
                    status = MnnDownloadStatus.FAILED,
                    errorMessage = "Missing required files: $missingRequired",
                    totalBytes = REQUIRED_TOTAL_SIZE,
                    downloadedBytes = completedRequiredBytes
                ))
                return@withContext Result.failure(
                    IllegalStateException("Missing required files: $missingRequired")
                )
            }
            
            // 必需文件完成，标记模型为READY状态
            // 关键修改：不再等待可选文件下载完成才标记READY
            updateState(modelId, MnnDownloadState(
                modelId = modelId,
                status = MnnDownloadStatus.READY,
                completedFiles = requiredCount,
                totalFiles = requiredCount,
                totalBytes = REQUIRED_TOTAL_SIZE,
                downloadedBytes = completedRequiredBytes
            ))
            
            FileLogger.i(TAG, "downloadModel: $modelId REQUIRED FILES COMPLETED - Model is now READY")
            
            // Bug5修复: 只有多模态模型才下载可选文件
            if (isMultimodal) {
                // 第二阶段：后台异步下载可选文件（不阻塞）
                launch(Dispatchers.IO) {
                    downloadOptionalFilesInBackground(modelId, modelPath, targetDir)
                }
            } else {
                // 纯文本模型：直接标记为COMPLETED
                updateState(modelId, MnnDownloadState(
                    modelId = modelId,
                    status = MnnDownloadStatus.COMPLETED,
                    completedFiles = requiredCount,
                    totalFiles = requiredCount,
                    totalBytes = REQUIRED_TOTAL_SIZE,
                    downloadedBytes = completedRequiredBytes
                ))
                FileLogger.i(TAG, "downloadModel: $modelId is text-only model, skipping optional files (isMultimodal=false)")
            }
            
            // 立即返回成功，因为必需文件已经下载完成
            Result.success(Unit)
            
        } catch (e: Exception) {
            FileLogger.e(TAG, "downloadModel: $modelId failed", e)
            updateState(modelId, MnnDownloadState(
                modelId = modelId,
                status = MnnDownloadStatus.FAILED,
                errorMessage = e.message,
                totalBytes = REQUIRED_TOTAL_SIZE,
                downloadedBytes = 0
            ))
            Result.failure(e)
        }
    }
    
    /**
     * 后台下载可选文件（不阻塞模型READY状态）
     */
    private suspend fun downloadOptionalFilesInBackground(
        modelId: String,
        modelPath: String,
        targetDir: File
    ) {
        FileLogger.d(TAG, "downloadOptionalFilesInBackground: starting for $modelId")
        
        updateState(modelId, MnnDownloadState(
            modelId = modelId,
            status = MnnDownloadStatus.DOWNLOADING_OPTIONAL,
            totalBytes = REQUIRED_TOTAL_SIZE,
            downloadedBytes = REQUIRED_TOTAL_SIZE,
            totalOptionalBytes = OPTIONAL_TOTAL_SIZE,
            downloadedOptionalBytes = 0
        ))
        
        var completedOptionalBytes = 0L
        
        for (fileName in OPTIONAL_FILES) {
            FileLogger.d(TAG, "downloadOptionalFilesInBackground: downloading optional file $fileName")
            
            updateState(modelId, MnnDownloadState(
                modelId = modelId,
                status = MnnDownloadStatus.DOWNLOADING_OPTIONAL,
                currentFile = fileName,
                totalBytes = REQUIRED_TOTAL_SIZE,
                downloadedBytes = REQUIRED_TOTAL_SIZE,
                totalOptionalBytes = OPTIONAL_TOTAL_SIZE,
                downloadedOptionalBytes = completedOptionalBytes
            ))
            
            val result = downloadOptionalFileWithProgress(modelPath, fileName, targetDir,
                onProgress = { progress ->
                    val estimatedSize = OPTIONAL_FILE_SIZES[fileName] ?: 1_000_000L
                    val currentFileBytes = (estimatedSize * progress).toLong()
                    val overallOptionalBytes = completedOptionalBytes + currentFileBytes
                    
                    val requiredWeight = REQUIRED_TOTAL_SIZE.toFloat() / (REQUIRED_TOTAL_SIZE + OPTIONAL_TOTAL_SIZE)
                    val overallProgress = requiredWeight + (1 - requiredWeight) * (overallOptionalBytes.toFloat() / OPTIONAL_TOTAL_SIZE)
                    
                    updateState(modelId, MnnDownloadState(
                        modelId = modelId,
                        status = MnnDownloadStatus.DOWNLOADING_OPTIONAL,
                        currentFile = fileName,
                        totalBytes = REQUIRED_TOTAL_SIZE,
                        downloadedBytes = REQUIRED_TOTAL_SIZE,
                        totalOptionalBytes = OPTIONAL_TOTAL_SIZE,
                        downloadedOptionalBytes = overallOptionalBytes
                    ))
                }
            )
            
            when (result) {
                is DownloadResult.Success -> {
                    val estimatedSize = OPTIONAL_FILE_SIZES[fileName] ?: 1_000_000L
                    completedOptionalBytes += estimatedSize
                    FileLogger.i(TAG, "downloadOptionalFilesInBackground: optional file $fileName completed")
                }
                is DownloadResult.NotFound -> {
                    FileLogger.i(TAG, "downloadOptionalFilesInBackground: optional file $fileName not found (404), skipping")
                }
                is DownloadResult.RetryableError -> {
                    FileLogger.w(TAG, "downloadOptionalFilesInBackground: optional file $fileName failed, skipping")
                }
            }
        }
        
        updateState(modelId, MnnDownloadState(
            modelId = modelId,
            status = MnnDownloadStatus.COMPLETED,
            completedFiles = REQUIRED_FILES.size,
            totalFiles = REQUIRED_FILES.size,
            totalBytes = REQUIRED_TOTAL_SIZE,
            downloadedBytes = REQUIRED_TOTAL_SIZE,
            totalOptionalBytes = OPTIONAL_TOTAL_SIZE,
            downloadedOptionalBytes = completedOptionalBytes
        ))
        
        FileLogger.i(TAG, "downloadOptionalFilesInBackground: all optional files for $modelId completed")
    }
    
    /**
     * 下载必需文件（无限重试直到成功或明确失败）
     * 网络中断后持续重试，每次从头重新下载
     */
    private fun downloadRequiredFile(
        modelPath: String,
        fileName: String,
        targetDir: File,
        onFileProgress: (Float) -> Unit = {},
        onActualSizeRetrieved: (Long) -> Unit = {}
    ): DownloadResult {
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
            
            val result = downloadFileOnce(modelPath, fileName, targetDir, onFileProgress, onActualSizeRetrieved)
            
            when (result) {
                is DownloadResult.Success -> {
                    FileLogger.i(TAG, "downloadRequiredFile: [$fileName] download succeeded on attempt $attempt")
                    return DownloadResult.Success
                }
                is DownloadResult.NotFound -> {
                    // 必需文件不应该返回404，但万一返回则失败
                    FileLogger.e(TAG, "downloadRequiredFile: [$fileName] file not found (404)")
                    return DownloadResult.NotFound
                }
                is DownloadResult.RetryableError -> {
                    // 网络错误，重试
                    FileLogger.w(TAG, "downloadRequiredFile: [$fileName] attempt $attempt failed (${result.message}), retrying in 2 seconds...")
                    try {
                        Thread.sleep(2000L)
                    } catch (e: InterruptedException) {
                        FileLogger.e(TAG, "downloadRequiredFile: [$fileName] retry interrupted", e)
                        return DownloadResult.RetryableError("Interrupted")
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
    ): DownloadResult {
        val targetFile = File(targetDir, fileName)
        
        // 如果文件已存在，认为已下载成功
        if (targetFile.exists()) {
            FileLogger.i(TAG, "downloadOptionalFile: [$fileName] already exists, skipping download")
            return DownloadResult.Success
        }
        
        FileLogger.i(TAG, "downloadOptionalFile: [$fileName] attempting download")
        
        val result = downloadFileOnce(modelPath, fileName, targetDir, {}, {})
        
        return when (result) {
            is DownloadResult.Success -> {
                FileLogger.i(TAG, "downloadOptionalFile: [$fileName] downloaded successfully")
                DownloadResult.Success
            }
            is DownloadResult.NotFound -> {
                FileLogger.i(TAG, "downloadOptionalFile: [$fileName] not found (404), skipping (optional file)")
                DownloadResult.NotFound
            }
            is DownloadResult.RetryableError -> {
                FileLogger.w(TAG, "downloadOptionalFile: [$fileName] download failed (${result.message}), skipping (optional file)")
                DownloadResult.RetryableError(result.message)
            }
        }
    }
    
    /**
     * 下载可选文件（带进度回调）
     */
    private fun downloadOptionalFileWithProgress(
        modelPath: String,
        fileName: String,
        targetDir: File,
        onProgress: (Float) -> Unit = {}
    ): DownloadResult {
        val targetFile = File(targetDir, fileName)
        
        if (targetFile.exists()) {
            FileLogger.i(TAG, "downloadOptionalFileWithProgress: [$fileName] already exists, skipping")
            return DownloadResult.Success
        }
        
        FileLogger.i(TAG, "downloadOptionalFileWithProgress: [$fileName] attempting download")
        
        val result = downloadFileOnce(modelPath, fileName, targetDir, onProgress, {})
        
        return when (result) {
            is DownloadResult.Success -> {
                FileLogger.i(TAG, "downloadOptionalFileWithProgress: [$fileName] downloaded successfully")
                DownloadResult.Success
            }
            is DownloadResult.NotFound -> {
                FileLogger.i(TAG, "downloadOptionalFileWithProgress: [$fileName] not found (404), skipping")
                DownloadResult.NotFound
            }
            is DownloadResult.RetryableError -> {
                FileLogger.w(TAG, "downloadOptionalFileWithProgress: [$fileName] failed, skipping")
                DownloadResult.RetryableError(result.message)
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
        onFileProgress: (Float) -> Unit = {},
        onActualSizeRetrieved: (Long) -> Unit = {}
    ): DownloadResult {
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
            
            if (contentLength > 0) {
                onActualSizeRetrieved(contentLength.toLong())
            } else {
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
 * 
 * 进度计算：
 * - DOWNLOADING_REQUIRED: 基于必需文件下载字节数 (downloadedBytes/totalBytes)
 * - READY: 必需文件已下载完成，模型可用，可选文件继续下载中
 * - DOWNLOADING_OPTIONAL: 可选文件下载中
 * - COMPLETED: 所有文件下载完成
 */
data class MnnDownloadState(
    val modelId: String,
    val status: MnnDownloadStatus = MnnDownloadStatus.IDLE,
    val currentFile: String? = null,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val downloadedOptionalBytes: Long = 0,
    val totalOptionalBytes: Long = 0,
    val errorMessage: String? = null
) {
    /**
     * 进度百分比 (0.0 - 1.0)
     * 
     * 对于 READY 状态，返回基于必需文件的进度（100%）
     * 对于 DOWNLOADING_OPTIONAL 状态，返回整体进度（必需100% + 可选进度）
     */
    val progress: Float
        get() = when (status) {
            MnnDownloadStatus.READY -> 1f
            MnnDownloadStatus.DOWNLOADING_OPTIONAL -> {
                val requiredWeight = totalBytes.toFloat() / (totalBytes + totalOptionalBytes)
                val optionalProgress = if (totalOptionalBytes > 0) {
                    downloadedOptionalBytes.toFloat() / totalOptionalBytes
                } else 0f
                requiredWeight + (1 - requiredWeight) * optionalProgress
            }
            else -> {
                if (totalBytes > 0 && downloadedBytes > 0) {
                    (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                } else 0f
            }
        }
    
    /**
     * 是否为最终完成状态
     */
    val isCompleted: Boolean
        get() = status == MnnDownloadStatus.COMPLETED
}

/**
 * MNN 下载状态枚举
 */
enum class MnnDownloadStatus {
    IDLE,                       // 空闲
    DOWNLOADING_REQUIRED,       // 正在下载必需文件
    READY,                      // 必需文件已下载完成，模型可用（可选文件继续后台下载）
    DOWNLOADING_OPTIONAL,       // 可选文件下载中（模型已就绪）
    COMPLETED,                  // 所有文件下载完成
    FAILED,                     // 下载失败
    CANCELLED                   // 已取消
}
