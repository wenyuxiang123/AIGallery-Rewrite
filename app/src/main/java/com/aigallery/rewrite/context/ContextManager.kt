package com.aigallery.rewrite.context

import com.aigallery.rewrite.domain.model.ChatMessage
import com.aigallery.rewrite.domain.model.MessageRole
import com.aigallery.rewrite.util.FileLogger

/**
 * Token budget-driven context manager
 * 
 * Replaces fixed MAX_HISTORY_TURNS with dynamic token-based selection.
 * Allocates budget across: system prompt > user message > memories > history.
 * 
 * Token estimation: ~4 chars = 1 token (Chinese), ~4 chars = 1 token (English)
 * Conservative estimate: 1 token per 2 Chinese chars, 1 token per 4 English chars
 */
class ContextManager(
    private val maxContextTokens: Int = 2048,
    private val reservedForOutput: Int = 512,
    private val memoryBudgetRatio: Float = 0.20f,
    private val maxMessageChars: Int = 300
) {
    companion object {
        private const val TAG = "ContextManager"
    }

    /**
     * Build context within token budget
     * 
     * @param systemPrompt GSSC-generated system prompt (must keep)
     * @param memories Relevant memory strings, ordered by relevance
     * @param history Conversation history, ordered chronologically
     * @param currentUserMessage Current user message (must keep)
     * @return Formatted prompt string within budget
     */
    fun buildContext(
        systemPrompt: String,
        memories: List<String>,
        history: List<ChatMessage>,
        currentUserMessage: String
    ): String {
        var budget = maxContextTokens - reservedForOutput
        
        // 1. System prompt (must keep)
        val systemTokens = estimateTokens(systemPrompt)
        budget -= systemTokens
        
        // 2. Current user message (must keep)
        val userTokens = estimateTokens(currentUserMessage)
        budget -= userTokens
        
        // 3. Memories (up to memoryBudgetRatio of remaining budget)
        val memoryBudget = (budget * memoryBudgetRatio).toInt().coerceAtLeast(0)
        val selectedMemories = selectMemoriesWithinBudget(memories, memoryBudget)
        val memoryTokens = estimateTokens(selectedMemories.joinToString("\n"))
        budget -= memoryTokens
        
        // 4. History (fill remaining budget, newest first then reverse)
        val selectedHistory = selectHistoryWithinBudget(history, budget.coerceAtLeast(0))
        
        FileLogger.d(TAG, "buildContext: sys=$systemTokens, usr=$userTokens, mem=$memoryTokens, hist=${estimateTokens(selectedHistory.joinToString { it.content })}, budget_left=$budget")
        
        return formatPrompt(systemPrompt, selectedMemories, selectedHistory, currentUserMessage)
    }

    /**
     * Estimate token count for a string
     * Conservative: CJK chars = 1 token each, ASCII = 1 token per 4 chars
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var tokens = 0
        for (char in text) {
            tokens += if (char.code > 0x4E00) 1 else 0  // CJK = 1 token
        }
        val asciiChars = text.count { it.code <= 0x4E00 }
        tokens += (asciiChars + 3) / 4  // ASCII = ~0.25 token per char
        return tokens
    }

    /**
     * Select memories within budget, most relevant first
     */
    private fun selectMemoriesWithinBudget(memories: List<String>, budget: Int): List<String> {
        if (budget <= 0 || memories.isEmpty()) return emptyList()
        
        val selected = mutableListOf<String>()
        var usedTokens = 0
        
        for (memory in memories) {
            val truncated = memory.take(200)
            val tokens = estimateTokens(truncated)
            if (usedTokens + tokens <= budget) {
                selected.add(truncated)
                usedTokens += tokens
            } else {
                break
            }
        }
        
        return selected
    }

    /**
     * Select history within budget, newest messages prioritized
     * Returns in chronological order (oldest first)
     */
    private fun selectHistoryWithinBudget(history: List<ChatMessage>, budget: Int): List<ChatMessage> {
        if (budget <= 0 || history.isEmpty()) return emptyList()
        
        val selected = mutableListOf<ChatMessage>()
        var usedTokens = 0
        
        // Iterate from newest to oldest
        for (msg in history.reversed()) {
            if (msg.isStreaming || msg.content.isBlank()) continue
            
            val role = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                else -> continue
            }
            val content = msg.content.take(maxMessageChars)
            val tokens = estimateTokens(content) + 2  // +2 for role tokens
            
            if (usedTokens + tokens <= budget) {
                selected.add(0, msg)  // Insert at front to maintain chronological order
                usedTokens += tokens
            } else {
                break  // Budget exhausted
            }
        }
        
        return selected
    }

    /**
     * Format selected context into MNN prompt format
     * Uses \u0001/\u0002 delimiters (same as existing buildPrompt)
     */
    private fun formatPrompt(
        systemPrompt: String,
        memories: List<String>,
        history: List<ChatMessage>,
        currentUserMessage: String
    ): String {
        val sb = StringBuilder()
        
        // System prompt (includes GSSC + memories)
        if (systemPrompt.isNotEmpty()) {
            sb.append("\u0001system\u0002$systemPrompt")
        }
        
        // Memories as additional context within system block
        if (memories.isNotEmpty()) {
            val memContent = memories.joinToString("\n") { "- $it" }
            if (systemPrompt.isNotEmpty()) {
                sb.append("\n\n【记忆】\n$memContent")
            } else {
                sb.append("\u0001system\u0002【记忆】\n$memContent")
            }
        }
        
        // History
        for (msg in history) {
            val role = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                else -> continue
            }
            sb.append("\u0001${role}\u0002${msg.content.take(maxMessageChars)}")
        }
        
        // Current user message
        sb.append("\u0001user\u0002$currentUserMessage")
        
        return sb.toString()
    }
}
