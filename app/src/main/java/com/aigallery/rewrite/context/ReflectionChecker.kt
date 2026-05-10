package com.aigallery.rewrite.context

import com.aigallery.rewrite.domain.model.ChatMessage
import com.aigallery.rewrite.domain.model.MessageRole
import com.aigallery.rewrite.util.FileLogger

/**
 * Reflection self-correction mechanism
 * 
 * Checks model output quality and triggers retry if needed.
 * Lightweight approach: heuristic-based quality scoring without
 * requiring an additional LLM call for verification.
 * 
 * Quality signals:
 * - Repetition detection (same phrase repeated 3+ times)
 * - Incomplete output (ends mid-sentence)
 * - Gibberish detection (high ratio of non-CJK/non-alpha chars)
 * - Too short for a meaningful answer
 */
class ReflectionChecker(
    private val minResponseLength: Int = 5,
    private val maxRepetitionRatio: Float = 0.4f,
    private val maxRetryAttempts: Int = 2
) {
    companion object {
        private const val TAG = "ReflectionChecker"
    }

    /**
     * Quality check result
     */
    data class ReflectionResult(
        val isAcceptable: Boolean,
        val qualityScore: Float,       // 0.0 - 1.0
        val issues: List<QualityIssue>,
        val shouldRetry: Boolean,
        val retryPrompt: String? = null
    )

    enum class QualityIssue {
        TOO_SHORT,
        REPETITIVE,
        INCOMPLETE,
        GIBBERISH,
        OFF_TOPIC
    }

    /**
     * Check output quality
     */
    fun check(
        output: String,
        userMessage: String,
        retryCount: Int = 0
    ): ReflectionResult {
        val issues = mutableListOf<QualityIssue>()
        var score = 1.0f

        // 1. Too short check
        if (output.length < minResponseLength) {
            issues.add(QualityIssue.TOO_SHORT)
            score -= 0.4f
        }

        // 2. Repetition check
        val repetitionRatio = calculateRepetitionRatio(output)
        if (repetitionRatio > maxRepetitionRatio) {
            issues.add(QualityIssue.REPETITIVE)
            score -= 0.3f
        }

        // 3. Incomplete output check
        if (isIncomplete(output)) {
            issues.add(QualityIssue.INCOMPLETE)
            score -= 0.2f
        }

        // 4. Gibberish check
        if (isGibberish(output)) {
            issues.add(QualityIssue.GIBBERISH)
            score -= 0.5f
        }

        score = score.coerceIn(0f, 1f)
        val isAcceptable = score >= 0.5f
        val shouldRetry = !isAcceptable && retryCount < maxRetryAttempts

        val retryPrompt = if (shouldRetry) {
            buildRetryPrompt(issues, userMessage)
        } else null

        FileLogger.d(TAG, "check: score=$score, issues=${issues.size}, acceptable=$isAcceptable, retry=$shouldRetry")
        
        return ReflectionResult(
            isAcceptable = isAcceptable,
            qualityScore = score,
            issues = issues,
            shouldRetry = shouldRetry,
            retryPrompt = retryPrompt
        )
    }

    /**
     * Calculate how repetitive the output is
     * Checks for repeated phrases (3+ word sequences appearing multiple times)
     */
    private fun calculateRepetitionRatio(text: String): Float {
        if (text.length < 20) return 0f
        
        val words = text.chunked(4)  // Split into 4-char chunks (roughly 2 Chinese words)
        if (words.size < 5) return 0f
        
        val totalChunks = words.size.toFloat()
        val uniqueChunks = words.distinct().size.toFloat()
        
        return 1f - (uniqueChunks / totalChunks)
    }

    /**
     * Check if output appears incomplete
     */
    private fun isIncomplete(text: String): Boolean {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return true
        
        // Ends with common incomplete indicators
        val incompleteEndings = listOf(
            ",", "，", "、", "的", "了", "是", "在", "和",
            "because", "since", "however", "but", "and", "or"
        )
        
        return incompleteEndings.any { trimmed.endsWith(it) }
    }

    /**
     * Check for gibberish output
     * High ratio of unusual characters suggests generation issues
     */
    private fun isGibberish(text: String): Boolean {
        if (text.isEmpty()) return true
        
        var normalChars = 0
        var unusualChars = 0
        
        for (char in text) {
            val code = char.code
            when {
                // CJK Unified Ideographs
                code in 0x4E00..0x9FFF -> normalChars++
                // ASCII letters and digits
                code in 0x30..0x39 || code in 0x41..0x5A || code in 0x61..0x7A -> normalChars++
                // Common punctuation
                code in 0x2000..0x206F || code in 0x3000..0x303F || code in 0xFF00..0xFFEF -> normalChars++
                // Space, newline, tab
                code == 32 || code == 10 || code == 13 || code == 9 -> normalChars++
                else -> unusualChars++
            }
        }
        
        val total = normalChars + unusualChars
        return if (total > 0) (unusualChars.toFloat() / total) > 0.3f else true
    }

    /**
     * Build a retry prompt that addresses detected quality issues
     */
    private fun buildRetryPrompt(issues: List<QualityIssue>, originalMessage: String): String {
        val hints = issues.map { issue ->
            when (issue) {
                QualityIssue.TOO_SHORT -> "请给出更详细的回答"
                QualityIssue.REPETITIVE -> "请不要重复同样的内容，提供更多有价值的信息"
                QualityIssue.INCOMPLETE -> "请完整回答，不要中途断开"
                QualityIssue.GIBBERISH -> "请用清晰的中文重新回答"
                QualityIssue.OFF_TOPIC -> "请针对问题直接回答"
            }
        }
        
        return "请重新回答以下问题。注意：${hints.joinToString("；")}。\\n\\n用户问题：$originalMessage"
    }
}
