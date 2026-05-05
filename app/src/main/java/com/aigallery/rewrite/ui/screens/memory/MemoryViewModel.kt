package com.aigallery.rewrite.ui.screens.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.memory.MemoryItem
import com.aigallery.rewrite.memory.MemoryManager
import com.aigallery.rewrite.memory.MemoryType
import com.aigallery.rewrite.memory.VectorStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoryViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    // 由于没有完整的依赖注入图，直接创建实例（演示用）
    // 真实项目中应该通过 Hilt 注入
    private val vectorStore = VectorStore(application)
    private val memoryManager = MemoryManager(application, vectorStore)

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
        loadDemoData() // 加载演示数据
    }

    /**
     * 加载所有记忆
     */
    private fun loadMemories() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // TODO: 从 memoryManager 获取真实数据
                // val allMemories = memoryManager.getMemoriesByType()
                _state.update { it.copy(isLoading = false) }
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
     * 加载演示数据（用于开发测试）
     */
    private fun loadDemoData() {
        val demoMemories = listOf(
            MemoryItem(
                id = "demo_1",
                content = "用户喜欢用简洁明了的方式回答问题，不喜欢长篇大论",
                type = MemoryType.CORE,
                importance = 9
            ),
            MemoryItem(
                id = "demo_2",
                content = "用户正在开发一个 AI Android 应用，项目名称为 AIGallery",
                type = MemoryType.LONG_TERM,
                importance = 8
            ),
            MemoryItem(
                id = "demo_3",
                content = "需要集成 MNN 推理引擎来实现本地 AI 推理",
                type = MemoryType.WORKING,
                importance = 7
            ),
            MemoryItem(
                id = "demo_4",
                content = "刚刚修复了 Material3 的编译警告问题",
                type = MemoryType.SHORT_TERM,
                importance = 5
            ),
            MemoryItem(
                id = "demo_5",
                content = "模型下载管理器支持 Hugging Face 和 ModelScope 两个源",
                type = MemoryType.LONG_TERM,
                importance = 6
            ),
            MemoryItem(
                id = "demo_6",
                content = "5 层记忆系统：瞬时、短期、工作、长期、核心",
                type = MemoryType.CORE,
                importance = 10
            ),
            MemoryItem(
                id = "demo_7",
                content = "用户偏好技术细节，喜欢了解底层实现原理",
                type = MemoryType.CORE,
                importance = 9
            ),
            MemoryItem(
                id = "demo_8",
                content = "聊天界面实现了流式 Token 输出效果",
                type = MemoryType.SHORT_TERM,
                importance = 4
            )
        )

        _state.update { it.copy(memories = demoMemories) }
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
     * 搜索记忆（向量相似度搜索）
     */
    fun performVectorSearch(query: String) {
        viewModelScope.launch {
            _state.update { it.copy(isSearching = true) }
            try {
                // TODO: 使用真实的向量搜索
                // val results = memoryManager.searchMemories(query)
                _state.update { it.copy(isSearching = false) }
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
     * 删除记忆
     */
    fun deleteMemory(memoryId: String) {
        viewModelScope.launch {
            try {
                // memoryManager.deleteMemory(memoryId)
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
                // memoryManager.clearMemoryType(type)
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
     * 添加测试记忆
     */
    fun addTestMemory() {
        val newMemory = MemoryItem(
            content = "测试记忆 - ${System.currentTimeMillis()}",
            type = MemoryType.INSTANT,
            importance = 3
        )
        _state.update { currentState ->
            currentState.copy(memories = currentState.memories + newMemory)
        }
    }
}

/**
 * 记忆界面状态
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
