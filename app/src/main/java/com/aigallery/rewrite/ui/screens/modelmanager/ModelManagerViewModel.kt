package com.aigallery.rewrite.ui.screens.modelmanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

data class ModelManagerState(
    val models: List<AIModel> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val selectedProvider: String? = null
)

@HiltViewModel
class ModelManagerViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(ModelManagerState())
    val state: StateFlow<ModelManagerState> = _state.asStateFlow()

    init {
        loadModels()
    }

    private fun loadModels() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // In a real app, we would load download status from database
                val models = ModelCatalog.supportedModels.map { model ->
                    // Simulate some models as downloaded for demo
                    model.copy(
                        status = if (model.id == "qwen2-7b" || model.id == "llama3-8b") {
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
        viewModelScope.launch {
            _state.update { state ->
                state.copy(
                    models = state.models.map { model ->
                        if (model.id == modelId) {
                            model.copy(status = ModelStatus.DOWNLOADING, downloadProgress = 0f)
                        } else model
                    }
                )
            }

            // Simulate download progress
            for (progress in 1..10) {
                kotlinx.coroutines.delay(500)
                _state.update { state ->
                    state.copy(
                        models = state.models.map { model ->
                            if (model.id == modelId) {
                                model.copy(downloadProgress = progress / 10f)
                            } else model
                        }
                    )
                }
            }

            // Complete download
            _state.update { state ->
                state.copy(
                    models = state.models.map { model ->
                        if (model.id == modelId) {
                            model.copy(status = ModelStatus.DOWNLOADED, downloadProgress = 1f)
                        } else model
                    }
                )
            }
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            _state.update { state ->
                state.copy(
                    models = state.models.map { model ->
                        if (model.id == modelId) {
                            model.copy(status = ModelStatus.NOT_DOWNLOADED, downloadProgress = 0f)
                        } else model
                    }
                )
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
}
