package com.aigallery.rewrite.ui.screens.llmchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.inference.InferenceConfig
import com.aigallery.rewrite.inference.MnnInferenceEngine
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
    private val inferenceEngine: MnnInferenceEngine
) : ViewModel() {

    companion object {
        private const val TAG = "ChatSession"
    }

    private var inferenceJob: Job? = null
    private val messageIdCounter = AtomicLong(0)

    private val _state = MutableStateFlow(ChatSessionState())
    val state: StateFlow<ChatSessionState> = _state.asStateFlow()

    // 推理引擎状态
    private val _engineState = MutableStateFlow(EngineState())
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val sessionId = "session_${System.currentTimeMillis()}"

    init {
        FileLogger.d(TAG, "init: ViewModel created, sessionId=$sessionId")
        updateEngineState()
    }

    private fun nextMessageId(): Long = messageIdCounter.incrementAndGet()

    /**
     * 更新引擎状态
     */
    private fun updateEngineState() {
        _engineState.update { state ->
            state.copy(
                isInitialized = inferenceEngine.isInitialized,
                loadedModelName = inferenceEngine.getLoadedModelName(),
                memoryUsageMB = inferenceEngine.getMemoryUsageMB()
            )
        }
    }

    /**
     * 发送消息
     */
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
            var tokenCount = 0
            try {
                // 检查引擎是否初始化
                if (inferenceEngine.isInitialized) {
                    FileLogger.d(TAG, "sendMessage: using MNN inference")
                    _engineState.update { it.copy(isGenerating = true) }
                    
                    // 构建完整提示词
                    val fullPrompt = buildPrompt(message)
                    
                    // 使用流式推理
                    inferenceEngine.inferStream(
                        prompt = fullPrompt,
                        config = InferenceConfig(
                            maxLength = 512,
                            temperature = 0.7f,
                            topK = 40,
                            topP = 0.9f,
                            numThreads = 4
                        )
                    ).collect { token ->
                        updateStreamingMessage(aiMessageId, token)
                        tokenCount++
                        if (tokenCount % 20 == 0) {
                            FileLogger.d(TAG, "sendMessage: streamed $tokenCount tokens")
                        }
                    }
                    
                    _engineState.update { 
                        it.copy(
                            isGenerating = false,
                            lastInferenceMs = inferenceEngine.getInferenceStats().lastInferenceTimeMs,
                            tokensPerSecond = inferenceEngine.getInferenceStats().tokensPerSecond
                        )
                    }
                    
                } else {
                    // Fallback 模式
                    FileLogger.d(TAG, "sendMessage: falling back to mock response")
                    val fallbackResponse = generateFallbackResponse(message)
                    for (char in fallbackResponse) {
                        updateStreamingMessage(aiMessageId, char.toString())
                        delay(30)
                    }
                }
                
                markStreamingComplete(aiMessageId)
                FileLogger.d(TAG, "sendMessage: stream completed, tokenCount=$tokenCount")
                
            } catch (e: Exception) {
                FileLogger.e(TAG, "sendMessage: inference failed at token $tokenCount", e)
                updateStreamingMessage(aiMessageId, "推理失败: ${e.message}")
                markStreamingComplete(aiMessageId)
                _engineState.update { it.copy(isGenerating = false) }
            }
        }
    }

    /**
     * 构建提示词
     */
    private fun buildPrompt(userMessage: String): String {
        val history = _state.value.messages
            .filter { it.role != MessageRole.SYSTEM }
            .takeLast(10) // 只保留最近10轮对话
        
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append("你是一个有用的AI助手。<|im_end|>\n")
        
        for (msg in history) {
            when (msg.role) {
                MessageRole.USER -> sb.append("<|im_start|>user\n${msg.content}<|im_end|>\n")
                MessageRole.ASSISTANT -> sb.append("<|im_start|>assistant\n${msg.content}<|im_end|>\n")
                MessageRole.SYSTEM -> {} // 已处理
            }
        }
        
        sb.append("<|im_start|>user\n$userMessage<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        
        return sb.toString()
    }

    /**
     * 生成 fallback 回复
     */
    private fun generateFallbackResponse(userMessage: String): String {
        return if (!inferenceEngine.isInitialized) {
            "⚠️ MNN 推理引擎尚未加载模型\n\n" +
            "请在「模型管理」中下载并加载 MNN 模型后使用完整功能。\n\n" +
            "当前模型: ${inferenceEngine.getLoadedModelName() ?: "未加载"}\n\n" +
            "你的消息: ${userMessage.take(50)}"
        } else {
            "你好！我是 AIGallery AI 助手。\n\n" +
            "当前处于演示模式。\n\n" +
            "你的消息: ${userMessage.take(50)}"
        }
    }

    /**
     * 停止生成
     */
    fun stopGeneration() {
        FileLogger.d(TAG, "stopGeneration")
        inferenceJob?.cancel()
        inferenceJob = null
        _state.update { s ->
            s.copy(messages = s.messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it }, isGenerating = false)
        }
        _engineState.update { it.copy(isGenerating = false) }
    }

    /**
     * 清空聊天
     */
    fun clearChat() {
        FileLogger.d(TAG, "clearChat")
        _state.update { it.copy(messages = emptyList()) }
        // 重置对话上下文
        inferenceEngine.resetConversation()
    }

    /**
     * 刷新引擎状态
     */
    fun refreshEngineState() {
        updateEngineState()
    }

    private fun addMessage(message: ChatMessage) {
        _state.update { s -> s.copy(messages = s.messages + message, isGenerating = message.role == MessageRole.ASSISTANT) }
    }

    private fun updateStreamingMessage(messageId: Long, newContent: String) {
        val currentLen = _state.value.messages.find { it.id == messageId }?.content?.length ?: 0
        FileLogger.v(TAG, "updateStreaming: id=$messageId, char='$newContent', currentLen=$currentLen")
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

/**
 * 引擎状态
 */
data class EngineState(
    val isInitialized: Boolean = false,
    val isGenerating: Boolean = false,
    val loadedModelName: String? = null,
    val memoryUsageMB: Float = 0f,
    val lastInferenceMs: Long = 0,
    val tokensPerSecond: Float = 0f
)
