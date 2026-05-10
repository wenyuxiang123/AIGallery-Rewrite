package com.aigallery.rewrite.tool

import org.json.JSONObject

class WebSearchTool : BaseTool(
    name = "web_search",
    description = "Search the internet for information.",
    category = ToolCategory.WEB
) {
    override fun getParametersSchema(): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("query", JSONObject().apply {
                    put("type", "string")
                    put("description", "Search keywords")
                })
            })
            put("required", org.json.JSONArray().put("query"))
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResponse {
        val query = params["query"]?.toString()
            ?: return ToolResponse.failure("Missing query", name)
        return ToolResponse.success("Web search not yet integrated. Query: $query.", name)
    }
}
