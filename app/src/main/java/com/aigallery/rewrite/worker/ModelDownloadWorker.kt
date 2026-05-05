package com.aigallery.rewrite.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
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
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val okHttpClient: OkHttpClient
) : CoroutineWorker(context, workerParams) {

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
                var downloadedBytes = 0L

                FileOutputStream(outputFile).use { fos ->
                    body.byteStream().use { inputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            // Report progress
                            val progress = if (totalBytes > 0) {
                                ((downloadedBytes * 100) / totalBytes).toInt()
                            } else {
                                0
                            }

                            setProgress(workDataOf(
                                KEY_PROGRESS to progress,
                                KEY_DOWNLOADED_BYTES to downloadedBytes,
                                KEY_TOTAL_BYTES to totalBytes
                            ))
                        }
                    }
                }
            }

            // Signal completion
            Result.success(workDataOf(
                KEY_OUTPUT_PATH to outputFile.absolutePath
            ))
        } catch (e: Exception) {
            // Clean up partial file on failure
            outputFile.delete()
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_ERROR = "error"

        /**
         * Create a work request for downloading a model
         */
        fun createWorkRequest(
            modelId: String,
            downloadUrl: String,
            mirrorUrl: String? = null
        ): OneTimeWorkRequest {
            val inputData = workDataOf(
                KEY_MODEL_ID to modelId,
                KEY_DOWNLOAD_URL to (mirrorUrl ?: downloadUrl)
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()

            return OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(inputData)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag("model_download")
                .addTag(modelId)
                .build()
        }
    }
}
