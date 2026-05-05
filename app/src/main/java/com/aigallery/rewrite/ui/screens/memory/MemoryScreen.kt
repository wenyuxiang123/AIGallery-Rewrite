package com.aigallery.rewrite.ui.screens.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aigallery.rewrite.domain.model.*
import com.aigallery.rewrite.ui.components.*
import com.aigallery.rewrite.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onNavigateToDetail: (String) -> Unit,
    viewModel: MemoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var addDialogLayer by remember { mutableStateOf<MemoryLayer?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记忆中心") }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加记忆")
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Memory architecture overview
            item {
                MemoryArchitectureCard(config = state.config)
            }

            // Layer filter chips
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.selectedLayer == null,
                        onClick = { viewModel.selectLayer(null) },
                        label = { Text("全部") }
                    )
                    MemoryLayer.entries.take(3).forEach { layer ->
                        FilterChip(
                            selected = state.selectedLayer == layer,
                            onClick = { viewModel.selectLayer(layer) },
                            label = { Text(layer.displayName) }
                        )
                    }
                }
            }

            // Working memory section
            if (state.selectedLayer == null || state.selectedLayer == MemoryLayer.WORKING) {
                item {
                    SectionHeader(title = "1. ${MemoryLayer.WORKING.displayName}")
                }
                if (state.workingMemories.isEmpty()) {
                    item {
                        Text(
                            text = "当前会话无工作记忆",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(state.workingMemories, key = { it.id }) { memory ->
                        MemoryCard(
                            memory = memory,
                            onClick = { onNavigateToDetail(memory.id) },
                            onDelete = { viewModel.deleteMemory(memory) }
                        )
                    }
                }
            }

            // Short-term memory section
            if (state.selectedLayer == null || state.selectedLayer == MemoryLayer.SHORT_TERM) {
                item {
                    SectionHeader(title = "2. ${MemoryLayer.SHORT_TERM.displayName}")
                }
                if (state.shortTermMemories.isEmpty()) {
                    item {
                        Text(
                            text = "暂无短期记忆",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(state.shortTermMemories.take(5), key = { it.id }) { memory ->
                        MemoryCard(
                            memory = memory,
                            onClick = { onNavigateToDetail(memory.id) },
                            onDelete = { viewModel.deleteMemory(memory) }
                        )
                    }
                }
            }

            // Long-term memory section
            if (state.selectedLayer == null || state.selectedLayer == MemoryLayer.LONG_TERM) {
                item {
                    SectionHeader(title = "3. ${MemoryLayer.LONG_TERM.displayName}")
                }
                if (state.longTermMemories.isEmpty()) {
                    item {
                        Text(
                            text = "暂无长期记忆",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(state.longTermMemories, key = { it.id }) { memory ->
                        MemoryCard(
                            memory = memory,
                            onClick = { onNavigateToDetail(memory.id) },
                            onDelete = { viewModel.deleteMemory(memory) }
                        )
                    }
                }
            }

            // Knowledge base section
            if (state.selectedLayer == null || state.selectedLayer == MemoryLayer.KNOWLEDGE_BASE) {
                item {
                    SectionHeader(title = "4. ${MemoryLayer.KNOWLEDGE_BASE.displayName}")
                }
                if (state.knowledgeBaseMemories.isEmpty()) {
                    item {
                        Text(
                            text = "暂无知识库内容",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(state.knowledgeBaseMemories, key = { it.id }) { memory ->
                        MemoryCard(
                            memory = memory,
                            onClick = { onNavigateToDetail(memory.id) },
                            onDelete = { viewModel.deleteMemory(memory) }
                        )
                    }
                }
            }

            // Persona memory section
            if (state.selectedLayer == null || state.selectedLayer == MemoryLayer.PERSONA) {
                item {
                    SectionHeader(title = "5. ${MemoryLayer.PERSONA.displayName}")
                }
                if (state.personaMemories.isEmpty()) {
                    item {
                        Text(
                            text = "暂无角色记忆",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(state.personaMemories, key = { it.id }) { memory ->
                        MemoryCard(
                            memory = memory,
                            onClick = { onNavigateToDetail(memory.id) },
                            onDelete = { viewModel.deleteMemory(memory) }
                        )
                    }
                }
            }

            // Search results
            if (state.searchQuery.isNotBlank()) {
                item {
                    SectionHeader(title = "搜索结果")
                }
                if (state.searchResults.isEmpty()) {
                    item {
                        Text(
                            text = "未找到相关记忆",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(state.searchResults, key = { it.id }) { memory ->
                        MemoryCard(
                            memory = memory,
                            onClick = { onNavigateToDetail(memory.id) },
                            onDelete = { viewModel.deleteMemory(memory) }
                        )
                    }
                }
            }
        }
    }

    // Add memory dialog
    if (showAddDialog) {
        AddMemoryDialog(
            onDismiss = { showAddDialog = false },
            onAddShortTerm = { content, importance ->
                viewModel.addShortTermMemory(content, importance)
                showAddDialog = false
            },
            onAddLongTerm = { content, tags ->
                viewModel.addLongTermMemory(content, tags)
                showAddDialog = false
            },
            onAddKnowledgeBase = { title, content, sourceType ->
                viewModel.addKnowledgeBaseMemory(title, content, sourceType)
                showAddDialog = false
            },
            onAddPersona = { content, personaType, traits ->
                viewModel.addPersonaMemory(content, personaType, traits)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun MemoryArchitectureCard(config: MemoryConfig) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "五层记忆架构",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoryLayer.entries.forEachIndexed { index, layer ->
                    val color = when (layer) {
                        MemoryLayer.WORKING -> MemoryWorkingColor
                        MemoryLayer.SHORT_TERM -> MemoryShortTermColor
                        MemoryLayer.LONG_TERM -> MemoryLongTermColor
                        MemoryLayer.KNOWLEDGE_BASE -> MemoryKnowledgeBaseColor
                        MemoryLayer.PERSONA -> MemoryPersonaColor
                    }
                    val enabled = when (layer) {
                        MemoryLayer.WORKING -> config.workingMemoryEnabled
                        MemoryLayer.SHORT_TERM -> config.shortTermMemoryEnabled
                        MemoryLayer.LONG_TERM -> config.longTermMemoryEnabled
                        MemoryLayer.KNOWLEDGE_BASE -> config.knowledgeBaseEnabled
                        MemoryLayer.PERSONA -> config.personaMemoryEnabled
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${index + 1}. ${layer.displayName}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = layer.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (enabled) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "已启用",
                                tint = SuccessColor,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Icon(
                                Icons.Default.Cancel,
                                contentDescription = "已禁用",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onAddShortTerm: (String, Float) -> Unit,
    onAddLongTerm: (String, List<String>) -> Unit,
    onAddKnowledgeBase: (String, String, KnowledgeSourceType) -> Unit,
    onAddPersona: (String, PersonaType, Map<String, Float>) -> Unit
) {
    var selectedLayer by remember { mutableStateOf<MemoryLayer?>(MemoryLayer.SHORT_TERM) }
    var content by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加记忆") },
        text = {
            Column {
                // Layer selection
                Text("选择记忆层级", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MemoryLayer.entries.take(3).forEachIndexed { index, layer ->
                        FilterChip(
                            selected = selectedLayer == layer,
                            onClick = { selectedLayer = layer },
                            label = { Text("${index + 1}") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title field for knowledge base
                if (selectedLayer == MemoryLayer.KNOWLEDGE_BASE) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("标题") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Content field
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("记忆内容") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    maxLines = 6
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (selectedLayer) {
                        MemoryLayer.SHORT_TERM -> onAddShortTerm(content, 0.5f)
                        MemoryLayer.LONG_TERM -> onAddLongTerm(content, emptyList())
                        MemoryLayer.KNOWLEDGE_BASE -> onAddKnowledgeBase(
                            title.ifBlank { "未命名" },
                            content,
                            KnowledgeSourceType.USER_NOTES
                        )
                        MemoryLayer.PERSONA -> onAddPersona(
                            content,
                            PersonaType.CUSTOM_PERSONA,
                            emptyMap()
                        )
                        else -> {}
                    }
                },
                enabled = content.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
