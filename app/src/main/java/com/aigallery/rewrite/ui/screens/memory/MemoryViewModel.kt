package com.aigallery.rewrite.ui.screens.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.data.repository.MemoryRepository
import com.aigallery.rewrite.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MemoryType(val displayName: String) {
    SHORT_TERM("短期"),
    LONG_TERM("长期"),
    KNOWLEDGE("知识库"),
    PERSONA("人设")
}

data class UiMemoryItem(
    val id: String,
    val content: String,
    val type: MemoryType,
    val importance: Int = 5,
    val createdAt: Long = System.currentTimeMillis()
)

data class MemoryState(
    val memories: List<UiMemoryItem> = emptyList(),
    val isLoading: Boolean = false,
    val isSearching: Boolean = false,
    val error: String? = null
) {
    fun getTypeCounts(): Map<MemoryType, Int> = memories.groupingBy { it.type }.eachCount()
}

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MemoryState())
    val state: StateFlow<MemoryState> = _state.asStateFlow()

    private val _selectedType = MutableStateFlow<MemoryType?>(null)
    val selectedType: StateFlow<MemoryType?> = _selectedType.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredMemories: StateFlow<List<UiMemoryItem>> =
        combine(_state, _selectedType, _searchQuery) { state, type, query ->
            var filtered = state.memories
            if (type != null) filtered = filtered.filter { it.type == type }
            if (query.isNotBlank()) filtered = filtered.filter { it.content.contains(query, ignoreCase = true) }
            filtered.sortedByDescending { it.createdAt }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadMemories()
    }

    private fun loadMemories() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                combine(
                    memoryRepository.getShortTermMemories(),
                    memoryRepository.getLongTermMemories(),
                    memoryRepository.getKnowledgeBaseMemories(),
                    memoryRepository.getPersonaMemories()
                ) { shortTerm, longTerm, knowledge, persona ->
                    val items = mutableListOf<UiMemoryItem>()
                    shortTerm.forEach { items.add(UiMemoryItem(it.id, it.content, MemoryType.SHORT_TERM, (it.importance * 10).toInt().coerceIn(1, 10), it.createdAt)) }
                    longTerm.forEach { items.add(UiMemoryItem(it.id, it.content, MemoryType.LONG_TERM, 7, it.createdAt)) }
                    knowledge.forEach { items.add(UiMemoryItem(it.id, "[${it.title}] ${it.content}", MemoryType.KNOWLEDGE, 6, it.createdAt)) }
                    persona.forEach { items.add(UiMemoryItem(it.id, it.content, MemoryType.PERSONA, 9, it.createdAt)) }
                    items
                }.collect { items ->
                    _state.update { it.copy(memories = items, isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun selectType(type: MemoryType?) { _selectedType.value = type }
    fun updateSearchQuery(query: String) { _searchQuery.value = query }

    fun deleteMemory(id: String) {
        viewModelScope.launch {
            try { memoryRepository.deleteShortTermMemory(id) } catch (_: Exception) {}
            try { memoryRepository.deleteLongTermMemory(id) } catch (_: Exception) {}
            try { memoryRepository.deleteKnowledgeBaseMemory(id) } catch (_: Exception) {}
            try { memoryRepository.deletePersonaMemory(id) } catch (_: Exception) {}
            _state.update { it.copy(memories = it.memories.filter { m -> m.id != id }) }
        }
    }

    fun refresh() { loadMemories() }

    fun addTestMemory() {
        viewModelScope.launch {
            try {
                memoryRepository.addShortTermMemory("测试记忆 - ${System.currentTimeMillis()}", 0.5f)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }
}
