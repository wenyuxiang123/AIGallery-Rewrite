package com.aigallery.rewrite.tool

import org.json.JSONObject

class CalculatorTool : BaseTool(
    name = "calculator",
    description = "Execute mathematical calculations. Input a math expression, returns the result.",
    category = ToolCategory.MATH
) {
    override fun getParametersSchema(): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("expression", JSONObject().apply {
                    put("type", "string")
                    put("description", "Math expression like 2+3*4 or sqrt(16)")
                })
            })
            put("required", org.json.JSONArray().put("expression"))
        }
    }

    override suspend fun execute(params: Map<String, Any>): ToolResponse {
        val expression = params["expression"]?.toString()
            ?: return ToolResponse.failure("Missing expression", name)
        return try {
            val result = evaluateExpression(expression.replace(" ", ""))
            ToolResponse.success("$expression = $result", name)
        } catch (e: Exception) {
            ToolResponse.failure("Calculation error: ${e.message}", name)
        }
    }

    private fun evaluateExpression(expr: String): String {
        val e = expr.replace("^", "**")
        return try {
            val result = when {
                e.contains("+") -> e.split("+").mapNotNull { it.trim().toDoubleOrNull() }.reduce { a, b -> a + b }
                e.contains("-") && !e.startsWith("-") -> {
                    val parts = e.split("-")
                    if (parts.size == 2) (parts[0].trim().toDoubleOrNull() ?: 0.0) - (parts[1].trim().toDoubleOrNull() ?: 0.0)
                    else throw IllegalArgumentException("Complex subtraction not supported")
                }
                e.contains("**") -> {
                    val parts = e.split("**")
                    if (parts.size == 2) Math.pow(parts[0].trim().toDouble(), parts[1].trim().toDouble())
                    else throw IllegalArgumentException("Complex power not supported")
                }
                e.contains("*") -> e.split("*").mapNotNull { it.trim().toDoubleOrNull() }.reduce { a, b -> a * b }
                e.contains("/") -> {
                    val parts = e.split("/")
                    if (parts.size == 2) {
                        val d = parts[1].trim().toDouble()
                        if (d == 0.0) throw ArithmeticException("Division by zero")
                        parts[0].trim().toDouble() / d
                    } else throw IllegalArgumentException("Complex division not supported")
                }
                else -> e.toDouble()
            }
            if (result == result.toLong().toDouble()) result.toLong().toString()
            else String.format("%.4f", result).trimEnd('0').trimEnd('.')
        } catch (e: Exception) {
            throw IllegalArgumentException("Cannot evaluate: ${e.message}")
        }
    }
}
