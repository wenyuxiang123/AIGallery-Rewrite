package com.aigallery.rewrite.context

import com.aigallery.rewrite.data.local.entity.ChatMessageEntity
import com.aigallery.rewrite.domain.model.ChatMessage
import com.aigallery.rewrite.domain.model.MessageRole
import com.aigallery.rewrite.inference.InferenceConfig
import com.aigallery.rewrite.inference.InferenceEngine
import com.aigallery.rewrite.util.FileLogger
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Conversation summary compressor with LLM + heuristic fallback
 * 
 * Strategy:
 * 1. Try LLM compression (works well on 3B+ models)
 * 2. Fallback: keyword extraction + structured summary
 * 3. Last resort: simple truncation
 */
class MemoryCompressor(
    private val inferenceEngine: InferenceEngine,
    private val maxSummaryTokens: Int = 150,
    private val compressTimeoutMs: Long = 30_000L
) {
    companion object {
        private const val TAG = "MemoryCompressor"
        
        // Simplified prompt for small models (< 3B)
        private const val COMPRESS_PROMPT_SMALL = "总结要点：\n"
        
        // Detailed prompt for larger models (3B+)
        private const val COMPRESS_PROMPT_LARGE = "请用2-3句话总结以下对话的关键信息。保留：用户偏好、重要事实、待办事项、未解决的问题。只输出摘要，不要其他内容。\n\n"
    }

    /**
     * Compress a list of messages into a summary string
     */
    suspend fun compress(messages: List<ChatMessage>): String {
        if (messages.isEmpty()) return ""
        if (messages.size <= 2) {
            return extractKeyFacts(messages)
        }
        
        // Build conversation text
        val convText = messages.joinToString("\n") { msg ->
            val role = when (msg.role) {
                MessageRole.USER -> "用户"
                MessageRole.ASSISTANT -> "助手"
                else -> ""
            }
            "$role: ${msg.content.take(150)}"
        }
        
        // Try LLM compression
        val prompt = COMPRESS_PROMPT_SMALL + convText.take(800)
        
        return try {
            val result = withTimeoutOrNull(compressTimeoutMs) {
                inferenceEngine.infer(prompt, InferenceConfig(
                    maxLength = maxSummaryTokens,
                    temperature = 0.3f,
                    topK = 10,
                    topP = 0.9f
                ))
            }
            
            val summary = result?.text?.trim() ?: ""
            if (summary.isNotBlank() && summary.length > 10 && !isGarbled(summary)) {
                FileLogger.d(TAG, "compress: LLM summary (${summary.length} chars) from ${messages.size} messages")
                summary
            } else {
                FileLogger.w(TAG, "compress: LLM output poor, using keyword extraction")
                extractKeywordsAndFacts(messages)
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "compress: failed", e)
            extractKeywordsAndFacts(messages)
        }
    }

    /**
     * Improved fallback: keyword extraction + structured summary
     * Much better than just taking first 50 chars of 3 messages
     */
    private fun extractKeywordsAndFacts(messages: List<ChatMessage>): String {
        val userMessages = messages.filter { it.role == MessageRole.USER && it.content.isNotBlank() }
        val assistantMessages = messages.filter { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }
        
        val parts = mutableListOf<String>()
        
        // Extract key user intents (first sentence of each user message, up to 5)
        userMessages.take(5).forEach { msg ->
            val firstSentence = msg.content
                .split(Regex("[。！？.!?\n]"))
                .firstOrNull { it.isNotBlank() }
                ?.trim()?.take(60)
            if (firstSentence != null) {
                parts.add("用户说: $firstSentence")
            }
        }
        
        // Extract key assistant responses (first sentence, up to 3)
        assistantMessages.take(3).forEach { msg ->
            val firstSentence = msg.content
                .split(Regex("[。！？.!?\n]"))
                .firstOrNull { it.isNotBlank() }
                ?.trim()?.take(80)
            if (firstSentence != null) {
                parts.add("助手答: $firstSentence")
            }
        }
        
        val summary = parts.joinToString("; ")
        FileLogger.d(TAG, "extractKeywordsAndFacts: ${summary.length} chars from ${messages.size} messages")
        return summary.ifBlank { extractKeyFacts(messages) }
    }

    /**
     * Last resort: simple truncation
     */
    private fun extractKeyFacts(messages: List<ChatMessage>): String {
        return messages
            .filter { it.role == MessageRole.USER && it.content.isNotBlank() }
            .take(3)
            .joinToString("; ") { it.content.take(50) }
    }

    /**
     * Check if text is garbled (common with small models)
     */
    private fun isGarbled(text: String): Boolean {
        if (text.length < 5) return true
        // Check for excessive repetition
        val words = text.split(Regex("\\s+"))
        if (words.size > 4) {
            val uniqueRatio = words.toSet().size.toFloat() / words.size
            if (uniqueRatio < 0.3f) return true
        }
        // Check for very long non-space sequences (likely garbled)
        var consecutiveNonSpace = 0
        for (c in text) {
            if (c.isWhitespace()) {
                if (consecutiveNonSpace > 80) return true
                consecutiveNonSpace = 0
            } else {
                consecutiveNonSpace++
            }
        }
        return consecutiveNonSpace > 80
    }

    fun needsCompression(messages: List<ChatMessage>, maxTokens: Int): Boolean {
        val totalTokens = messages.sumOf { estimateTokens(it.content) }
        return totalTokens > maxTokens
    }

    private fun estimateTokens(text: String): Int {
        var tokens = 0
        for (char in text) {
            tokens += if (char.code > 0x4E00) 1 else 0
        }
        val asciiChars = text.count { it.code <= 0x4E00 }
        tokens += (asciiChars + 3) / 4
        return tokens
    }
}
