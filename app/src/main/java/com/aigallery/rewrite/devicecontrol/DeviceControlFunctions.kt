package com.aigallery.rewrite.devicecontrol

/**
 * LLM Function Calling 集成 - 爱马仕级全操控
 * 
 * 将所有设备控制能力注册为LLM可调用的Function
 * LLM根据用户自然语言选择调用哪个Function
 * 参数自动解析，结果自动返回给LLM
 */
object DeviceControlFunctions {

    // ==================== 函数定义列表 ====================
    
    /**
     * 所有可用的设备控制函数
     * 每个函数都有唯一的名称、描述和参数定义
     */
    val deviceFunctions: List<FunctionDef> = listOf(
        // ========== 屏幕操作 ==========
        
        /**
         * 点击屏幕指定坐标
         * 参数: x - X坐标, y - Y坐标
         */
        FunctionDef(
            name = "click",
            description = "点击屏幕指定坐标位置",
            parameters = listOf(
                FunctionParameter("x", "INT", "X坐标像素值", required = true),
                FunctionParameter("y", "INT", "Y坐标像素值", required = true)
            ),
            category = FunctionCategory.SCREEN,
            dangerLevel = DangerLevel.LOW,
            example = "click(x=540, y=960) // 点击屏幕中心"
        ),
        
        /**
         * 点击包含指定文本的元素
         * 参数: text - 元素文本
         */
        FunctionDef(
            name = "click_by_text",
            description = "点击包含指定文本的界面元素",
            parameters = listOf(
                FunctionParameter("text", "STRING", "要点击的元素文本", required = true),
                FunctionParameter("exact_match", "BOOLEAN", "是否精确匹配", required = false, defaultValue = false)
            ),
            category = FunctionCategory.SCREEN,
            dangerLevel = DangerLevel.LOW,
            example = "click_by_text(text=\"确认\", exact_match=true)"
        ),
        
        /**
         * 在屏幕上滑动
         * 参数: direction - 方向(up/down/left/right), distance - 距离像素
         */
        FunctionDef(
            name = "swipe",
            description = "在屏幕上滑动一段距离",
            parameters = listOf(
                FunctionParameter("direction", "STRING", "滑动方向: up/down/left/right", required = true),
                FunctionParameter("distance", "INT", "滑动距离(像素)", required = false, defaultValue = 500)
            ),
            category = FunctionCategory.SCREEN,
            dangerLevel = DangerLevel.LOW,
            example = "swipe(direction=\"up\", distance=500) // 向上滑动"
        ),
        
        /**
         * 拖拽操作
         * 参数: startX, startY - 起点坐标, endX, endY - 终点坐标
         */
        FunctionDef(
            name = "drag",
            description = "从起点拖拽到终点",
            parameters = listOf(
                FunctionParameter("start_x", "INT", "起点X坐标", required = true),
                FunctionParameter("start_y", "INT", "起点Y坐标", required = true),
                FunctionParameter("end_x", "INT", "终点X坐标", required = true),
                FunctionParameter("end_y", "INT", "终点Y坐标", required = true)
            ),
            category = FunctionCategory.SCREEN,
            dangerLevel = DangerLevel.LOW,
            example = "drag(start_x=500, start_y=1000, end_x=500, end_y=500)"
        ),
        
        /**
         * 滚动页面
         * 参数: direction - 方向(forward/backward)
         */
        FunctionDef(
            name = "scroll",
            description = "滚动当前页面",
            parameters = listOf(
                FunctionParameter("direction", "STRING", "滚动方向: forward(向下)/backward(向上)", required = true)
            ),
            category = FunctionCategory.SCREEN,
            dangerLevel = DangerLevel.LOW,
            example = "scroll(direction=\"forward\")"
        ),
        
        /**
         * 输入文本
         * 参数: text - 要输入的文本
         */
        FunctionDef(
            name = "input_text",
            description = "在当前焦点输入框输入文本",
            parameters = listOf(
                FunctionParameter("text", "STRING", "要输入的文本内容", required = true)
            ),
            category = FunctionCategory.SCREEN,
            dangerLevel = DangerLevel.LOW,
            example = "input_text(text=\"你好世界\")"
        ),
        
        // ========== 系统按键 ==========
        
        /**
         * 按下系统按键
         * 参数: key - 按键名称(home/back/recent/power/notifications/quick_settings/screenshot)
         */
        FunctionDef(
            name = "press_key",
            description = "按下系统按键",
            parameters = listOf(
                FunctionParameter("key", "STRING", "按键名称: home/back/recent/power/notifications/quick_settings/screenshot", required = true)
            ),
            category = FunctionCategory.SYSTEM,
            dangerLevel = DangerLevel.LOW,
            example = "press_key(key=\"home\") // 按Home键"
        ),
        
        // ========== 连接控制 ==========
        
        /**
         * 开关WiFi
         * 参数: enabled - true打开/false关闭
         */
        FunctionDef(
            name = "set_wifi",
            description = "打开或关闭WiFi",
            parameters = listOf(
                FunctionParameter("enabled", "BOOLEAN", "true打开WiFi, false关闭WiFi", required = true)
            ),
            category = FunctionCategory.CONNECTIVITY,
            dangerLevel = DangerLevel.MEDIUM,
            requiresConfirmation = true,
            example = "set_wifi(enabled=true)"
        ),
        
        /**
         * 开关蓝牙
         * 参数: enabled - true打开/false关闭
         */
        FunctionDef(
            name = "set_bluetooth",
            description = "打开或关闭蓝牙",
            parameters = listOf(
                FunctionParameter("enabled", "BOOLEAN", "true打开蓝牙, false关闭蓝牙", required = true)
            ),
            category = FunctionCategory.CONNECTIVITY,
            dangerLevel = DangerLevel.MEDIUM,
            requiresConfirmation = true,
            example = "set_bluetooth(enabled=false)"
        ),
        
        // ========== 设备控制 ==========
        
        /**
         * 设置音量
         * 参数: level - 音量级别(0-100)
         */
        FunctionDef(
            name = "set_volume",
            description = "设置媒体音量大小",
            parameters = listOf(
                FunctionParameter("level", "INT", "音量级别 0-100", required = true)
            ),
            category = FunctionCategory.DEVICE,
            dangerLevel = DangerLevel.LOW,
            example = "set_volume(level=50) // 设置音量50%"
        ),
        
        /**
         * 设置亮度
         * 参数: level - 亮度级别(0.0-1.0)
         */
        FunctionDef(
            name = "set_brightness",
            description = "设置屏幕亮度",
            parameters = listOf(
                FunctionParameter("level", "FLOAT", "亮度级别 0.0-1.0", required = true)
            ),
            category = FunctionCategory.DEVICE,
            dangerLevel = DangerLevel.LOW,
            example = "set_brightness(level=0.7) // 设置亮度70%"
        ),
        
        /**
         * 开关手电筒
         * 参数: enabled - true打开/false关闭
         */
        FunctionDef(
            name = "toggle_flashlight",
            description = "打开或关闭手机手电筒",
            parameters = listOf(
                FunctionParameter("enabled", "BOOLEAN", "true打开手电筒, false关闭手电筒", required = true)
            ),
            category = FunctionCategory.DEVICE,
            dangerLevel = DangerLevel.LOW,
            example = "toggle_flashlight(enabled=true)"
        ),
        
        /**
         * 震动
         * 参数: duration - 震动时长毫秒
         */
        FunctionDef(
            name = "vibrate",
            description = "让手机震动",
            parameters = listOf(
                FunctionParameter("duration", "INT", "震动时长(毫秒)", required = false, defaultValue = 500)
            ),
            category = FunctionCategory.DEVICE,
            dangerLevel = DangerLevel.LOW,
            example = "vibrate(duration=300)"
        ),
        
        // ========== 应用管理 ==========
        
        /**
         * 启动应用
         * 参数: app_name - 应用名称或包名
         */
        FunctionDef(
            name = "launch_app",
            description = "打开指定应用",
            parameters = listOf(
                FunctionParameter("app_name", "STRING", "应用名称或包名", required = true)
            ),
            category = FunctionCategory.APP,
            dangerLevel = DangerLevel.LOW,
            example = "launch_app(app_name=\"微信\")"
        ),
        
        /**
         * 关闭应用
         * 参数: app_name - 应用名称或包名
         */
        FunctionDef(
            name = "force_stop_app",
            description = "强制关闭指定应用",
            parameters = listOf(
                FunctionParameter("app_name", "STRING", "应用名称或包名", required = true)
            ),
            category = FunctionCategory.APP,
            dangerLevel = DangerLevel.HIGH,
            requiresConfirmation = true,
            example = "force_stop_app(app_name=\"微信\")"
        ),
        
        // ========== 通讯 ==========
        
        /**
         * 拨打电话
         * 参数: number - 电话号码
         */
        FunctionDef(
            name = "make_call",
            description = "拨打电话给指定号码",
            parameters = listOf(
                FunctionParameter("number", "STRING", "电话号码", required = true)
            ),
            category = FunctionCategory.COMMUNICATION,
            dangerLevel = DangerLevel.HIGH,
            requiresConfirmation = true,
            confirmationMessage = "即将拨打电话，是否继续？",
            example = "make_call(number=\"13800138000\")"
        ),
        
        /**
         * 发送短信
         * 参数: number - 电话号码, text - 短信内容
         */
        FunctionDef(
            name = "send_sms",
            description = "发送短信给指定号码",
            parameters = listOf(
                FunctionParameter("number", "STRING", "收件人电话号码", required = true),
                FunctionParameter("text", "STRING", "短信内容", required = true)
            ),
            category = FunctionCategory.COMMUNICATION,
            dangerLevel = DangerLevel.HIGH,
            requiresConfirmation = true,
            confirmationMessage = "即将发送短信给指定号码，是否继续？",
            example = "send_sms(number=\"13800138000\", text=\"测试短信\")"
        ),
        
        // ========== 剪贴板 ==========
        
        /**
         * 复制文本到剪贴板
         * 参数: text - 要复制的文本
         */
        FunctionDef(
            name = "set_clipboard",
            description = "复制指定文本到剪贴板",
            parameters = listOf(
                FunctionParameter("text", "STRING", "要复制的文本内容", required = true)
            ),
            category = FunctionCategory.SYSTEM,
            dangerLevel = DangerLevel.LOW,
            example = "set_clipboard(text=\"要复制的文本\")"
        ),
        
        /**
         * 获取剪贴板内容
         */
        FunctionDef(
            name = "get_clipboard",
            description = "获取剪贴板当前内容",
            parameters = emptyList(),
            category = FunctionCategory.SYSTEM,
            dangerLevel = DangerLevel.LOW,
            example = "get_clipboard()"
        ),
        
        // ========== 屏幕分析 ==========
        
        /**
         * 分析当前屏幕内容
         */
        FunctionDef(
            name = "analyze_screen",
            description = "分析当前屏幕界面，返回所有可操作元素列表",
            parameters = emptyList(),
            category = FunctionCategory.INFO,
            dangerLevel = DangerLevel.NONE,
            example = "analyze_screen()"
        ),
        
        /**
         * 查找界面元素
         * 参数: text - 要查找的文本
         */
        FunctionDef(
            name = "find_element",
            description = "在当前屏幕查找包含指定文本的元素",
            parameters = listOf(
                FunctionParameter("text", "STRING", "要查找的元素文本", required = true),
                FunctionParameter("exact_match", "BOOLEAN", "是否精确匹配", required = false, defaultValue = false)
            ),
            category = FunctionCategory.INFO,
            dangerLevel = DangerLevel.NONE,
            example = "find_element(text=\"设置\")"
        ),
        
        /**
         * 获取当前Activity信息
         */
        FunctionDef(
            name = "get_current_activity",
            description = "获取当前界面的Activity名称和包名",
            parameters = emptyList(),
            category = FunctionCategory.INFO,
            dangerLevel = DangerLevel.NONE,
            example = "get_current_activity()"
        ),
        
        /**
         * 截取当前屏幕
         */
        FunctionDef(
            name = "screenshot",
            description = "截取当前屏幕截图",
            parameters = emptyList(),
            category = FunctionCategory.INFO,
            dangerLevel = DangerLevel.LOW,
            example = "screenshot()"
        ),
        
        // ========== Shell命令 ==========
        
        /**
         * 执行Shell命令
         * 警告：需要root权限，危险操作
         */
        FunctionDef(
            name = "execute_shell",
            description = "执行Shell命令（需要root权限）",
            parameters = listOf(
                FunctionParameter("command", "STRING", "要执行的Shell命令", required = true)
            ),
            category = FunctionCategory.ADVANCED,
            dangerLevel = DangerLevel.EXTREME,
            requiresConfirmation = true,
            confirmationMessage = "即将执行Shell命令，这是危险操作，是否继续？",
            example = "execute_shell(command=\"ls -la /data/\")"
        )
    )

    // ==================== 函数分类 ====================

    enum class FunctionCategory(val displayName: String) {
        SCREEN("屏幕操作"),
        SYSTEM("系统控制"),
        CONNECTIVITY("连接设置"),
        DEVICE("设备控制"),
        APP("应用管理"),
        COMMUNICATION("通讯功能"),
        INFO("信息查询"),
        ADVANCED("高级功能")
    }

    enum class DangerLevel(val displayName: String) {
        NONE("无风险"),
        LOW("低风险"),
        MEDIUM("中等风险"),
        HIGH("高风险"),
        EXTREME("极高风险")
    }

    // ==================== 数据类 ====================

    /**
     * 函数定义
     */
    data class FunctionDef(
        val name: String,
        val description: String,
        val parameters: List<FunctionParameter>,
        val category: FunctionCategory,
        val dangerLevel: DangerLevel = DangerLevel.LOW,
        val requiresConfirmation: Boolean = false,
        val confirmationMessage: String? = null,
        val example: String? = null
    ) {
        /**
         * 转换为OpenAI Function格式
         */
        fun toOpenAIFunction(): Map<String, Any> {
            return mapOf(
                "name" to name,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to parameters.associate { param ->
                        param.name to mapOf(
                            "type" to param.type.lowercase(),
                            "description" to param.description
                        )
                    },
                    "required" to parameters.filter { it.required }.map { it.name }
                )
            )
        }
    }

    /**
     * 函数参数定义
     */
    data class FunctionParameter(
        val name: String,
        val type: String,
        val description: String,
        val required: Boolean = false,
        val defaultValue: Any? = null,
        val enumValues: List<String>? = null
    )

    /**
     * 函数调用请求
     */
    data class FunctionCallRequest(
        val functionName: String,
        val arguments: Map<String, Any>
    )

    /**
     * 函数调用结果
     */
    data class FunctionCallResult(
        val functionName: String,
        val success: Boolean,
        val result: Any?,
        val error: String? = null
    )

    // ==================== 工具方法 ====================

    /**
     * 根据名称查找函数定义
     */
    fun findFunction(name: String): FunctionDef? {
        return deviceFunctions.find { it.name == name }
    }

    /**
     * 获取指定分类的所有函数
     */
    fun getFunctionsByCategory(category: FunctionCategory): List<FunctionDef> {
        return deviceFunctions.filter { it.category == category }
    }

    /**
     * 获取需要确认的高风险函数
     */
    fun getDangerousFunctions(): List<FunctionDef> {
        return deviceFunctions.filter { it.dangerLevel >= DangerLevel.HIGH }
    }

    /**
     * 生成函数调用提示文本
     */
    fun generateFunctionHint(): String {
        return buildString {
            appendLine("## 可用的设备控制函数")
            appendLine()
            
            FunctionCategory.entries.forEach { category ->
                val functions = getFunctionsByCategory(category)
                if (functions.isNotEmpty()) {
                    appendLine("### ${category.displayName}")
                    functions.forEach { func ->
                        val riskIndicator = when (func.dangerLevel) {
                            DangerLevel.NONE -> "✅"
                            DangerLevel.LOW -> "⚪"
                            DangerLevel.MEDIUM -> "⚠️"
                            DangerLevel.HIGH -> "🔴"
                            DangerLevel.EXTREME -> "☠️"
                        }
                        appendLine("- $riskIndicator **${func.name}**: ${func.description}")
                        func.parameters.takeIf { it.isNotEmpty() }?.let { params ->
                            params.forEach { param ->
                                val required = if (param.required) "(必填)" else "(可选)"
                                appendLine("  - ${param.name}: ${param.description} $required")
                            }
                        }
                    }
                    appendLine()
                }
            }
        }
    }
}
