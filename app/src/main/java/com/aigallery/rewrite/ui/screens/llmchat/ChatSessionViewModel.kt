package com.aigallery.rewrite.ui.screens.llmchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatSessionViewModel @Inject constructor(
) : ViewModel() {

    private var inferenceJob: Job? = null

    private val _state = MutableStateFlow(ChatSessionState())
    val state: StateFlow<ChatSessionState> = _state.asStateFlow()

    private val sessionId = "session_${System.currentTimeMillis()}"

    fun sendMessage(message: String) {
        if (message.isBlank()) return

        // 添加用户消息
        addMessage(
            ChatMessage(
                content = message,
                role = MessageRole.USER,
                timestamp = System.currentTimeMillis()
            )
        )

        // 添加 AI 消息占位
        val aiMessageId = System.currentTimeMillis()
        addMessage(
            ChatMessage(
                id = aiMessageId,
                content = "",
                role = MessageRole.ASSISTANT,
                timestamp = System.currentTimeMillis(),
                isStreaming = true
            )
        )

        // 模拟流式推理（fallback 模式）
        inferenceJob = viewModelScope.launch {
            try {
                val fallbackResponse = generateFallbackResponse(message)
                var fullResponse = ""
                for (char in fallbackResponse) {
                    fullResponse += char
                    updateStreamingMessage(aiMessageId, char.toString())
                    delay(30) // 模拟流式输出延迟
                }
                markStreamingComplete(aiMessageId)
            } catch (e: Exception) {
                updateStreamingMessage(aiMessageId, "回复失败: ${e.message}")
                markStreamingComplete(aiMessageId)
            }
        }
    }

    /**
     * Fallback 模式：生成模拟回复
     * 当 MNN 引擎不可用时使用
     */
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
    val id: Long = System.currentTimeMillis(),
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
