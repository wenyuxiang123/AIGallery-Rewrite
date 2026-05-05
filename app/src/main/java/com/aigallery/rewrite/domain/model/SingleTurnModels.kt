package com.aigallery.rewrite.domain.model

import java.util.UUID

/**
 * Single-turn task domain models
 */

/**
 * Single-turn task types
 */
enum class SingleTurnTaskType(
    val displayName: String,
    val description: String,
    val icon: String,
    val defaultPromptTemplate: String
) {
    WRITING(
        "写作助手",
        "帮助用户撰写文章、邮件、报告等",
        "edit_note",
        "请帮我撰写以下主题的内容：\n\n主题：{input}\n\n要求：\n1. 文章类型：{style}\n2. 篇幅：{length}\n3. 风格：{tone}"
    ),
    TRANSLATION(
        "翻译",
        "多语言翻译，支持中英日韩等",
        "translate",
        "请将以下内容翻译成{target_language}：\n\n{input}\n\n翻译要求：\n1. 保持原意准确\n2. 语言自然流畅\n3. 符合目标语言习惯"
    ),
    SUMMARY(
        "摘要",
        "快速提炼文章或文本的核心要点",
        "summarize",
        "请为以下内容生成简洁准确的摘要：\n\n{input}\n\n摘要要求：\n1. 保留核心信息\n2. 长度：{length}\n3. 格式：{format}"
    ),
    REWRITE(
        "改写",
        "同义改写、优化表达、改变风格",
        "refresh",
        "请对以下内容进行改写：\n\n{input}\n\n改写要求：\n1. 改变方式：{style}\n2. 目标受众：{audience}\n3. 保持原意"
    ),
    ANALYSIS(
        "分析",
        "深度分析数据、文本或问题",
        "analytics",
        "请分析以下内容：\n\n{input}\n\n分析维度：\n1. 主要发现：\n2. 关键洞察：\n3. 建议行动："
    ),
    BRAINSTORM(
        "头脑风暴",
        "快速生成创意想法和解决方案",
        "lightbulb",
        "请针对以下问题进行头脑风暴：\n\n问题：{input}\n\n要求：\n1. 生成{count}个想法\n2. 包括可行性和创新性评估\n3. 提供简要实施建议"
    )
}

/**
 * Single-turn task template
 */
data class TaskTemplate(
    val id: String = UUID.randomUUID().toString(),
    val type: SingleTurnTaskType,
    val name: String,
    val description: String,
    val promptTemplate: String,
    val parameters: List<TemplateParameter> = emptyList(),
    val isBuiltIn: Boolean = true,
    val isFavorite: Boolean = false,
    val usageCount: Int = 0
)

data class TemplateParameter(
    val name: String,
    val label: String,
    val type: ParameterType,
    val required: Boolean = true,
    val defaultValue: Any? = null,
    val options: List<String> = emptyList()
)

enum class ParameterType {
    TEXT,
    NUMBER,
    SELECT,
    MULTI_SELECT,
    SLIDER
}

/**
 * Task input parameters
 */
data class TaskInput(
    val template: TaskTemplate,
    val inputText: String,
    val parameters: Map<String, Any> = emptyMap()
)

/**
 * Single-turn task state
 */
data class SingleTurnState(
    val currentTask: TaskTemplate? = null,
    val inputText: String = "",
    val parameters: Map<String, Any> = emptyMap(),
    val outputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val history: List<TaskResult> = emptyList()
)

/**
 * Task result
 */
data class TaskResult(
    val id: String = UUID.randomUUID().toString(),
    val taskType: SingleTurnTaskType,
    val input: String,
    val output: String,
    val parameters: Map<String, Any>,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0
)
