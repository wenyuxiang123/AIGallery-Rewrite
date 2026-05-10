package com.aigallery.rewrite.trace

import com.aigallery.rewrite.tool.ToolCall
import com.aigallery.rewrite.tool.ToolResponse
import com.aigallery.rewrite.util.FileLogger
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent trace logger for observability
 * 
 * Logs all agent actions (inference, tool calls, context changes) in JSONL format.
 * Each line is a self-contained JSON object for easy parsing and analysis.
 * 
 * Use cases:
 * - Debug agent behavior
 * - Show "thinking process" to users in UI
 * - Performance analysis (token/s, tool execution time)
 * - Audit trail for safety
 */
class AgentTraceLogger(
    private val logDir: File
) {
    companion object {
        private const val TAG = "AgentTraceLogger"
        private const val MAX_LOG_SIZE_MB = 5L
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    
    private var currentLogFile: File? = null
    private var writer: FileWriter? = null

    private fun ensureWriter() {
        val today = dateFormat.format(Date())
        val logFile = File(logDir, "trace_$today.jsonl")
        
        if (logFile != currentLogFile) {
            writer?.flush()
            writer?.close()
            rotateIfNeeded()
            logDir.mkdirs()
            writer = FileWriter(logFile, true)
            currentLogFile = logFile
        }
    }

    /**
     * Log an inference event
     */
    fun logInference(
        promptTokens: Int,
        outputTokens: Int,
        durationMs: Long,
        modelId: String,
        success: Boolean
    ) {
        val entry = createBaseEntry("inference").apply {
            put("modelId", modelId)
            put("promptTokens", promptTokens)
            put("outputTokens", outputTokens)
            put("durationMs", durationMs)
            put("tokensPerSecond", if (durationMs > 0) outputTokens * 1000f / durationMs else 0f)
            put("success", success)
        }
        appendEntry(entry)
    }

    /**
     * Log a tool call event
     */
    fun logToolCall(
        call: ToolCall,
        result: ToolResponse,
        durationMs: Long
    ) {
        val entry = createBaseEntry("tool_call").apply {
            put("toolName", call.toolName)
            put("params", JSONObject(call.parameters.mapValues { it.toString() }))
            put("success", result.success)
            put("resultPreview", result.content.take(200))
            put("error", result.error ?: "")
            put("durationMs", durationMs)
        }
        appendEntry(entry)
    }

    /**
     * Log a context compression event
     */
    fun logContextCompress(
        originalTokens: Int,
        compressedTokens: Int,
        method: String  // "summary" | "truncation" | "budget"
    ) {
        val entry = createBaseEntry("context_compress").apply {
            put("originalTokens", originalTokens)
            put("compressedTokens", compressedTokens)
            put("compressionRatio", if (originalTokens > 0) compressedTokens.toFloat() / originalTokens else 0f)
            put("method", method)
        }
        appendEntry(entry)
    }

    /**
     * Log a ReAct loop step
     */
    fun logReActStep(
        step: Int,
        maxSteps: Int,
        hasToolCall: Boolean,
        outputLength: Int
    ) {
        val entry = createBaseEntry("react_step").apply {
            put("step", step)
            put("maxSteps", maxSteps)
            put("hasToolCall", hasToolCall)
            put("outputLength", outputLength)
        }
        appendEntry(entry)
    }

    /**
     * Log a skill match event
     */
    fun logSkillMatch(
        userMessage: String,
        matchedSkillId: String?,
        confidence: Float
    ) {
        val entry = createBaseEntry("skill_match").apply {
            put("userMessage", userMessage.take(100))
            put("matchedSkillId", matchedSkillId ?: "none")
            put("confidence", confidence)
        }
        appendEntry(entry)
    }

    /**
     * Log a memory retrieval event
     */
    fun logMemoryRetrieval(
        query: String,
        memoryType: String,
        resultCount: Int,
        durationMs: Long
    ) {
        val entry = createBaseEntry("memory_retrieval").apply {
            put("query", query.take(100))
            put("memoryType", memoryType)
            put("resultCount", resultCount)
            put("durationMs", durationMs)
        }
        appendEntry(entry)
    }

    /**
     * Read recent trace entries for UI display
     */
    fun getRecentEntries(limit: Int = 50): List<JSONObject> {
        val entries = mutableListOf<JSONObject>()
        try {
            val logFiles = logDir.listFiles()
                ?.filter { it.name.endsWith(".jsonl") }
                ?.sortedByDescending { it.lastModified() }
                ?: return emptyList()
            for (logFile in logFiles) {
                if (entries.size >= limit) break
                logFile.readLines().reversed().forEach { line ->
                    if (entries.size >= limit) return@forEach
                    try { entries.add(JSONObject(line)) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "getRecentEntries: failed", e)
        }
        return entries
    }

    private fun createBaseEntry(type: String): JSONObject {
        return JSONObject().apply {
            put("timestamp", timeFormat.format(Date()))
            put("type", type)
        }
    }

    private fun appendEntry(entry: JSONObject) {
        try {
            ensureWriter()
            writer?.apply {
                write(entry.toString())
                write("\n")
                flush()
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "appendEntry: failed", e)
        }
    }

    private fun rotateIfNeeded() {
        try {
            val totalSize = logDir.listFiles()
                ?.filter { it.name.endsWith(".jsonl") }
                ?.sumOf { it.length() } ?: 0L
            if (totalSize > MAX_LOG_SIZE_MB * 1024 * 1024) {
                logDir.listFiles()
                    ?.filter { it.name.endsWith(".jsonl") }
                    ?.sortedBy { it.lastModified() }
                    ?.forEach { file ->
                        if (logDir.listFiles()?.sumOf { it.length() } ?: 0L > MAX_LOG_SIZE_MB * 1024 * 1024 / 2) {
                            file.delete()
                        }
                    }
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "rotateIfNeeded: failed", e)
        }
    }

    fun close() {
        writer?.flush()
        writer?.close()
        writer = null
        currentLogFile = null
    }
}
