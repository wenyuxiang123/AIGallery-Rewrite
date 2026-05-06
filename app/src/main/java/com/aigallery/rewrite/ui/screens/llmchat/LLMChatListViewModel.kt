package com.aigallery.rewrite.ui.screens.llmchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.data.local.AIGalleryDatabase
import com.aigallery.rewrite.data.local.dao.ChatMessageDao
import com.aigallery.rewrite.data.local.dao.ChatSessionDao
import com.aigallery.rewrite.data.repository.ChatRepository
import com.aigallery.rewrite.data.repository.ChatRepositoryImpl
import com.aigallery.rewrite.domain.model.ChatSession
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class LLMChatListState(
    val sessions: List<ChatSession> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * LLM 聊天列表 ViewModel
 * 支持 Room 数据库持久化和内存模式降级
 */
@HiltViewModel
class LLMChatListViewModel @Inject constructor(
    private val database: AIGalleryDatabase?
) : ViewModel() {

    // 容错标志：数据库是否可用
    private var useInMemory = database == null
    
    // ChatRepository 实例
    private val chatRepository: ChatRepository? = if (!useInMemory && database != null) {
        try {
            ChatRepositoryImpl(database.chatSessionDao(), database.chatMessageDao(), Gson())
        } catch (e: Exception) {
            useInMemory = true
            null
        }
    } else {
        null
    }
    
    // 内存存储（数据库不可用时的降级方案）
    private val _memorySessions = MutableStateFlow<List<ChatSession>>(emptyList())

    private val _state = MutableStateFlow(LLMChatListState())
    val state: StateFlow<LLMChatListState> = _state.asStateFlow()

    init {
        // 尝试从数据库加载，如果失败则降级到内存模式
        tryLoadSessions()
    }

    /**
     * 尝试加载会话列表
     */
    private fun tryLoadSessions() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            
            if (!useInMemory && chatRepository != null) {
                try {
                    // 尝试从数据库加载
                    chatRepository.getAllSessions().collect { sessions ->
                        _state.update { 
                            it.copy(
                                sessions = sessions,
                                isLoading = false,
                                error = null
                            ) 
                        }
                    }
                } catch (e: Exception) {
                    // 数据库加载失败，降级到内存模式
                    handleDatabaseError(e)
                }
            } else {
                // 使用内存模式
                useMemoryMode()
            }
        }
    }

    /**
     * 切换到内存模式
     */
    private fun useMemoryMode() {
        useInMemory = true
        _state.update { 
            it.copy(
                sessions = _memorySessions.value,
                isLoading = false,
                error = if (_memorySessions.value.isEmpty()) null else "数据库不可用，显示内存缓存"
            ) 
        }
    }

    /**
     * 处理数据库错误
     */
    private fun handleDatabaseError(e: Exception) {
        useInMemory = true
        // 将内存中的数据同步到状态
        _state.update { 
            it.copy(
                sessions = _memorySessions.value,
                isLoading = false,
                error = "数据库初始化失败，已降级到内存模式"
            ) 
        }
    }

    /**
     * 创建新会话
     * @return 新会话 ID
     */
    fun createNewSession(): String {
        val sessionId = UUID.randomUUID().toString()
        val newSession = ChatSession(
            id = sessionId,
            title = "新对话",
            modelId = "qwen2.5-1.5b" // 默认使用小模型
        )
        
        if (useInMemory || chatRepository == null) {
            // 内存模式
            _memorySessions.update { listOf(newSession) + it }
            _state.update { it.copy(sessions = _memorySessions.value) }
        } else {
            // 数据库模式
            viewModelScope.launch {
                try {
                    chatRepository.createSession("新对话", "qwen2.5-1.5b")
                    // 数据库创建会自动更新 Flow，这里不需要手动更新
                } catch (e: Exception) {
                    // 数据库操作失败，降级到内存
                    _memorySessions.update { listOf(newSession) + _memorySessions.value }
                    _state.update { it.copy(sessions = _memorySessions.value) }
                }
            }
        }
        
        return sessionId
    }

    /**
     * 删除会话
     */
    fun deleteSession(sessionId: String) {
        if (useInMemory || chatRepository == null) {
            // 内存模式
            _memorySessions.update { sessions -> sessions.filter { it.id != sessionId } }
            _state.update { it.copy(sessions = _memorySessions.value) }
        } else {
            // 数据库模式
            viewModelScope.launch {
                try {
                    chatRepository.deleteSession(sessionId)
                } catch (e: Exception) {
                    // 数据库操作失败，降级到内存
                    _memorySessions.update { sessions -> sessions.filter { it.id != sessionId } }
                    _state.update { it.copy(sessions = _memorySessions.value) }
                }
            }
        }
    }

    /**
     * 清空所有会话
     */
    fun clearAllSessions() {
        if (useInMemory || chatRepository == null) {
            // 内存模式
            _memorySessions.update { emptyList() }
            _state.update { it.copy(sessions = emptyList()) }
        } else {
            // 数据库模式
            viewModelScope.launch {
                try {
                    _memorySessions.value.forEach { session ->
                        chatRepository.deleteSession(session.id)
                    }
                } catch (e: Exception) {
                    // 数据库操作失败，降级到内存
                    _memorySessions.update { emptyList() }
                    _state.update { it.copy(sessions = emptyList()) }
                }
            }
        }
    }

    /**
     * 更新会话标题
     */
    fun updateSessionTitle(sessionId: String, title: String) {
        if (useInMemory || chatRepository == null) {
            // 内存模式
            _memorySessions.update { sessions ->
                sessions.map { session ->
                    if (session.id == sessionId) session.copy(title = title) else session
                }
            }
            _state.update { it.copy(sessions = _memorySessions.value) }
        } else {
            // 数据库模式
            viewModelScope.launch {
                try {
                    val session = chatRepository.getSessionById(sessionId)
                    if (session != null) {
                        chatRepository.updateSession(session.copy(title = title))
                    }
                } catch (e: Exception) {
                    // 忽略更新错误
                }
            }
        }
    }

    /**
     * 刷新会话列表
     */
    fun refresh() {
        if (!useInMemory && chatRepository != null) {
            tryLoadSessions()
        } else {
            _state.update { it.copy(sessions = _memorySessions.value) }
        }
    }

    /**
     * 检查是否使用数据库模式
     */
    fun isUsingDatabase(): Boolean = !useInMemory
}
