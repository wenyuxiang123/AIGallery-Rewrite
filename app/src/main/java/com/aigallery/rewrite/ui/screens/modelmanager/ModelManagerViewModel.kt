package com.aigallery.rewrite.ui.screens.modelmanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.download.MnnDownloadState
import com.aigallery.rewrite.download.MnnDownloadStatus
import com.aigallery.rewrite.download.MnnModelDownloader
import com.aigallery.rewrite.domain.model.AIModel
import com.aigallery.rewrite.domain.model.ModelCatalog
import com.aigallery.rewrite.domain.model.ModelStatus
import com.aigallery.rewrite.inference.MnnInferenceEngine
import com.aigallery.rewrite.inference.InferenceConfig
import com.aigallery.rewrite.util.FileLogger
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
    val selectedProvider: String? = null,
    val mnnDownloadProgress: Map<String, Float> = emptyMap()
)

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    private val application: Application,
    private val mnnDownloader: MnnModelDownloader,
    private val inferenceEngine: MnnInferenceEngine
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ModelManager"
    }

    // 进度更新任务
    private var progressUpdateJob: Job? = null
    private var mnnDownloadJobs = mutableMapOf<String, Job>()

    private val _state = MutableStateFlow(ModelManagerState())
    val state: StateFlow<ModelManagerState> = _state.asStateFlow()

    init {
        loadModels()
        startProgressUpdater()
        observeMnnDownloadStates()
    }

    /**
     * 启动进度更新器
     */
    private fun startProgressUpdater() {
        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch {
            while (isActive) {
                delay(500)
                try {
                    updateModelStatusFromDownloadManager()
                } catch (e: Exception) {
                    // 忽略更新错误
                }
            }
        }
    }

    /**
     * 观察 MNN 下载状态
     */
    private fun observeMnnDownloadStates() {
        viewModelScope.launch {
            mnnDownloader.downloadStates.collect { states ->
                _state.update { state ->
                    val progress = states.mapValues { it.value.progress }
                    state.copy(mnnDownloadProgress = progress)
                }
            }
        }
    }

    /**
     * 从下载管理器更新模型状态
     */
    private fun updateModelStatusFromDownloadManager() {
        _state.update { currentState ->
            val updatedModels = currentState.models.map { model ->
                if (model.isMnnModel) {
                    // MNN 模型状态
                    if (mnnDownloader.isModelDownloaded(model.id)) {
                        model.copy(status = ModelStatus.DOWNLOADED, downloadProgress = 1f)
                    } else {
                        val mnnState = mnnDownloader.downloadStates.value[model.id]
                        when (mnnState?.status) {
                            MnnDownloadStatus.DOWNLOADING -> model.copy(
                                status = ModelStatus.DOWNLOADING,
                                downloadProgress = mnnState.progress
                            )
                            MnnDownloadStatus.COMPLETED -> model.copy(
                                status = ModelStatus.DOWNLOADED,
                                downloadProgress = 1f
                            )
                            MnnDownloadStatus.FAILED -> model.copy(
                                status = ModelStatus.DOWNLOAD_FAILED,
                                downloadProgress = 0f
                            )
                            else -> model
                        }
                    }
                } else {
                    // GGUF 模型状态
                    if (mnnDownloader.isModelDownloaded(model.id)) {
                        model.copy(status = ModelStatus.DOWNLOADED, downloadProgress = 1f)
                    } else if (model.status == ModelStatus.DOWNLOADING || model.status == ModelStatus.PAUSED) {
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
        FileLogger.d(TAG, "loadModels: start")
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // 获取已下载的模型列表
                val downloadedMnnModels = mnnDownloader.getDownloadedModels().toSet()

                val models = ModelCatalog.supportedModels.map { model ->
                    val isDownloaded = if (model.isMnnModel) {
                        model.id in downloadedMnnModels
                    } else {
                        mnnDownloader.isModelDownloaded(model.id)
                    }
                    
                    model.copy(
                        status = if (isDownloaded) {
                            ModelStatus.DOWNLOADED
                        } else {
                            ModelStatus.NOT_DOWNLOADED
                        }
                    )
                }
                FileLogger.d(TAG, "loadModels: got ${models.size} models")
                _state.update { it.copy(models = models, isLoading = false) }
            } catch (e: Exception) {
                FileLogger.e(TAG, "loadModels: failed", e)
                _state.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    /**
     * 下载模型
     * 
     * GGUF 模型会自动重定向到对应的 MNN 版本进行下载，
     * 因为 GGUF 格式无法使用 MNN 推理引擎
     */
    fun downloadModel(modelId: String) {
        FileLogger.d(TAG, "downloadModel: id=$modelId")
        val model = _state.value.models.find { it.id == modelId } ?: run {
            FileLogger.w(TAG, "downloadModel: model not found: $modelId")
            return
        }

        // 防止重复点击：如果已经在下载中，跳过
        if (model.status == ModelStatus.DOWNLOADING) {
            FileLogger.d(TAG, "downloadModel: already downloading, skip: $modelId")
            return
        }
        // 检查MNN版本是否也在下载中
        if (!model.isMnnModel) {
            val mnnModelId = modelId + "-mnn"
            val mnnModel = _state.value.models.find { it.id == mnnModelId }
            if (mnnModel != null && mnnModel.status == ModelStatus.DOWNLOADING) {
                FileLogger.d(TAG, "downloadModel: MNN version already downloading, skip: $mnnModelId")
                return
            }
        }
        // 检查是否已有下载Job在运行
        if (mnnDownloadJobs.containsKey(modelId) || mnnDownloadJobs.containsKey(modelId + "-mnn")) {
            FileLogger.d(TAG, "downloadModel: download job already running for $modelId")
            return
        }

        viewModelScope.launch {
            FileLogger.d(TAG, "downloadModel: isMnnModel=${model.isMnnModel}, name=${model.name}")

            if (model.isMnnModel) {
                // MNN 模型直接下载
                FileLogger.d(TAG, "downloadModel: downloading MNN model directly")
                _state.update { state ->
                    state.copy(
                        models = state.models.map { m ->
                            if (m.id == modelId) {
                                m.copy(status = ModelStatus.DOWNLOADING, downloadProgress = 0f)
                            } else m
                        }
                    )
                }
                downloadMnnModel(model)
            } else {
                // GGUF 模型 -> 自动重定向到对应的 MNN 版本
                FileLogger.d(TAG, "downloadModel: GGUF model detected, looking for MNN version")
                
                val mnnModelId = modelId + "-mnn"
                val mnnModel = _state.value.models.find { it.id == mnnModelId }
                
                if (mnnModel != null) {
                    FileLogger.i(TAG, "downloadModel: redirecting GGUF model $modelId to MNN model $mnnModelId")
                    
                    // 更新 GGUF 模型状态为 "正在跳转到 MNN 版本"
                    _state.update { state ->
                        state.copy(
                            models = state.models.map { m ->
                                if (m.id == modelId) {
                                    m.copy(status = ModelStatus.DOWNLOADING, downloadProgress = 0f, 
                                          name = "${model.name} (正在使用MNN版本)")
                                } else m
                            }
                        )
                    }
                    
                    // 延迟一点让状态更新被看到
                    delay(300)
                    
                    // 下载 MNN 模型
                    downloadMnnModel(mnnModel)
                } else {
                    FileLogger.w(TAG, "downloadModel: no MNN version found for $modelId")
                    
                    // 更新状态为下载失败，并显示提示
                    _state.update { state ->
                        state.copy(
                            models = state.models.map { m ->
                                if (m.id == modelId) {
                                    m.copy(status = ModelStatus.DOWNLOAD_FAILED, downloadProgress = 0f)
                                } else m
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * 下载 GGUF 模型
     */
    private fun downloadGgufModel(model: AIModel) {
        val downloadManager = com.aigallery.rewrite.download.ModelDownloadManager(application)
        
        val downloadUrl = model.mirrorUrl.ifEmpty { model.downloadUrl }
        val downloadSource = if (downloadUrl.contains("modelscope", ignoreCase = true)) {
            com.aigallery.rewrite.download.ModelSource.MODEL_SCOPE
        } else {
            com.aigallery.rewrite.download.ModelSource.HUGGING_FACE
        }

        val downloadId = downloadManager.downloadModel(
            modelId = model.id,
            modelName = model.name,
            source = downloadSource,
            downloadUrl = downloadUrl
        )

        if (downloadId == -1L) {
            _state.update { state ->
                state.copy(
                    models = state.models.map { m ->
                        if (m.id == model.id) {
                            m.copy(status = ModelStatus.DOWNLOAD_FAILED, downloadProgress = 0f)
                        } else m
                    }
                )
            }
        }
    }

    /**
     * 下载 MNN 模型
     */
    private fun downloadMnnModel(model: AIModel) {
        FileLogger.d(TAG, "downloadMnnModel: starting for model=${model.id}, name=${model.name}")
        
        val job = viewModelScope.launch {
            FileLogger.d(TAG, "downloadMnnModel: calling mnnDownloader.downloadModel")
            
            val result = mnnDownloader.downloadModel(
                modelId = model.id,
                onProgress = { current, total, fileName ->
                    FileLogger.d(TAG, "downloadMnnModel progress: $fileName ($current/$total)")
                },
                onByteProgress = { progress ->
                    _state.update { state ->
                        state.copy(
                            models = state.models.map { m ->
                                if (m.id == model.id) {
                                    m.copy(
                                        status = ModelStatus.DOWNLOADING,
                                        downloadProgress = progress
                                    )
                                } else m
                            }
                        )
                    }
                }
            )

            FileLogger.d(TAG, "downloadMnnModel: download completed, checking result")
            
            result.fold(
                onSuccess = {
                    FileLogger.i(TAG, "downloadMnnModel: ${model.id} completed successfully")
                    _state.update { state ->
                        state.copy(
                            models = state.models.map { m ->
                                if (m.id == model.id) {
                                    m.copy(status = ModelStatus.DOWNLOADED, downloadProgress = 1f)
                                } else m
                            }
                        )
                    }
                },
                onFailure = { error ->
                    FileLogger.e(TAG, "downloadMnnModel: ${model.id} failed with error: ${error.message}", error)
                    _state.update { state ->
                        state.copy(
                            models = state.models.map { m ->
                                if (m.id == model.id) {
                                    m.copy(status = ModelStatus.DOWNLOAD_FAILED, downloadProgress = 0f)
                                } else m
                            }
                        )
                    }
                }
            )
            
            FileLogger.d(TAG, "downloadMnnModel: job finished for ${model.id}")
            mnnDownloadJobs.remove(model.id)
        }
        
        mnnDownloadJobs[model.id] = job
    }

    /**
     * 加载模型到推理引擎
     */
    fun loadModel(modelId: String) {
        FileLogger.d(TAG, "loadModel: id=$modelId")
        val model = _state.value.models.find { it.id == modelId } ?: return

        viewModelScope.launch {
            _state.update { state ->
                state.copy(
                    models = state.models.map { m ->
                        if (m.id == modelId) {
                            m.copy(status = ModelStatus.INITIALIZING)
                        } else m
                    }
                )
            }

            try {
                val modelPath = if (model.isMnnModel) {
                    mnnDownloader.getModelDir(modelId)
                } else {
                    com.aigallery.rewrite.download.ModelDownloadManager(application).getModelFilePath(modelId)
                        ?: throw IllegalStateException("Model file not found")
                }

                FileLogger.d(TAG, "loadModel: path=$modelPath")

                val success = inferenceEngine.initialize(
                    modelPath = modelPath,
                    config = InferenceConfig(
                        maxLength = 512,
                        temperature = 0.7f,
                        topK = 40,
                        topP = 0.9f,
                        numThreads = 4,
                        contextWindow = 2048
                    )
                )

                if (success) {
                    FileLogger.i(TAG, "loadModel: ${model.name} loaded successfully")
                    _state.update { state ->
                        state.copy(
                            models = state.models.map { m ->
                                if (m.id == modelId) {
                                    m.copy(status = ModelStatus.READY)
                                } else m
                            }
                        )
                    }
                } else {
                    FileLogger.e(TAG, "loadModel: ${model.name} failed")
                    _state.update { state ->
                        state.copy(
                            models = state.models.map { m ->
                                if (m.id == modelId) {
                                    m.copy(status = ModelStatus.FAILED)
                                } else m
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "loadModel: ${model.name} exception", e)
                _state.update { state ->
                    state.copy(
                        models = state.models.map { m ->
                            if (m.id == modelId) {
                                m.copy(status = ModelStatus.FAILED)
                            } else m
                        },
                        error = e.message
                    )
                }
            }
        }
    }

    /**
     * 卸载模型
     */
    fun unloadModel() {
        viewModelScope.launch {
            inferenceEngine.release()
            loadModels() // 刷新状态
        }
    }

    /**
     * 取消下载
     */
    fun cancelDownload(modelId: String) {
        // 取消 MNN 下载任务
        mnnDownloadJobs[modelId]?.cancel()
        mnnDownloadJobs.remove(modelId)
        
        // 取消 GGUF 下载
        val downloadManager = com.aigallery.rewrite.download.ModelDownloadManager(application)
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
     * 删除已下载模型
     */
    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            val model = _state.value.models.find { it.id == modelId }
            
            if (model?.isMnnModel == true) {
                mnnDownloader.deleteModel(modelId)
            } else {
                com.aigallery.rewrite.download.ModelDownloadManager(application).deleteModel(modelId)
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
     * 重试
     */
    fun retry() {
        loadModels()
    }

    override fun onCleared() {
        super.onCleared()
        progressUpdateJob?.cancel()
        mnnDownloadJobs.values.forEach { it.cancel() }
        mnnDownloadJobs.clear()
    }
}
