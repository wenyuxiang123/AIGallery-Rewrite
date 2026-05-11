package com.aigallery.rewrite.ui.screens.llmchat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aigallery.rewrite.domain.model.AIModel
import com.aigallery.rewrite.domain.model.ModelCatalog
import com.aigallery.rewrite.domain.model.ModelStatus
import com.aigallery.rewrite.domain.model.ModelTier
import com.aigallery.rewrite.domain.model.MessageRole
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSessionScreen(
    viewModel: ChatSessionViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var userInput by remember { mutableStateOf("") }
    var showModelSelector by remember { mutableStateOf(false) }

    // 自动滚动到底部（添加异常处理防止崩溃）
    LaunchedEffect(state.messages.size, state.isGenerating) {
        try {
            if (state.messages.isNotEmpty()) {
                coroutineScope.launch {
                    listState.animateScrollToItem(state.messages.size - 1)
                }
            }
        } catch (e: Exception) {
            // 忽略滚动异常
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = state.selectedModel?.name ?: "选择模型",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "菜单")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = "历史对话")
                    }
                    IconButton(onClick = { showModelSelector = true }) {
                        Icon(Icons.Default.ModelTraining, contentDescription = "选择模型")
                    }
                    IconButton(onClick = viewModel::clearChat) {
                        Icon(Icons.Default.Delete, contentDescription = "清除对话")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            BottomInputBar(
                input = userInput,
                onInputChange = { userInput = it },
                onSend = {
                    viewModel.sendMessage(userInput)
                    userInput = ""
                },
                onStop = viewModel::stopGeneration,
                isGenerating = state.isGenerating,
                engineState = engineState  // 传递引擎状态
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (state.messages.isEmpty()) {
                // 空状态
                EmptyChatState(onPromptClick = { prompt ->
                    viewModel.sendMessage(prompt)
                })
            } else {
                // 消息列表
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        ChatMessageBubble(message = message)
                    }

                    // 底部留白
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    // 模型管理面板
    if (showModelSelector) {
        ModelManagementSheet(
            state = state,
            engineState = engineState,
            onModelSelected = { model ->
                viewModel.selectModel(model)
                showModelSelector = false
            },
            onDownload = { modelId -> viewModel.downloadModel(modelId) },
            onCancelDownload = { modelId -> viewModel.cancelDownload(modelId) },
            onDelete = { modelId -> viewModel.deleteModel(modelId) },
            onDismiss = { showModelSelector = false }
        )
    }
}

/**
 * 空聊天状态
 */
@Composable
private fun EmptyChatState(onPromptClick: (String) -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Default.Email,
                    contentDescription = null,
                    modifier = Modifier
                        .size(60.dp)
                        .align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "开始你的对话",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "输入消息开始与 AI 聊天",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 快捷提示
        QuickPromptChips(onPromptClick = onPromptClick)
    }
}

/**
 * 快捷提示标签
 */
@Composable
private fun QuickPromptChips(onPromptClick: (String) -> Unit = {}) {
    val prompts = listOf(
        "介绍一下自己",
        "你有什么功能？",
        "支持哪些模型？",
        "什么是记忆系统？"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "试试这些问题：",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        // 使用 Column + Row 替代 FlowRow 避免实验性 API 问题
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                prompts.take(2).forEach { prompt ->
                    AssistChip(
                        onClick = { onPromptClick(prompt) },
                        label = { Text(prompt) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                prompts.drop(2).forEach { prompt ->
                    AssistChip(
                        onClick = { onPromptClick(prompt) },
                        label = { Text(prompt) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * 聊天消息气泡
 */
@Composable
private fun ChatMessageBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        // AI 头像
        if (!isUser) {
            Avatar(isUser = false)
            Spacer(modifier = Modifier.width(12.dp))
        }

        // 消息气泡
        val bubbleShape = if (isUser) {
            RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        } else {
            RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        }

        val bubbleColor = if (isUser) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }

        val textColor = if (isUser) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )

                // 正在生成指示器
                if (message.isStreaming) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(textColor.copy(alpha = 0.3f))
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(textColor.copy(alpha = 0.5f))
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(textColor.copy(alpha = 0.7f))
                        )
                    }
                }
            }
        }

        // 用户头像
        if (isUser) {
            Spacer(modifier = Modifier.width(12.dp))
            Avatar(isUser = true)
        }
    }
}

/**
 * 头像组件
 */
@Composable
private fun Avatar(isUser: Boolean) {
    val backgroundColor = if (isUser) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    val icon = if (isUser) Icons.Default.Person else Icons.Default.Extension

    Surface(
        shape = CircleShape,
        color = backgroundColor,
        modifier = Modifier.size(36.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.Center),
                tint = contentColor
            )
        }
    }
}

/**
 * 底部输入栏
 */
@Composable
private fun BottomInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isGenerating: Boolean,
    engineState: EngineState? = null  // 添加可选参数
) {
    val showLoadingProgress = engineState?.let { it.isLoading || it.isInitializing } ?: false
    val loadProgress = engineState?.loadProgress ?: 0f
    
    Surface(
        shadowElevation = 8.dp,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 模型加载进度条
            if (showLoadingProgress) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { loadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(
                        text = "模型加载中……${(loadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // 正在生成提示（使用简单条件渲染，避免 AnimatedVisibility 版本兼容问题）
            if (isGenerating) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "AI 正在生成...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    TextButton(onClick = onStop) {
                        Text("停止")
                    }
                }
            }

            // 输入框
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息...") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4
                )

                val sendEnabled = input.isNotBlank() && !isGenerating
                IconButton(
                    onClick = onSend,
                    enabled = sendEnabled,
                    modifier = Modifier.background(
                        color = if (sendEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "发送",
                        tint = if (sendEnabled) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 模型管理面板（底部弹出式）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelManagementSheet(
    state: ChatSessionState,
    engineState: EngineState,
    onModelSelected: (AIModel) -> Unit,
    onDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "选择档位",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // PH0: 推测解码开关
            if (state.selectedTier == ModelTier.RECOMMENDED) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "推测解码",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "小模型起草+大模型验证，速度翻倍质量零损失",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.speculativeDecodingEnabled,
                        onCheckedChange = { /* toggled via ViewModel */ }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
            }
            
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.allModels) { model ->
                    TierModelCard(
                        model = model,
                        isCurrentModel = state.selectedModel?.id == model.id,
                        isSelectedTier = model.tier == state.selectedTier,
                        downloadProgress = state.downloadProgress[model.id] ?: 0f,
                        onSelect = { onModelSelected(model) },
                        onDownload = { onDownload(model.id) },
                        onCancelDownload = { onCancelDownload(model.id) },
                        onDelete = { onDelete(model.id) }
                    )
                }
            }
        }
    }
}

/**
 * 档位模型卡片组件 — PH0 改造
 */
@Composable
private fun TierModelCard(
    model: AIModel,
    isCurrentModel: Boolean,
    isSelectedTier: Boolean,
    downloadProgress: Float,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCurrentModel -> MaterialTheme.colorScheme.primaryContainer
                isSelectedTier -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // PH0: 档位标签
                    Surface(
                        color = when (model.tier) {
                            ModelTier.RECOMMENDED -> Color(0xFF8B5CF6)  // 紫色
                            ModelTier.SPEED -> Color(0xFF22C55E)        // 绿色
                            ModelTier.DAILY -> Color(0xFF3B82F6)        // 蓝色
                            ModelTier.QUALITY -> Color(0xFFF59E0B)      // 橙色
                            ModelTier.MAXIMUM -> Color(0xFFEF4444)      // 红色
                        },
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = model.tier.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (isCurrentModel) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "${model.size} | ${model.quantization} | ${model.tier.targetSpeed}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                
                if (model.status == ModelStatus.DOWNLOADING) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                    Text(
                        text = "${(downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            
            when (model.status) {
                ModelStatus.NOT_DOWNLOADED -> {
                    TextButton(onClick = onDownload) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("下载")
                    }
                }
                ModelStatus.DOWNLOADING -> {
                    TextButton(onClick = onCancelDownload) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("取消")
                    }
                }
                ModelStatus.DOWNLOADED -> {
                    if (!isCurrentModel) {
                        TextButton(onClick = onSelect) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("加载")
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
                else -> {}
            }
        }
    }
}
