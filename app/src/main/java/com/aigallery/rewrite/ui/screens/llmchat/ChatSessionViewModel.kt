package com.aigallery.rewrite.ui.screens.llmchat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.data.repository.ChatRepository
import com.aigallery.rewrite.data.repository.MemoryRepository
import com.aigallery.rewrite.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatSessionState(
    val session: ChatSession? = null,
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val error: String? = null,
    val selectedModel: AIModel? = null
)

@HiltViewModel
class ChatSessionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val memoryRepository: MemoryRepository
) : ViewModel() {

    private val sessionId: String = savedStateHandle["sessionId"] ?: ""

    private val _state = MutableStateFlow(ChatSessionState())
    val state: StateFlow<ChatSessionState> = _state.asStateFlow()

    init {
        loadSession()
        loadMessages()
    }

    private fun loadSession() {
        viewModelScope.launch {
            val session = chatRepository.getSessionById(sessionId)
            val model = session?.modelId?.let { modelId ->
                ModelCatalog.supportedModels.find { it.id == modelId }
            }
            _state.update { it.copy(session = session, selectedModel = model) }
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            chatRepository.getMessagesBySession(sessionId)
                .catch { e ->
                    _state.update { it.copy(error = e.message) }
                }
                .collect { messages ->
                    _state.update { it.copy(messages = messages) }
                }
        }
    }

    fun updateInputText(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _state.value.inputText.trim()
        if (text.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, inputText = "") }

            // Add user message
            val userMessage = ChatMessage(
                role = MessageRole.USER,
                content = text
            )
            chatRepository.addMessage(sessionId, userMessage)

            // Add working memory
            memoryRepository.addWorkingMemory(sessionId, "用户: $text")

            // Simulate AI response
            _state.update { it.copy(isLoading = true) }
            
            // Simulated response - in real app, this would call LLM API
            kotlinx.coroutines.delay(1000)
            
            val assistantMessage = ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "这是一个模拟的AI回复。您发送的消息是: \"$text\"\n\n在实际应用中，这里会调用本地部署的LLM模型进行推理。"
            )
            chatRepository.addMessage(sessionId, assistantMessage)

            // Add to short-term memory
            memoryRepository.addShortTermMemory(
                content = "用户: $text\n助手: 这是一个模拟的AI回复。",
                importance = 0.6f
            )

            _state.update { it.copy(isLoading = false, isStreaming = false) }
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            chatRepository.clearSessionMessages(sessionId)
            memoryRepository.clearWorkingMemories(sessionId)
        }
    }

    fun retry() {
        loadSession()
        loadMessages()
    }
}
