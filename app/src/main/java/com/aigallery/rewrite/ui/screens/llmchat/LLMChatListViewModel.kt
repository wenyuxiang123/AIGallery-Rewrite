package com.aigallery.rewrite.ui.screens.llmchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.domain.model.ChatSession
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

@HiltViewModel
class LLMChatListViewModel @Inject constructor(
) : ViewModel() {

    // 使用内存存储，避免数据库初始化问题
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())

    private val _state = MutableStateFlow(LLMChatListState())
    val state: StateFlow<LLMChatListState> = _state.asStateFlow()

    init {
        // 初始加载（内存中为空）
        _state.update { it.copy(isLoading = false, sessions = emptyList()) }
    }

    fun createNewSession(): String {
        val sessionId = UUID.randomUUID().toString()
        val newSession = ChatSession(
            id = sessionId,
            title = "新对话",
            modelId = "qwen2-7b"
        )
        _sessions.update { listOf(newSession) + it }
        _state.update { it.copy(sessions = _sessions.value) }
        return sessionId
    }

    fun deleteSession(sessionId: String) {
        _sessions.update { sessions -> sessions.filter { it.id != sessionId } }
        _state.update { it.copy(sessions = _sessions.value) }
    }

    fun clearAllSessions() {
        _sessions.update { emptyList() }
        _state.update { it.copy(sessions = emptyList()) }
    }
}
