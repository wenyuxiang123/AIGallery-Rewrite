package com.aigallery.rewrite.devicecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android无障碍服务实现 - 爱马仕级全操控核心
 * 
 * 功能：
 * - 捕获并缓存当前界面所有节点树
 * - 提供节点查询、操作的完整实现
 * - 监听界面变化事件
 * - 手势分发 (dispatchGesture)
 * - 截屏能力
 */
class AccessibilityServiceImpl : AccessibilityService() {

    companion object {
        /** 单例引用 */
        @Volatile
        private var instance: AccessibilityServiceImpl? = null
        
        fun getInstance(): AccessibilityServiceImpl? = instance
        
        /** 服务运行状态 */
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        
        /** 当前窗口根节点 */
        private val _rootNode = MutableStateFlow<AccessibilityNodeInfo?>(null)
        val rootNode: StateFlow<AccessibilityNodeInfo?> = _rootNode.asStateFlow()
        
        /** 当前Activity名称 */
        private val _currentActivity = MutableStateFlow<String?>(null)
        val currentActivity: StateFlow<String?> = _currentActivity.asStateFlow()
        
        /** 全局操作常量 */
        const val GLOBAL_ACTION_HOME = AccessibilityService.GLOBAL_ACTION_HOME
        const val GLOBAL_ACTION_BACK = AccessibilityService.GLOBAL_ACTION_BACK
        const val GLOBAL_ACTION_RECENTS = AccessibilityService.GLOBAL_ACTION_RECENTS
        const val GLOBAL_ACTION_POWER_DIALOG = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        const val GLOBAL_ACTION_NOTIFICATIONS = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        const val GLOBAL_ACTION_QUICK_SETTINGS = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        const val GLOBAL_ACTION_SCREENSHOT = AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
    }

    // ==================== 生命周期 ====================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isRunning.value = true
        
        // 配置服务信息
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        
        // 更新当前Activity
        _currentActivity.value = event.className?.toString()
        
        // 获取并缓存根节点
        val root = rootInActiveWindow
        _rootNode.value = root
    }

    override fun onInterrupt() {
        _isRunning.value = false
        _rootNode.value = null
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        _rootNode.value = null
        instance = null
    }

    // ==================== 节点操作 ====================

    /**
     * 查找包含指定文本的节点
     */
    fun findNodeByText(text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        return _rootNode.value?.findAccessibilityNodeInfosByText(
            if (exactMatch) "^$text$" else text
        )?.firstOrNull()
    }

    /**
     * 查找指定viewId的节点
     */
    fun findNodeById(viewId: String): AccessibilityNodeInfo? {
        return _rootNode.value?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
    }

    /**
     * 获取所有可点击的节点
     */
    fun getClickableNodes(): List<AccessibilityNodeInfo> {
        return collectNodes(_rootNode.value) { it.isClickable }
    }

    /**
     * 获取所有可编辑的节点
     */
    fun getEditableNodes(): List<AccessibilityNodeInfo> {
        return collectNodes(_rootNode.value) { node ->
            node.isEditable || node.isFocusable
        }
    }

    /**
     * 获取所有可见节点
     */
    fun getAllVisibleNodes(): List<AccessibilityNodeInfo> {
        return collectNodes(_rootNode.value) { it.isVisibleToUser }
    }

    /**
     * 递归收集满足条件的节点
     */
    private fun collectNodes(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) {
                result.add(AccessibilityNodeInfo.obtain(node))
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    // ==================== 节点操作 ====================

    /**
     * 点击指定节点
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            // 尝试向上查找可点击的父节点
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    return result
                }
                val temp = parent.parent
                parent.recycle()
                parent = temp
            }
            false
        }
    }

    /**
     * 长按指定节点
     */
    fun longClickNode(node: AccessibilityNodeInfo): Boolean {
        return if (node.isLongClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        } else {
            node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }
    }

    /**
     * 点击指定坐标
     */
    fun click(x: Int, y: Int): Boolean {
        return dispatchTapGesture(x, y)
    }

    /**
     * 模拟点击坐标（通过查找并点击该位置的节点）
     */
    private fun simulateClick(x: Int, y: Int): Boolean {
        val root = _rootNode.value ?: return false
        
        // 查找包含该坐标的节点
        val nodeAtPoint = findNodeAtPoint(root, x, y)
        if (nodeAtPoint != null) {
            val clicked = clickNode(nodeAtPoint)
            nodeAtPoint.recycle()
            return clicked
        }
        return false
    }

    /**
     * 在指定坐标查找节点
     */
    private fun findNodeAtPoint(root: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (root == null) return null
        
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        
        if (bounds.contains(x, y)) {
            // 先检查子节点
            for (i in 0 until root.childCount) {
                val child = root.getChild(i) ?: continue
                val result = findNodeAtPoint(child, x, y)
                child.recycle()
                if (result != null) return result
            }
            return AccessibilityNodeInfo.obtain(root)
        }
        return null
    }

    /**
     * 长按指定坐标
     */
    fun longClick(x: Int, y: Int): Boolean {
        return dispatchTapGesture(x, y, 500)
    }

    /**
     * 在节点中输入文本
     */
    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * 滚动向前（向下）
     */
    fun scrollForward(node: AccessibilityNodeInfo): Boolean {
        return if (node.isScrollable) {
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        } else {
            false
        }
    }

    /**
     * 滚动向后（向上）
     */
    fun scrollBackward(node: AccessibilityNodeInfo): Boolean {
        return if (node.isScrollable) {
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        } else {
            false
        }
    }

    /**
     * 获取节点文本描述
     */
    fun getNodeDescription(node: AccessibilityNodeInfo): String {
        return buildString {
            node.text?.let { append("文本: $it\n") }
            node.contentDescription?.let { append("描述: $it\n") }
            node.className?.let { append("类型: $it\n") }
            node.viewIdResourceName?.let { append("ID: $it\n") }
            
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            append("位置: ($bounds.left, $bounds.top) - ($bounds.right, $bounds.bottom)\n")
            
            append("可点击: ${node.isClickable}, ")
            append("可滚动: ${node.isScrollable}, ")
            append("可编辑: ${node.isEditable}\n")
        }
    }

    /**
     * 获取节点的边界矩形
     */
    fun getNodeBounds(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
    }

    // ==================== 全局操作 ====================

    /**
     * 执行全局操作
     */
    fun doPerformGlobalAction(action: Int): Boolean {
        return try {
            super.performGlobalAction(action)
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 手势分发 ====================

    /**
     * 通过手势模拟点击
     * 注意：Android 7.0+ 支持 GestureDescription
     */
    fun dispatchTapGesture(x: Int, y: Int, duration: Long = 100): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }
        
        return try {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            
            val stroke = AccessibilityService.GestureDescription.StrokeDescription(
                path,
                0,
                duration
            )
            
            val gesture = AccessibilityService.GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 通过手势模拟滑动
     */
    fun dispatchSwipeGesture(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        duration: Long = 300
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }
        
        return try {
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            
            val stroke = AccessibilityService.GestureDescription.StrokeDescription(
                path,
                0,
                duration
            )
            
            val gesture = AccessibilityService.GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            false
        }
    }
}
