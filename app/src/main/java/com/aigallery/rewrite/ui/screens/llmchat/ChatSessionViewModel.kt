package com.aigallery.rewrite.ui.screens.llmchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@HiltViewModel
class ChatSessionViewModel @Inject constructor(
) : ViewModel() {

    private var inferenceJob: Job? = null
    private val messageIdCounter = AtomicLong(0)

    private val _state = MutableStateFlow(ChatSessionState())
    val state: StateFlow<ChatSessionState> = _state.asStateFlow()

    private val sessionId = "session_${System.currentTimeMillis()}"

    private fun nextMessageId(): Long {
        return messageIdCounter.incrementAndGet()
    }

    fun sendMessage(message: String) {
        if (message.isBlank()) return

        // 添加用户消息
        val userMsgId = nextMessageId()
        addMessage(
            ChatMessage(
                id = userMsgId,
                content = message,
                role = MessageRole.USER,
                timestamp = System.currentTimeMillis()
            )
        )

        // 添加 AI 消息占位
        val aiMessageId = nextMessageId()
        addMessage(
            ChatMessage(
                id = aiMessageId,
                content = "",
                role = MessageRole.ASSISTANT,
                timestamp = System.currentTimeMillis(),
                isStreaming = true
            )
        )

        // Fallback 模式模拟流式推理
        inferenceJob = viewModelScope.launch {
            try {
                val fallbackResponse = generateFallbackResponse(message)
                for (char in fallbackResponse) {
                    updateStreamingMessage(aiMessageId, char.toString())
                    delay(30)
                }
                markStreamingComplete(aiMessageId)
            } catch (e: Exception) {
                updateStreamingMessage(aiMessageId, "回复失败: ${e.message}")
                markStreamingComplete(aiMessageId)
            }
        }
    }

    private fun generateFallbackResponse(userMessage: String): String {
        return "你好！我是 AIGallery AI 助手。\n\n" +
                "当前处于演示模式，MNN 推理引擎尚未加载模型。\n" +
                "请在「模型管理」中下载并加载模型后使用完整功能。\n\n" +
                "你的消息：${userMessage.take(100)}"
    }

    fun stopGeneration() {
        inferenceJob?.cancel()
        inferenceJob = null
        _state.update { currentState ->
            val updatedMessages = currentState.messages.map { message ->
                if (message.isStreaming) message.copy(isStreaming = false) else message
            }
            currentState.copy(messages = updatedMessages, isGenerating = false)
        }
    }

    fun clearChat() {
        _state.update { it.copy(messages = emptyList()) }
    }

    fun regenerateLastMessage() {
        val messages = _state.value.messages
        val lastUserMessageIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserMessageIndex >= 0) {
            val truncatedMessages = messages.take(lastUserMessageIndex)
            val lastUserMessage = messages[lastUserMessageIndex]
            _state.update { it.copy(messages = truncatedMessages) }
            sendMessage(lastUserMessage.content)
        }
    }

    private fun addMessage(message: ChatMessage) {
        _state.update { currentState ->
            currentState.copy(
                messages = currentState.messages + message,
                isGenerating = message.role == MessageRole.ASSISTANT
            )
        }
    }

    private fun updateStreamingMessage(messageId: Long, newContent: String) {
        _state.update { currentState ->
            val updatedMessages = currentState.messages.map { message ->
                if (message.id == messageId) {
                    message.copy(content = message.content + newContent)
                } else message
            }
            currentState.copy(messages = updatedMessages)
        }
    }

    private fun markStreamingComplete(messageId: Long) {
        _state.update { currentState ->
            val updatedMessages = currentState.messages.map { message ->
                if (message.id == messageId) {
                    message.copy(isStreaming = false)
                } else message
            }
            currentState.copy(messages = updatedMessages, isGenerating = false)
        }
    }

    override fun onCleared() {
        super.onCleared()
        inferenceJob?.cancel()
    }
}

data class ChatSessionState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val sessionId: String = ""
)

data class ChatMessage(
    val id: Long,
    val content: String,
    val role: MessageRole,
    val timestamp: Long,
    val isStreaming: Boolean = false
)

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT
}
