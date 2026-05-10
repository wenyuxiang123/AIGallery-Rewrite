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
import com.aigallery.rewrite.domain.model.ModelTier
import com.aigallery.rewrite.data.repository.MemoryRepository
import com.aigallery.rewrite.tool.ToolRegistry
import com.aigallery.rewrite.tool.ToolCall
import com.aigallery.rewrite.tool.ToolResponse
import com.aigallery.rewrite.inference.SpeculativeDecoder
import com.aigallery.rewrite.context.ContextManager
import com.aigallery.rewrite.context.MemoryCompressor
import com.aigallery.rewrite.context.GSSCPromptBuilder
import com.aigallery.rewrite.skill.SkillRegistry
import com.aigallery.rewrite.skill.SkillMatch
import com.aigallery.rewrite.trace.AgentTraceLogger
import com.aigallery.rewrite.context.ReflectionChecker
import com.aigallery.rewrite.download.MnnModelDownloader
import com.aigallery.rewrite.download.MnnDownloadStatus
import com.aigallery.rewrite.domain.model.MemoryConfig
import com.aigallery.rewrite.inference.InferenceConfig
import com.aigallery.rewrite.inference.MnnInferenceEngine
import com.aigallery.rewrite.util.FileLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@HiltViewModel
class ChatSessionViewModel @Inject constructor(
    private val inferenceEngine: MnnInferenceEngine,
    private val memoryRepository: MemoryRepository,
    private val chatMessageDao: ChatMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val mnnDownloader: MnnModelDownloader,
    private val toolRegistry: ToolRegistry,
    private val contextManager: ContextManager,
    private val memoryCompressor: MemoryCompressor,
    private val gsscPromptBuilder: GSSCPromptBuilder,
    private val skillRegistry: SkillRegistry,
    private val traceLogger: AgentTraceLogger,
    private val reflectionChecker: ReflectionChecker,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "ChatSession"
        private const val MAX_MEMORY_CHARS = 500
        private const val MAX_HISTORY_TURNS = 6
        private const val MAX_THINKING_BLOCK_CHARS = 500
        private const val MAX_REACT_STEPS = 5  // P0: ReAct max loop steps
        private const val REACT_STEP_TAG = "[ReAct]"  // Display prefix for tool steps
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
        
        // 加载所有模型（用于模型管理面板）
        loadAllModels()
        
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
     * 加载所有模型（用于模型管理面板）
     */
    private fun loadAllModels() {
        viewModelScope.launch {
            try {
                val downloadedMnnModels = mnnDownloader.getDownloadedModels().toSet()
                val allModels = ModelCatalog.supportedModels.map { model ->
                    val isDownloaded = model.id in downloadedMnnModels
                    model.copy(status = if (isDownloaded) ModelStatus.DOWNLOADED else ModelStatus.NOT_DOWNLOADED)
                }
                _state.update { it.copy(allModels = allModels) }
                FileLogger.d(TAG, "loadAllModels: ${allModels.size} models, ${downloadedMnnModels.size} downloaded")
            } catch (e: Exception) {
                FileLogger.e(TAG, "loadAllModels: failed", e)
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
     * Bug3修复: 清理文本中的控制字符
     * - 移除内部用的控制字符（\u0001\u0002是prompt分隔符，不能进入模型输入）
     * - 移除其他控制字符（保留换行\n和制表符\t）
     */
    private fun cleanContent(text: String): String {
        return text
            // 移除内部用的控制字符（\u0001\u0002是prompt分隔符，不能进入模型输入）
            .replace(Regex("[\u0001\u0002]"), "")
            // 移除其他控制字符（保留换行\n和制表符\t）
            .replace(Regex("[\u0000\u0003-\u0008\u000B\u000C\u000E-\u001F\u007F]"), "")
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

        // Bug3修复: 添加原始消息日志
        FileLogger.d(TAG, "sendMessage: raw_len=${message.length}, raw_preview=${message.take(30)}")
        val cleanedMessage = cleanContent(message)
        FileLogger.d(TAG, "sendMessage: cleaned_len=${cleanedMessage.length}, content=${cleanedMessage.take(50)}")

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
                    
                    // P0: ReAct loop inference (replaces direct single-pass inference)
                    val reactConfig = InferenceConfig(
                        maxLength = 2048,
                        temperature = 0.7f,
                        topK = 40,
                        topP = 0.9f,
                        numThreads = 0,
                        repeatPenalty = 1.2f,
                        backend = if (inferenceEngine.isOpenCLAvailable()) "opencl" else "cpu",
                        attentionMode = when (_state.value.selectedTier) {
                            ModelTier.MAXIMUM -> 14
                            else -> 10
                        },
                        precision = "low",
                        enableSpeculativeDecoding = _state.value.speculativeDecodingEnabled && 
                            _state.value.selectedTier == ModelTier.RECOMMENDED
                    )
                    
                    runReActLoop(fullPrompt, aiMessageId, reactConfig)
                    FileLogger.d(TAG, "sendMessage: ReAct loop completed, tokenCount=$tokenCount")

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
    /**
     * P2: Build prompt using ContextManager + GSSCPromptBuilder
     * Replaces fixed MAX_HISTORY_TURNS with token-budget-driven context selection
     * Uses GSSC framework for dynamic system prompt construction
     */
    private suspend fun buildPrompt(userMessage: String): String {
        // 1. Retrieve cross-session memories (same as before, but now with FTS5)
        val memoryStrings = mutableListOf<String>()
        
        try {
            // Short-term memories
            val shortTerm = memoryRepository.getRecentShortTermMemories(memoryConfig.shortTermWindowSize)
            memoryStrings.addAll(shortTerm.map { it.content })
            
            // Long-term memories (now uses FTS5)
            if (memoryConfig.longTermMemoryEnabled) {
                val longTerm = memoryRepository.searchLongTermMemories(userMessage)
                    .take(memoryConfig.longTermRetrievalLimit)
                memoryStrings.addAll(longTerm.map { it.content })
            }
            
            // Persona memories
            if (memoryConfig.personaMemoryEnabled) {
                val personas = memoryRepository.getPersonaMemoriesSync()
                memoryStrings.addAll(personas.map { it.content })
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "buildPrompt: memory retrieval failed", e)
        }
        
        // 2. P3: Skill matching - find relevant skills
        val skillMatches = skillRegistry.matchSkill(userMessage)
        val bestSkill = skillMatches.firstOrNull()
        if (bestSkill != null && bestSkill.confidence > 0.3f) {
            traceLogger.logSkillMatch(userMessage, bestSkill.skill.id, bestSkill.confidence)
            FileLogger.d(TAG, "buildPrompt: matched skill '${bestSkill.skill.name}' (confidence=${bestSkill.confidence})")
        }
        
        // 3. Build GSSC dynamic system prompt
        val tierName = _state.value.selectedTier.name.lowercase()
        val hasToolHistory = _state.value.toolSteps.isNotEmpty()
        val systemPrompt = gsscPromptBuilder.buildPrompt(
            userMessage = userMessage,
            sessionMemories = memoryStrings,
            hasToolHistory = hasToolHistory,
            modelTier = tierName
        )
        
        // 3. Get non-streaming history
        val history = _state.value.messages
            .filter { !it.isStreaming && it.content.isNotBlank() }
        
        // 4. Use ContextManager to build context within token budget
        val prompt = contextManager.buildContext(
            systemPrompt = systemPrompt,
            memories = memoryStrings,
            history = history,
            currentUserMessage = userMessage
        )
        
        FileLogger.d(TAG, "buildPrompt: GSSC+ContextManager prompt_len=${prompt.length}")
        return prompt
    }
    
    /**
     * P2: Compress old conversation history when token budget is exceeded
     * Called after each inference completes
     */
    private suspend fun compressOldMessages() {
        try {
            val messages = _state.value.messages.filter { !it.isStreaming && it.content.isNotBlank() }
            if (!memoryCompressor.needsCompression(messages, maxTokens = 1500)) {
                return
            }
            
            // Compress messages older than the most recent 4 turns
            val recentCount = 4  // Keep last 4 messages
            if (messages.size <= recentCount) return
            
            val oldMessages = messages.dropLast(recentCount)
            if (oldMessages.isEmpty()) return
            
            val summary = memoryCompressor.compress(oldMessages)
            if (summary.isNotBlank()) {
                // Store summary in short-term memory for future context
                memoryRepository.addShortTermMemory(
                    content = "[对话摘要] $summary",
                    importance = 0.7f
                )
                FileLogger.d(TAG, "compressOldMessages: stored summary (${summary.length} chars) from ${oldMessages.size} old messages")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "compressOldMessages: failed", e)
        }
    }


    /**
     * P0: ReAct 推理循环
     * 
     * 流程：
     * 1. 模型推理生成输出
     * 2. 如果输出包含工具调用 → 解析工具 → 执行 → 将结果注入上下文 → 继续推理
     * 3. 如果输出是纯文本 → 输出给用户
     * 4. 最多循环 MAX_REACT_STEPS 次
     */
    private suspend fun runReActLoop(
        initialPrompt: String,
        assistantMsgId: Long,
        config: InferenceConfig
    ) {
        var currentPrompt = initialPrompt
        var step = 0
        
        while (step < MAX_REACT_STEPS) {
            step++
            FileLogger.d(TAG, "ReAct step $step/$MAX_REACT_STEPS")
            traceLogger.logReActStep(step, MAX_REACT_STEPS, false, 0)
            
            var fullOutput = ""
            var hasToolCall = false
            
            try {
                withTimeoutOrNull(120_000L) {
                    inferenceEngine.inferStream(currentPrompt, config).collect { token ->
                        // Filter thinking blocks
                        if (inToolCallBlock) {
                            thinkingBuffer += token
                            return@collect
                        }
                        if (token == "") {
                            inToolCallBlock = true
                            thinkingBlockChars = 0
                            return@collect
                        }
                        thinkingBlockChars++
                        if (thinkingBlockChars <= MAX_THINKING_BLOCK_CHARS) {
                            fullOutput += token
                            updateStreamingMessage(assistantMsgId, token)
                        }
                    }
                } ?: run {
                    FileLogger.w(TAG, "ReAct step $step: inference timed out")
                    updateStreamingMessage(assistantMsgId, "
[推理超时]")
                    break
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "ReAct step $step: stream error", e)
                break
            }
            
            // Check for tool calls
            val toolCalls = toolRegistry.parseToolCalls(fullOutput)
            if (toolCalls.isEmpty()) {
                // No tool calls - we're done
                FileLogger.d(TAG, "ReAct: no tool calls, output complete at step $step")
                break
            }
            
            // Execute tool calls
            hasToolCall = true
            for (call in toolCalls) {
                FileLogger.i(TAG, "ReAct: executing tool ${call.toolName}")
                val toolStartTime = System.currentTimeMillis()
                
                // Add tool step to UI
                val toolStep = ToolStep(
                    step = step,
                    toolName = call.toolName,
                    parameters = call.parameters
                )
                _state.update { it.copy(toolSteps = it.toolSteps + toolStep) }
                
                // Execute the tool
                val result = toolRegistry.execute(call)
                
                // Update tool step with result
                _state.update { s ->
                    s.copy(toolSteps = s.toolSteps.map {
                        if (it.step == step && it.toolName == call.toolName && it.isExecuting)
                            it.copy(result = result.content.take(200), isExecuting = false)
                        else it
                    })
                }
                
                FileLogger.i(TAG, "ReAct: tool ${call.toolName} result: ${result.content.take(100)}")
                traceLogger.logToolCall(call, result, System.currentTimeMillis() - toolStartTime)
                
                // Inject tool result into context and continue
                val toolResultPrompt = buildToolResultPrompt(call, result)
                currentPrompt = toolResultPrompt
            }
        }
        
        if (step >= MAX_REACT_STEPS) {
            FileLogger.w(TAG, "ReAct: reached max steps")
            updateStreamingMessage(assistantMsgId, "
[已达最大推理步数]")
        }
        
        // Clear tool steps after completion
        _state.update { it.copy(toolSteps = emptyList()) }
        
        // P4: Reflection self-correction check
        val finalOutput = _state.value.messages.find { it.id == assistantMsgId }?.content ?: ""
        val userMsg = _state.value.messages.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
        val reflectionResult = reflectionChecker.check(finalOutput, userMsg, retryCount = 0)
        
        if (reflectionResult.shouldRetry && reflectionResult.retryPrompt != null) {
            FileLogger.d(TAG, "ReAct: reflection triggered retry (score=${reflectionResult.qualityScore}, issues=${reflectionResult.issues})")
            // Re-infer with retry prompt (limited to 1 retry to avoid loops)
            try {
                var retryOutput = ""
                withTimeoutOrNull(60_000L) {
                    inferenceEngine.inferStream(reflectionResult.retryPrompt, config).collect { token ->
                        if (!inToolCallBlock) {
                            retryOutput += token
                            updateStreamingMessage(assistantMsgId, token)
                        }
                    }
                }
                traceLogger.logInference(0, 0, 0, "reflection_retry", true)
            } catch (e: Exception) {
                FileLogger.w(TAG, "ReAct: reflection retry failed", e)
            }
        }
        
        markStreamingComplete(assistantMsgId)
        
        // P2: Compress old messages after inference completes
        compressOldMessages()
    }
    
    /**
     * Build prompt for tool result injection
     */
    private fun buildToolResultPrompt(call: ToolCall, result: ToolResponse): String {
        val resultContent = if (result.success) result.content else "Error: ${result.error}"
        val messages = mutableListOf<Pair<String, String>>()
        
        // Add memory context
        val memoryContext = getMemoryContext()
        if (memoryContext.isNotEmpty()) {
            messages.add("system" to memoryContext)
        }
        
        // Add recent history
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
        
        // Add tool result as observation
        messages.add("user" to "Tool ${call.toolName} returned: $resultContent
Please use this result to answer the user.")
        
        val sb = StringBuilder()
        for ((role, msgContent) in messages) {
            sb.append("$role$msgContent")
        }
        return sb.toString()
    }
    
    private fun getMemoryContext(): String {
        return try {
            val workingMemories = memoryRepository.getWorkingMemories(sessionId).first()
            if (workingMemories.isNotEmpty()) {
                workingMemories.take(3).joinToString("
") { "- ${it.content.take(MAX_MEMORY_CHARS)}" }
            } else ""
        } catch (e: Exception) { "" }
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
     * 选择模型档位
     */
    fun selectTier(tier: ModelTier) {
        FileLogger.d(TAG, "selectTier: $tier")
        _state.update { it.copy(selectedTier = tier) }
        
        // 自动选择该档位对应的模型
        val tierModel = ModelCatalog.supportedModels.find { it.tier == tier }
        if (tierModel != null) {
            selectModel(tierModel)
        }
    }
    
    /**
     * 切换推测解码
     */
    fun toggleSpeculativeDecoding(enabled: Boolean) {
        FileLogger.d(TAG, "toggleSpeculativeDecoding: $enabled")
        _state.update { it.copy(speculativeDecodingEnabled = enabled) }
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
                
                // PH0: 根据模型档位配置硬件加速参数
                val tier = model.tier
                val attentionMode = when (tier) {
                    ModelTier.MAXIMUM -> 14  // 4B+ 用 KV-TQ4
                    else -> 10               // 其他用 KV-INT8
                }
                val backend = if (inferenceEngine.isOpenCLAvailable()) "opencl" else "cpu"
                val openclCache = File(context.filesDir, "mnn_opencl_cache").absolutePath
                
                val success = inferenceEngine.initialize(
                    modelPath = modelPath,
                    config = InferenceConfig(
                        maxLength = 2048,
                        temperature = 0.7f,
                        topK = 40,
                        topP = 0.9f,
                        numThreads = 0,  // 0=自动检测
                        contextWindow = 2048,
                        backend = backend,
                        attentionMode = attentionMode,
                        precision = "low",
                        openclCachePath = openclCache
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

    /**
     * 下载模型
     */
    fun downloadModel(modelId: String) {
        FileLogger.d(TAG, "downloadModel: id=$modelId")
        viewModelScope.launch {
            try {
                mnnDownloader.downloadModel(
                    modelId = modelId,
                    onProgress = { current, total, fileName ->
                        FileLogger.d(TAG, "downloadModel: $fileName ($current/$total)")
                    },
                    onByteProgress = { progress ->
                        FileLogger.d(TAG, "downloadModel: byte progress=${(progress * 100).toInt()}%")
                    }
                )
            } catch (e: Exception) {
                FileLogger.e(TAG, "downloadModel: failed", e)
            }
        }
        
        // 监听下载进度
        viewModelScope.launch {
            mnnDownloader.downloadStates.collect { states ->
                val progress = states.mapValues { it.value.progress }
                _state.update { it.copy(downloadProgress = progress) }
                val modelState = states[modelId]
                if (modelState?.status == MnnDownloadStatus.COMPLETED || modelState?.status == MnnDownloadStatus.READY) {
                    loadAllModels()
                    loadAvailableModels()
                    return@collect
                }
            }
        }
    }

    /**
     * 取消下载
     */
    fun cancelDownload(modelId: String) {
        FileLogger.d(TAG, "cancelDownload: id=$modelId")
        // MnnModelDownloader 没有直接的 cancel 方法，只能重新加载状态
        loadAllModels()
    }

    /**
     * 删除模型
     */
    fun deleteModel(modelId: String) {
        FileLogger.d(TAG, "deleteModel: id=$modelId")
        viewModelScope.launch {
            try {
                mnnDownloader.deleteModel(modelId)
                loadAllModels()
                loadAvailableModels()
                val currentModelName = engineState.value.loadedModelName
                if (currentModelName?.contains(modelId, ignoreCase = true) == true) {
                    inferenceEngine.release()
                    updateEngineState()
                    _state.update { it.copy(selectedModel = null) }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "deleteModel: failed", e)
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
    val selectedTier: ModelTier = ModelTier.RECOMMENDED,  // PH0: 当前选中档位
    val availableModels: List<AIModel> = emptyList(),
    val allModels: List<AIModel> = emptyList(),
    val downloadProgress: Map<String, Float> = emptyMap(),
    val speculativeDecodingEnabled: Boolean = true  // PH0: 推测解码默认开启
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

/**
 * ReAct tool execution step (for UI display)
 */
data class ToolStep(
    val step: Int,
    val toolName: String,
    val parameters: Map<String, Any>,
    val result: String? = null,
    val isExecuting: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)
