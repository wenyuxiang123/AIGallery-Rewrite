package com.aigallery.rewrite.tool

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DateTimeTool : BaseTool(
    name = "datetime",
    description = "Get current date and time.",
    category = ToolCategory.SYSTEM
) {
    override fun getParametersSchema(): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("format", JSONObject().apply {
                    put("type", "string")
                    put("description", "Format: full, date, time, timestamp")
                })
            })
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResponse {
        val format = params["format"]?.toString() ?: "full"
        val now = Date()
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val result = when (format) {
            "full" -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINESE).apply { timeZone = tz }.format(now)
            "date" -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { timeZone = tz }.format(now)
            "time" -> SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply { timeZone = tz }.format(now)
            "timestamp" -> now.time.toString()
            else -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply { timeZone = tz }.format(now)
        }
        return ToolResponse.success(result, name)
    }
}
