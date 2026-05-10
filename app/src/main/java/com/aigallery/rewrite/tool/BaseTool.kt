package com.aigallery.rewrite.tool

import org.json.JSONObject

abstract class BaseTool(
    val name: String,
    val description: String,
    val category: ToolCategory = ToolCategory.UTILITY
) {
    abstract fun getParametersSchema(): JSONObject
    abstract suspend fun execute(params: Map<String, Any>): ToolResponse
    open fun requiresApproval(): Boolean = false
    
    fun toFunctionDefinition(): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", getParametersSchema())
        }
    }
}
