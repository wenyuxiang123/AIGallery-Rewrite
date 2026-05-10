package com.aigallery.rewrite.tool

import com.aigallery.rewrite.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Web search tool using DuckDuckGo Instant Answer API
 * 
 * FREE: No API key required
 * API: https://api.duckduckgo.com/
 */
class WebSearchTool(
    private val httpClient: OkHttpClient
) : BaseTool(
    name = "web_search",
    description = "搜索互联网信息，使用DuckDuckGo即时回答API"
) {
    companion object {
        private const val TAG = "WebSearchTool"
        private const val API_BASE = "https://api.duckduckgo.com/"
    }

    override fun getParametersSchema(): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("query", JSONObject().apply {
                    put("type", "string")
                    put("description", "搜索关键词")
                })
            })
            put("required", listOf("query"))
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResponse {
        val query = params["query"]?.toString() ?: return ToolResponse.failure(
            error = "缺少搜索关键词",
            toolName = name
        )

        return try {
            val results = duckDuckGoSearch(query)
            ToolResponse.success(content = results, toolName = name)
        } catch (e: Exception) {
            FileLogger.e(TAG, "execute: failed for query=$query", e)
            ToolResponse.failure(error = "搜索失败: ${e.message}", toolName = name)
        }
    }

    private suspend fun duckDuckGoSearch(query: String): String = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "${API_BASE}?q=$encodedQuery&format=json&no_html=1&skip_disambig=0"
        
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            .get()
            .build()
        
        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        
        if (!response.isSuccessful || responseBody.isEmpty()) {
            return@withContext "未找到相关结果"
        }
        
        try {
            val json = JSONObject(responseBody)
            formatDuckDuckGoResponse(query, json)
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to parse DuckDuckGo response: ${e.message}")
            "搜索结果格式异常"
        }
    }

    private fun formatDuckDuckGoResponse(query: String, json: JSONObject): String {
        val builder = StringBuilder()
        
        // AbstractText is usually a good summary
        val abstract = json.optString("AbstractText", "")
        if (abstract.isNotBlank()) {
            builder.append(abstract).append("\n\n")
        }
        
        // Heading
        val heading = json.optString("Heading", "")
        if (heading.isNotBlank() && heading != "Disambiguation") {
            builder.append("主要结果: ").append(heading).append("\n")
        }
        
        // AbstractURL
        val abstractUrl = json.optString("AbstractURL", "")
        if (abstractUrl.isNotBlank()) {
            builder.append("详情: ").append(abstractUrl).append("\n\n")
        }
        
        // RelatedTopics
        val relatedTopics = json.optJSONArray("RelatedTopics")
        if (relatedTopics != null && relatedTopics.length() > 0) {
            builder.append("相关推荐:\n")
            for (i in 0 until minOf(3, relatedTopics.length())) {
                val topic = relatedTopics.optJSONObject(i) ?: continue
                val text = topic.optString("Text", "").take(80)
                if (text.isNotBlank()) {
                    builder.append("  - $text\n")
                }
            }
        }
        
        // Direct answer
        val answer = json.optString("Answer", "")
        if (answer.isNotBlank() && builder.length < 50) {
            return answer
        }
        
        if (builder.isBlank()) {
            return "搜索「$query」未找到具体结果，建议换个关键词试试"
        }
        
        return builder.toString()
    }
}
