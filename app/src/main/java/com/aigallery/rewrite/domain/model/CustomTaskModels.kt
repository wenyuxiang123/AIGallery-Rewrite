package com.aigallery.rewrite.domain.model

import java.util.UUID

/**
 * Custom task domain models - 爱马仕级全操控扩展
 */

// ==================== 设备控制相关模型 ====================

/**
 * 设备控制任务
 */
data class DeviceControlTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val taskType: DeviceControlTaskType,
    val parameters: Map<String, Any> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val executedAt: Long? = null,
    val status: DeviceControlTaskStatus = DeviceControlTaskStatus.PENDING
)

/**
 * 设备控制任务类型
 */
enum class DeviceControlTaskType(val displayName: String, val description: String) {
    // 屏幕操作
    CLICK("点击", "点击指定坐标或元素"),
    SWIPE("滑动", "在屏幕上滑动"),
    INPUT_TEXT("输入文本", "在输入框中输入文本"),
    PRESS_KEY("按键", "按下系统按键"),
    SCROLL("滚动", "滚动页面"),
    
    // 系统控制
    SET_WIFI("WiFi控制", "开关WiFi"),
    SET_BLUETOOTH("蓝牙控制", "开关蓝牙"),
    SET_VOLUME("音量控制", "调节音量"),
    SET_BRIGHTNESS("亮度控制", "调节亮度"),
    TOGGLE_FLASHLIGHT("手电筒", "开关手电筒"),
    SET_SCREEN("屏幕控制", "开关屏幕"),
    
    // 应用管理
    LAUNCH_APP("启动应用", "打开指定应用"),
    FORCE_STOP_APP("停止应用", "强制停止应用"),
    GET_INSTALLED_APPS("获取应用列表", "获取已安装应用列表"),
    
    // 通讯
    MAKE_CALL("拨打电话", "拨打电话"),
    SEND_SMS("发送短信", "发送短信"),
    
    // 剪贴板
    SET_CLIPBOARD("复制到剪贴板", "复制文本到剪贴板"),
    GET_CLIPBOARD("获取剪贴板", "获取剪贴板内容"),
    
    // 屏幕分析
    ANALYZE_SCREEN("分析屏幕", "分析当前屏幕内容"),
    FIND_ELEMENT("查找元素", "查找界面元素"),
    GET_SCREENSHOT("截图", "截取当前屏幕"),
    
    // 高级
    EXECUTE_SHELL("执行Shell", "执行Shell命令"),
    GESTURE_RECORD("录制手势", "录制手势操作"),
    GESTURE_PLAYBACK("回放手势", "回放已录制手势")
}

/**
 * 设备控制任务状态
 */
enum class DeviceControlTaskStatus {
    PENDING,    // 待执行
    RUNNING,    // 执行中
    SUCCESS,    // 成功
    FAILED,     // 失败
    CANCELLED   // 已取消
}

/**
 * 设备状态
 */
data class DeviceState(
    val wifiEnabled: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val flashlightOn: Boolean = false,
    val brightness: Float = 0.5f,
    val volume: Int = 50,
    val screenOn: Boolean = true,
    val locationEnabled: Boolean = false,
    val airplaneMode: Boolean = false,
    val currentActivity: String? = null,
    val currentPackage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 屏幕元素信息
 */
data class ScreenElement(
    val id: String,
    val text: String?,
    val description: String?,
    val className: String?,
    val bounds: ElementBounds,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isVisible: Boolean
)

/**
 * 元素边界
 */
data class ElementBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 手势信息
 */
data class GestureInfo(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val events: List<GestureEventInfo>,
    val totalDuration: Long,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 手势事件信息
 */
data class GestureEventInfo(
    val type: GestureEventType,
    val startX: Int,
    val startY: Int,
    val endX: Int? = null,
    val endY: Int? = null,
    val duration: Long,
    val text: String? = null
)

/**
 * 手势事件类型
 */
enum class GestureEventType {
    TAP,         // 点击
    LONG_PRESS,  // 长按
    SWIPE,       // 滑动
    DRAG,        // 拖拽
    INPUT        // 输入
}

/**
 * LLM函数调用记录
 */
data class FunctionCallRecord(
    val id: String = UUID.randomUUID().toString(),
    val functionName: String,
    val parameters: Map<String, Any>,
    val result: String?,
    val success: Boolean,
    val dangerLevel: DangerLevel,
    val requiresConfirmation: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
)

/**
 * 危险操作级别
 */
enum class DangerLevel {
    NONE,    // 无风险
    LOW,     // 低风险
    MEDIUM,  // 中等风险
    HIGH,    // 高风险
    EXTREME  // 极高风险
}

/**
 * 设备控制配置
 */
data class DeviceControlConfig(
    val enableNaturalLanguageControl: Boolean = true,
    val enableGestureRecording: Boolean = true,
    val enableDangerConfirmation: Boolean = true,
    val defaultSwipeDistance: Int = 500,
    val defaultSwipeDuration: Long = 300,
    val autoAnalyzeScreen: Boolean = true,
    val logAllOperations: Boolean = true,
    val allowedDangerousFunctions: Set<String> = emptySet(), // 允许自动执行的危险函数
    val blockedPackages: Set<String> = emptySet() // 禁止操作的包名
)

// ==================== 原有模型 ====================

/**
 * Custom task types
 */
enum class CustomTaskType(val displayName: String, val description: String) {
    AGENT_CHAT("Agent Chat", "智能体对话 + 工具调用"),
    MOBILE_ACTIONS("Mobile Actions", "手机控制（手电筒、WiFi、短信等）"),
    DEVICE_CONTROL("Device Control", "爱马仕级全操控 - 完整设备控制"),
    EXAMPLE_TASK("Example Task", "示例任务模板"),
    TINY_GARDEN("Tiny Garden", "浏览器自动化")
}

/**
 * Custom skill/task definition
 */
data class CustomSkill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val type: CustomTaskType,
    val icon: String? = null,
    val systemPrompt: String,
    val tools: List<ToolDefinition> = emptyList(),
    val isBuiltIn: Boolean = false,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val usageCount: Int = 0
)

/**
 * Tool definition for agent
 */
data class ToolDefinition(
    val id: String,
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList(),
    val code: String? = null,
    val apiEndpoint: String? = null
)

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false,
    val defaultValue: Any? = null
)

/**
 * Agent chat state
 */
data class AgentChatState(
    val currentSkill: CustomSkill? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val toolCalls: List<ToolCall> = emptyList()
)

/**
 * Tool call tracking
 */
data class ToolCall(
    val id: String = UUID.randomUUID().toString(),
    val toolId: String,
    val toolName: String,
    val parameters: Map<String, Any>,
    val result: String? = null,
    val status: ToolCallStatus = ToolCallStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ToolCallStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED
}

/**
 * Mobile action types
 */
enum class MobileActionType(
    val displayName: String,
    val description: String,
    val category: ActionCategory
) {
    // Connectivity
    TOGGLE_WIFI("切换WiFi", "开关WiFi", ActionCategory.CONNECTIVITY),
    TOGGLE_BLUETOOTH("切换蓝牙", "开关蓝牙", ActionCategory.CONNECTIVITY),
    TOGGLE_MOBILE_DATA("切换移动数据", "开关移动数据", ActionCategory.CONNECTIVITY),
    TOGGLE_AIRPLANE_MODE("飞行模式", "开关飞行模式", ActionCategory.CONNECTIVITY),
    
    // Device
    TOGGLE_FLASHLIGHT("手电筒", "开关闪光灯", ActionCategory.DEVICE),
    TOGGLE_VIBRATE("震动模式", "开关震动", ActionCategory.DEVICE),
    BRIGHTNESS_UP("增加亮度", "提高屏幕亮度", ActionCategory.DEVICE),
    BRIGHTNESS_DOWN("降低亮度", "降低屏幕亮度", ActionCategory.DEVICE),
    
    // Communication
    SEND_SMS("发送短信", "发送短信给指定联系人", ActionCategory.COMMUNICATION),
    MAKE_CALL("拨打电话", "拨打电话给指定联系人", ActionCategory.COMMUNICATION),
    
    // Media
    PLAY_PAUSE("播放/暂停", "媒体播放控制", ActionCategory.MEDIA),
    NEXT_TRACK("下一首", "下一首歌曲", ActionCategory.MEDIA),
    PREV_TRACK("上一首", "上一首歌曲", ActionCategory.MEDIA),
    
    // System
    OPEN_APP("打开应用", "打开指定应用", ActionCategory.SYSTEM),
    CLOSE_APP("关闭应用", "关闭指定应用", ActionCategory.SYSTEM),
    TAKE_SCREENSHOT("截图", "截取当前屏幕", ActionCategory.SYSTEM),
    
    // Assistant
    ASK_USER("询问用户", "向用户请求更多信息", ActionCategory.ASSISTANT)
}

enum class ActionCategory(val displayName: String) {
    CONNECTIVITY("连接"),
    DEVICE("设备"),
    COMMUNICATION("通讯"),
    MEDIA("媒体"),
    SYSTEM("系统"),
    ASSISTANT("助手")
}

/**
 * Mobile action result
 */
data class MobileActionResult(
    val action: MobileActionType,
    val success: Boolean,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
