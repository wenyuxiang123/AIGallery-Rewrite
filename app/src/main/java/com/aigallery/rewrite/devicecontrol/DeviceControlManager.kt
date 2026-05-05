package com.aigallery.rewrite.devicecontrol

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
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
    private val context: Context
) {
    companion object {
        private const val TAG = "DeviceControlManager"
        
        // 全局单例
        @Volatile
        private var INSTANCE: DeviceControlManager? = null
        
        fun getInstance(context: Context): DeviceControlManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DeviceControlManager(
                    context.applicationContext
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
                val success = service.dispatchTapGesture(x, y)
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
                val success = service.dispatchTapGesture(x, y, duration)
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
                    "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
                    "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
                    "RECENT" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    "POWER" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                    "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    "QUICK_SETTINGS" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                    "SCREENSHOT" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
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

    // ==================== 操作日志 ====================

    private fun logOperation(operation: String, description: String, success: Boolean, error: String? = null) {
        val log = OperationLog(
            id = System.currentTimeMillis().toString(),
            operation = operation,
            description = description,
            success = success,
            errorMessage = error,
            timestamp = System.currentTimeMillis()
        )
        _operationLogs.value = _operationLogs.value + log
    }

    fun clearLogs() {
        _operationLogs.value = emptyList()
    }

    // ==================== 手势录制 ====================

    fun startRecording() {
        accessibilityService?.let { service ->
            // 启动手势录制
        }
    }

    fun stopRecording(name: String) {
        // 停止并保存录制
    }

    fun playGesture(gesture: List<Pair<Int, Int>>) {
        // 播放手势
    }
}

/**
 * 操作日志
 */
data class OperationLog(
    val id: String,
    val operation: String,
    val description: String,
    val success: Boolean,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
