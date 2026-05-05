package com.aigallery.rewrite.ui.screens.memory

import androidx.compose.animation.AnimatedVisibility
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
import com.aigallery.rewrite.memory.MemoryItem
import com.aigallery.rewrite.memory.MemoryType
import com.aigallery.rewrite.ui.components.EmptyState

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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::addTestMemory
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加测试记忆")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 统计卡片
            MemoryStatsCard(
                stats = state.getTypeCounts(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // 类型过滤标签
            TypeFilterChips(
                selectedType = selectedType,
                onTypeSelected = viewModel::selectType,
                stats = state.getTypeCounts(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // 搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索记忆...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true
            )

            when {
                state.isLoading -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                filteredMemories.isEmpty() -> {
                    EmptyState(
                        title = "暂无记忆",
                        description = "开始聊天后，AI 会自动记住重要信息"
                    )
                }
                else -> {
                    // 记忆列表
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                text = "共 ${filteredMemories.size} 条记忆",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        items(filteredMemories, key = { it.id }) { memory ->
                            MemoryCard(
                                memory = memory,
                                onClick = { onMemoryClick(memory.id) },
                                onDelete = { viewModel.deleteMemory(memory.id) }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(72.dp)) // FAB 留白
                        }
                    }
                }
            }
        }
    }
}

/**
 * 记忆统计卡片
 */
@Composable
private fun MemoryStatsCard(
    stats: Map<MemoryType, Int>,
    modifier: Modifier = Modifier
) {
    val total = stats.values.sum()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "记忆总览",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "$total 条",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 各类型统计
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalGap = 8.dp
            ) {
                MemoryType.values().forEach { type ->
                    val count = stats[type] ?: 0
                    MemoryTypeChip(
                        type = type,
                        count = count,
                        selected = false,
                        onClick = {}
                    )
                }
            }
        }
    }
}

/**
 * 类型过滤标签
 */
@Composable
private fun TypeFilterChips(
    selectedType: MemoryType?,
    onTypeSelected: (MemoryType?) -> Unit,
    stats: Map<MemoryType, Int>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "按类型筛选",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalGap = 8.dp
        ) {
            // "全部" 标签
            FilterChip(
                selected = selectedType == null,
                onClick = { onTypeSelected(null) },
                label = { Text("全部") },
                leadingIcon = if (selectedType == null) {
                    { Icon(Icons.Default.AllInclusive, contentDescription = null) }
                } else null
            )

            MemoryType.values().forEach { type ->
                val count = stats[type] ?: 0
                FilterChip(
                    selected = selectedType == type,
                    onClick = { onTypeSelected(type) },
                    label = { Text("${type.getTypeName()} ($count)") },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(type.getColor())
                        )
                    }
                )
            }
        }
    }
}

/**
 * 记忆类型标签
 */
@Composable
private fun MemoryTypeChip(
    type: MemoryType,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (selected) {
        type.getColor()
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = if (selected) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White else type.getColor())
            )
            Text(
                text = type.getTypeName(),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 记忆卡片
 */
@Composable
private fun MemoryCard(
    memory: MemoryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 头部：类型标签、时间、操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 类型标签
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = memory.type.getColor().copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = memory.type.getTypeName(),
                            style = MaterialTheme.typography.labelSmall,
                            color = memory.type.getColor(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // 重要性星级
                    Text(
                        text = memory.getImportanceStars(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFC107)
                    )
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 记忆内容
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 底部：时间、访问次数
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = memory.getFormattedTime(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "访问 ${memory.accessCount} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 获取记忆类型对应的颜色
 */
private fun MemoryType.getColor(): Color {
    return when (this) {
        MemoryType.CORE -> Color(0xFFF44336)     // 红 - 核心记忆
        MemoryType.LONG_TERM -> Color(0xFFFF9800) // 橙 - 长期记忆
        MemoryType.WORKING -> Color(0xFF2196F3)   // 蓝 - 工作记忆
        MemoryType.SHORT_TERM -> Color(0xFF4CAF50) // 绿 - 短期记忆
        MemoryType.INSTANT -> Color(0xFF9E9E9E)   // 灰 - 瞬时记忆
    }
}

/**
 * 获取记忆类型显示名称
 */
private fun MemoryType.getTypeName(): String {
    return when (this) {
        MemoryType.CORE -> "核心"
        MemoryType.LONG_TERM -> "长期"
        MemoryType.WORKING -> "工作"
        MemoryType.SHORT_TERM -> "短期"
        MemoryType.INSTANT -> "瞬时"
    }
}
