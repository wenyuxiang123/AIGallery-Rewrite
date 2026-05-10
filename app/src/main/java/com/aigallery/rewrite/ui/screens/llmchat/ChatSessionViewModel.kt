package com.aigallery.rewrite.ui.screens.llmchat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aigallery.rewrite.data.local.dao.ChatMessageDao
import com.aigallery.rewrite.data.local.dao.ChatSessionDao
import com.aigallery.rewrite.data.local.entity.ChatMessageEntity
import com.aigallery.rewrite.data.local.entity.ChatSessionEntity
import com.aigallery.rewrite.domain.model.AIModel
import com.aigallery.rewrite.domain.model.MessageRole
import com.aigallery.rewrite.domain.model.ModelStatus
import com.aigallery.rewrite.domain.model.ModelCatalog
import com.aigallery.rewrite.data.repository.MemoryRepository
import com.aigallery.rewrite.download.MnnModelDownloader
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
    private val memoryRepository: MemoryRepository,
    private val chatMessageDao: ChatMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val mnnDownloader: MnnModelDownloader,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "ChatSession"
        private const val MAX_MEMORY_CHARS = 500
        private const val MAX_HISTORY_TURNS = 6  // Keep last 6 messages (3 user + 3 assistant)
        private const val MAX_THINKING_BLOCK_CHARS = 500  // Safety limit for thinking block
        private val memoryConfig = MemoryConfig(shortTermMemoryEnabled = true, shortTermWindowSize = 5, longTermMemoryEnabled = true, longTermRetrievalLimit = 3, personaMemoryEnabled = true)
        private const val KEY_SESSION_ID = "sessionId"
    }

    private var inferenceJob: Job? = null
    private val messageIdCounter = AtomicLong(0)
    
    // Bug2修复: thinking block 状态机变量
    private var thinkingBuffer = ""
    private var inToolCallBlock = false
    private var thinkingBlockChars = 0

    private val _state = MutableStateFlow(ChatSessionState())
    val state: StateFlow<ChatSessionState> = _state.asStateFlow()

    // 推理引擎状态
    private val _engineState = MutableStateFlow(EngineState())
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    // sessionId: 从 SavedStateHandle 获取（导航传入）或生成新的
    private val sessionId: String = savedStateHandle.get<String>(KEY_SESSION_ID)
        ?: "session_${System.currentTimeMillis()}".also {
            savedStateHandle[KEY_SESSION_ID] = it
        }

    init {
        FileLogger.d(TAG, "init: ViewModel created, sessionId=$sessionId")
        updateEngineState()
        
        // 自动尝试恢复上次加载的模型
        autoRestoreModel()
        
        // 加载可用模型列表
        loadAvailableModels()
        
        // 恢复消息和 session 元数据
        restoreSessionData()
    }

    /**
     * 恢复 session 数据（消息和模型）
     */
    private fun restoreSessionData() {
        viewModelScope.launch {
            try {
                // 从数据库加载消息
                val entities = chatMessageDao.getMessagesBySessionSync(sessionId)
                if (entities.isNotEmpty()) {
                    val messages = entities.map { it.toDomain() }
                    _state.update { it.copy(messages = messages) }
                    FileLogger.d(TAG, "restoreSessionData: loaded ${messages.size} messages from DB")
                    
                    // 恢复 messageIdCounter
                    val maxId = messages.maxOfOrNull { it.id } ?: 0
                    messageIdCounter.set(maxId)
                }
                
                // 恢复 session 元数据（模型）
                val session = chatSessionDao.getSessionById(sessionId)
                if (session != null && session.modelId.isNotEmpty()) {
                    val model = ModelCatalog.getModelById(session.modelId)
                    if (model != null) {
                        _state.update { it.copy(selectedModel = model) }
                        FileLogger.d(TAG, "restoreSessionData: restored model=${model.name}")
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "restoreSessionData: failed", e)
            }
        }
    }

    private fun nextMessageId(): Long = messageIdCounter.incrementAndGet()

    /**
     * 加载可用模型列表（已下载的模型）
     */
    private fun loadAvailableModels() {
        viewModelScope.launch {
            try {
                val downloadedMnnModels = mnnDownloader.getDownloadedModels().toSet()
                val available = ModelCatalog.supportedModels.filter { model ->
                    model.id in downloadedMnnModels
                }.map { it.copy(status = ModelStatus.DOWNLOADED) }
                _state.update { it.copy(availableModels = available) }
                FileLogger.d(TAG, "loadAvailableModels: found ${available.size} available models")
            } catch (e: Exception) {
                FileLogger.e(TAG, "loadAvailableModels: failed", e)
            }
        }
    }

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
     * 清理文本中的控制字符和 HTML 标签
     */
    private fun cleanContent(text: String): String {
        return text
            // 移除控制字符 (保留换行和制表符)
            .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]"), "")
            // 移除 HTML 标签
            .replace(Regex("<[^>]+>"), "")
            .trim()
    }

    /**
     * 处理 thinking block 过滤
     * 使用状态机正确匹配 <tool_call> 和 </tool_call> 标签
     * 
     * @param token 新收到的 token
     * @return 如果不在 thinking block 中，返回要显示的内容；否则返回 null
     */
    private fun processThinkingBlock(token: String): String? {
        // 累积 buffer 用于检测标签
        thinkingBuffer += token
        
        // 检测 <tool_call> 标签开始
        if (!inToolCallBlock && thinkingBuffer.contains("<tool_call>")) {
            inToolCallBlock = true
            thinkingBuffer = ""
            return null
        }
        
        // 检测 </tool_call> 标签结束
        if (inToolCallBlock && thinkingBuffer.contains("</tool_call>")) {
            inToolCallBlock = false
            thinkingBuffer = ""
            return null
        }
        
        // 如果在 thinking block 中
        if (inToolCallBlock) {
            thinkingBlockChars += token.length
            // 安全限制：超过最大长度强制退出 thinking block
            if (thinkingBlockChars > MAX_THINKING_BLOCK_CHARS) {
                inToolCallBlock = false
                thinkingBlockChars = 0
                thinkingBuffer = ""
                FileLogger.w(TAG, "processThinkingBlock: forced exit due to max length exceeded")
            }
            return null
        }
        
        // 不在 thinking block 中，正常返回内容
        return token
    }


    /**
     * 发送消息
     */
    fun sendMessage(message: String) {
        if (message.isBlank()) {
            FileLogger.w(TAG, "sendMessage: blank message ignored")
            return
        }

        val cleanedMessage = cleanContent(message)
        FileLogger.d(TAG, "sendMessage: content=${cleanedMessage.take(50)}")

        val userMsgId = nextMessageId()
        val userMsg = ChatMessage(id = userMsgId, content = cleanedMessage, role = MessageRole.USER, timestamp = System.currentTimeMillis())
        addMessage(userMsg)
        
        // 持久化用户消息
        viewModelScope.launch {
            try {
                chatMessageDao.insertMessage(userMsg.toEntity(sessionId))
                // 更新 session 的 updatedAt
                updateSessionTimestamp()
            } catch (e: Exception) {
                FileLogger.e(TAG, "sendMessage: failed to persist user message", e)
            }
        }
        
        FileLogger.d(TAG, "sendMessage: user msg added, id=$userMsgId")

        // Store user message to WorkingMemory (cleaned content)
        viewModelScope.launch {
            try {
                memoryRepository.addWorkingMemory(sessionId, "[用户] $cleanedMessage")
                FileLogger.d(TAG, "sendMessage: stored user msg to working memory, sessionId=$sessionId")
            } catch (e: Exception) { FileLogger.e(TAG, "sendMessage: failed to store working memory", e) }
        }

        val aiMessageId = nextMessageId()
        val aiMsg = ChatMessage(id = aiMessageId, content = "", role = MessageRole.ASSISTANT, timestamp = System.currentTimeMillis(), isStreaming = true)
        addMessage(aiMsg)
        
        // 持久化 AI 占位消息
        viewModelScope.launch {
            try {
                chatMessageDao.insertMessage(aiMsg.toEntity(sessionId))
            } catch (e: Exception) {
                FileLogger.e(TAG, "sendMessage: failed to persist AI placeholder", e)
            }
        }
        
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
                    
                    val fullPrompt = buildPrompt(cleanedMessage)
                    
                    // Bug2修复: 使用状态机正确匹配 <tool_call> 和 </tool_call> 标签
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
                        val result = processThinkingBlock(token)
                        result?.let {
                            updateStreamingMessage(aiMessageId, it)
                            tokenCount++
                        }
                        
                        if (tokenCount % 20 == 0) {
                            FileLogger.d(TAG, "sendMessage: streamed $tokenCount tokens")
                        }
                    }
                    
                    // 修复：如果流结束后仍在 thinking 块中，flush 剩余内容
                    FileLogger.d(TAG, "sendMessage: stream completed, tokenCount=$tokenCount")

                    _engineState.update { 
                        it.copy(
                            isGenerating = false,
                            lastInferenceMs = inferenceEngine.getInferenceStats().lastInferenceTimeMs,
                            tokensPerSecond = inferenceEngine.getInferenceStats().tokensPerSecond
                        )
                    }

                    // Store AI response to working memory (cleaned content)
                    val aiResponse = _state.value.messages.find { it.id == aiMessageId }?.content ?: ""
                    if (aiResponse.isNotBlank()) {
                        try {
                            val cleanedResponse = cleanContent(aiResponse)
                            memoryRepository.addWorkingMemory(sessionId, "[助手] ${cleanedResponse.take(200)}")
                            FileLogger.d(TAG, "sendMessage: stored AI response to working memory, len=${cleanedResponse.length}")
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
                    val fallbackResponse = generateFallbackResponse(cleanedMessage)
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
     * 更新 session 的 updatedAt 时间戳
     */
    private suspend fun updateSessionTimestamp() {
        try {
            val existing = chatSessionDao.getSessionById(sessionId)
            if (existing != null) {
                chatSessionDao.updateSession(existing.copy(updatedAt = System.currentTimeMillis()))
            } else {
                // 创建新的 session
                val model = _state.value.selectedModel
                chatSessionDao.insertSession(
                    ChatSessionEntity(
                        id = sessionId,
                        title = "新对话",
                        modelId = model?.id ?: "",
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "updateSessionTimestamp: failed", e)
        }
    }

    /**
     * 构建完整提示词 - 简洁格式，适合小模型
     * 只注入跨 session 的记忆（short-term, long-term, knowledge base, persona）
     * 不注入 working memory（因为对话历史已经包含了当前 session）
     */
    private suspend fun buildPrompt(userMessage: String): String {
        val messages = mutableListOf<Pair<String, String>>()

        // 1. Retrieve cross-session memories ONLY (short-term, long-term, knowledge base, persona)
        // Working memory is NOT included because conversation history already contains current session content
        val memoryStrings = mutableListOf<String>()
        
        try {
            // Get short-term memories (recent N turns)
            val shortTerm = memoryRepository.getRecentShortTermMemories(memoryConfig.shortTermWindowSize)
            memoryStrings.addAll(shortTerm.map { it.content })
            
            // Get long-term memories (semantic search)
            if (memoryConfig.longTermMemoryEnabled) {
                val longTerm = memoryRepository.searchLongTermMemories(userMessage)
                    .take(memoryConfig.longTermRetrievalLimit)
                memoryStrings.addAll(longTerm.map { it.content })
            }
            
            // Get persona memories
            if (memoryConfig.personaMemoryEnabled) {
                val personas = memoryRepository.getPersonaMemoriesSync()
                memoryStrings.addAll(personas.map { it.content })
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "buildPrompt: memory retrieval failed", e)
        }
        
        FileLogger.d(TAG, "buildPrompt: retrieved ${memoryStrings.size} cross-session memories (excluded working memory)")

        // 2. Add memory context as a simple system instruction (not as separate messages)
        val memoryContext = if (memoryStrings.isNotEmpty()) {
            val memSb = StringBuilder("【背景】\n")
            for (mem in memoryStrings.take(3)) {
                val line = "${mem.take(100)}\n"
                if (memSb.length + line.length > 300) break
                memSb.append(line)
            }
            memSb.toString().trimEnd()
        } else {
            ""
        }

        // 3. Add conversation history (last N completed messages)
        val history = _state.value.messages
            .filter { !it.isStreaming && it.content.isNotBlank() }
            .takeLast(MAX_HISTORY_TURNS)

        // 4. Build message list with simplified format
        if (memoryContext.isNotEmpty()) {
            messages.add("system" to memoryContext)
        }

        for (msg in history) {
            val role = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                else -> continue
            }
            messages.add(role to msg.content.take(300))
        }

        // 5. Add current user message
        messages.add("user" to userMessage)

        // 6. Build control-char delimited string: \u0001role\u0002content\u0001role\u0002content...
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
            try {
                // 清空数据库中的消息
                chatMessageDao.deleteMessagesBySession(sessionId)
                memoryRepository.clearWorkingMemories(sessionId)
            } catch (e: Exception) {
                FileLogger.e(TAG, "clearChat: failed to clear DB messages", e)
            }
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
        
        // 更新数据库中的 session 模型信息
        viewModelScope.launch {
            try {
                val existing = chatSessionDao.getSessionById(sessionId)
                if (existing != null) {
                    chatSessionDao.updateSession(existing.copy(modelId = model.id))
                } else {
                    chatSessionDao.insertSession(
                        ChatSessionEntity(
                            id = sessionId,
                            title = "新对话",
                            modelId = model.id,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "selectModel: failed to update session", e)
            }
        }
        
        // 真正加载模型到推理引擎
        viewModelScope.launch {
            try {
                _engineState.update { it.copy(isInitializing = true, isLoading = true, loadProgress = 0.1f) }
                updateEngineState()
                
                // 获取模型路径
                val modelPath = mnnDownloader.getModelDir(model.id)
                FileLogger.d(TAG, "selectModel: loading model from path=$modelPath")
                
                // 验证模型目录存在
                val modelDir = java.io.File(modelPath)
                if (!modelDir.exists()) {
                    throw IllegalStateException("Model directory not found: $modelPath")
                }
                
                // 释放旧模型
                if (inferenceEngine.isInitialized) {
                    FileLogger.d(TAG, "selectModel: releasing previous model")
                    inferenceEngine.release()
                }
                
                // 加载新模型
                val success = inferenceEngine.initialize(
                    modelPath = modelPath,
                    config = InferenceConfig(
                        maxLength = 2048,
                        temperature = 0.7f,
                        topK = 40,
                        topP = 0.9f,
                        numThreads = 0,
                        contextWindow = 2048
                    )
                )
                
                if (success) {
                    FileLogger.i(TAG, "selectModel: model ${model.name} loaded successfully from $modelPath")
                    _engineState.update { it.copy(loadProgress = 1f) }
                    updateEngineState()
                } else {
                    FileLogger.e(TAG, "selectModel: failed to load model ${model.name}")
                    _engineState.update { it.copy(loadProgress = 0f) }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "selectModel: failed", e)
                _engineState.update { it.copy(loadProgress = 0f) }
            } finally {
                _engineState.update { it.copy(isInitializing = false, isLoading = false) }
                updateEngineState()
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
        
        // 持久化流完成状态到数据库
        viewModelScope.launch {
            try {
                val message = _state.value.messages.find { it.id == messageId }
                if (message != null) {
                    chatMessageDao.updateMessage(message.toEntity(sessionId))
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "markStreamingComplete: failed to persist", e)
            }
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
    val selectedModel: AIModel? = null,
    val availableModels: List<AIModel> = emptyList()
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

/**
 * ChatMessage 与 ChatMessageEntity 互相转换
 */
fun ChatMessage.toEntity(sessionId: String) = ChatMessageEntity(
    id = id.toString(),
    sessionId = sessionId,
    role = role.name.lowercase(),
    content = content,
    imageUrls = "",
    audioUrl = null,
    timestamp = timestamp,
    isStreaming = isStreaming,
    error = null
)

fun ChatMessageEntity.toDomain() = ChatMessage(
    id = id.toLong(),
    role = MessageRole.valueOf(role.uppercase()),
    content = content,
    timestamp = timestamp,
    isStreaming = isStreaming
)
