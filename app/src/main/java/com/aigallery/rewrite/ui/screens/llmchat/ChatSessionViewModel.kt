package com.aigallery.rewrite.ui.screens.llmchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.util.FileLogger
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

    companion object {
        private const val TAG = "ChatSession"
    }

    private var inferenceJob: Job? = null
    private val messageIdCounter = AtomicLong(0)

    private val _state = MutableStateFlow(ChatSessionState())
    val state: StateFlow<ChatSessionState> = _state.asStateFlow()

    private val sessionId = "session_${System.currentTimeMillis()}"

    init {
        FileLogger.d(TAG, "init: ViewModel created, sessionId=$sessionId")
    }

    private fun nextMessageId(): Long = messageIdCounter.incrementAndGet()

    fun sendMessage(message: String) {
        if (message.isBlank()) {
            FileLogger.w(TAG, "sendMessage: blank message ignored")
            return
        }

        FileLogger.d(TAG, "sendMessage: content=${message.take(50)}")

        val userMsgId = nextMessageId()
        addMessage(ChatMessage(id = userMsgId, content = message, role = MessageRole.USER, timestamp = System.currentTimeMillis()))
        FileLogger.d(TAG, "sendMessage: user msg added, id=$userMsgId")

        val aiMessageId = nextMessageId()
        addMessage(ChatMessage(id = aiMessageId, content = "", role = MessageRole.ASSISTANT, timestamp = System.currentTimeMillis(), isStreaming = true))
        FileLogger.d(TAG, "sendMessage: AI placeholder added, id=$aiMessageId, total messages=${_state.value.messages.size}")

        inferenceJob = viewModelScope.launch {
            try {
                FileLogger.d(TAG, "sendMessage: start fallback stream")
                val fallbackResponse = generateFallbackResponse(message)
                var charCount = 0
                for (char in fallbackResponse) {
                    updateStreamingMessage(aiMessageId, char.toString())
                    charCount++
                    if (charCount % 10 == 0) {
                        FileLogger.d(TAG, "sendMessage: streamed $charCount chars")
                    }
                    delay(30)
                }
                markStreamingComplete(aiMessageId)
                FileLogger.d(TAG, "sendMessage: stream completed, response length=${fallbackResponse.length}")
            } catch (e: Exception) {
                FileLogger.e(TAG, "sendMessage: stream failed at char $charCount", e)
                updateStreamingMessage(aiMessageId, "回复失败: ${e.message}")
                markStreamingComplete(aiMessageId)
            }
        }
    }

    private fun generateFallbackResponse(userMessage: String): String {
        return "你好！我是 AIGallery AI 助手。\n\n当前处于演示模式，MNN 推理引擎尚未加载模型。\n请在「模型管理」中下载并加载模型后使用完整功能。\n\n你的消息：${userMessage.take(100)}"
    }

    fun stopGeneration() {
        FileLogger.d(TAG, "stopGeneration")
        inferenceJob?.cancel()
        inferenceJob = null
        _state.update { s ->
            s.copy(messages = s.messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it }, isGenerating = false)
        }
    }

    fun clearChat() {
        FileLogger.d(TAG, "clearChat")
        _state.update { it.copy(messages = emptyList()) }
    }

    private fun addMessage(message: ChatMessage) {
        _state.update { s -> s.copy(messages = s.messages + message, isGenerating = message.role == MessageRole.ASSISTANT) }
    }

    private fun updateStreamingMessage(messageId: Long, newContent: String) {
        val currentLen = _state.value.messages.find { it.id == messageId }?.content?.length ?: 0
        FileLogger.d(TAG, "updateStreaming: id=$messageId, char='$newContent', currentLen=$currentLen")
        _state.update { s ->
            s.copy(messages = s.messages.map { if (it.id == messageId) it.copy(content = it.content + newContent) else it })
        }
    }

    private fun markStreamingComplete(messageId: Long) {
        val finalLen = _state.value.messages.find { it.id == messageId }?.content?.length ?: 0
        FileLogger.d(TAG, "markStreamingComplete: id=$messageId, finalLen=$finalLen")
        _state.update { s ->
            s.copy(messages = s.messages.map { if (it.id == messageId) it.copy(isStreaming = false) else it }, isGenerating = false)
        }
    }

    override fun onCleared() {
        FileLogger.d(TAG, "onCleared")
        super.onCleared()
        inferenceJob?.cancel()
    }
}

data class ChatSessionState(val messages: List<ChatMessage> = emptyList(), val isGenerating: Boolean = false, val sessionId: String = "")
data class ChatMessage(val id: Long, val content: String, val role: MessageRole, val timestamp: Long, val isStreaming: Boolean = false)
enum class MessageRole { SYSTEM, USER, ASSISTANT }
