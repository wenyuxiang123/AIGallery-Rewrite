package com.aigallery.rewrite.ui.screens.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.data.repository.MemoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 记忆类型（5 层记忆系统 - UI展示用）
 */
enum class MemoryType {
    INSTANT,     // 瞬时记忆
    SHORT_TERM,  // 短期记忆
    WORKING,     // 工作记忆
    LONG_TERM,   // 长期记忆
    CORE         // 核心记忆
}

/**
 * 记忆数据项（UI展示用）
 */
data class MemoryItem(
    val id: String,
    val content: String,
    val type: MemoryType,
    val importance: Int = 5,
    val createdAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0
) {
    /**
     * 获取格式化的时间显示
     */
    fun getFormattedTime(): String {
        val elapsed = System.currentTimeMillis() - createdAt
        val minutes = elapsed / (1000 * 60)
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            days < 7 -> "${days}天前"
            else -> "${days / 7}周前"
        }
    }

    /**
     * 获取重要性星级
     */
    fun getImportanceStars(): String = "★".repeat(importance) + "☆".repeat(10 - importance)
}

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MemoryState())
    val state: StateFlow<MemoryState> = _state.asStateFlow()

    // 当前选中的记忆类型标签
    private val _selectedType = MutableStateFlow<MemoryType?>(null)
    val selectedType: StateFlow<MemoryType?> = _selectedType.asStateFlow()

    // 搜索查询
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // 过滤后的记忆列表
    val filteredMemories: StateFlow<List<MemoryItem>> =
        combine(_state, _selectedType, _searchQuery) { state, type, query ->
            var filtered = state.memories

            // 按类型过滤
            if (type != null) {
                filtered = filtered.filter { it.type == type }
            }

            // 按搜索词过滤
            if (query.isNotBlank()) {
                filtered = filtered.filter {
                    it.content.contains(query, ignoreCase = true)
                }
            }

            // 按时间倒序
            filtered.sortedByDescending { it.createdAt }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        loadMemories()
    }

    /**
     * 加载所有记忆（从各个 MemoryRepository 源合并）
     */
    private fun loadMemories() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // 并行加载各层记忆
                val shortTermFlow = memoryRepository.getShortTermMemories()
                val longTermFlow = memoryRepository.getLongTermMemories()
                val knowledgeFlow = memoryRepository.getKnowledgeBaseMemories()
                val personaFlow = memoryRepository.getPersonaMemories()
                
                // 合并所有记忆流
                combine(
                    shortTermFlow,
                    longTermFlow,
                    knowledgeFlow,
                    personaFlow
                ) { shortTerm, longTerm, knowledge, persona ->
                    val allMemories = mutableListOf<MemoryItem>()
                    
                    // 短期记忆
                    allMemories.addAll(shortTerm.map { mem ->
                        MemoryItem(
                            id = mem.id,
                            content = mem.content,
                            type = MemoryType.SHORT_TERM,
                            importance = (mem.importance * 10).toInt().coerceIn(1, 10),
                            createdAt = mem.createdAt,
                            accessCount = mem.turnCount
                        )
                    })
                    
                    // 长期记忆
                    allMemories.addAll(longTerm.map { mem ->
                        MemoryItem(
                            id = mem.id,
                            content = mem.content,
                            type = MemoryType.LONG_TERM,
                            importance = 7,
                            createdAt = mem.createdAt,
                            accessCount = mem.accessCount
                        )
                    })
                    
                    // 知识库
                    allMemories.addAll(knowledge.map { mem ->
                        MemoryItem(
                            id = mem.id,
                            content = "[${mem.title}] ${mem.content}",
                            type = MemoryType.LONG_TERM,
                            importance = 6,
                            createdAt = mem.createdAt,
                            accessCount = 0
                        )
                    })
                    
                    // 角色记忆
                    allMemories.addAll(persona.map { mem ->
                        MemoryItem(
                            id = mem.id,
                            content = mem.content,
                            type = MemoryType.CORE,
                            importance = 9,
                            createdAt = mem.createdAt,
                            accessCount = 0
                        )
                    })
                    
                    allMemories
                }.collect { memories ->
                    _state.update { it.copy(memories = memories, isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
            }
        }
    }

    /**
     * 选择记忆类型
     */
    fun selectType(type: MemoryType?) {
        _selectedType.value = type
    }

    /**
     * 更新搜索查询
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * 搜索记忆（使用 MemoryRepository 的检索功能）
     */
    fun performVectorSearch(query: String) {
        if (query.isBlank()) {
            return
        }
        
        viewModelScope.launch {
            _state.update { it.copy(isSearching = true) }
            try {
                val results = memoryRepository.retrieveAllRelevantMemories(
                    context = query,
                    config = com.aigallery.rewrite.domain.model.MemoryConfig(
                        shortTermMemoryEnabled = true,
                        shortTermWindowSize = 10,
                        longTermMemoryEnabled = true,
                        longTermRetrievalLimit = 10,
                        knowledgeBaseEnabled = true,
                        personaMemoryEnabled = true
                    )
                )
                
                // 将搜索结果转换为 UI 类型
                val searchResults = results.map { mem ->
                    MemoryItem(
                        id = mem.id,
                        content = mem.content,
                        type = when (mem.memoryLayer) {
                            com.aigallery.rewrite.domain.model.MemoryLayer.WORKING -> MemoryType.WORKING
                            com.aigallery.rewrite.domain.model.MemoryLayer.SHORT_TERM -> MemoryType.SHORT_TERM
                            com.aigallery.rewrite.domain.model.MemoryLayer.LONG_TERM -> MemoryType.LONG_TERM
                            com.aigallery.rewrite.domain.model.MemoryLayer.KNOWLEDGE_BASE -> MemoryType.LONG_TERM
                            com.aigallery.rewrite.domain.model.MemoryLayer.PERSONA -> MemoryType.CORE
                        },
                        importance = 7,
                        createdAt = mem.createdAt,
                        accessCount = 0
                    )
                }
                
                // 搜索结果合并到 memories 字段（按任务要求）
                _state.update { currentState ->
                    currentState.copy(
                        memories = searchResults,
                        isSearching = false
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSearching = false,
                        error = e.message
                    )
                }
            }
        }
    }

    /**
     * 删除记忆（根据类型）
     */
    fun deleteMemory(memoryId: String) {
        viewModelScope.launch {
            try {
                // 根据 ID 找到对应记忆并删除（这里简化处理，实际可存储类型信息）
                // 先尝试删除短期记忆
                try {
                    memoryRepository.deleteShortTermMemory(memoryId)
                } catch (e: Exception) { /* ignore */ }
                
                // 再尝试删除长期记忆
                try {
                    memoryRepository.deleteLongTermMemory(memoryId)
                } catch (e: Exception) { /* ignore */ }
                
                // 尝试删除知识库
                try {
                    memoryRepository.deleteKnowledgeBaseMemory(memoryId)
                } catch (e: Exception) { /* ignore */ }
                
                // 尝试删除角色记忆
                try {
                    memoryRepository.deletePersonaMemory(memoryId)
                } catch (e: Exception) { /* ignore */ }
                
                // 从 UI 列表移除
                _state.update { currentState ->
                    currentState.copy(
                        memories = currentState.memories.filter { it.id != memoryId }
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * 清除某类记忆
     */
    fun clearMemoryType(type: MemoryType) {
        viewModelScope.launch {
            try {
                when (type) {
                    MemoryType.SHORT_TERM -> {
                        // 删除最近的短期记忆
                        val recent = memoryRepository.getRecentShortTermMemories(100)
                        recent.forEach { 
                            memoryRepository.deleteShortTermMemory(it.id)
                        }
                    }
                    MemoryType.LONG_TERM -> {
                        // 遍历删除长期记忆
                        memoryRepository.getLongTermMemories().first().forEach {
                            memoryRepository.deleteLongTermMemory(it.id)
                        }
                    }
                    MemoryType.CORE -> {
                        // 删除角色记忆
                        memoryRepository.getPersonaMemories().first().forEach {
                            memoryRepository.deletePersonaMemory(it.id)
                        }
                    }
                    else -> { /* INSTANT and WORKING are session-scoped, not managed here */ }
                }
                
                // 刷新列表
                _state.update { currentState ->
                    currentState.copy(
                        memories = currentState.memories.filter { it.type != type }
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * 刷新数据
     */
    fun refresh() {
        loadMemories()
    }

    /**
     * 添加测试记忆（演示用）
     */
    fun addTestMemory() {
        viewModelScope.launch {
            try {
                val newMemory = memoryRepository.addShortTermMemory(
                    content = "测试记忆 - ${System.currentTimeMillis()}",
                    importance = 0.5f
                )
                
                _state.update { currentState ->
                    currentState.copy(
                        memories = currentState.memories + MemoryItem(
                            id = newMemory.id,
                            content = newMemory.content,
                            type = MemoryType.SHORT_TERM,
                            importance = (newMemory.importance * 10).toInt().coerceIn(1, 10),
                            createdAt = newMemory.createdAt,
                            accessCount = newMemory.turnCount
                        )
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }
}

/**
 * 记忆界面状态（按要求只有4个字段）
 */
data class MemoryState(
    val memories: List<MemoryItem> = emptyList(),
    val isLoading: Boolean = false,
    val isSearching: Boolean = false,
    val error: String? = null
) {
    /**
     * 获取各类型记忆的统计数据
     */
    fun getTypeCounts(): Map<MemoryType, Int> {
        return memories.groupingBy { it.type }.eachCount()
    }
}
