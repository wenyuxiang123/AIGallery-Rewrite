package com.aigallery.rewrite.devicecontrol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备控制总入口 - 爱马仕级全操控核心管理器
 * 
 * 单例模式，管理所有设备控制能力
 * 提供统一的API接口给UI层和LLM层调用
 */
@Singleton
class DeviceControlManager @Inject constructor(
    private val context: Context,
    private val deviceControlService: DeviceControlService
) {
    companion object {
        private const val TAG = "DeviceControlManager"
        
        // 全局单例
        @Volatile
        private var INSTANCE: DeviceControlManager? = null
        
        fun getInstance(context: Context): DeviceControlManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DeviceControlManager(
                    context.applicationContext,
                    DeviceControlService(context.applicationContext)
                ).also { INSTANCE = it }
            }
        }
    }

    // ==================== 子模块引用 ====================
    
    /** 无障碍服务 */
    val accessibilityService: AccessibilityServiceImpl?
        get() = AccessibilityServiceImpl.getInstance()
    
    /** 手势录制器 */
    val gestureRecorder: GestureRecorder by lazy {
        GestureRecorder(accessibilityService)
    }
    
    /** 屏幕分析器 */
    val screenAnalyzer: ScreenAnalyzer by lazy {
        ScreenAnalyzer(accessibilityService)
    }

    // ==================== 协程作用域 ====================
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ==================== 状态 ====================

    /** 无障碍服务是否启用 */
    val isAccessibilityEnabled: StateFlow<Boolean>
        get() = AccessibilityServiceImpl.isRunning

    /** 当前Activity名称 */
    val currentActivity: StateFlow<String?>
        get() = AccessibilityServiceImpl.currentActivity

    /** 操作日志 */
    private val _operationLogs = MutableStateFlow<List<OperationLog>>(emptyList())
    val operationLogs: StateFlow<List<OperationLog>> = _operationLogs.asStateFlow()

    // ==================== 基础操作 ====================

    /**
     * 点击指定坐标
     */
    fun click(x: Int, y: Int): Result<Unit> {
        return try {
            val service = accessibilityService
            if (service == null) {
                Result.failure(IllegalStateException("Accessibility service not available"))
            } else {
                val success = service.dispatchTapGesture(x.toFloat(), y.toFloat())
                logOperation("click", "点击 ($x, $y)", success)
                if (success) Result.success(Unit) else Result.failure(Exception("Click failed"))
            }
        } catch (e: Exception) {
            logOperation("click", "点击 ($x, $y)", false, e.message)
            Result.failure(e)
        }
    }

    /**
     * 长按指定坐标
     */
    fun longClick(x: Int, y: Int, duration: Long = 500): Result<Unit> {
        return try {
            val service = accessibilityService
            if (service == null) {
                Result.failure(IllegalStateException("Accessibility service not available"))
            } else {
                val success = service.dispatchTapGesture(x.toFloat(), y.toFloat(), duration)
                logOperation("longClick", "长按 ($x, $y)", success)
                if (success) Result.success(Unit) else Result.failure(Exception("Long click failed"))
            }
        } catch (e: Exception) {
            logOperation("longClick", "长按 ($x, $y)", false, e.message)
            Result.failure(e)
        }
    }

    /**
     * 滑动
     */
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 300): Result<Unit> {
        return try {
            val service = accessibilityService
            if (service == null) {
                Result.failure(IllegalStateException("Accessibility service not available"))
            } else {
                val success = service.dispatchSwipeGesture(startX, startY, endX, endY, duration)
                logOperation("swipe", "滑动 ($startX,$startY) -> ($endX,$endY)", success)
                if (success) Result.success(Unit) else Result.failure(Exception("Swipe failed"))
            }
        } catch (e: Exception) {
            logOperation("swipe", "滑动", false, e.message)
            Result.failure(e)
        }
    }

    /**
     * 拖拽
     */
    fun drag(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 500): Result<Unit> {
        return try {
            val service = accessibilityService
            if (service == null) {
                Result.failure(IllegalStateException("Accessibility service not available"))
            } else {
                val success = service.dispatchSwipeGesture(startX, startY, endX, endY, duration)
                logOperation("drag", "拖拽 ($startX,$startY) -> ($endX,$endY)", success)
                if (success) Result.success(Unit) else Result.failure(Exception("Drag failed"))
            }
        } catch (e: Exception) {
            logOperation("drag", "拖拽", false, e.message)
            Result.failure(e)
        }
    }

    /**
     * 输入文本到当前焦点输入框
     */
    fun inputText(text: String): Result<Unit> {
        return try {
            val service = accessibilityService
            if (service == null) {
                Result.failure(IllegalStateException("Accessibility service not available"))
            } else {
                // 查找当前焦点输入框
                val focusedNode = findFocusedNode()
                if (focusedNode != null) {
                    val success = service.inputText(focusedNode, text)
                    logOperation("inputText", "输入 \"$text\"", success)
                    focusedNode.recycle()
                    if (success) Result.success(Unit) else Result.failure(Exception("Input failed"))
                } else {
                    Result.failure(Exception("No focused input field"))
                }
            }
        } catch (e: Exception) {
            logOperation("inputText", "输入文本", false, e.message)
            Result.failure(e)
        }
    }

    /**
     * 按键操作
     * 支持: HOME, BACK, RECENT, POWER, VOLUME_UP, VOLUME_DOWN
     */
    fun pressKey(keyName: String): Result<Unit> {
        return try {
            val service = accessibilityService
            if (service == null) {
                Result.failure(IllegalStateException("Accessibility service not available"))
            } else {
                val action = when (keyName.uppercase()) {
                    "HOME" -> AccessibilityServiceImpl.GLOBAL_ACTION_HOME
                    "BACK" -> AccessibilityServiceImpl.GLOBAL_ACTION_BACK
                    "RECENT" -> AccessibilityServiceImpl.GLOBAL_ACTION_RECENTS
                    "POWER" -> AccessibilityServiceImpl.GLOBAL_ACTION_POWER_DIALOG
                    "NOTIFICATIONS" -> AccessibilityServiceImpl.GLOBAL_ACTION_NOTIFICATIONS
                    "QUICK_SETTINGS" -> AccessibilityServiceImpl.GLOBAL_ACTION_QUICK_SETTINGS
                    "SREENSHOT" -> AccessibilityServiceImpl.GLOBAL_ACTION_SCREENSHOT
                    else -> return Result.failure(IllegalArgumentException("Unknown key: $keyName"))
                }
                val success = service.performGlobalAction(action)
                logOperation("pressKey", "按键 $keyName", success)
                if (success) Result.success(Unit) else Result.failure(Exception("Key press failed"))
            }
        } catch (e: Exception) {
            logOperation("pressKey", "按键 $keyName", false, e.message)
            Result.failure(e)
        }
    }

    /**
     * 截取当前屏幕
     */
    fun screenshot(): Result<Bitmap> {
        return try {
            // 需要MediaProjection，这里返回失败提示
            Result.failure(UnsupportedOperationException("Screenshot requires MediaProjection permission"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== 节点操作 ====================

    /**
     * 按文本查找元素
     */
    fun getNodeByText(text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        return accessibilityService?.findNodeByText(text, exactMatch)
    }

    /**
     * 按ID查找元素
     */
    fun getNodeById(id: String): AccessibilityNodeInfo? {
        return accessibilityService?.findNodeById(id)
    }

    /**
     * 点击指定节点
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val success = accessibilityService?.clickNode(node) ?: false
        logOperation("clickNode", "点击节点 ${node.viewIdResourceName ?: node.text}", success)
        return success
    }

    /**
     * 滚动向前（向下）
     */
    fun scrollForward(node: AccessibilityNodeInfo): Boolean {
        val success = accessibilityService?.scrollForward(node) ?: false
        logOperation("scrollForward", "向下滚动", success)
        return success
    }

    /**
     * 滚动向后（向上）
     */
    fun scrollBackward(node: AccessibilityNodeInfo): Boolean {
        val success = accessibilityService?.scrollBackward(node) ?: false
        logOperation("scrollBackward", "向上滚动", success)
        return success
    }

    /**
     * 获取当前Activity名称
     */
    fun getCurrentActivity(): String? {
        return currentActivity.value
    }

    /**
     * 获取所有可见节点
     */
    fun getAllNodes(): List<AccessibilityNodeInfo> {
        return accessibilityService?.getAllVisibleNodes() ?: emptyList()
    }

    /**
     * 执行全局操作
     */
    fun performGlobalAction(action: Int): Boolean {
        val success = accessibilityService?.performGlobalAction(action) ?: false
        logOperation("globalAction", "执行全局操作 $action", success)
        return success
    }

    /**
     * 查找焦点节点
     */
    private fun findFocusedNode(): AccessibilityNodeInfo? {
        val root = AccessibilityServiceImpl.rootNode.value ?: return null
        
        // 递归查找焦点节点
        fun findFocused(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isFocused && node.isEditable) return node
            
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findFocused(child)
                child.recycle()
                if (result != null) return result
            }
            return null
        }
        
        return findFocused(root)
    }

    // ==================== 设备控制快捷方法 ====================

    /** WiFi控制 */
    fun setWifiEnabled(enabled: Boolean) = deviceControlService.setWifiEnabled(enabled)
    fun isWifiEnabled() = deviceControlService.isWifiEnabled()

    /** 蓝牙控制 */
    fun setBluetoothEnabled(enabled: Boolean) = deviceControlService.setBluetoothEnabled(enabled)
    fun isBluetoothEnabled() = deviceControlService.isBluetoothEnabled()

    /** 音量控制 */
    fun setVolume(volume: Int) = deviceControlService.setVolume(AudioManager.STREAM_MUSIC, volume)
    fun getVolume() = deviceControlService.getVolume(AudioManager.STREAM_MUSIC)
    fun increaseVolume() = deviceControlService.increaseVolume()
    fun decreaseVolume() = deviceControlService.decreaseVolume()
    fun setMute(muted: Boolean) = deviceControlService.setMute(muted)

    /** 亮度控制 */
    fun setBrightness(level: Float) = deviceControlService.setBrightness(level)
    fun getBrightness() = deviceControlService.getBrightness()

    /** 手电筒 */
    fun setFlashlight(enabled: Boolean) = deviceControlService.setFlashlight(enabled)
    fun isFlashlightOn() = deviceControlService.isFlashlightOn()

    /** 屏幕控制 */
    fun setScreenOn() = deviceControlService.setScreenOn()
    fun setScreenOff() = deviceControlService.setScreenOff()
    fun isScreenOn() = deviceControlService.isScreenOn()

    /** 应用管理 */
    fun launchApp(packageName: String) = deviceControlService.launchApp(packageName)
    fun forceStopApp(packageName: String) = deviceControlService.forceStopApp(packageName)
    fun isAppInstalled(packageName: String) = deviceControlService.isAppInstalled(packageName)
    fun getInstalledApps() = deviceControlService.getInstalledApps()

    /** 剪贴板 */
    fun setClipboard(text: String) = deviceControlService.setClipboard(text)
    fun getClipboard() = deviceControlService.getClipboard()

    /** 电话/短信 */
    fun makeCall(number: String) = deviceControlService.makeCall(number)
    fun sendSms(number: String, text: String) = deviceControlService.sendSms(number, text)

    /** 定位 */
    fun toggleLocation(enabled: Boolean) = deviceControlService.toggleLocation(enabled)
    fun isLocationEnabled() = deviceControlService.isLocationEnabled()

    /** 震动 */
    fun vibrate(duration: Long = 500) = deviceControlService.vibrate(duration)
    fun setVibrateMode(enabled: Boolean) = deviceControlService.setVibrateMode(enabled)
    fun isVibrateMode() = deviceControlService.isVibrateMode()

    /** Shell命令 */
    fun executeShell(command: String) = scope.launch {
        deviceControlService.executeShell(command)
    }

    // ==================== 屏幕分析 ====================

    /**
     * 分析当前屏幕
     */
    fun analyzeScreen(): ScreenAnalyzer.ScreenDescription? {
        return screenAnalyzer.analyzeScreen()
    }

    /**
     * 生成LLM友好的屏幕描述
     */
    fun generateLLMDescription(): String {
        return screenAnalyzer.generateLLMDescription()
    }

    /**
     * 获取所有可点击元素
     */
    fun findClickableElements(): List<ScreenAnalyzer.UIElement> {
        return screenAnalyzer.findClickableElements()
    }

    /**
     * 获取所有输入字段
     */
    fun findInputFields(): List<ScreenAnalyzer.UIElement> {
        return screenAnalyzer.findInputFields()
    }

    // ==================== 手势录制 ====================

    /**
     * 开始录制手势
     */
    fun startRecording() {
        gestureRecorder.startRecording()
    }

    /**
     * 停止录制并返回手势序列
     */
    fun stopRecording(name: String = "Unnamed"): GestureRecorder.GestureSequence? {
        return gestureRecorder.stopRecording(name)
    }

    /**
     * 回放手势
     */
    fun playGesture(gesture: GestureRecorder.GestureSequence): Boolean {
        return gestureRecorder.playGesture(gesture)
    }

    /**
     * 回放手势（指定速度）
     */
    fun playGestureAtSpeed(gesture: GestureRecorder.GestureSequence, speed: Float): Boolean {
        return gestureRecorder.playGestureAtSpeed(gesture, speed)
    }

    // ==================== 日志 ====================

    /**
     * 记录操作日志
     */
    private fun logOperation(operation: String, detail: String, success: Boolean, error: String? = null) {
        val log = OperationLog(
            operation = operation,
            detail = detail,
            success = success,
            error = error,
            timestamp = System.currentTimeMillis()
        )
        
        _operationLogs.value = (_operationLogs.value + log).takeLast(100) // 保留最近100条
    }

    /**
     * 清除日志
     */
    fun clearLogs() {
        _operationLogs.value = emptyList()
    }

    /**
     * 请求无障碍权限
     */
    fun requestAccessibilityPermission() {
        accessibilityService?.requestAccessibilityPermission()
    }

    /**
     * 检查是否需要请求无障碍权限
     */
    fun needsAccessibilityPermission(): Boolean {
        return accessibilityService == null || !isAccessibilityEnabled.value
    }
}

/**
 * 操作日志数据类
 */
data class OperationLog(
    val operation: String,
    val detail: String,
    val success: Boolean,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
