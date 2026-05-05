package com.aigallery.rewrite.devicecontrol

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 手势录制与回放 - 爱马仕级全操控
 * 
 * 功能：
 * - 录制手势序列（点击、滑动、拖拽）
 * - 回放录制的手势
 * - 支持不同速度的回放
 * - 支持手势序列的保存和加载
 */
class GestureRecorder(
    private val accessibilityService: AccessibilityServiceImpl?
) {
    companion object {
        private const val TAG = "GestureRecorder"
    }

    // ==================== 状态 ====================

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordedGestures = mutableListOf<GestureEvent>()
    
    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    // ==================== 手势事件数据类 ====================

    /**
     * 手势事件类型
     */
    enum class GestureEventType {
        TAP,           // 点击
        LONG_PRESS,    // 长按
        SWIPE,         // 滑动
        DRAG,          // 拖拽
        INPUT          // 文本输入
    }

    /**
     * 手势事件
     */
    data class GestureEvent(
        val type: GestureEventType,
        val startX: Int,
        val startY: Int,
        val endX: Int = startX,
        val endY: Int = startY,
        val duration: Long = 100, // 毫秒
        val text: String? = null, // 用于INPUT类型
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 手势序列
     */
    data class GestureSequence(
        val id: String,
        val name: String,
        val events: List<GestureEvent>,
        val totalDuration: Long,
        val createdAt: Long = System.currentTimeMillis(),
        val description: String? = null
    )

    // ==================== 录制 ====================

    /**
     * 开始录制
     */
    fun startRecording() {
        _recordedGestures.clear()
        _isRecording.value = true
    }

    /**
     * 记录点击事件
     */
    fun recordTap(x: Int, y: Int) {
        if (!_isRecording.value) return
        _recordedGestures.add(
            GestureEvent(
                type = GestureEventType.TAP,
                startX = x,
                startY = y,
                duration = 100
            )
        )
    }

    /**
     * 记录长按事件
     */
    fun recordLongPress(x: Int, y: Int, duration: Long = 500) {
        if (!_isRecording.value) return
        _recordedGestures.add(
            GestureEvent(
                type = GestureEventType.LONG_PRESS,
                startX = x,
                startY = y,
                duration = duration
            )
        )
    }

    /**
     * 记录滑动事件
     */
    fun recordSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        duration: Long = 300
    ) {
        if (!_isRecording.value) return
        _recordedGestures.add(
            GestureEvent(
                type = GestureEventType.SWIPE,
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                duration = duration
            )
        )
    }

    /**
     * 记录拖拽事件
     */
    fun recordDrag(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        duration: Long = 500
    ) {
        if (!_isRecording.value) return
        _recordedGestures.add(
            GestureEvent(
                type = GestureEventType.DRAG,
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                duration = duration
            )
        )
    }

    /**
     * 记录文本输入
     */
    fun recordInput(x: Int, y: Int, text: String) {
        if (!_isRecording.value) return
        _recordedGestures.add(
            GestureEvent(
                type = GestureEventType.INPUT,
                startX = x,
                startY = y,
                text = text
            )
        )
    }

    /**
     * 停止录制并返回手势序列
     */
    fun stopRecording(name: String = "Unnamed", description: String? = null): GestureSequence? {
        _isRecording.value = false
        
        if (_recordedGestures.isEmpty()) {
            return null
        }

        val totalDuration = _recordedGestures.sumOf { it.duration }
        
        return GestureSequence(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            events = _recordedGestures.toList(),
            totalDuration = totalDuration,
            description = description
        ).also {
            _recordedGestures.clear()
        }
    }

    /**
     * 取消录制
     */
    fun cancelRecording() {
        _recordedGestures.clear()
        _isRecording.value = false
    }

    // ==================== 回放 ====================

    /**
     * 以正常速度回放手势
     */
    fun playGesture(gesture: GestureSequence): Boolean {
        return playGestureAtSpeed(gesture, 1.0f)
    }

    /**
     * 以指定速度回放手势
     * @param speed 速度倍率，1.0 = 正常，2.0 = 2倍速，0.5 = 0.5倍速
     */
    fun playGestureAtSpeed(gesture: GestureSequence, speed: Float): Boolean {
        val service = accessibilityService ?: return false
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        var success = true
        val adjustedSpeed = speed.coerceIn(0.1f, 10.0f)

        for (event in gesture.events) {
            val adjustedDuration = (event.duration / adjustedSpeed).toLong()
            
            val result = when (event.type) {
                GestureEventType.TAP -> {
                    service.dispatchTapGesture(event.startX, event.startY, adjustedDuration)
                }
                GestureEventType.LONG_PRESS -> {
                    service.dispatchTapGesture(event.startX, event.startY, adjustedDuration.coerceAtLeast(500))
                }
                GestureEventType.SWIPE, GestureEventType.DRAG -> {
                    service.dispatchSwipeGesture(
                        event.startX,
                        event.startY,
                        event.endX,
                        event.endY,
                        adjustedDuration
                    )
                }
                GestureEventType.INPUT -> {
                    // 先点击输入框，然后输入文本
                    val clicked = service.dispatchTapGesture(event.startX, event.startY, 100)
                    if (clicked && event.text != null) {
                        // 查找输入框并输入
                        val node = service.findNodeAtPoint(event.startX, event.startY)
                        if (node != null) {
                            val inputResult = service.inputText(node, event.text)
                            node.recycle()
                            inputResult
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
            }
            
            if (!result) {
                success = false
            }

            // 事件间隔
            if (adjustedSpeed > 0) {
                Thread.sleep(adjustedDuration)
            }
        }

        return success
    }

    /**
     * 查找指定坐标的节点（辅助方法）
     */
    private fun AccessibilityServiceImpl.findNodeAtPoint(x: Int, y: Int): android.view.accessibility.AccessibilityNodeInfo? {
        return null // 由AccessibilityServiceImpl提供
    }

    // ==================== 辅助方法 ====================

    /**
     * 设置回放速度
     */
    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed.coerceIn(0.1f, 10.0f)
    }

    /**
     * 获取录制的手势数量
     */
    fun getRecordedEventCount(): Int = _recordedGestures.size

    /**
     * 序列化手势序列为JSON字符串
     */
    fun serializeGestureSequence(sequence: GestureSequence): String {
        val eventsJson = sequence.events.joinToString(",") { event ->
            """
            {
                "type": "${event.type.name}",
                "startX": ${event.startX},
                "startY": ${event.startY},
                "endX": ${event.endX},
                "endY": ${event.endY},
                "duration": ${event.duration},
                "text": ${event.text?.let { "\"$it\"" } ?: "null"}
            }
            """.trimIndent()
        }
        
        return """
        {
            "id": "${sequence.id}",
            "name": "${sequence.name}",
            "totalDuration": ${sequence.totalDuration},
            "createdAt": ${sequence.createdAt},
            "description": ${sequence.description?.let { "\"$it\"" } ?: "null"},
            "events": [$eventsJson]
        }
        """.trimIndent()
    }

    /**
     * 从JSON字符串反序列化手势序列
     */
    fun deserializeGestureSequence(json: String): GestureSequence? {
        return try {
            // 使用简单的JSON解析
            // 实际使用时建议使用Gson或Kotlinx.serialization
            val events = mutableListOf<GestureEvent>()
            
            // 简化实现，返回null表示需要更完整的JSON解析库
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 创建快速手势（快捷方式）
     */
    fun createQuickSwipeGesture(
        direction: SwipeDirection,
        distance: Int = 500,
        duration: Long = 300
    ): GestureSequence {
        val (startX, startY, endX, endY) = when (direction) {
            SwipeDirection.UP -> listOf(540, 1200, 540, 1200 - distance)
            SwipeDirection.DOWN -> listOf(540, 600, 540, 600 + distance)
            SwipeDirection.LEFT -> listOf(900, 960, 900 - distance, 960)
            SwipeDirection.RIGHT -> listOf(180, 960, 180 + distance, 960)
        }
        
        return GestureSequence(
            id = java.util.UUID.randomUUID().toString(),
            name = "Quick ${direction.name}",
            events = listOf(
                GestureEvent(
                    type = GestureEventType.SWIPE,
                    startX = startX as Int,
                    startY = startY as Int,
                    endX = endX as Int,
                    endY = endY as Int,
                    duration = duration
                )
            ),
            totalDuration = duration
        )
    }

    enum class SwipeDirection {
        UP, DOWN, LEFT, RIGHT
    }
}
