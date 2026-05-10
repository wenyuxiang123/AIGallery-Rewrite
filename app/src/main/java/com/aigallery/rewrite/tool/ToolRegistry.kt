package com.aigallery.rewrite.tool

import com.aigallery.rewrite.util.FileLogger
import org.json.JSONObject
import okhttp3.OkHttpClient

/**
 * Tool Registry - register and execute tools with circuit breaker
 */
class ToolRegistry {
    companion object {
        private const val TAG = "ToolRegistry"
        const val FUNC_START = "✿FUNCTION✿"
        const val FUNC_END = "✿RESULT✿"
        const val TOOL_CALL_START = "<tool_call>"
        const val TOOL_CALL_END = "</tool_call>"
    }

    private val tools = mutableMapOf<String, BaseTool>()
    private val circuitBreakers = mutableMapOf<String, CircuitBreaker>()

    fun register(tool: BaseTool) {
        tools[tool.name] = tool
        circuitBreakers[tool.name] = CircuitBreaker(toolName = tool.name)
        FileLogger.d(TAG, "register: ${tool.name} (${tool.category})")
    }

    fun registerAll(vararg toolList: BaseTool) { toolList.forEach { register(it) } }
    fun getRegisteredTools(): List<BaseTool> = tools.values.toList()
    fun getTool(name: String): BaseTool? = tools[name]
    
    /**
     * Get list of available tool names
     */
    fun getAvailableToolNames(): List<String> = tools.keys.toList()

    /**
     * Register built-in tools with baseDir for file operations
     */
    fun registerBuiltinTools(baseDir: String) {
        register(CalculatorTool())
        register(DateTimeTool())
        register(ReadFileTool(baseDir))
        FileLogger.d(TAG, "registerBuiltinTools: registered CalculatorTool, DateTimeTool, ReadFileTool with baseDir=$baseDir")
    }

    /**
     * Register external tools that need OkHttpClient (WebSearchTool, WeatherTool, etc.)
     */
    fun registerExternalTools(okHttpClient: OkHttpClient, baseDir: String) {
        try {
            register(WebSearchTool(okHttpClient))
            FileLogger.d(TAG, "registerExternalTools: registered WebSearchTool")
        } catch (e: Exception) {
            FileLogger.e(TAG, "registerExternalTools: failed to register WebSearchTool: ${e.message}")
        }
    }

    suspend fun execute(call: ToolCall): ToolResponse {
        val tool = tools[call.toolName]
            ?: return ToolResponse.failure("Unknown tool: ${call.toolName}", call.toolName)
        val breaker = circuitBreakers[call.toolName]
        if (breaker != null && !breaker.tryPass()) {
            return ToolResponse.failure("Tool ${call.toolName} circuit breaker open", call.toolName)
        }
        val startTime = System.currentTimeMillis()
        return try {
            val result = tool.execute(call.parameters)
            val duration = System.currentTimeMillis() - startTime
            if (result.success) breaker?.recordSuccess() else breaker?.recordFailure()
            result.copy(durationMs = duration, toolName = call.toolName)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            breaker?.recordFailure()
            FileLogger.e(TAG, "execute: ${call.toolName} failed", e)
            ToolResponse.failure(e.message ?: "Unknown error", call.toolName, duration)
        }
    }

    fun getToolsJson(): String {
        val arr = org.json.JSONArray()
        tools.values.forEach { arr.put(it.toFunctionDefinition()) }
        return arr.toString(2)
    }

    fun parseToolCalls(output: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        // Format 1: Qwen 3.5 FUNC format
        if (output.contains(FUNC_START)) {
            val parts = output.split(FUNC_START)
            for (part in parts.drop(1)) {
                try {
                    val lines = part.trim().split("\n")
                    if (lines.isNotEmpty()) {
                        val toolName = lines[0].trim()
                        val paramsJson = if (lines.size > 1) lines[1].trim() else "{}"
                        calls.add(ToolCall(toolName = toolName, parameters = jsonToMap(paramsJson)))
                    }
                } catch (e: Exception) { 
                    FileLogger.w(TAG, "parseToolCalls: FUNC error: ${e.message}") 
                }
            }
        }
        // Format 2: XML format
        if (output.contains(TOOL_CALL_START)) {
            val pattern = Regex(Regex.escape(TOOL_CALL_START) + "(.*?)" + Regex.escape(TOOL_CALL_END), RegexOption.DOT_MATCHES_ALL)
            pattern.findAll(output).forEach { match ->
                try {
                    val json = JSONObject(match.groupValues[1].trim())
                    val toolName = json.getString("name")
                    val args = json.optJSONObject("arguments") ?: JSONObject()
                    calls.add(ToolCall(toolName = toolName, parameters = jsonToMap(args.toString())))
                } catch (e: Exception) { FileLogger.w(TAG, "parseToolCalls: tool_call error: ${e.message}") }
            }
        }
        return calls
    }

    fun containsToolCall(output: String): Boolean {
        return output.contains(FUNC_START) || output.contains(TOOL_CALL_START)
    }

    fun stripToolCalls(output: String): String {
        var cleaned = output
        val funcPattern = Regex(Regex.escape(FUNC_START) + ".*?" + Regex.escape(FUNC_END), RegexOption.DOT_MATCHES_ALL)
        cleaned = funcPattern.replace(cleaned, "")
        val toolCallPattern = Regex(Regex.escape(TOOL_CALL_START) + ".*?" + Regex.escape(TOOL_CALL_END), RegexOption.DOT_MATCHES_ALL)
        cleaned = toolCallPattern.replace(cleaned, "")
        return cleaned.trim()
    }

    private fun jsonToMap(json: String): Map<String, Any> {
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, Any>()
            obj.keys().forEach { key -> map[key] = obj.get(key) }
            map
        } catch (e: Exception) { emptyMap() }
    }
}
