package com.aigallery.rewrite.tool

import com.aigallery.rewrite.util.FileLogger
import org.json.JSONObject

data class ToolCall(
    val toolName: String,
    val parameters: Map<String, Any> = emptyMap()
)

data class ToolResponse(
    val success: Boolean,
    val content: String,
    val toolName: String,
    val error: String? = null,
    val durationMs: Long = 0
) {
    companion object {
        fun success(content: String, toolName: String, durationMs: Long = 0) =
            ToolResponse(success = true, content = content, toolName = toolName, durationMs = durationMs)
        fun failure(error: String, toolName: String, durationMs: Long = 0) =
            ToolResponse(success = false, content = "", toolName = toolName, error = error, durationMs = durationMs)
    }
}

enum class ToolCategory(val displayName: String) {
    SYSTEM("System"), MATH("Math"), WEB("Web"), FILE("File"), UTILITY("Utility")
}
