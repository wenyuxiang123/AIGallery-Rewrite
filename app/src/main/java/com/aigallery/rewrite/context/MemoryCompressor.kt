package com.aigallery.rewrite.context

import com.aigallery.rewrite.data.local.entity.ChatMessageEntity
import com.aigallery.rewrite.domain.model.ChatMessage
import com.aigallery.rewrite.domain.model.MessageRole
import com.aigallery.rewrite.inference.InferenceConfig
import com.aigallery.rewrite.inference.InferenceEngine
import com.aigallery.rewrite.util.FileLogger
import kotlinx.coroutines.withTimeoutOrNull

/**
 * LLM-based conversation summary compressor
 * 
 * Compresses long conversation history into concise summaries,
 * preserving key facts, user preferences, and unresolved questions.
 * Triggered when token count exceeds threshold.
 */
class MemoryCompressor(
    private val inferenceEngine: InferenceEngine,
    private val maxSummaryTokens: Int = 150,
    private val compressTimeoutMs: Long = 30_000L
) {
    companion object {
        private const val TAG = "MemoryCompressor"
        private const val COMPRESS_PROMPT = "请用2-3句话总结以下对话的关键信息。保留：用户偏好、重要事实、待办事项、未解决的问题。只输出摘要，不要其他内容。"
    }

    /**
     * Compress a list of messages into a summary string
     * 
     * @param messages Messages to compress (usually older messages beyond context window)
     * @return Summary string, or empty string if compression fails
     */
    suspend fun compress(messages: List<ChatMessage>): String {
        if (messages.isEmpty()) return ""
        if (messages.size <= 2) {
            // Too few messages to meaningfully compress, just concatenate
            return messages.joinToString(" ") { it.content.take(100) }
        }
        
        // Build conversation text for compression
        val convText = messages.joinToString("\n") { msg ->
            val role = when (msg.role) {
                MessageRole.USER -> "用户"
                MessageRole.ASSISTANT -> "助手"
                else -> ""
            }
            "$role: ${msg.content.take(150)}"
        }
        
        // Use the inference engine to generate summary
        // Format as a simple prompt (not ChatML, to minimize token waste)
        val prompt = "$COMPRESS_PROMPT\n\n$convText"
        
        return try {
            val result = withTimeoutOrNull(compressTimeoutMs) {
                inferenceEngine.infer(prompt, InferenceConfig(
                    maxLength = maxSummaryTokens,
                    temperature = 0.3f,  // Low temperature for factual summary
                    topK = 10,
                    topP = 0.9f
                ))
            }
            
            val summary = result?.text?.trim() ?: ""
            if (summary.isNotBlank()) {
                FileLogger.d(TAG, "compress: generated summary (${summary.length} chars) from ${messages.size} messages")
                summary
            } else {
                FileLogger.w(TAG, "compress: inference returned empty, falling back to extraction")
                extractKeyFacts(messages)
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "compress: failed", e)
            extractKeyFacts(messages)
        }
    }

    /**
     * Fallback: Extract key facts without LLM inference
     * Takes first N chars of each message as a crude summary
     */
    private fun extractKeyFacts(messages: List<ChatMessage>): String {
        return messages
            .filter { it.role == MessageRole.USER && it.content.isNotBlank() }
            .take(3)
            .joinToString("; ") { it.content.take(50) }
    }

    /**
     * Check if messages need compression based on token budget
     * 
     * @param messages Current message list
     * @param maxTokens Maximum token budget for history
     * @return true if compression would be beneficial
     */
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
