package com.aigallery.rewrite.ui.screens.llmchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.domain.model.AIModel
import com.aigallery.rewrite.domain.model.MessageRole
import com.aigallery.rewrite.data.repository.MemoryRepository
import com.aigallery.rewrite.domain.model.MemoryConfig
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
    private val inferenceEngine: MnnInferenceEngine,
    private val memoryRepository: MemoryRepository
) : ViewModel() {

    companion object {
        private const val TAG = "ChatSession"
        private const val MAX_MEMORY_CHARS = 500
        private const val MAX_HISTORY_TURNS = 6  // Keep last 6 messages (3 user + 3 assistant)
        private val memoryConfig = MemoryConfig(shortTermMemoryEnabled = true, shortTermWindowSize = 5, longTermMemoryEnabled = true, longTermRetrievalLimit = 3, personaMemoryEnabled = true)
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
        
        // 自动尝试恢复上次加载的模型
        autoRestoreModel()
    }

    private fun nextMessageId(): Long = messageIdCounter.incrementAndGet()

    /**
     * 尝试自动恢复上次加载的模型
     */
    private fun autoRestoreModel() {
        viewModelScope.launch {
            if (inferenceEngine.isInitialized) {
                FileLogger.d(TAG, "autoRestoreModel: engine already initialized")
                return@launch
            }
            
            FileLogger.d(TAG, "autoRestoreModel: attempting to restore previously loaded model...")
            _engineState.update { it.copy(isInitializing = true, isLoading = true, loadProgress = 0.1f) }
            
            try {
                // 模拟加载进度更新
                val progressJob = launch {
                    var progress = 0.1f
                    while (progress < 0.9f) {
                        delay(500)
                        progress += 0.05f
                        _engineState.update { it.copy(loadProgress = progress.coerceAtMost(0.9f)) }
                    }
                }
                
                val restored = inferenceEngine.autoRestore()
                progressJob.cancel()
                
                if (restored) {
                    FileLogger.i(TAG, "autoRestoreModel: model restored successfully")
                    _engineState.update { it.copy(loadProgress = 1f) }
                } else {
                    FileLogger.d(TAG, "autoRestoreModel: no model to restore (no saved path or files missing)")
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "autoRestoreModel: failed", e)
                _engineState.update { it.copy(loadProgress = 0f) }
            } finally {
                updateEngineState()
                _engineState.update { it.copy(isInitializing = false, isLoading = false) }
            }
        }
    }

    /**
     * 更新引擎状态
     */
    private fun updateEngineState() {
        _engineState.update { state ->
            state.copy(
                isInitialized = inferenceEngine.isInitialized,
                loadedModelName = inferenceEngine.getLoadedModelName(),
                loadedModelPath = inferenceEngine.getLoadedModelPath(),
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

        // Store user message to WorkingMemory
        viewModelScope.launch {
            try {
                memoryRepository.addWorkingMemory(sessionId, "[用户] $message")
                FileLogger.d(TAG, "sendMessage: stored user msg to working memory, sessionId=$sessionId")
            } catch (e: Exception) { FileLogger.e(TAG, "sendMessage: failed to store working memory", e) }
        }

        val aiMessageId = nextMessageId()
        addMessage(ChatMessage(id = aiMessageId, content = "", role = MessageRole.ASSISTANT, timestamp = System.currentTimeMillis(), isStreaming = true))
        FileLogger.d(TAG, "sendMessage: AI placeholder added, id=$aiMessageId, total messages=${_state.value.messages.size}")

        inferenceJob = viewModelScope.launch {
            var tokenCount = 0
            try {
                // 等待模型加载完成（最多等30秒）
                if (!inferenceEngine.isInitialized) {
                    FileLogger.d(TAG, "sendMessage: engine not ready, triggering model auto-restore...")
                    updateStreamingMessage(aiMessageId, "⏳ 正在恢复模型...")
                    _engineState.update { it.copy(isLoading = true, loadProgress = 0.1f) }
                    
                    // 触发自动恢复加载
                    val restoreJob = launch {
                        try {
                            inferenceEngine.autoRestore()
                        } catch (e: Exception) {
                            FileLogger.e(TAG, "sendMessage: auto-restore failed", e)
                        }
                    }
                    
                    // 等待加载完成或超时，同时更新进度
                    var waitCount = 0
                    while (!inferenceEngine.isInitialized && waitCount < 300) { // 300 * 100ms = 30s
                        delay(100)
                        waitCount++
                        // 模拟进度（0.1到0.9之间）
                        val progress = 0.1f + (waitCount.toFloat() / 300f) * 0.8f
                        _engineState.update { it.copy(loadProgress = progress.coerceAtMost(0.9f)) }
                    }
                    
                    // 取消恢复任务（如果已完成则无影响）
                    restoreJob.cancel()
                    _engineState.update { it.copy(isLoading = false, loadProgress = if (inferenceEngine.isInitialized) 1f else 0f) }
                }
                
                if (inferenceEngine.isInitialized) {
                    FileLogger.d(TAG, "sendMessage: using MNN inference")
                    _engineState.update { it.copy(isGenerating = true) }
                    
                    // 清除"加载中"提示，准备接收推理结果
                    _state.update { s ->
                        s.copy(messages = s.messages.map { if (it.id == aiMessageId) it.copy(content = "") else it })
                    }
                    
                    // 构建完整提示词（包含记忆上下文+对话历史）
                    val fullPrompt = buildPrompt(message)
                    FileLogger.d(TAG, "sendMessage: built prompt, length=${fullPrompt.length}")
                    
                    // 流式输出过滤：过滤<think>到</think>之间的内容
                    var tokenBuffer = ""
                    var inThinkingBlock = false
                    
                    // 使用流式推理
                    inferenceEngine.inferStream(
                        prompt = fullPrompt,
                        config = InferenceConfig(
                            maxLength = 2048,
                            temperature = 0.7f,
                            topK = 40,
                            topP = 0.9f,
                            numThreads = 0,
                            repeatPenalty = 1.2f
                        )
                    ).collect { token ->
                        tokenBuffer += token
                        
                        // 处理<think>标签
                        if (tokenBuffer.contains("<think>")) {
                            inThinkingBlock = true
                            tokenBuffer = tokenBuffer.substringAfter("<think>")
                        }
                        
                        // 处理</think>标签
                        if (tokenBuffer.contains("</think>")) {
                            inThinkingBlock = false
                            tokenBuffer = tokenBuffer.substringAfter("</think>")
                        }
                        
                        // 只在非thinking块时输出
                        if (!inThinkingBlock && tokenBuffer.isNotEmpty()) {
                            updateStreamingMessage(aiMessageId, tokenBuffer)
                            tokenCount++
                            tokenBuffer = ""
                        }
                        
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

                    // Store AI response to working memory
                    val aiResponse = _state.value.messages.find { it.id == aiMessageId }?.content ?: ""
                    if (aiResponse.isNotBlank()) {
                        try {
                            memoryRepository.addWorkingMemory(sessionId, "[助手] ${aiResponse.take(200)}")
                            FileLogger.d(TAG, "sendMessage: stored AI response to working memory, len=${aiResponse.length}")
                        } catch (e: Exception) {
                            FileLogger.e(TAG, "sendMessage: failed to store AI working memory", e)
                        }
                    }
                } else {
                    // 超时，模型仍未加载
                    FileLogger.d(TAG, "sendMessage: model load timeout, falling back to mock response")
                    _state.update { s ->
                        s.copy(messages = s.messages.map { if (it.id == aiMessageId) it.copy(content = "") else it })
                    }
                    val fallbackResponse = generateFallbackResponse(message)
                    for (char in fallbackResponse) {
                        updateStreamingMessage(aiMessageId, char.toString())
                        delay(30)
                    }
                }
                
                markStreamingComplete(aiMessageId)
                FileLogger.d(TAG, "sendMessage: stream completed, tokenCount=$tokenCount")
                
                // 更新引擎状态
                updateEngineState()
                
            } catch (e: Exception) {
                FileLogger.e(TAG, "sendMessage: inference failed at token $tokenCount", e)
                updateStreamingMessage(aiMessageId, "推理失败: ${e.message}")
                markStreamingComplete(aiMessageId)
                _engineState.update { it.copy(isGenerating = false) }
            }
        }
    }

    /**
     * 构建完整提示词 - JSON格式多轮对话
     * 格式: [{"r":"system","c":"记忆上下文"}, {"r":"user","c":"..."}, {"r":"assistant","c":"..."}, ...]
     */
    private suspend fun buildPrompt(userMessage: String): String {
        val messages = mutableListOf<Pair<String, String>>()

        // 1. Retrieve relevant memories
        val memories = try {
            memoryRepository.retrieveAllRelevantMemories(userMessage, memoryConfig, sessionId)
        } catch (e: Exception) {
            FileLogger.e(TAG, "buildPrompt: memory retrieval failed", e)
            emptyList()
        }
        FileLogger.d(TAG, "buildPrompt: retrieved ${memories.size} memories")

        // 2. Add memory context as system message if available
        if (memories.isNotEmpty()) {
            val memSb = StringBuilder("【相关记忆】\n")
            for (mem in memories.take(5)) {
                val line = "- ${mem.content.take(80)}\n"
                if (memSb.length + line.length > MAX_MEMORY_CHARS) break
                memSb.append(line)
            }
            messages.add("system" to memSb.toString().trimEnd())
        }

        // 3. Add conversation history (last N completed messages)
        val history = _state.value.messages
            .filter { !it.isStreaming && it.content.isNotBlank() }
            .takeLast(MAX_HISTORY_TURNS)

        for (msg in history) {
            val role = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                else -> continue
            }
            messages.add(role to msg.content.take(300))
        }

        // 4. Add current user message
        messages.add("user" to userMessage)

        // 5. Build control-char delimited string: \u0001role\u0002content\u0001role\u0002content...
        val sb = StringBuilder()
        for ((role, msgContent) in messages) {
            sb.append("\u0001$role\u0002$msgContent")
        }

        FileLogger.d(TAG, "buildPrompt: total messages=${messages.size}, prompt_len=${sb.length}")
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
        inferenceEngine.resetConversation()
        viewModelScope.launch {
            try { memoryRepository.clearWorkingMemories(sessionId) } catch (_: Exception) {}
        }
    }

    /**
     * 刷新引擎状态
     */
    fun refreshEngineState() {
        updateEngineState()
    }

    /**
     * 选择模型
     */
    fun selectModel(model: AIModel) {
        FileLogger.d(TAG, "selectModel: modelId=${model.id}, name=${model.name}")
        _state.update { it.copy(selectedModel = model) }
        
        // 加载模型到推理引擎
        viewModelScope.launch {
            try {
                _engineState.update { it.copy(isInitializing = true, isLoading = true, loadProgress = 0.1f) }
                updateEngineState()
                
                // 模拟加载进度更新
                val progressJob = launch {
                    var progress = 0.1f
                    while (progress < 0.9f) {
                        delay(500)
                        progress += 0.05f
                        _engineState.update { it.copy(loadProgress = progress.coerceAtMost(0.9f)) }
                    }
                }
                
                FileLogger.i(TAG, "selectModel: model selected successfully")
                progressJob.cancel()
                _engineState.update { it.copy(loadProgress = 1f) }
            } catch (e: Exception) {
                FileLogger.e(TAG, "selectModel: failed", e)
                _engineState.update { it.copy(loadProgress = 0f) }
            } finally {
                _engineState.update { it.copy(isInitializing = false, isLoading = false) }
            }
        }
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
        // Promote working memories to short-term
        viewModelScope.launch {
            try {
                memoryRepository.getWorkingMemories(sessionId).first().forEach { mem ->
                    if (mem.content.length > 10) {
                        memoryRepository.addShortTermMemory(mem.content, 0.5f)
                    }
                }
                memoryRepository.clearWorkingMemories(sessionId)
            } catch (_: Exception) {}
        }
    }
}

/**
 * 引擎状态
 */
data class EngineState(
    val isInitialized: Boolean = false,
    val isGenerating: Boolean = false,
    val isInitializing: Boolean = false,
    val isLoading: Boolean = false,       // 模型加载中
    val loadProgress: Float = 0f,          // 加载进度 0-1
    val loadedModelName: String? = null,
    val loadedModelPath: String? = null,
    val memoryUsageMB: Float = 0f,
    val lastInferenceMs: Long = 0,
    val tokensPerSecond: Float = 0f
)

/**
 * 聊天会话状态
 */
data class ChatSessionState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val sessionId: String = "",
    val selectedModel: AIModel? = null
)

/**
 * 聊天消息
 */
data class ChatMessage(
    val id: Long,
    val content: String,
    val role: MessageRole,
    val timestamp: Long,
    val isStreaming: Boolean = false
)
