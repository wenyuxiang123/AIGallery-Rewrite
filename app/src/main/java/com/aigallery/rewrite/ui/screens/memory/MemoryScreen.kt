package com.aigallery.rewrite.ui.screens.memory

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: MemoryViewModel = hiltViewModel(),
    onMemoryClick: (String) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val filteredMemories by viewModel.filteredMemories.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记忆中心") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "刷新") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::addTestMemory) { Icon(Icons.Default.Add, contentDescription = "添加测试记忆") }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            MemoryStatsCard(stats = state.getTypeCounts(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            TypeFilterChips(selectedType = selectedType, onTypeSelected = viewModel::selectType, stats = state.getTypeCounts(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            OutlinedTextField(
                value = searchQuery, onValueChange = viewModel::updateSearchQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索记忆...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { viewModel.updateSearchQuery("") }) { Icon(Icons.Default.Clear, contentDescription = "清除") } },
                singleLine = true
            )
            when {
                state.isLoading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                filteredMemories.isEmpty() -> EmptyState()
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Text("共 ${filteredMemories.size} 条记忆", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(filteredMemories, key = { it.id }) { memory ->
                            MemoryCard(memory = memory, onClick = { onMemoryClick(memory.id) }, onDelete = { viewModel.deleteMemory(memory.id) })
                        }
                        item { Spacer(modifier = Modifier.height(72.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryStatsCard(stats: Map<MemoryType, Int>, modifier: Modifier = Modifier) {
    val total = stats.values.sum()
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("记忆总览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("$total 条", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoryType.entries.forEach { type ->
                    val count = stats[type] ?: 0
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(type.color()))
                            Text(type.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Text("$count 条", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeFilterChips(selectedType: MemoryType?, onTypeSelected: (MemoryType?) -> Unit, stats: Map<MemoryType, Int>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text("按类型筛选", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selectedType == null, onClick = { onTypeSelected(null) }, label = { Text("全部") })
            MemoryType.entries.forEach { type ->
                val count = stats[type] ?: 0
                FilterChip(selected = selectedType == type, onClick = { onTypeSelected(type) }, label = { Text("${type.displayName}($count)") })
            }
        }
    }
}

@Composable
private fun MemoryCard(memory: UiMemoryItem, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(8.dp), color = memory.type.color().copy(alpha = 0.15f)) {
                    Text(memory.type.displayName, style = MaterialTheme.typography.labelSmall, color = memory.type.color(), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(memory.content, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(16.dp))
        Text("暂无记忆", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("开始聊天后，AI 会自动记录重要信息", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun MemoryType.color(): Color = when (this) {
    MemoryType.SHORT_TERM -> Color(0xFF4CAF50)
    MemoryType.LONG_TERM -> Color(0xFFFF9800)
    MemoryType.KNOWLEDGE -> Color(0xFF2196F3)
    MemoryType.PERSONA -> Color(0xFFF44336)
}
