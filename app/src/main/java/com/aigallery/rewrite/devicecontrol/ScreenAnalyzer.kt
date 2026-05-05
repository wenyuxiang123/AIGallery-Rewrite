package com.aigallery.rewrite.devicecontrol

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 屏幕分析器 - 爱马仕级全操控
 * 
 * 功能：
 * - 将当前界面转为结构化文本供LLM理解
 * - 分析可点击元素
 * - 分析输入字段
 * - 生成UI元素描述
 */
class ScreenAnalyzer(
    private val accessibilityService: AccessibilityServiceImpl?
) {
    companion object {
        private const val TAG = "ScreenAnalyzer"
    }

    // ==================== 分析结果状态 ====================

    private val _lastAnalysis = MutableStateFlow<ScreenDescription?>(null)
    val lastAnalysis: StateFlow<ScreenDescription?> = _lastAnalysis.asStateFlow()

    // ==================== 屏幕描述数据类 ====================

    /**
     * 屏幕描述
     */
    data class ScreenDescription(
        val activityName: String,
        val packageName: String,
        val title: String?,
        val summary: String,
        val elements: List<UIElement>,
        val clickableElements: List<UIElement>,
        val inputFields: List<UIElement>,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * UI元素
     */
    data class UIElement(
        val id: String?,
        val text: String?,
        val description: String?,
        val className: String?,
        val bounds: Rect,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val isImportant: Boolean = true
    ) {
        /**
         * 获取元素的简洁描述
         */
        fun toShortDescription(): String {
            return text ?: description ?: "[$className]"
        }

        /**
         * 获取元素的详细描述
         */
        fun toDetailedDescription(): String {
            return buildString {
                append("[${className?.substringAfterLast('.') ?: "Unknown"}]")
                text?.let { append(" 文本: \"$it\"") }
                description?.let { append(" 描述: \"$it\"") }
                id?.let { append(" ID: $it") }
                if (isClickable) append(" [可点击]")
                if (isEditable) append(" [可编辑]")
                if (isScrollable) append(" [可滚动]")
                append(" 位置: (${bounds.left}, ${bounds.top})")
            }
        }
    }

    // ==================== 分析方法 ====================

    /**
     * 分析当前屏幕
     * 返回结构化的屏幕描述，供LLM理解界面
     */
    fun analyzeScreen(): ScreenDescription? {
        val rootNode = AccessibilityServiceImpl.rootNode.value ?: return null
        
        val activityName = AccessibilityServiceImpl.currentActivity.value ?: "Unknown"
        val packageName = rootNode.packageName?.toString() ?: "Unknown"
        
        val allElements = mutableListOf<UIElement>()
        val clickableElements = mutableListOf<UIElement>()
        val inputFields = mutableListOf<UIElement>()
        
        // 递归遍历节点树
        traverseNode(rootNode, allElements, clickableElements, inputFields)
        
        // 提取标题
        val title = extractTitle(allElements)
        
        // 生成摘要
        val summary = generateSummary(allElements, clickableElements, inputFields)
        
        val description = ScreenDescription(
            activityName = activityName,
            packageName = packageName,
            title = title,
            summary = summary,
            elements = allElements,
            clickableElements = clickableElements,
            inputFields = inputFields
        )
        
        _lastAnalysis.value = description
        return description
    }

    /**
     * 递归遍历节点收集信息
     */
    private fun traverseNode(
        node: AccessibilityNodeInfo?,
        allElements: MutableList<UIElement>,
        clickableElements: MutableList<UIElement>,
        inputFields: MutableList<UIElement>
    ) {
        if (node == null) return
        
        if (node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            
            val element = UIElement(
                id = node.viewIdResourceName,
                text = node.text?.toString(),
                description = node.contentDescription?.toString(),
                className = node.className?.toString(),
                bounds = bounds,
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isScrollable = node.isScrollable,
                isImportant = isImportantElement(node)
            )
            
            allElements.add(element)
            
            if (element.isClickable && element.isImportant) {
                clickableElements.add(element)
            }
            
            if (element.isEditable) {
                inputFields.add(element)
            }
        }
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                traverseNode(child, allElements, clickableElements, inputFields)
            }
        }
    }

    /**
     * 判断元素是否重要
     */
    private fun isImportantElement(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: return false
        
        // 重要组件类型
        val importantClasses = listOf(
            "android.widget.Button",
            "android.widget.TextView", // 有文本的TextView可能是按钮
            "android.widget.EditText",
            "android.widget.ImageButton",
            "android.widget.CheckBox",
            "android.widget.RadioButton",
            "android.widget.Switch",
            "android.widget.ImageView", // 可能是有功能的图标
            "android.view.View" // 通用视图
        )
        
        // 不重要的组件类型
        val ignoreClasses = listOf(
            "android.widget.ListView",
            "android.widget.RecyclerView", // 子元素更重要
            "android.widget.ScrollView",
            "android.view.ViewGroup"
        )
        
        return when {
            ignoreClasses.any { className.contains(it) } -> false
            importantClasses.any { className.contains(it) } -> true
            node.text?.isNotEmpty() == true -> true
            node.contentDescription?.isNotEmpty() == true -> true
            else -> node.isClickable || node.isEditable
        }
    }

    /**
     * 提取屏幕标题
     */
    private fun extractTitle(elements: List<UIElement>): String? {
        // 查找Toolbar、ActionBar等标题元素
        val titleCandidates = elements.filter { element ->
            val className = element.className ?: ""
            val text = element.text ?: ""
            
            (className.contains("TextView") && text.isNotEmpty()) &&
            (className.contains("Toolbar") || 
             className.contains("ActionBar") ||
             text.contains("标题") ||
             text.contains("Title"))
        }
        
        return titleCandidates.firstOrNull()?.text
    }

    /**
     * 生成屏幕摘要
     */
    private fun generateSummary(
        elements: List<UIElement>,
        clickableElements: List<UIElement>,
        inputFields: List<UIElement>
    ): String {
        return buildString {
            appendLine("=== 屏幕分析摘要 ===")
            appendLine("包含 ${elements.size} 个界面元素")
            appendLine("其中 ${clickableElements.size} 个可点击元素")
            appendLine("其中 ${inputFields.size} 个输入字段")
            
            if (clickableElements.isNotEmpty()) {
                appendLine("\n主要操作按钮:")
                clickableElements.take(10).forEachIndexed { index, element ->
                    appendLine("  ${index + 1}. ${element.toShortDescription()}")
                }
                if (clickableElements.size > 10) {
                    appendLine("  ... 还有 ${clickableElements.size - 10} 个")
                }
            }
            
            if (inputFields.isNotEmpty()) {
                appendLine("\n输入字段:")
                inputFields.take(5).forEachIndexed { index, element ->
                    appendLine("  ${index + 1}. ${element.toShortDescription()} (${element.id ?: "无ID"})")
                }
            }
        }
    }

    // ==================== 查找方法 ====================

    /**
     * 获取所有可点击元素
     */
    fun findClickableElements(): List<UIElement> {
        val analysis = analyzeScreen()
        return analysis?.clickableElements ?: emptyList()
    }

    /**
     * 获取所有输入字段
     */
    fun findInputFields(): List<UIElement> {
        val analysis = analyzeScreen()
        return analysis?.inputFields ?: emptyList()
    }

    /**
     * 根据文本查找元素
     */
    fun findElementByText(text: String, exactMatch: Boolean = false): UIElement? {
        val analysis = analyzeScreen() ?: return null
        
        return analysis.elements.find { element ->
            val elementText = element.text ?: element.description ?: return@find false
            if (exactMatch) {
                elementText == text
            } else {
                elementText.contains(text, ignoreCase = true)
            }
        }
    }

    /**
     * 根据ID查找元素
     */
    fun findElementById(id: String): UIElement? {
        val analysis = analyzeScreen() ?: return null
        
        return analysis.elements.find { element ->
            element.id == id || element.id?.endsWith(id) == true
        }
    }

    /**
     * 获取指定坐标的元素
     */
    fun findElementAtPoint(x: Int, y: Int): UIElement? {
        val analysis = analyzeScreen() ?: return null
        
        return analysis.elements.find { element ->
            element.bounds.contains(x, y)
        }
    }

    // ==================== 描述方法 ====================

    /**
     * 获取节点的详细描述字符串
     */
    fun getElementDescription(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        
        return buildString {
            appendLine("=== UI元素详情 ===")
            appendLine("类名: ${node.className}")
            appendLine("包名: ${node.packageName}")
            appendLine("ID: ${node.viewIdResourceName ?: "无"}")
            appendLine("文本: ${node.text ?: "无"}")
            appendLine("描述: ${node.contentDescription ?: "无"}")
            appendLine("状态: ${getStateDescription(node)}")
            appendLine("位置: (${bounds.left}, ${bounds.top}) - (${bounds.right}, ${bounds.bottom})")
            appendLine("尺寸: ${bounds.width()} x ${bounds.height()}")
        }
    }

    /**
     * 获取节点状态描述
     */
    private fun getStateDescription(node: AccessibilityNodeInfo): String {
        return buildList {
            if (node.isEnabled) add("启用")
            if (node.isFocused) add("聚焦")
            if (node.isSelected) add("选中")
            if (node.isChecked) add("选中")
            if (node.isClickable) add("可点击")
            if (node.isLongClickable) add("可长按")
            if (node.isScrollable) add("可滚动")
            if (node.isEditable) add("可编辑")
            if (node.isPassword) add("密码")
            if (node.isMultiLine) add("多行")
            if (node.isVisibleToUser) add("可见")
        }.joinToString(", ").ifEmpty { "无状态" }
    }

    /**
     * 生成LLM友好的界面描述
     */
    fun generateLLMDescription(): String {
        val analysis = analyzeScreen() ?: return "无法获取屏幕信息（无障碍服务可能未启用）"
        
        return buildString {
            appendLine("当前界面: ${analysis.title ?: analysis.activityName}")
            appendLine("应用: ${analysis.packageName}")
            appendLine()
            appendLine("## 可操作元素")
            
            analysis.clickableElements.forEachIndexed { index, element ->
                val position = getPositionHint(element.bounds)
                val action = inferAction(element)
                appendLine("${index + 1}. ${element.toShortDescription()}$position [$action]")
            }
            
            if (analysis.inputFields.isNotEmpty()) {
                appendLine()
                appendLine("## 输入字段")
                analysis.inputFields.forEachIndexed { index, element ->
                    appendLine("${index + 1}. ${element.id ?: "输入框"}")
                    element.text?.let { appendLine("   当前值: \"$it\"") }
                }
            }
            
            appendLine()
            appendLine("## 提示")
            appendLine("- 可以用\"点击[序号]\"或\"点击[文本]\"来执行点击操作")
            appendLine("- 可以用\"在[序号]输入[内容]\"来输入文本")
            appendLine("- 可以用\"向上/下滑动\"来滚动页面")
        }
    }

    /**
     * 获取位置提示
     */
    private fun getPositionHint(bounds: Rect): String {
        return when {
            bounds.top < 200 -> " [顶部]"
            bounds.bottom > 1500 -> " [底部]"
            else -> " [中部]"
        }
    }

    /**
     * 推断元素可能的操作
     */
    private fun inferAction(element: UIElement): String {
        val text = (element.text ?: element.description ?: "").lowercase()
        
        return when {
            element.isEditable -> "输入"
            text.contains("确认") || text.contains("确定") || text.contains("ok") || text.contains("提交") -> "确认"
            text.contains("取消") || text.contains("关闭") || text.contains("关闭") -> "取消"
            text.contains("返回") || text.contains("back") -> "返回"
            text.contains("设置") || text.contains("setting") -> "设置"
            text.contains("搜索") || text.contains("search") -> "搜索"
            text.contains("分享") || text.contains("share") -> "分享"
            else -> "点击"
        }
    }
}
