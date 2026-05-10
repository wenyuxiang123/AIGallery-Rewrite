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
        
        // 检查引擎是否已经初始化，如果是则更新状态
        if (inferenceEngine.isInitialized) {
            FileLogger.d(TAG, "init: engine already initialized")
            _engineState.update { it.copy(isInitialized = true) }
        }
        
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
     * 更新推理引擎状态
     */
    private fun updateEngineState() {
        val isInit = inferenceEngine.isInitialized
        val stats = inferenceEngine.getInferenceStats()
        _engineState.update {
            it.copy(
                isInitialized = isInit,
                memoryUsageMB = inferenceEngine.getMemoryUsageMB(),
                lastInferenceMs = stats.lastInferenceTimeMs,
                tokensPerSecond = stats.tokensPerSecond
            )
        }
        FileLogger.d(TAG, "updateEngineState: isInitialized=$isInit")
    }

    /**
     * 发送消息（核心 P0）
     */
    fun sendMessage(userMessage: String) {
        if (userMessage.isBlank()) {
            FileLogger.w(TAG, "sendMessage: empty message")
            return
        }
        
        FileLogger.d(TAG, "sendMessage: userMessage length=${userMessage.length}")
        
        // 取消之前的推理
        inferenceJob?.cancel()
        
        // 添加用户消息
        val userMsg = ChatMessage(
            id = nextMessageId(),
            content = userMessage,
            role = MessageRole.USER,
            timestamp = System.currentTimeMillis()
        )
        addMessage(userMsg)
        
        // 持久化用户消息
        viewModelScope.launch {
            try {
                chatMessageDao.insertMessage(userMsg.toEntity(sessionId))
            } catch (e: Exception) {
                FileLogger.e(TAG, "sendMessage: failed to persist user message", e)
            }
        }
        
        // 启动推理
        inferenceJob = viewModelScope.launch {
            try {
                generateResponse(userMessage, userMsg.id)
            } catch (e: Exception) {
                FileLogger.e(TAG, "sendMessage: generateResponse failed", e)
                _state.update { s ->
                    s.copy(
                        messages = s.messages.map { if (it.isStreaming) it.copy(isStreaming = false, content = it.content + "\n[Error: ${e.message}]") else it },
                        isGenerating = false
                    )
                }
            }
        }
    }

    /**
     * 生成回复（P0: 核心推理逻辑）
     */
    private suspend fun generateResponse(userMessage: String, userMessageId: Long) {
        // P0: 检查引擎状态
        if (!inferenceEngine.isInitialized) {
            FileLogger.w(TAG, "generateResponse: engine not initialized")
            val assistantMsg = ChatMessage(
                id = nextMessageId(),
                content = "请先选择一个模型后再发送消息",
                role = MessageRole.ASSISTANT,
                timestamp = System.currentTimeMillis()
            )
            addMessage(assistantMsg)
            return
        }
        
        // P1: Skill 匹配
        val skillMatch = skillRegistry.matchSkill(userMessage)
        if (skillMatch != null) {
            FileLogger.d(TAG, "generateResponse: matched skill=${skillMatch.skill.name}")
            executeSkill(skillMatch, userMessage)
            return
        }
        
        // P2: ReAct 循环（最多 MAX_REACT_STEPS 步）
        var reactStep = 0
        var lastToolResult: String? = null
        var finalResponse: String? = null
        val toolSteps = mutableListOf<ToolStep>()
        
        while (reactStep < MAX_REACT_STEPS) {
            FileLogger.d(TAG, "generateResponse: ReAct step $reactStep")
            
            // Step 1: 构建上下文（使用 domain ChatMessage）
            val history = _state.value.messages
            val domainMessages = history.toDomainMessages()
            
            // 从记忆库获取相关记忆
            val relevantMemories = memoryRepository.retrieveAllRelevantMemories(
                context = userMessage,
                config = memoryConfig,
                sessionId = sessionId
            )
            val memoryStrings = relevantMemories.map { it.content }
            
            // 构建 system prompt（使用 GSSC 框架）
            val systemPrompt = gsscPromptBuilder.buildPrompt(
                userMessage = userMessage,
                sessionMemories = memoryStrings,
                hasToolHistory = toolSteps.isNotEmpty(),
                modelTier = _state.value.selectedTier.name
            )
            
            // 构建上下文
            val fullPrompt = contextManager.buildContext(
                systemPrompt = systemPrompt,
                memories = memoryStrings,
                history = domainMessages,
                currentUserMessage = userMessage
            )
            
            // 检查是否需要压缩
            val compressedPrompt = if (memoryCompressor.needsCompression(domainMessages, 1500)) {
                val compressed = memoryCompressor.compress(domainMessages)
                // 重新构建带压缩内容的 prompt
                contextManager.buildContext(
                    systemPrompt = systemPrompt + "\n\n[压缩摘要] $compressed",
                    memories = emptyList(),
                    history = domainMessages.takeLast(4),
                    currentUserMessage = userMessage
                )
            } else {
                fullPrompt
            }
            
            // Step 2: 流式推理
            val assistantMsgId = nextMessageId()
            val assistantMsg = ChatMessage(
                id = assistantMsgId,
                content = "",
                role = MessageRole.ASSISTANT,
                timestamp = System.currentTimeMillis(),
                isStreaming = true
            )
            addMessage(assistantMsg)
            
            var fullResponse = ""
            val buffer = StringBuilder()
            var thinkingBuffer = ""
            var inThinkingBlock = false
            
            try {
                inferenceEngine.inferStream(compressedPrompt, InferenceConfig(maxLength = 2048))
                    .collect { token ->
                        // Bug2修复: 正确处理 thinking block
                        buffer.append(token)
                        
                        // 检测 thinking block
                        if (buffer.contains("<think>")) {
                            inThinkingBlock = true
                            thinkingBuffer = buffer.substringAfter("<think>")
                        }
                        
                        if (inThinkingBlock) {
                            thinkingBuffer += token
                            if (thinkingBuffer.contains("</think>")) {
                                // 去掉 thinking block，只保留最终回复
                                val cleanContent = thinkingBuffer.substringBefore("</think>")
                                buffer.clear()
                                buffer.append(cleanContent)
                                inThinkingBlock = false
                                thinkingBuffer = ""
                            }
                        }
                        
                        // 更新 UI（每隔一定 token 或 buffer 满了才更新）
                        if (buffer.length >= 10 || token.contains("\n")) {
                            val content = buffer.toString()
                            updateStreamingMessage(assistantMsgId, content)
                            buffer.clear()
                        }
                        
                        fullResponse = if (inThinkingBlock) buffer.toString().substringBefore("<think>") else buffer.toString()
                    }
            } catch (e: Exception) {
                FileLogger.e(TAG, "generateResponse: inferStream failed", e)
                updateStreamingMessage(assistantMsgId, "\n[Error: ${e.message}]")
            }
            
            // 移除 thinking block 残留
            fullResponse = fullResponse.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            
            // Step 3: 解析工具调用
            val toolCalls = toolRegistry.parseToolCalls(fullResponse)
            
            if (toolCalls.isEmpty()) {
                // 没有工具调用，ReAct 结束
                finalResponse = fullResponse
                markStreamingComplete(assistantMsgId)
                break
            }
            
            // 有工具调用，执行 ReAct
            for (toolCall in toolCalls) {
                FileLogger.d(TAG, "generateResponse: ReAct executing tool=${toolCall.toolName}")
                
                val toolStep = ToolStep(
                    step = reactStep,
                    toolName = toolCall.toolName,
                    parameters = toolCall.parameters,
                    isExecuting = true
                )
                toolSteps.add(toolStep)
                
                // 更新 toolSteps 到 state
                _state.update { it.copy(toolSteps = toolSteps.toList()) }
                
                val result = toolRegistry.execute(toolCall)
                lastToolResult = result.content
                
                // 更新 toolStep
                val index = toolSteps.indexOfFirst { it.step == reactStep && it.toolName == toolCall.toolName }
                if (index >= 0) {
                    toolSteps[index] = toolSteps[index].copy(
                        result = result.content,
                        isExecuting = false
                    )
                    _state.update { it.copy(toolSteps = toolSteps.toList()) }
                }
                
                // 添加工具结果到上下文
                val toolResultMsg = ChatMessage(
                    id = nextMessageId(),
                    content = "Tool ${toolCall.toolName} result: ${result.content}",
                    role = MessageRole.USER,  // 工具结果作为用户消息继续对话
                    timestamp = System.currentTimeMillis()
                )
                addMessage(toolResultMsg)
            }
            
            reactStep++
        }
        
        // P2: 如果有工具使用记录但最终没有回复，生成总结
        if (finalResponse == null && toolSteps.isNotEmpty()) {
            finalResponse = "我已经帮你完成了 $reactStep 步操作，结果如下：\n\n${toolSteps.joinToString("\n\n") { "• ${it.toolName}: ${it.result ?: "执行中..."}" }}"
        }
        
        // P3: 持久化助手回复（如果有新消息）
        if (finalResponse != null && finalResponse.isNotBlank()) {
            viewModelScope.launch {
                try {
                    val assistantMsg = ChatMessage(
                        id = nextMessageId(),
                        content = finalResponse,
                        role = MessageRole.ASSISTANT,
                        timestamp = System.currentTimeMillis()
                    )
                    chatMessageDao.insertMessage(assistantMsg.toEntity(sessionId))
                } catch (e: Exception) {
                    FileLogger.e(TAG, "generateResponse: failed to persist assistant message", e)
                }
            }
        }
        
        // P3: Reflection 检查
        if (finalResponse != null && _state.value.selectedTier == ModelTier.QUALITY || _state.value.selectedTier == ModelTier.MAXIMUM) {
            viewModelScope.launch {
                val reflection = reflectionChecker.check(finalResponse)
                if (reflection.needsImprovement) {
                    FileLogger.d(TAG, "generateResponse: reflection flagged improvement needed: ${reflection.reason}")
                    // 可以在这里自动触发重新生成
                }
            }
        }
        
        // P3: 更新工作记忆
        viewModelScope.launch {
            try {
                memoryRepository.addWorkingMemory(sessionId, "用户: $userMessage")
                if (finalResponse != null) {
                    memoryRepository.addWorkingMemory(sessionId, "助手: ${finalResponse.take(200)}")
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "generateResponse: failed to update working memory", e)
            }
        }
        
        updateEngineState()
    }

    /**
     * 执行 Skill
     */
    private suspend fun executeSkill(skillMatch: SkillMatch, userMessage: String) {
        FileLogger.d(TAG, "executeSkill: skill=${skillMatch.skill.name}, confidence=${skillMatch.confidence}")
        
        val result = skillRegistry.execute(skillMatch, userMessage)
        
        val assistantMsg = ChatMessage(
            id = nextMessageId(),
            content = result,
            role = MessageRole.ASSISTANT,
            timestamp = System.currentTimeMillis()
        )
        addMessage(assistantMsg)
        
        // 持久化
        viewModelScope.launch {
            try {
                chatMessageDao.insertMessage(assistantMsg.toEntity(sessionId))
            } catch (e: Exception) {
                FileLogger.e(TAG, "executeSkill: failed to persist", e)
            }
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
        _state.update { it.copy(messages = emptyList(), toolSteps = emptyList()) }
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
                // 使用 cpu 作为默认后端（OpenCL 检测不在接口中）
                val backend = "cpu"
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
                    _engineState.update { 
                        it.copy(
                            loadProgress = 1f,
                            isInitialized = true,
                            loadedModelName = model.name,
                            loadedModelPath = modelPath
                        ) 
                    }
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

    /**
     * Convert UI ChatMessage to domain ChatMessage
     */
    private fun List<ChatMessage>.toDomainMessages(): List<com.aigallery.rewrite.domain.model.ChatMessage> {
        return map { uiMsg ->
            com.aigallery.rewrite.domain.model.ChatMessage(
                id = uiMsg.id.toString(),
                role = when(uiMsg.role) {
                    MessageRole.USER -> com.aigallery.rewrite.domain.model.MessageRole.USER
                    MessageRole.ASSISTANT -> com.aigallery.rewrite.domain.model.MessageRole.ASSISTANT
                    MessageRole.SYSTEM -> com.aigallery.rewrite.domain.model.MessageRole.SYSTEM
                },
                content = uiMsg.content,
                isStreaming = uiMsg.isStreaming,
                timestamp = uiMsg.timestamp
            )
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
    val speculativeDecodingEnabled: Boolean = true,  // PH0: 推测解码默认开启
    val toolSteps: List<ToolStep> = emptyList()  // ReAct 工具执行步骤
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
