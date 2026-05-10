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
 * 
 * Returns instant answers, abstract summaries, and related topics.
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

    override fun getParameters(): JSONObject {
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

    override suspend fun run(params: Map<String, Any>): ToolResponse {
        val query = params["query"]?.toString() ?: return ToolResponse(
            success = false,
            content = "",
            error = "缺少搜索关键词"
        )

        return try {
            val results = duckDuckGoSearch(query)
            ToolResponse(success = true, content = results)
        } catch (e: Exception) {
            FileLogger.e(TAG, "run: failed for query=$query", e)
            ToolResponse(success = false, content = "", error = "搜索失败: ${e.message}")
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
            return "未找到相关结果"
        }
        
        try {
            val json = JSONObject(responseBody)
            formatDuckDuckGoResponse(query, json)
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to parse DuckDuckGo response", e)
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
        
        // Infobox structured data
        val infobox = json.optJSONObject("Infobox")
        if (infobox != null && infobox.has("content")) {
            val content = infobox.getJSONObject("content")
            builder.append("详细信息:\n")
            var count = 0
            val keys = content.keys()
            while (keys.hasNext() && count < 5) {
                val key = keys.next()
                val value = content.optString(key, "").take(100)
                if (value.isNotBlank()) {
                    builder.append("  $key: $value\n")
                    count++
                }
            }
            builder.append("\n")
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
        if (answer.isNotBlank() && builder.length() < 50) {
            return answer
        }
        
        if (builder.isBlank()) {
            return "搜索「$query」未找到具体结果，建议换个关键词试试"
        }
        
        builder.toString()
    }
}
