package com.aigallery.rewrite.tool

import com.aigallery.rewrite.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Weather query tool
 * 
 * Uses 和风天气 (QWeather) free API for weather data.
 * Requires API key configured in tool parameters.
 * 
 * If no API key is set, returns a helpful message instead of error.
 * 
 * Free tier: 1000 requests/day
 * API docs: https://dev.qweather.com/docs/api/
 */
class WeatherTool(
    private val httpClient: OkHttpClient
) : BaseTool(
    name = "weather",
    description = "查询指定城市的天气信息，包括温度、湿度、风力等"
) {
    companion object {
        private const val TAG = "WeatherTool"
        private const val GEO_API = "https://geoapi.qweather.com/v2/city/lookup"
        private const val WEATHER_API = "https://devapi.qweather.com/v7/weather/now"
        // Default API key - user should replace with their own
        private var apiKey: String = ""
    }

    /**
     * Set QWeather API key
     */
    fun setApiKey(key: String) {
        apiKey = key
    }

    override fun getParameters(): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("city", JSONObject().apply {
                    put("type", "string")
                    put("description", "城市名称，如'北京'、'上海'")
                })
            })
            put("required", listOf("city"))
        }
    }

    override suspend fun run(params: Map<String, Any>): ToolResponse {
        val city = params["city"]?.toString() ?: return ToolResponse(
            success = false,
            content = "",
            error = "缺少城市参数"
        )

        if (apiKey.isEmpty()) {
            return ToolResponse(
                success = true,
                content = "天气查询功能尚未配置API密钥。请在设置中配置和风天气API Key（免费注册：https://dev.qweather.com/）。当前城市：$city"
            )
        }

        return try {
            val locationId = lookupCity(city)
            if (locationId == null) {
                ToolResponse(
                    success = false,
                    content = "",
                    error = "未找到城市: $city"
                )
            } else {
                val weatherJson = queryWeather(locationId)
                val resultText = parseWeatherResponse(city, weatherJson)
                ToolResponse(success = true, content = resultText)
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "run: failed for city=$city", e)
            ToolResponse(success = false, content = "", error = "天气查询失败: ${e.message}")
        }
    }

    /**
     * Look up city ID via geo API
     */
    private suspend fun lookupCity(city: String): String? = withContext(Dispatchers.IO) {
        try {
            val encodedCity = URLEncoder.encode(city, "UTF-8")
            val url = "$GEO_API?location=$encodedCity&key=$apiKey"
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "")
                val locations = json.optJSONArray("location")
                if (locations != null && locations.length() > 0) {
                    locations.getJSONObject(0).optString("id", null)
                } else null
            } else null
        } catch (e: Exception) {
            FileLogger.e(TAG, "lookupCity: failed", e)
            null
        }
    }

    /**
     * Query current weather by location ID
     */
    private suspend fun queryWeather(locationId: String): JSONObject = withContext(Dispatchers.IO) {
        val url = "$WEATHER_API?location=$locationId&key=$apiKey"
        val request = Request.Builder().url(url).get().build()
        val response = httpClient.newCall(request).execute()
        
        if (response.isSuccessful) {
            JSONObject(response.body?.string() ?: "")
        } else {
            JSONObject()
        }
    }

    /**
     * Parse weather API response into readable Chinese text
     */
    private fun parseWeatherResponse(city: String, json: JSONObject): String {
        val now = json.optJSONObject("now") ?: return "暂无天气数据"
        
        val temp = now.optString("temp", "?")
        val feelsLike = now.optString("feelsLike", "?")
        val text = now.optString("text", "?")
        val humidity = now.optString("humidity", "?")
        val windDir = now.optString("windDir", "?")
        val windScale = now.optString("windScale", "?")
        val precip = now.optString("precip", "?")
        
        return "$city当前天气: $text, 温度${temp}°C(体感${feelsLike}°C), 湿度${humidity}%, ${windDir}${windScale}级, 降水${precip}mm"
    }
}
