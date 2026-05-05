package com.aigallery.rewrite.ui.screens.llmchat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.inference.InferenceConfig
import com.aigallery.rewrite.inference.MnnInferenceEngine
import com.aigallery.rewrite.memory.MemoryManager
import com.aigallery.rewrite.memory.MemoryType
import com.aigallery.rewrite.memory.VectorStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatSessionViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    // 推理引擎
    private val inferenceEngine = MnnInferenceEngine()

    // 记忆管理器（演示用，真实项目应通过注入）
    private val vectorStore = VectorStore(application)
    private val memoryManager = MemoryManager(application, vectorStore)

    // 当前推理任务
    private var inferenceJob: Job? = null

    private val _state = MutableStateFlow(ChatSessionState())
    val state: StateFlow<ChatSessionState> = _state.asStateFlow()

    // 当前会话 ID
    private val sessionId = "session_${System.currentTimeMillis()}"

    // 记忆注入开关
    private val memoryInjectionEnabled = MutableStateFlow(true)
    val memoryInjection: StateFlow<Boolean> = memoryInjectionEnabled

    init {
        // 初始化引擎（使用 fallback 模式）
        viewModelScope.launch {
            inferenceEngine.initialize("", InferenceConfig())
        }
    }

    /**
     * 发送消息
     */
    fun sendMessage(message: String) {
        if (message.isBlank()) return

        // 1. 存入记忆系统
        viewModelScope.launch {
            memoryManager.processMessage(message, MessageRole.USER, sessionId)
        }

        // 2. 添加用户消息到 UI
        addMessage(
            ChatMessage(
                content = message,
                role = MessageRole.USER,
                timestamp = System.currentTimeMillis()
            )
        )

        // 3. 添加 AI 消息（初始为空）
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

        // 4. 开始流式推理
        inferenceJob = viewModelScope.launch {
            try {
                val config = InferenceConfig(
                    maxLength = 2048,
                    temperature = 0.7f,
                    topP = 0.9f
                )

                // 构建 Prompt - 包含记忆注入
                val prompt = buildPromptWithContext(message)

                // 流式接收回复
                var fullResponse = ""
                inferenceEngine.inferStream(prompt, config).collect { token ->
                    fullResponse += token
                    updateStreamingMessage(aiMessageId, token)
                }

                // 推理完成 - 将 AI 回复也存入记忆
                if (fullResponse.isNotBlank()) {
                    memoryManager.processMessage(fullResponse, MessageRole.ASSISTANT, sessionId)
                }

                // 标记流式输出完成
                markStreamingComplete(aiMessageId)

            } catch (e: Exception) {
                updateStreamingMessage(
                    aiMessageId,
                    "推理失败: ${e.message}"
                )
                markStreamingComplete(aiMessageId)
            }
        }
    }

    /**
     * 构建带记忆上下文的 Prompt
     */
    private suspend fun buildPromptWithContext(newMessage: String): String {
        val promptBuilder = StringBuilder()

        // 系统提示
        promptBuilder.append("<|system|>\n")
        promptBuilder.append("你是 AIGallery AI 助手，一个有帮助、诚实、无害的 AI。\n")
        promptBuilder.append("请用中文回答用户的问题。\n")

        // 注入相关记忆（如果开启）
        if (memoryInjectionEnabled.value) {
            val contextMemories = memoryManager.buildContextPrompt(newMessage)
            if (contextMemories.isNotBlank()) {
                promptBuilder.append(contextMemories)
            }
        }

        promptBuilder.append("<|end|>\n")

        // 添加对话历史（最近 10 条）
        val history = _state.value.messages
            .filter { it.role != MessageRole.SYSTEM }
            .takeLast(10)

        history.forEach { message ->
            val role = when (message.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                else -> "system"
            }
            promptBuilder.append("<|$role|>\n")
            promptBuilder.append(message.content)
            promptBuilder.append("\n<|end|>\n")
        }

        // 添加新消息
        promptBuilder.append("<|user|>\n")
        promptBuilder.append(newMessage)
        promptBuilder.append("\n<|end|>\n")
        promptBuilder.append("<|assistant|>\n")

        return promptBuilder.toString()
    }

    /**
     * 停止当前生成
     */
    fun stopGeneration() {
        inferenceJob?.cancel()
        inferenceJob = null

        // 标记当前正在流式输出的消息为完成
        _state.update { currentState ->
            val updatedMessages = currentState.messages.map { message ->
                if (message.isStreaming) {
                    message.copy(isStreaming = false)
                } else message
            }
            currentState.copy(messages = updatedMessages, isGenerating = false)
        }
    }

    /**
     * 切换记忆注入开关
     */
    fun toggleMemoryInjection() {
        memoryInjectionEnabled.value = !memoryInjectionEnabled.value
    }

    /**
     * 清除对话历史
     */
    fun clearChat() {
        _state.update {
            it.copy(messages = emptyList())
        }

        // 清除当前会话的瞬时记忆
        viewModelScope.launch {
            memoryManager.clearMemoryType(MemoryType.INSTANT)
            memoryManager.clearMemoryType(MemoryType.SHORT_TERM)
        }
    }

    /**
     * 重新生成最后一条消息
     */
    fun regenerateLastMessage() {
        val messages = _state.value.messages
        val lastUserMessageIndex = messages.indexOfLast { it.role == MessageRole.USER }

        if (lastUserMessageIndex >= 0) {
            // 移除最后一条用户消息及之后的所有消息
            val truncatedMessages = messages.take(lastUserMessageIndex)
            val lastUserMessage = messages[lastUserMessageIndex]

            _state.update { it.copy(messages = truncatedMessages) }

            // 重新发送
            sendMessage(lastUserMessage.content)
        }
    }

    /**
     * 添加消息
     */
    private fun addMessage(message: ChatMessage) {
        _state.update { currentState ->
            currentState.copy(
                messages = currentState.messages + message,
                isGenerating = message.role == MessageRole.ASSISTANT
            )
        }
    }

    /**
     * 更新流式消息
     */
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

    /**
     * 标记流式输出完成
     */
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
        viewModelScope.launch {
            inferenceEngine.release()
            memoryManager.consolidateSession(sessionId)
        }
    }
}

/**
 * 聊天会话状态
 */
data class ChatSessionState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val sessionId: String = ""
)

/**
 * 聊天消息
 */
data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val content: String,
    val role: MessageRole,
    val timestamp: Long,
    val isStreaming: Boolean = false
)

/**
 * 消息角色
 */
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT
}
