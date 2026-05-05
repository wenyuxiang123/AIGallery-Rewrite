package com.aigallery.rewrite.ui.screens.customtasks

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
import androidx.compose.ui.unit.dp
import com.aigallery.rewrite.domain.model.CustomTaskType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomTasksScreen(
    onNavigateToAgentChat: (String) -> Unit,
    onNavigateToMobileActions: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自定义任务") }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "任务类型",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Agent Chat
            item {
                TaskCard(
                    icon = Icons.Default.SmartToy,
                    title = CustomTaskType.AGENT_CHAT.displayName,
                    description = CustomTaskType.AGENT_CHAT.description,
                    onClick = { onNavigateToAgentChat("default") }
                )
            }

            // Mobile Actions
            item {
                TaskCard(
                    icon = Icons.Default.PhoneAndroid,
                    title = CustomTaskType.MOBILE_ACTIONS.displayName,
                    description = CustomTaskType.MOBILE_ACTIONS.description,
                    onClick = onNavigateToMobileActions
                )
            }

            // Example Task
            item {
                TaskCard(
                    icon = Icons.Default.Description,
                    title = CustomTaskType.EXAMPLE_TASK.displayName,
                    description = CustomTaskType.EXAMPLE_TASK.description,
                    onClick = { /* Navigate */ }
                )
            }

            // Tiny Garden
            item {
                TaskCard(
                    icon = Icons.Default.Yard,
                    title = CustomTaskType.TINY_GARDEN.displayName,
                    description = CustomTaskType.TINY_GARDEN.description,
                    onClick = { /* Navigate */ }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "工具能力",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Tools grid
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ToolChip(
                        icon = Icons.Default.Wifi,
                        label = "WiFi控制",
                        modifier = Modifier.weight(1f)
                    )
                    ToolChip(
                        icon = Icons.Default.FlashlightOn,
                        label = "手电筒",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ToolChip(
                        icon = Icons.Default.Message,
                        label = "发送短信",
                        modifier = Modifier.weight(1f)
                    )
                    ToolChip(
                        icon = Icons.Default.VolumeUp,
                        label = "音量控制",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ToolChip(
                        icon = Icons.Default.Brightness6,
                        label = "屏幕亮度",
                        modifier = Modifier.weight(1f)
                    )
                    ToolChip(
                        icon = Icons.Default.Bluetooth,
                        label = "蓝牙控制",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ToolChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    AssistChip(
        onClick = { },
        label = { Text(label) },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        modifier = modifier
    )
}
