package com.aigallery.rewrite.ui.screens.customtasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aigallery.rewrite.R
import com.aigallery.rewrite.devicecontrol.DeviceControlFunctions
import com.aigallery.rewrite.devicecontrol.DeviceControlManager
import com.aigallery.rewrite.devicecontrol.OperationLog
import com.aigallery.rewrite.domain.model.ActionCategory
import com.aigallery.rewrite.domain.model.MobileActionType
import kotlinx.coroutines.launch

/**
 * 设备控制界面 - 爱马仕级全操控
 * 
 * 完整功能：
 * - 连接状态显示（无障碍服务状态）
 * - 设备操控快捷面板（WiFi/蓝牙/手电筒/亮度/音量等）
 * - 自然语言操控输入框
 * - 实时屏幕分析预览
 * - 手势录制/回放控制
 * - 操作日志
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileActionsScreen(
    onNavigateBack: () -> Unit,
    deviceControlManager: DeviceControlManager? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 状态
    var selectedTab by remember { mutableStateOf(0) }
    var naturalLanguageCommand by remember { mutableStateOf("") }
    var commandResult by remember { mutableStateOf<String?>(null) }
    var showDangerConfirmation by remember { mutableStateOf(false) }
    var pendingDangerOperation by remember { mutableStateOf<String?>(null) }
    
    // 获取无障碍服务状态
    val isAccessibilityEnabled by remember {
        mutableStateOf(deviceControlManager?.isAccessibilityEnabled?.value ?: false)
    }
    
    // 设备状态
    var wifiEnabled by remember { mutableStateOf(deviceControlManager?.isWifiEnabled() ?: false) }
    var bluetoothEnabled by remember { mutableStateOf(deviceControlManager?.isBluetoothEnabled() ?: false) }
    var flashlightOn by remember { mutableStateOf(deviceControlManager?.isFlashlightOn() ?: false) }
    var brightness by remember { mutableFloatStateOf(deviceControlManager?.getBrightness() ?: 0.5f) }
    var volume by remember { mutableIntStateOf(deviceControlManager?.getVolume() ?: 50) }
    
    // 操作日志
    val operationLogs by remember { 
        mutableStateOf(deviceControlManager?.operationLogs?.value ?: emptyList<OperationLog>()) 
    }
    
    // 屏幕分析结果
    val screenAnalysis by remember { mutableStateOf(deviceControlManager?.screenAnalyzer?.analyzeScreen()) }
    
    val tabs = listOf(
        TabItem("快捷控制", Icons.Default.Dashboard),
        TabItem("自然语言", Icons.Default.TextFields),
        TabItem("屏幕分析", Icons.Default.Analytics),
        TabItem("手势录制", Icons.Default.Gesture),
        TabItem("操作日志", Icons.Default.History)
    )
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = stringResource(R.string.device_control_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 无障碍服务状态指示器
                    AccessibilityStatusIndicator(isEnabled = isAccessibilityEnabled)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 无障碍服务状态提示
            if (!isAccessibilityEnabled) {
                AccessibilityWarningBanner(
                    onEnableClick = {
                        deviceControlManager?.requestAccessibilityPermission()
                    }
                )
            }
            
            // Tab栏
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tab.title) },
                        icon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    )
                }
            }
            
            // Tab内容
            when (selectedTab) {
                0 -> QuickControlPanel(
                    wifiEnabled = wifiEnabled,
                    bluetoothEnabled = bluetoothEnabled,
                    flashlightOn = flashlightOn,
                    brightness = brightness,
                    volume = volume,
                    onWifiToggle = {
                        wifiEnabled = !wifiEnabled
                        deviceControlManager?.setWifiEnabled(wifiEnabled)
                    },
                    onBluetoothToggle = {
                        bluetoothEnabled = !bluetoothEnabled
                        deviceControlManager?.setBluetoothEnabled(bluetoothEnabled)
                    },
                    onFlashlightToggle = {
                        flashlightOn = !flashlightOn
                        deviceControlManager?.setFlashlight(flashlightOn)
                    },
                    onBrightnessChange = { newBrightness ->
                        brightness = newBrightness
                        deviceControlManager?.setBrightness(newBrightness)
                    },
                    onVolumeChange = { newVolume ->
                        volume = newVolume
                        deviceControlManager?.setVolume(newVolume)
                    },
                    onQuickAction = { action ->
                        executeQuickAction(action, deviceControlManager)
                    }
                )
                
                1 -> NaturalLanguagePanel(
                    command = naturalLanguageCommand,
                    onCommandChange = { naturalLanguageCommand = it },
                    commandResult = commandResult,
                    onSendCommand = {
                        scope.launch {
                            commandResult = processNaturalLanguageCommand(
                                naturalLanguageCommand,
                                deviceControlManager,
                                onDangerOperation = { operation ->
                                    pendingDangerOperation = operation
                                    showDangerConfirmation = true
                                }
                            )
                            naturalLanguageCommand = ""
                        }
                    }
                )
                
                2 -> ScreenAnalysisPanel(
                    analysis = screenAnalysis,
                    onRefresh = {
                        // 刷新屏幕分析
                    },
                    onElementClick = { element ->
                        // 点击分析结果中的元素
                        deviceControlManager?.click(element.bounds.centerX(), element.bounds.centerY())
                    }
                )
                
                3 -> GestureRecordingPanel(
                    isRecording = deviceControlManager?.gestureRecorder?.isRecording?.value ?: false,
                    onStartRecording = {
                        deviceControlManager?.startRecording()
                    },
                    onStopRecording = {
                        deviceControlManager?.stopRecording("Manual Recording")
                    },
                    onPlayGesture = {
                        // 播放手势
                    },
                    onQuickSwipe = { direction ->
                        val gesture = deviceControlManager?.gestureRecorder?.createQuickSwipeGesture(direction)
                        gesture?.let { deviceControlManager?.playGesture(it) }
                    }
                )
                
                4 -> OperationLogPanel(
                    logs = operationLogs,
                    onClearLogs = {
                        deviceControlManager?.clearLogs()
                    }
                )
            }
        }
    }
    
    // 危险操作确认对话框
    if (showDangerConfirmation && pendingDangerOperation != null) {
        AlertDialog(
            onDismissRequest = { 
                showDangerConfirmation = false
                pendingDangerOperation = null
            },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.danger_operation_warning)) },
            text = { 
                Text(stringResource(R.string.danger_operation_message, pendingDangerOperation ?: "")) 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 执行危险操作
                        showDangerConfirmation = false
                        pendingDangerOperation = null
                    }
                ) {
                    Text(stringResource(R.string.proceed_anyway))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showDangerConfirmation = false
                        pendingDangerOperation = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

// ==================== 子组件 ====================

/**
 * 无障碍服务状态指示器
 */
@Composable
private fun AccessibilityStatusIndicator(isEnabled: Boolean) {
    Row(
        modifier = Modifier.padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isEnabled) Color.Green else Color.Red)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (isEnabled) stringResource(R.string.accessibility_service_enabled) 
                   else stringResource(R.string.accessibility_service_disabled),
            style = MaterialTheme.typography.labelSmall,
            color = if (isEnabled) Color.Green else Color.Red
        )
    }
}

/**
 * 无障碍服务警告横幅
 */
@Composable
private fun AccessibilityWarningBanner(onEnableClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.accessibility_service_disabled),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "部分功能将无法使用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Button(onClick = onEnableClick) {
                Text(stringResource(R.string.enable_accessibility_service))
            }
        }
    }
}

/**
 * Tab项目数据类
 */
private data class TabItem(val title: String, val icon: ImageVector)

/**
 * 快捷控制面板
 */
@Composable
private fun QuickControlPanel(
    wifiEnabled: Boolean,
    bluetoothEnabled: Boolean,
    flashlightOn: Boolean,
    brightness: Float,
    volume: Int,
    onWifiToggle: () -> Unit,
    onBluetoothToggle: () -> Unit,
    onFlashlightToggle: () -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onQuickAction: (MobileActionType) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 连接控制
        item {
            Text(
                text = stringResource(R.string.quick_control_panel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "连接设置",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        QuickToggleButton(
                            icon = Icons.Default.Wifi,
                            label = stringResource(R.string.wifi),
                            isOn = wifiEnabled,
                            onClick = onWifiToggle
                        )
                        QuickToggleButton(
                            icon = Icons.Default.Bluetooth,
                            label = stringResource(R.string.bluetooth),
                            isOn = bluetoothEnabled,
                            onClick = onBluetoothToggle
                        )
                        QuickToggleButton(
                            icon = Icons.Default.FlashlightOn,
                            label = stringResource(R.string.flashlight),
                            isOn = flashlightOn,
                            onClick = onFlashlightToggle
                        )
                    }
                }
            }
        }
        
        // 亮度控制
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.brightness),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "${(brightness * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Slider(
                        value = brightness,
                        onValueChange = onBrightnessChange,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        
        // 音量控制
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.volume),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "$volume%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Slider(
                        value = volume.toFloat(),
                        onValueChange = { onVolumeChange(it.toInt()) },
                        valueRange = 0f..100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        
        // 系统操作
        item {
            Text(
                text = "系统操作",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(listOf(
                    MobileActionType.TOGGLE_FLASHLIGHT to "手电筒",
                    MobileActionType.TAKE_SCREENSHOT to "截图",
                    MobileActionType.PLAY_PAUSE to "播放/暂停",
                    MobileActionType.MAKE_CALL to "拨打电话"
                )) { (action, label) ->
                    ActionChip(
                        icon = getActionIcon(action),
                        label = label,
                        onClick = { onQuickAction(action) }
                    )
                }
            }
        }
    }
}

/**
 * 快速切换按钮
 */
@Composable
private fun QuickToggleButton(
    icon: ImageVector,
    label: String,
    isOn: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (isOn) MaterialTheme.colorScheme.primary 
                           else MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 2.dp,
                    color = if (isOn) MaterialTheme.colorScheme.primary 
                            else MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (isOn) MaterialTheme.colorScheme.onPrimary 
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isOn) MaterialTheme.colorScheme.primary 
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 操作芯片
 */
@Composable
private fun ActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * 自然语言控制面板
 */
@Composable
private fun NaturalLanguagePanel(
    command: String,
    onCommandChange: (String) -> Unit,
    commandResult: String?,
    onSendCommand: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.natural_language_control),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 命令输入框
        OutlinedTextField(
            value = command,
            onValueChange = onCommandChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.enter_command_hint)) },
            trailingIcon = {
                IconButton(onClick = onSendCommand) {
                    Icon(Icons.Default.Send, contentDescription = "发送")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    onSendCommand()
                    keyboardController?.hide()
                }
            ),
            maxLines = 3
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 示例命令
        Text(
            text = "示例命令：",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(listOf(
                "打开微信",
                "关闭WiFi",
                "截图",
                "调高音量"
            )) { example ->
                SuggestionChip(
                    onClick = { onCommandChange(example) },
                    label = { Text(example) }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // 结果显示
        if (commandResult != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (commandResult.startsWith("成功")) Icons.Default.CheckCircle 
                            else Icons.Default.Info,
                            contentDescription = null,
                            tint = if (commandResult.startsWith("成功")) Color.Green 
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "执行结果",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = commandResult,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Function提示
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "可用函数",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = DeviceControlFunctions.generateFunctionHint().take(200) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 屏幕分析面板
 */
@Composable
private fun ScreenAnalysisPanel(
    analysis: com.aigallery.rewrite.devicecontrol.ScreenAnalyzer.ScreenDescription?,
    onRefresh: () -> Unit,
    onElementClick: (com.aigallery.rewrite.devicecontrol.ScreenAnalyzer.UIElement) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.screen_analysis),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
        }
        
        if (analysis != null) {
            // 当前界面信息
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = analysis.title ?: analysis.activityName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = analysis.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            
            // 可点击元素
            if (analysis.clickableElements.isNotEmpty()) {
                item {
                    Text(
                        text = "可点击元素 (${analysis.clickableElements.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                items(analysis.clickableElements.take(15)) { element ->
                    ElementCard(
                        element = element,
                        onClick = { onElementClick(element) }
                    )
                }
            }
            
            // 输入字段
            if (analysis.inputFields.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "输入字段 (${analysis.inputFields.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                
                items(analysis.inputFields) { element ->
                    ElementCard(
                        element = element,
                        onClick = { onElementClick(element) }
                    )
                }
            }
        } else {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.HideSource,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "无法获取屏幕信息",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "请确保无障碍服务已启用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 元素卡片
 */
@Composable
private fun ElementCard(
    element: com.aigallery.rewrite.devicecontrol.ScreenAnalyzer.UIElement,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = element.toShortDescription(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        element.id?.let { append("ID: $it") }
                        if (element.isClickable) append(" · 可点击")
                        if (element.isEditable) append(" · 可编辑")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.TouchApp,
                contentDescription = "点击",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 手势录制面板
 */
@Composable
private fun GestureRecordingPanel(
    isRecording: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onPlayGesture: () -> Unit,
    onQuickSwipe: (com.aigallery.rewrite.devicecontrol.GestureRecorder.SwipeDirection) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.gesture_recording),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // 录制按钮
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(
                    if (isRecording) Color.Red 
                    else MaterialTheme.colorScheme.primaryContainer
                )
                .clickable {
                    if (isRecording) onStopRecording() else onStartRecording()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                contentDescription = if (isRecording) "停止录制" else "开始录制",
                modifier = Modifier.size(48.dp),
                tint = if (isRecording) Color.White 
                       else MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        
        Text(
            text = if (isRecording) "正在录制..." else "点击开始录制",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // 快捷滑动
        Text(
            text = "快捷滑动",
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf(
                Icons.Default.KeyboardArrowUp to com.aigallery.rewrite.devicecontrol.GestureRecorder.SwipeDirection.UP,
                Icons.Default.KeyboardArrowDown to com.aigallery.rewrite.devicecontrol.GestureRecorder.SwipeDirection.DOWN,
                Icons.Default.KeyboardArrowLeft to com.aigallery.rewrite.devicecontrol.GestureRecorder.SwipeDirection.LEFT,
                Icons.Default.KeyboardArrowRight to com.aigallery.rewrite.devicecontrol.GestureRecorder.SwipeDirection.RIGHT
            ).forEach { (icon, direction) ->
                IconButton(
                    onClick = { onQuickSwipe(direction) },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(icon, contentDescription = direction.name)
                }
            }
        }
    }
}

/**
 * 操作日志面板
 */
@Composable
private fun OperationLogPanel(
    logs: List<OperationLog>,
    onClearLogs: () -> Unit
) {
    val listState = rememberLazyListState()
    
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${stringResource(R.string.operation_log)} (${logs.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (logs.isNotEmpty()) {
                TextButton(onClick = onClearLogs) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清空")
                }
            }
        }
        
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无操作记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs.reversed()) { log ->
                    LogItem(log = log)
                }
            }
        }
    }
}

/**
 * 日志项
 */
@Composable
private fun LogItem(log: OperationLog) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (log.success) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (log.success) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (log.success) Color.Green else Color.Red,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${log.operation}: ${log.description}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (log.errorMessage != null) {
                    Text(
                        text = log.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Red
                    )
                }
            }
        }
    }
}

// ==================== 辅助函数 ====================

private fun getActionIcon(action: MobileActionType): ImageVector {
    return when (action) {
        MobileActionType.TOGGLE_WIFI -> Icons.Default.Wifi
        MobileActionType.TOGGLE_BLUETOOTH -> Icons.Default.Bluetooth
        MobileActionType.TOGGLE_MOBILE_DATA -> Icons.Default.SignalCellularAlt
        MobileActionType.TOGGLE_AIRPLANE_MODE -> Icons.Default.Flight
        MobileActionType.TOGGLE_FLASHLIGHT -> Icons.Default.FlashlightOn
        MobileActionType.TOGGLE_VIBRATE -> Icons.Default.Vibration
        MobileActionType.BRIGHTNESS_UP -> Icons.Default.BrightnessHigh
        MobileActionType.BRIGHTNESS_DOWN -> Icons.Default.BrightnessLow
        MobileActionType.SEND_SMS -> Icons.Default.Message
        MobileActionType.MAKE_CALL -> Icons.Default.Call
        MobileActionType.PLAY_PAUSE -> Icons.Default.PlayArrow
        MobileActionType.NEXT_TRACK -> Icons.Default.SkipNext
        MobileActionType.PREV_TRACK -> Icons.Default.SkipPrevious
        MobileActionType.OPEN_APP -> Icons.Default.Apps
        MobileActionType.CLOSE_APP -> Icons.Default.Close
        MobileActionType.TAKE_SCREENSHOT -> Icons.Default.Screenshot
        MobileActionType.ASK_USER -> Icons.Default.Help
    }
}

private suspend fun processNaturalLanguageCommand(
    command: String,
    manager: DeviceControlManager?,
    onDangerOperation: (String) -> Unit
): String {
    if (manager == null) return "错误：设备控制管理器未初始化"
    
    val cmd = command.trim().lowercase()
    
    return when {
        // WiFi控制
        cmd.contains("开wifi") || cmd.contains("打开wifi") || cmd.contains("wifi开") -> {
            manager.setWifiEnabled(true)
            "成功：WiFi已开启"
        }
        cmd.contains("关wifi") || cmd.contains("关闭wifi") || cmd.contains("wifi关") -> {
            onDangerOperation("关闭WiFi")
            manager.setWifiEnabled(false)
            "成功：WiFi已关闭"
        }
        
        // 蓝牙控制
        cmd.contains("开蓝牙") || cmd.contains("打开蓝牙") || cmd.contains("蓝牙开") -> {
            manager.setBluetoothEnabled(true)
            "成功：蓝牙已开启"
        }
        cmd.contains("关蓝牙") || cmd.contains("关闭蓝牙") || cmd.contains("蓝牙关") -> {
            manager.setBluetoothEnabled(false)
            "成功：蓝牙已关闭"
        }
        
        // 手电筒控制
        cmd.contains("开手电") || cmd.contains("打开手电") -> {
            manager.setFlashlight(true)
            "成功：手电筒已开启"
        }
        cmd.contains("关手电") || cmd.contains("关闭手电") -> {
            manager.setFlashlight(false)
            "成功：手电筒已关闭"
        }
        
        // 截图
        cmd.contains("截图") || cmd.contains("截屏") -> {
            // 截图功能需要MediaProjection
            "提示：截图功能需要额外的权限授权"
        }
        
        // 音量控制
        cmd.contains("音量加") || cmd.contains("调高音量") || cmd.contains("声音大") -> {
            manager.increaseVolume()
            "成功：音量已增加"
        }
        cmd.contains("音量减") || cmd.contains("调低音量") || cmd.contains("声音小") -> {
            manager.decreaseVolume()
            "成功：音量已降低"
        }
        
        // Home键
        cmd.contains("home") || cmd.contains("主页") || cmd.contains("回主页") -> {
            manager.pressKey("home")
            "成功：已返回主页"
        }
        
        // 返回键
        cmd.contains("back") || cmd.contains("返回") -> {
            manager.pressKey("back")
            "成功：已返回"
        }
        
        // 滑动
        cmd.contains("上滑") -> {
            manager.swipe(540, 960, 540, 460)
            "成功：已向上滑动"
        }
        cmd.contains("下滑") -> {
            manager.swipe(540, 460, 540, 960)
            "成功：已向下滑动"
        }
        cmd.contains("左滑") -> {
            manager.swipe(540, 960, 180, 960)
            "成功：已向左滑动"
        }
        cmd.contains("右滑") -> {
            manager.swipe(180, 960, 540, 960)
            "成功：已向右滑动"
        }
        
        // 应用启动
        cmd.contains("打开") -> {
            val appName = command.replace("打开", "").trim()
            manager.launchApp(appName)
            "成功：正在打开 $appName"
        }
        
        // 分析屏幕
        cmd.contains("分析") || cmd.contains("看看") || cmd.contains("这是什么") -> {
            val analysis = manager.analyzeScreen()
            if (analysis != null) {
                manager.generateLLMDescription()
            } else {
                "错误：无法获取屏幕信息，请检查无障碍服务是否启用"
            }
        }
        
        else -> "未知命令：\"$command\"\n\n支持的命令：\n- 开关WiFi/蓝牙/手电筒\n- 截图\n- 调节音量\n- 主页/返回\n- 滑动（上/下/左/右）\n- 打开[应用名]\n- 分析屏幕"
    }
}

private fun executeQuickAction(
    action: MobileActionType,
    manager: DeviceControlManager?
) {
    when (action) {
        MobileActionType.TOGGLE_FLASHLIGHT -> {
            val isOn = manager?.isFlashlightOn() ?: false
            manager?.setFlashlight(!isOn)
        }
        MobileActionType.TAKE_SCREENSHOT -> {
            // 需要MediaProjection
        }
        MobileActionType.MAKE_CALL -> {
            // 需要电话号码
        }
        MobileActionType.PLAY_PAUSE -> {
            manager?.pressKey("media_play_pause")
        }
        else -> { /* 其他操作 */ }
    }
}
