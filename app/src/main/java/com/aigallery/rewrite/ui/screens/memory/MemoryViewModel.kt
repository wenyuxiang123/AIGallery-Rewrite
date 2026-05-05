package com.aigallery.rewrite.ui.screens.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.data.repository.MemoryRepository
import com.aigallery.rewrite.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MemoryScreenState(
    val workingMemories: List<WorkingMemory> = emptyList(),
    val shortTermMemories: List<ShortTermMemory> = emptyList(),
    val longTermMemories: List<LongTermMemory> = emptyList(),
    val knowledgeBaseMemories: List<KnowledgeBaseMemory> = emptyList(),
    val personaMemories: List<PersonaMemory> = emptyList(),
    val config: MemoryConfig = MemoryConfig(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedLayer: MemoryLayer? = null,
    val searchQuery: String = "",
    val searchResults: List<MemoryItem> = emptyList()
)

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MemoryScreenState())
    val state: StateFlow<MemoryScreenState> = _state.asStateFlow()

    init {
        loadAllMemories()
    }

    private fun loadAllMemories() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            // Load short-term memories
            memoryRepository.getShortTermMemories()
                .catch { }
                .collect { memories ->
                    _state.update { it.copy(shortTermMemories = memories) }
                }
        }

        viewModelScope.launch {
            // Load long-term memories
            memoryRepository.getLongTermMemories()
                .catch { }
                .collect { memories ->
                    _state.update { it.copy(longTermMemories = memories) }
                }
        }

        viewModelScope.launch {
            // Load knowledge base memories
            memoryRepository.getKnowledgeBaseMemories()
                .catch { }
                .collect { memories ->
                    _state.update { it.copy(knowledgeBaseMemories = memories) }
                }
        }

        viewModelScope.launch {
            // Load persona memories
            memoryRepository.getPersonaMemories()
                .catch { }
                .collect { memories ->
                    _state.update { it.copy(personaMemories = memories, isLoading = false) }
                }
        }
    }

    fun selectLayer(layer: MemoryLayer?) {
        _state.update { it.copy(selectedLayer = layer) }
    }

    fun search(query: String) {
        _state.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList()) }
            return
        }

        viewModelScope.launch {
            val results = mutableListOf<MemoryItem>()
            
            // Search across all layers
            results.addAll(memoryRepository.searchLongTermMemories(query))
            results.addAll(memoryRepository.searchKnowledgeBase(query))
            
            _state.update { it.copy(searchResults = results) }
        }
    }

    fun addShortTermMemory(content: String, importance: Float = 0.5f) {
        viewModelScope.launch {
            memoryRepository.addShortTermMemory(content, importance)
        }
    }

    fun addLongTermMemory(content: String, tags: List<String> = emptyList()) {
        viewModelScope.launch {
            memoryRepository.addLongTermMemory(content, tags)
        }
    }

    fun addKnowledgeBaseMemory(
        title: String,
        content: String,
        sourceType: KnowledgeSourceType
    ) {
        viewModelScope.launch {
            memoryRepository.addKnowledgeBaseMemory(title, content, sourceType)
        }
    }

    fun addPersonaMemory(
        content: String,
        personaType: PersonaType,
        personalityTraits: Map<String, Float> = emptyMap()
    ) {
        viewModelScope.launch {
            memoryRepository.addPersonaMemory(content, personaType, personalityTraits)
        }
    }

    fun deleteMemory(memory: MemoryItem) {
        viewModelScope.launch {
            when (memory.memoryLayer) {
                MemoryLayer.WORKING -> memoryRepository.clearWorkingMemories("")
                MemoryLayer.SHORT_TERM -> memoryRepository.deleteShortTermMemory(memory.id)
                MemoryLayer.LONG_TERM -> memoryRepository.deleteLongTermMemory(memory.id)
                MemoryLayer.KNOWLEDGE_BASE -> memoryRepository.deleteKnowledgeBaseMemory(memory.id)
                MemoryLayer.PERSONA -> memoryRepository.deletePersonaMemory(memory.id)
            }
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            memoryRepository.clearAllMemories()
        }
    }

    fun updateConfig(config: MemoryConfig) {
        viewModelScope.launch {
            _state.update { it.copy(config = config) }
            // Save to DataStore in real implementation
        }
    }
}
