package com.aigallery.rewrite.ui.screens.modelmanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.download.DownloadStatus
import com.aigallery.rewrite.download.ModelDownloadManager
import com.aigallery.rewrite.download.ModelSource
import com.aigallery.rewrite.domain.model.AIModel
import com.aigallery.rewrite.domain.model.ModelCatalog
import com.aigallery.rewrite.domain.model.ModelStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelManagerState(
    val models: List<AIModel> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val selectedProvider: String? = null
)

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    private val application: Application
) : AndroidViewModel(application) {

    private val downloadManager = ModelDownloadManager(application)

    // 进度更新任务
    private var progressUpdateJob: Job? = null

    private val _state = MutableStateFlow(ModelManagerState())
    val state: StateFlow<ModelManagerState> = _state.asStateFlow()

    init {
        loadModels()
        startProgressUpdater()
    }

    /**
     * 启动进度更新器
     */
    private fun startProgressUpdater() {
        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch {
            while (isActive) {
                delay(500) // 每 500ms 更新一次进度
                try {
                    downloadManager.updateProgress()
                    updateModelStatusFromDownloadManager()
                } catch (e: Exception) {
                    // 忽略更新错误
                }
            }
        }
    }

    /**
     * 从下载管理器更新模型状态
     */
    private fun updateModelStatusFromDownloadManager() {
        val downloadStates = downloadManager.downloadStates.value

        _state.update { currentState ->
            val updatedModels = currentState.models.map { model ->
                val downloadState = downloadStates[model.id]
                if (downloadState != null) {
                    when (downloadState.status) {
                        DownloadStatus.DOWNLOADING -> model.copy(
                            status = ModelStatus.DOWNLOADING,
                            downloadProgress = downloadState.progress
                        )
                        DownloadStatus.PAUSED -> model.copy(
                            status = ModelStatus.PAUSED,
                            downloadProgress = downloadState.progress
                        )
                        DownloadStatus.COMPLETED, DownloadStatus.DOWNLOADED -> model.copy(
                            status = ModelStatus.DOWNLOADED,
                            downloadProgress = 1f
                        )
                        DownloadStatus.FAILED -> model.copy(
                            status = ModelStatus.FAILED,
                            downloadProgress = 0f
                        )
                        DownloadStatus.CANCELLED -> model.copy(
                            status = ModelStatus.NOT_DOWNLOADED,
                            downloadProgress = 0f
                        )
                        else -> model
                    }
                } else {
                    // 如果没有下载状态，检查本地文件
                    if (downloadManager.isModelDownloaded(model.id)) {
                        model.copy(status = ModelStatus.DOWNLOADED, downloadProgress = 1f)
                    } else if (model.status == ModelStatus.DOWNLOADING || model.status == ModelStatus.PAUSED) {
                        // 下载状态丢失，标记为未下载
                        model.copy(status = ModelStatus.NOT_DOWNLOADED, downloadProgress = 0f)
                    } else {
                        model
                    }
                }
            }
            currentState.copy(models = updatedModels)
        }
    }

    /**
     * 加载模型列表
     */
    private fun loadModels() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // 获取已下载的模型列表
                val downloadedModelIds = downloadManager.getDownloadedModels().toSet()

                val models = ModelCatalog.supportedModels.map { model ->
                    model.copy(
                        status = if (model.id in downloadedModelIds) {
                            ModelStatus.DOWNLOADED
                        } else {
                            ModelStatus.NOT_DOWNLOADED
                        }
                    )
                }
                _state.update { it.copy(models = models, isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    /**
     * 下载模型
     */
    fun downloadModel(modelId: String) {
        val model = _state.value.models.find { it.id == modelId } ?: return

        viewModelScope.launch {
            // 更新状态为下载中
            _state.update { state ->
                state.copy(
                    models = state.models.map { m ->
                        if (m.id == modelId) {
                            m.copy(status = ModelStatus.DOWNLOADING, downloadProgress = 0f)
                        } else m
                    }
                )
            }

            // 优先使用 ModelScope 镜像下载（国内更快）
            val downloadUrl = model.mirrorUrl.ifEmpty { model.downloadUrl }
            val downloadSource = if (downloadUrl.contains("modelscope", ignoreCase = true)) {
                ModelSource.MODEL_SCOPE
            } else {
                ModelSource.HUGGING_FACE
            }

            val downloadId = downloadManager.downloadModel(
                modelId = modelId,
                modelName = model.name,
                source = downloadSource,
                downloadUrl = downloadUrl
            )

            if (downloadId == -1L) {
                // 下载启动失败
                _state.update { state ->
                    state.copy(
                        models = state.models.map { m ->
                            if (m.id == modelId) {
                                m.copy(status = ModelStatus.FAILED, downloadProgress = 0f)
                            } else m
                        }
                    )
                }
            }
        }
    }

    /**
     * 取消下载
     */
    fun cancelDownload(modelId: String) {
        val downloadId = downloadManager.getDownloadIdByModelId(modelId)
        if (downloadId != -1L) {
            downloadManager.cancelDownload(downloadId)
        }
        _state.update { state ->
            state.copy(
                models = state.models.map { m ->
                    if (m.id == modelId) {
                        m.copy(status = ModelStatus.NOT_DOWNLOADED, downloadProgress = 0f)
                    } else m
                }
            )
        }
    }

    /**
     * 暂停下载
     */
    fun pauseDownload(modelId: String) {
        val downloadId = downloadManager.getDownloadIdByModelId(modelId)
        if (downloadId != -1L) {
            val success = downloadManager.pauseDownload(downloadId)
            if (success) {
                _state.update { state ->
                    state.copy(
                        models = state.models.map { m ->
                            if (m.id == modelId) {
                                m.copy(status = ModelStatus.PAUSED)
                            } else m
                        }
                    )
                }
            }
        }
    }

    /**
     * 恢复下载
     */
    fun resumeDownload(modelId: String) {
        val model = _state.value.models.find { it.id == modelId } ?: return
        
        // DownloadManager 不支持恢复，需要重新开始下载
        val downloadId = downloadManager.getDownloadIdByModelId(modelId)
        if (downloadId != -1L) {
            // 先取消旧下载
            downloadManager.cancelDownload(downloadId)
        }
        
        // 重新开始下载
        downloadModel(modelId)
    }

    /**
     * 删除已下载模型
     */
    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            val success = downloadManager.deleteModel(modelId)
            if (success) {
                _state.update { state ->
                    state.copy(
                        models = state.models.map { m ->
                            if (m.id == modelId) {
                                m.copy(status = ModelStatus.NOT_DOWNLOADED, downloadProgress = 0f)
                            } else m
                        }
                    )
                }
            }
        }
    }

    /**
     * 重试下载
     */
    fun retryDownload(modelId: String) {
        val model = _state.value.models.find { it.id == modelId } ?: return
        
        // 先取消任何现有下载
        val existingDownloadId = downloadManager.getDownloadIdByModelId(modelId)
        if (existingDownloadId != -1L) {
            downloadManager.cancelDownload(existingDownloadId)
        }
        
        // 重新下载
        downloadModel(modelId)
    }

    /**
     * 更新搜索查询
     */
    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    /**
     * 按提供商筛选
     */
    fun filterByProvider(provider: String?) {
        _state.update { it.copy(selectedProvider = provider) }
    }

    /**
     * 重试加载
     */
    fun retry() {
        loadModels()
    }

    override fun onCleared() {
        super.onCleared()
        progressUpdateJob?.cancel()
        downloadManager.cleanup()
    }
}
