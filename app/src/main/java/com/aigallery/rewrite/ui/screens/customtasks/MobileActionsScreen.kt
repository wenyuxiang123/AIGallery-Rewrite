package com.aigallery.rewrite.ui.screens.customtasks

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aigallery.rewrite.devicecontrol.DeviceControlManager

/**
 * 手机控制主界面
 * 提供手势录制、自动化任务、快捷操作等功能
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileActionsScreen(
    onNavigateToTask: (String) -> Unit = {}
) {
    val context = LocalContext.current

    // 无障碍服务状态
    var isAccessibilityEnabled by remember {
        mutableStateOf(DeviceControlManager.isAccessibilityServiceEnabled(context))
    }

    // 当前录制状态
    var isRecording by remember { mutableStateOf(false) }

    // 已录制的手势数量
    var gestureCount by remember { mutableStateOf(0) }

    // 快捷操作列表
    val quickActions = remember {
        listOf(
            QuickAction(
                id = "home",
                name = "返回主页",
                icon = Icons.Default.Home,
                description = "模拟按下 Home 键"
            ),
            QuickAction(
                id = "back",
                name = "返回",
                icon = Icons.Default.ArrowBack,
                description = "模拟按下返回键"
            ),
            QuickAction(
                id = "recent",
                name = "最近任务",
                icon = Icons.Default.Menu,
                description = "打开最近任务列表"
            ),
            QuickAction(
                id = "screenshot",
                name = "截屏",
                icon = Icons.Default.Screenshot,
                description = "截取当前屏幕"
            ),
            QuickAction(
                id = "lock",
                name = "锁屏",
                icon = Icons.Default.Lock,
                description = "锁定设备屏幕"
            ),
            QuickAction(
                id = "wake",
                name = "唤醒",
                icon = Icons.Default.PowerSettingsNew,
                description = "唤醒设备屏幕"
            )
        )
    }

    // 自动化任务模板
    val automationTasks = remember {
        listOf(
            AutomationTask(
                id = "auto_click",
                name = "自动点击",
                description = "在指定位置重复点击",
                icon = Icons.Default.TouchApp
            ),
            AutomationTask(
                id = "auto_swipe",
                name = "自动滑动",
                description = "录制并回放滑动手势",
                icon = Icons.Default.Swipe
            ),
            AutomationTask(
                id = "open_app",
                name = "打开应用",
                description = "自动打开指定应用",
                icon = Icons.Default.Apps
            ),
            AutomationTask(
                id = "text_input",
                name = "文本输入",
                description = "自动输入预设文本",
                icon = Icons.Default.TextFields
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手机控制") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = { /* 打开设置 */ }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 无障碍服务状态卡片
            item {
                AccessibilityStatusCard(
                    isEnabled = isAccessibilityEnabled,
                    onEnableClick = {
                        openAccessibilitySettings(context)
                    },
                    onRefreshClick = {
                        isAccessibilityEnabled = DeviceControlManager.isAccessibilityServiceEnabled(context)
                    }
                )
            }

            // 2. 手势录制控制
            item {
                GestureRecordingCard(
                    isRecording = isRecording,
                    gestureCount = gestureCount,
                    onToggleRecording = { isRecording = !isRecording },
                    onClearAll = { gestureCount = 0 },
                    enabled = isAccessibilityEnabled
                )
            }

            // 3. 快捷操作网格
            item {
                Column {
                    Text(
                        text = "快捷操作",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    QuickActionsGrid(
                        actions = quickActions,
                        enabled = isAccessibilityEnabled,
                        onActionClick = { actionId ->
                            // 执行快捷操作
                            performQuickAction(context, actionId)
                        }
                    )
                }
            }

            // 4. 自动化任务列表
            item {
                Column {
                    Text(
                        text = "自动化任务",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
                    )
                    automationTasks.forEach { task ->
                        AutomationTaskItem(
                            task = task,
                            enabled = isAccessibilityEnabled,
                            onClick = { onNavigateToTask(task.id) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            // 5. 使用说明
            item {
                InfoCard()
            }

            // 底部留白
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/**
 * 无障碍服务状态卡片
 */
@Composable
private fun AccessibilityStatusCard(
    isEnabled: Boolean,
    onEnableClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 状态指示器
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            if (isEnabled) Color(0xFF4CAF50)
                            else Color(0xFFF44336)
                        )
                )

                Column {
                    Text(
                        text = if (isEnabled) "无障碍服务已启用" else "无障碍服务未启用",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                    Text(
                        text = if (isEnabled) "可以正常使用手机控制功能" else "需要启用后才能使用自动化功能",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                        }
                    )
                }
            }

            Row {
                IconButton(onClick = onRefreshClick) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新状态")
                }
                if (!isEnabled) {
                    Button(onClick = onEnableClick) {
                        Text("去启用")
                    }
                }
            }
        }
    }
}

/**
 * 手势录制控制卡片
 */
@Composable
private fun GestureRecordingCard(
    isRecording: Boolean,
    gestureCount: Int,
    onToggleRecording: () -> Unit,
    onClearAll: () -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "手势录制",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "已录制 $gestureCount 个手势",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onClearAll,
                        enabled = enabled && gestureCount > 0
                    ) {
                        Text("清除")
                    }
                    Button(
                        onClick = onToggleRecording,
                        enabled = enabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    ) {
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isRecording) "停止" else "录制")
                    }
                }
            }

            if (isRecording) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "正在录制中... 在屏幕上执行手势即可被记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * 快捷操作网格
 */
@Composable
private fun QuickActionsGrid(
    actions: List<QuickAction>,
    enabled: Boolean,
    onActionClick: (String) -> Unit
) {
    val columns = 3
    val rows = (actions.size + columns - 1) / columns

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) { rowIndex ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(columns) { colIndex ->
                    val index = rowIndex * columns + colIndex
                    if (index < actions.size) {
                        val action = actions[index]
                        QuickActionButton(
                            action = action,
                            enabled = enabled,
                            onClick = { onActionClick(action.id) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * 快捷操作按钮
 */
@Composable
private fun QuickActionButton(
    action: QuickAction,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        enabled = enabled
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.name,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = action.name,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                }
            )
        }
    }
}

/**
 * 自动化任务项
 */
@Composable
private fun AutomationTaskItem(
    task: AutomationTask,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        enabled = enabled
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Icon(
                    imageVector = task.icon,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 使用说明卡片
 */
@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "使用说明",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val infoItems = listOf(
                "需要启用无障碍服务才能使用自动化功能",
                "录制的手势会保存在本地，可随时回放",
                "快捷操作可快速执行常用的系统操作",
                "自动化任务可配置复杂的多步骤流程"
            )

            infoItems.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 打开无障碍设置
 */
private fun openAccessibilitySettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
    } else {
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
    context.startActivity(intent)
}

/**
 * 执行快捷操作
 */
private fun performQuickAction(context: Context, actionId: String) {
    // TODO: 调用 DeviceControlManager 执行实际操作
    // DeviceControlManager.performAction(actionId)
}

// 数据类
data class QuickAction(
    val id: String,
    val name: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val description: String
)

data class AutomationTask(
    val id: String,
    val name: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
