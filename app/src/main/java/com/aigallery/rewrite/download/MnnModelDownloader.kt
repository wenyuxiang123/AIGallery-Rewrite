package com.aigallery.rewrite.download

import android.content.Context
import android.util.Log
import com.aigallery.rewrite.util.FileLogger
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
    private val context: Context
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
        onProgress: (current: Int, total: Int, fileName: String) -> Unit
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
        
        try {
            // 更新状态
            updateState(modelId, MnnDownloadState(
                modelId = modelId,
                status = MnnDownloadStatus.DOWNLOADING,
                totalFiles = totalFiles
            ))
            
            var completedFiles = 0
            
            for (fileName in allFiles) {
                FileLogger.d(TAG, "downloadModel: downloading $fileName")
                
                updateState(modelId, MnnDownloadState(
                    modelId = modelId,
                    status = MnnDownloadStatus.DOWNLOADING,
                    currentFile = fileName,
                    completedFiles = completedFiles,
                    totalFiles = totalFiles
                ))
                
                try {
                    val success = downloadFile(modelPath, fileName, targetDir)
                    if (success) {
                        completedFiles++
                        onProgress(completedFiles, totalFiles, fileName)
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
     * 下载单个文件
     */
    private fun downloadFile(
        modelPath: String,
        fileName: String,
        targetDir: File
    ): Boolean {
        // ModelScope 下载 URL 格式
        // https://modelscope.cn/models/{owner}/{repo}/resolve/{revision}/{file_path}
        val urlStr = "https://modelscope.cn/models/$modelPath/resolve/master/$fileName"
        
        FileLogger.d(TAG, "downloadFile: $urlStr")
        
        var connection: HttpURLConnection? = null
        var inputStream: java.io.InputStream? = null
        var outputStream: FileOutputStream? = null
        
        try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 300000  // 5 分钟，大文件需要更长
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Accept", "*/*")
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                FileLogger.e(TAG, "downloadFile: HTTP $responseCode for $fileName")
                return false
            }
            
            val contentLength = connection.contentLength
            FileLogger.d(TAG, "downloadFile: $fileName size=$contentLength")
            
            val targetFile = File(targetDir, fileName)
            inputStream = connection.inputStream
            outputStream = FileOutputStream(targetFile)
            
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
            }
            
            outputStream.flush()
            FileLogger.d(TAG, "downloadFile: $fileName done, ${totalBytesRead}bytes")
            
            return true
            
        } catch (e: Exception) {
            FileLogger.e(TAG, "downloadFile: $fileName failed", e)
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
