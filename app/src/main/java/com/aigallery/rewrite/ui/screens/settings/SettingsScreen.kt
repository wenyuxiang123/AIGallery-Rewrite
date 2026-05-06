package com.aigallery.rewrite.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {
    var darkMode by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(true) }
    var autoDownload by remember { mutableStateOf(true) }
    var memoryEnabled by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Appearance section
            SettingsSection(title = "外观") {
                SettingsItem(
                    icon = Icons.Default.DarkMode,
                    title = "深色模式",
                    subtitle = "跟随系统",
                    trailing = {
                        Switch(
                            checked = darkMode,
                            onCheckedChange = { darkMode = it }
                        )
                    }
                )
            }

            // General section
            SettingsSection(title = "通用") {
                SettingsItem(
                    icon = Icons.Default.Notifications,
                    title = "通知",
                    subtitle = "接收推送通知",
                    trailing = {
                        Switch(
                            checked = notifications,
                            onCheckedChange = { notifications = it }
                        )
                    }
                )
                SettingsItem(
                    icon = Icons.Default.Download,
                    title = "WiFi下载",
                    subtitle = "仅在WiFi下自动下载模型",
                    trailing = {
                        Switch(
                            checked = autoDownload,
                            onCheckedChange = { autoDownload = it }
                        )
                    }
                )
            }

            // AI section
            SettingsSection(title = "AI设置") {
                SettingsItem(
                    icon = Icons.Default.Storage,
                    title = "记忆系统",
                    subtitle = "启用五层记忆功能",
                    trailing = {
                        Switch(
                            checked = memoryEnabled,
                            onCheckedChange = { memoryEnabled = it }
                        )
                    }
                )
                SettingsItem(
                    icon = Icons.Default.Download,
                    title = "默认模型",
                    subtitle = "Qwen 2 7B",
                    onClick = { /* Select model */ }
                )
                SettingsItem(
                    icon = Icons.Default.Star,
                    title = "流式输出",
                    subtitle = "打字机效果",
                    trailing = {
                        Switch(checked = true, onCheckedChange = { })
                    }
                )
            }

            // Storage section
            SettingsSection(title = "存储") {
                SettingsItem(
                    icon = Icons.Default.Folder,
                    title = "存储位置",
                    subtitle = "内部存储",
                    onClick = { }
                )
                SettingsItem(
                    icon = Icons.Default.DeleteSweep,
                    title = "清理缓存",
                    subtitle = "清除临时文件",
                    onClick = { }
                )
            }

            // About section
            SettingsSection(title = "关于") {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "版本信息",
                    subtitle = "1.0.0",
                    onClick = { }
                )
                SettingsItem(
                    icon = Icons.Default.Code,
                    title = "开源许可",
                    subtitle = "查看开源组件许可",
                    onClick = { }
                )
                SettingsItem(
                    icon = Icons.Default.Feedback,
                    title = "反馈问题",
                    subtitle = "报告Bug或建议",
                    onClick = { }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        content()
        Divider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            trailing?.invoke()
            if (onClick != null && trailing == null) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
