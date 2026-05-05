package com.aigallery.rewrite.worker

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for downloading AI models
 */
class ModelDownloadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL) ?: return@withContext Result.failure()
        val outputFile = File(applicationContext.filesDir, "models/$modelId.gguf")

        try {
            // Ensure output directory exists
            outputFile.parentFile?.mkdirs()

            val request = Request.Builder()
                .url(downloadUrl)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        workDataOf(KEY_ERROR to "Download failed: ${response.code}")
                    )
                }

                val body = response.body ?: return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "Empty response body")
                )

                val totalBytes = body.contentLength()
                var bytesDownloaded = 0L

                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead

                            // Update progress
                            if (totalBytes > 0) {
                                val progress = bytesDownloaded.toFloat() / totalBytes
                                setProgressAsync(
                                    workDataOf(
                                        KEY_PROGRESS to progress,
                                        KEY_BYTES_DOWNLOADED to bytesDownloaded,
                                        KEY_TOTAL_BYTES to totalBytes
                                    )
                                )
                            }
                        }
                    }
                }

                return@withContext Result.success(
                    workDataOf(
                        KEY_MODEL_ID to modelId,
                        KEY_LOCAL_PATH to outputFile.absolutePath
                    )
                )
            }
        } catch (e: Exception) {
            outputFile.delete()
            return@withContext Result.failure(
                workDataOf(KEY_ERROR to e.message)
            )
        }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_LOCAL_PATH = "local_path"
        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_ERROR = "error"

        fun startDownload(modelId: String, downloadUrl: String): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_MODEL_ID to modelId,
                        KEY_DOWNLOAD_URL to downloadUrl
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        }
    }
}
