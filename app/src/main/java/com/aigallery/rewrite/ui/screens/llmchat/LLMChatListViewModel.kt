package com.aigallery.rewrite.ui.screens.llmchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.data.repository.ChatRepository
import com.aigallery.rewrite.domain.model.*
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
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LLMChatListState())
    val state: StateFlow<LLMChatListState> = _state.asStateFlow()

    init {
        loadSessions()
    }

    private fun loadSessions() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            chatRepository.getAllSessions()
                .catch { e ->
                    _state.update { it.copy(error = e.message, isLoading = false) }
                }
                .collect { sessions ->
                    _state.update { it.copy(sessions = sessions, isLoading = false) }
                }
        }
    }

    fun createNewSession(): String {
        val sessionId = UUID.randomUUID().toString()
        viewModelScope.launch {
            chatRepository.createSession(
                title = "新对话",
                modelId = "qwen2-7b" // Default model
            )
        }
        return sessionId
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            chatRepository.deleteSession(sessionId)
        }
    }

    fun clearAllSessions() {
        viewModelScope.launch {
            _state.value.sessions.forEach { session ->
                chatRepository.deleteSession(session.id)
            }
        }
    }
}
