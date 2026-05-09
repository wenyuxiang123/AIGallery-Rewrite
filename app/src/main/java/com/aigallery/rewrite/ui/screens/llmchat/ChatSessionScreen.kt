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
import com.aigallery.rewrite.domain.model.MessageRole
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSessionScreen(
    viewModel: ChatSessionViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onMenuClick: () -> Unit = {}
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

    // 模型选择对话框
    if (showModelSelector) {
        ModelSelectionDialog(
            selectedModel = state.selectedModel,
            onModelSelected = { model ->
                viewModel.selectModel(model)
                showModelSelector = false
            },
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
 * 模型选择对话框
 */
@Composable
private fun ModelSelectionDialog(
    selectedModel: AIModel?,
    onModelSelected: (AIModel) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 获取已下载的模型
                val downloadedModels = ModelCatalog.supportedModels.filter { 
                    it.status == ModelStatus.DOWNLOADED 
                }
                
                if (downloadedModels.isEmpty()) {
                    item {
                        Text(
                            text = "暂无可用模型，请先在模型管理页面下载模型",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(downloadedModels) { model ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onModelSelected(model) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedModel?.id == model.id)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = model.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${model.size} | ${model.parameters}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (selectedModel?.id == model.id) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "已选择",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
