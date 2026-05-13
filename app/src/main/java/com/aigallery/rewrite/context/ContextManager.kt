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
     * Format selected context into ChatML prompt format for Qwen2.5
     * Uses <|im_start|>/<|im_end|> delimiters that C++ layer converts to token IDs 151644/151645
     */
    private fun formatPrompt(
        systemPrompt: String,
        memories: List<String>,
        history: List<ChatMessage>,
        currentUserMessage: String
    ): String {
        val sb = StringBuilder()
        
        // ChatML format for Qwen2.5: <|im_start|>role\ncontent<|im_end|>\n
        // C++ layer will convert <|im_start|>→151644, <|im_end|>→151645
        
        // System prompt (includes GSSC)
        val systemContent = buildString {
            append(systemPrompt)
            if (memories.isNotEmpty()) {
                val memContent = memories.joinToString("\n") { "- $it" }
                if (systemPrompt.isNotEmpty()) {
                    append("\n\n【记忆】\n$memContent")
                } else {
                    append("【记忆】\n$memContent")
                }
            }
        }
        
        if (systemContent.isNotEmpty()) {
            sb.append("<|im_start|>system\n$systemContent<|im_end|>\n")
        }
        
        // History
        for (msg in history) {
            val role = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                else -> continue
            }
            sb.append("<|im_start|>$role\n${msg.content.take(maxMessageChars)}<|im_end|>\n")
        }
        
        // Current user message
        sb.append("<|im_start|>user\n$currentUserMessage<|im_end|>\n")
        
        // Assistant prompt (beginning of response)
        sb.append("<|im_start|>assistant\n")
        
        return sb.toString()
    }

    /**
     * Build chat history as JSON array for MNN response(ChatMessages) API
     * Returns: [{"role":"system","content":"..."},{"role":"user","content":"..."},...]
     * 
     * This replaces the ChatML text format with structured role-content pairs,
     * letting MNN's internal apply_chat_template handle formatting and tokenization.
     * This is the same approach as MNN's official MnnLlmChat demo.
     */
    fun buildChatHistoryJson(
        systemPrompt: String,
        memories: List<String>,
        history: List<ChatMessage>,
        currentUserMessage: String
    ): String {
        val entries = StringBuilder()
        entries.append("[")
        
        var first = true
        
        // System prompt (includes GSSC + memories)
        val systemContent = buildString {
            append(systemPrompt)
            if (memories.isNotEmpty()) {
                val memContent = memories.joinToString("\n") { "- $it" }
                if (systemPrompt.isNotEmpty()) {
                    append("\n\n【记忆】\n$memContent")
                } else {
                    append("【记忆】\n$memContent")
                }
            }
        }
        
        if (systemContent.isNotEmpty()) {
            entries.append("{\"role\":\"system\",\"content\":")
            entries.append(jsonEscape(systemContent))
            entries.append("}")
            first = false
        }
        
        // History messages
        for (msg in history) {
            val role = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                else -> continue
            }
            if (!first) entries.append(",")
            entries.append("{\"role\":\"$role\",\"content\":")
            entries.append(jsonEscape(msg.content.take(maxMessageChars)))
            entries.append("}")
            first = false
        }
        
        // Current user message
        if (!first) entries.append(",")
        entries.append("{\"role\":\"user\",\"content\":")
        entries.append(jsonEscape(currentUserMessage))
        entries.append("}")
        
        entries.append("]")
        return entries.toString()
    }
    
    /**
     * Escape a string for JSON value (adds surrounding quotes)
     */
    private fun jsonEscape(s: String): String {
        val escaped = s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

}