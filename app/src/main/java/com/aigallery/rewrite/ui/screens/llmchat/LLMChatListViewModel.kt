package com.aigallery.rewrite.ui.screens.llmchat

import android.util.Log
import androidx.lifecycle.ViewModel
import com.aigallery.rewrite.util.FileLogger
import androidx.lifecycle.viewModelScope
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

@HiltViewModel
class LLMChatListViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {

    companion object {
        private const val TAG = "LLMChatList"
    }

    private val _state = MutableStateFlow(LLMChatListState())
    val state: StateFlow<LLMChatListState> = _state.asStateFlow()

    init {
        loadSessions()
    }

    private fun loadSessions() {
        FileLogger.d(TAG, "loadSessions: start")
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                chatRepository.getAllSessions()
                    .catch { e ->
                        FileLogger.e(TAG, "loadSessions: catch", e)
                        _state.update { it.copy(error = e.message ?: "加载失败", isLoading = false) }
                    }
                    .collect { sessions ->
                        FileLogger.d(TAG, "loadSessions: got ${sessions.size} sessions")
                        _state.update { it.copy(sessions = sessions, isLoading = false) }
                    }
            } catch (e: Exception) {
                FileLogger.e(TAG, "loadSessions: exception", e)
                _state.update { it.copy(error = e.message ?: "加载失败", isLoading = false) }
            }
        }
    }

    fun createNewSession(): String {
        val sessionId = UUID.randomUUID().toString()
        FileLogger.d(TAG, "createNewSession: id=$sessionId")
        viewModelScope.launch {
            try {
                chatRepository.createSession("新对话", "qwen2.5-1.5b-mnn")
            } catch (e: Exception) {
                // 数据库操作失败也不崩溃
            }
        }
        return sessionId
    }

    fun deleteSession(sessionId: String) {
        FileLogger.d(TAG, "deleteSession: id=$sessionId")
        viewModelScope.launch {
            try {
                chatRepository.deleteSession(sessionId)
            } catch (e: Exception) {
                // 忽略
            }
        }
    }

    fun clearAllSessions() {
        viewModelScope.launch {
            try {
                _state.value.sessions.forEach { session ->
                    chatRepository.deleteSession(session.id)
                }
            } catch (e: Exception) {
                // 忽略
            }
        }
    }
}
