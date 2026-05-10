package com.aigallery.rewrite.context

import com.aigallery.rewrite.tool.ToolRegistry
import com.aigallery.rewrite.util.FileLogger

/**
 * GSSC (Goal-Situation-Support-Constraint) Dynamic System Prompt Builder
 * 
 * Instead of a static system prompt, dynamically constructs the system prompt
 * based on the current conversation context:
 * - Goal: What the user is trying to accomplish
 * - Situation: Current conversation state and context
 * - Support: Available tools and relevant memories
 * - Constraint: Model capabilities and output format requirements
 */
class GSSCPromptBuilder(
    private val toolRegistry: ToolRegistry
) {
    companion object {
        private const val TAG = "GSSCPromptBuilder"
        private const val MAX_GSSC_LENGTH = 400  // Keep system prompt compact for small models
    }

    /**
     * Build a dynamic system prompt based on GSSC framework
     * 
     * @param userMessage Current user message (for goal inference)
     * @param sessionMemories Relevant memories for this session
     * @param hasToolHistory Whether tools have been used in this conversation
     * @param modelTier Current model tier (affects constraint wording)
     * @return Formatted GSSC system prompt
     */
    fun buildPrompt(
        userMessage: String,
        sessionMemories: List<String> = emptyList(),
        hasToolHistory: Boolean = false,
        modelTier: String = "daily"
    ): String {
        val sb = StringBuilder()
        
        // Goal: Infer from user message
        val goal = inferGoal(userMessage)
        sb.append("目标: $goal")
        
        // Situation: Brief context from memories
        if (sessionMemories.isNotEmpty()) {
            val situation = sessionMemories.take(2).joinToString("; ") { it.take(60) }
            if (situation.isNotBlank()) {
                sb.append("\n背景: $situation")
            }
        }
        
        // Support: Available tools
        val availableTools = toolRegistry.getAvailableToolNames()
        if (availableTools.isNotEmpty()) {
            val toolList = availableTools.take(5).joinToString(", ")
            sb.append("\n可用工具: $toolList")
        }
        
        // Constraint: Based on model tier
        val constraint = getConstraintForTier(modelTier)
        sb.append("\n$constraint")
        
        // Truncate to max length (small models have limited context)
        val result = sb.toString().take(MAX_GSSC_LENGTH)
        FileLogger.d(TAG, "buildPrompt: GSSC prompt length=${result.length}")
        return result
    }

    /**
     * Infer the user's goal from their message
     */
    private fun inferGoal(userMessage: String): String {
        val msg = userMessage.trim().lowercase()
        return when {
            msg.contains("计算") || msg.contains("算") || msg.contains("多少") -> "数学计算"
            msg.contains("时间") || msg.contains("日期") || msg.contains("几点") || msg.contains("今天") -> "查询时间信息"
            msg.contains("搜索") || msg.contains("查找") || msg.contains("搜") -> "搜索信息"
            msg.contains("翻译") || msg.contains("translate") -> "翻译文本"
            msg.contains("写") || msg.contains("作文") || msg.contains("文章") -> "文本创作"
            msg.contains("解释") || msg.contains("什么是") || msg.contains("为什么") -> "知识解答"
            else -> "回答用户问题"
        }
    }

    /**
     * Get constraint text based on model tier
     */
    private fun getConstraintForTier(tier: String): String {
        return when (tier) {
            "speed" -> "简洁回答，不超过50字"
            "daily" -> "简明回答，重点突出"
            "recommended" -> "详细回答，可使用工具辅助"
            "quality" -> "全面深入回答"
            "maximum" -> "深度分析，结构清晰"
            else -> "简明回答"
        }
    }
}
