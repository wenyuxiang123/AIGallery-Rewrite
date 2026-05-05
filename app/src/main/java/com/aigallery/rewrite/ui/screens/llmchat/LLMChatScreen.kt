package com.aigallery.rewrite.ui.screens.llmchat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.annotation.OptIn

@file:OptIn(ExperimentalMaterial3Api::class)

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aigallery.rewrite.domain.model.ChatSession
import com.aigallery.rewrite.ui.components.EmptyState
import com.aigallery.rewrite.ui.components.LoadingIndicator
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LLMChatScreen(
    onNavigateToSession: (String) -> Unit,
    viewModel: LLMChatListViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI对话") },
                actions = {
                    if (state.sessions.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "清空")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val sessionId = viewModel.createNewSession()
                    onNavigateToSession(sessionId)
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建对话")
            }
        }
    ) { paddingValues ->
        when {
            state.isLoading -> {
                LoadingIndicator(
                    modifier = Modifier.padding(paddingValues),
                    message = "加载对话历史..."
                )
            }
            state.sessions.isEmpty() -> {
                EmptyState(
                    title = "暂无对话",
                    description = "点击右下角按钮开始新对话"
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.sessions, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            onClick = { onNavigateToSession(session.id) },
                            onDelete = { viewModel.deleteSession(session.id) }
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空所有对话") },
            text = { Text("确定要清空所有对话历史吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllSessions()
                        showClearDialog = false
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SessionCard(
    session: ChatSession,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = dateFormat.format(Date(session.updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
