package com.aigallery.rewrite.ui.screens.singleturn
@file:OptIn(ExperimentalMaterial3Api::class)

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api

@file:OptIn(ExperimentalMaterial3Api::class)
import androidx.hilt.navigation.compose.hiltViewModel
import com.aigallery.rewrite.domain.model.SingleTurnTaskType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleTurnScreen(
    viewModel: SingleTurnViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("单轮任务") },
                actions = {
                    if (state.history.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearHistory) {
                            Icon(Icons.Default.History, contentDescription = "清空历史")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Task type selector
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = "选择任务类型",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SingleTurnTaskType.entries.take(3).forEach { taskType ->
                            TaskTypeChip(
                                taskType = taskType,
                                isSelected = state.selectedTaskType == taskType,
                                onClick = { viewModel.selectTaskType(taskType) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SingleTurnTaskType.entries.drop(3).forEach { taskType ->
                            TaskTypeChip(
                                taskType = taskType,
                                isSelected = state.selectedTaskType == taskType,
                                onClick = { viewModel.selectTaskType(taskType) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // Task input and output area
            if (state.selectedTaskType != null) {
                Card(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // Input area
                        OutlinedTextField(
                            value = state.inputText,
                            onValueChange = viewModel::updateInput,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            label = { Text(state.selectedTaskType!!.displayName) },
                            placeholder = { Text("输入内容...") },
                            enabled = !state.isLoading
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Action buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (state.outputText.isNotEmpty()) {
                                OutlinedButton(onClick = viewModel::clearOutput) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("清空")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Button(
                                onClick = viewModel::executeTask,
                                enabled = state.inputText.isNotBlank() && !state.isLoading
                            ) {
                                if (state.isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("执行")
                            }
                        }

                        // Output area
                        if (state.outputText.isNotEmpty() || state.isLoading) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Divider()
                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "输出结果",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            if (state.isLoading) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.medium
                                ) {
                                    Text(
                                        text = state.outputText,
                                        modifier = Modifier
                                            .padding(12.dp)
                                            .verticalScroll(rememberScrollState()),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        // Error display
                        if (state.error != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.error!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else {
                // No task selected - show hint
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Lightbulb,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "选择任务类型开始",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "写作、翻译、摘要、改写等单轮任务",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskTypeChip(
    taskType: SingleTurnTaskType,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon: ImageVector = when (taskType) {
        SingleTurnTaskType.WRITING -> Icons.Default.EditNote
        SingleTurnTaskType.TRANSLATION -> Icons.Default.Translate
        SingleTurnTaskType.SUMMARY -> Icons.Default.Summarize
        SingleTurnTaskType.REWRITE -> Icons.Default.Refresh
        SingleTurnTaskType.ANALYSIS -> Icons.Default.Analytics
        SingleTurnTaskType.BRAINSTORM -> Icons.Default.Lightbulb
    }

    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = taskType.displayName,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        modifier = modifier.height(64.dp)
    )
}
