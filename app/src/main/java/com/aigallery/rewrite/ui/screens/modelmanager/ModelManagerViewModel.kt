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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val downloadManager = ModelDownloadManager(application)

    // 进度更新任务
    private var progressUpdateJob: kotlinx.coroutines.Job? = null

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
        progressUpdateJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(500) // 每 500ms 更新一次进度
                downloadManager.updateProgress()
                updateModelStatusFromDownloadManager()
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
                        DownloadStatus.COMPLETED -> model.copy(
                            status = ModelStatus.DOWNLOADED,
                            downloadProgress = 1f
                        )
                        DownloadStatus.FAILED -> model.copy(
                            status = ModelStatus.FAILED,
                            downloadProgress = 0f
                        )
                        else -> model
                    }
                } else model
            }
            currentState.copy(models = updatedModels)
        }
    }

    private fun loadModels() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // 获取已下载的模型列表
                val downloadedModelIds = downloadManager.getDownloadedModels()

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

            // 启动真实下载
            val downloadSource = if (modelId.contains("qwen", ignoreCase = true)) {
                ModelSource.MODEL_SCOPE // Qwen 系列优先使用 ModelScope（国内更快）
            } else {
                ModelSource.HUGGING_FACE
            }

            downloadManager.downloadModel(
                modelId = modelId,
                modelName = model.name,
                source = downloadSource
            )
        }
    }

    fun cancelDownload(modelId: String) {
        downloadManager.cancelDownload(modelId)
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

    fun pauseDownload(modelId: String) {
        downloadManager.pauseDownload(modelId)
    }

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

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun filterByProvider(provider: String?) {
        _state.update { it.copy(selectedProvider = provider) }
    }

    fun retry() {
        loadModels()
    }

    override fun onCleared() {
        super.onCleared()
        progressUpdateJob?.cancel()
        downloadManager.cleanup()
    }
}

data class ModelManagerState(
    val models: List<AIModel> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val selectedProvider: String? = null
)
