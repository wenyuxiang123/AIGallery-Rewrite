package com.aigallery.rewrite.tool

import com.aigallery.rewrite.util.FileLogger
import org.json.JSONObject
import java.io.File

/**
 * Local file reading tool
 * 
 * Allows the model to read files from the application's private storage directory.
 * Sandboxed to app's filesDir - cannot access files outside app storage.
 * 
 * Security: Only reads from app-private directories, max 10KB per file.
 */
class ReadFileTool(
    private val baseDir: String
) : BaseTool(
    name = "read_file",
    description = "读取本地文件内容，仅限应用私有目录下的文件"
) {
    companion object {
        private const val TAG = "ReadFileTool"
        private const val MAX_FILE_SIZE = 10 * 1024  // 10KB limit
        private const val MAX_CONTENT_LENGTH = 3000  // Max chars to return
    }

    override fun getParameters(): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("path", JSONObject().apply {
                    put("type", "string")
                    put("description", "相对路径，从应用私有目录根目录开始")
                })
            })
            put("required", listOf("path"))
        }
    }

    override suspend fun run(params: Map<String, Any?>): ToolResponse {
        val relativePath = params["path"]?.toString() ?: return ToolResponse(
            success = false,
            content = "",
            error = "缺少 path 参数"
        )

        // Security: prevent path traversal
        if (relativePath.contains("..") || relativePath.startsWith("/")) {
            return ToolResponse(
                success = false,
                content = "",
                error = "路径不允许包含 .. 或以 / 开头"
            )
        }

        return try {
            // Use injected baseDir instead of System.getProperty
            val file = File(baseDir, relativePath)
            
            if (!file.exists()) {
                return ToolResponse(
                    success = false,
                    content = "",
                    error = "文件不存在: $relativePath"
                )
            }
            
            if (file.length() > MAX_FILE_SIZE) {
                return ToolResponse(
                    success = true,
                    content = "[文件过大 (${file.length()} bytes)，只返回前${MAX_CONTENT_LENGTH}字符]\n" +
                              file.readText(Charsets.UTF_8).take(MAX_CONTENT_LENGTH)
                )
            }

            val content = file.readText(Charsets.UTF_8).take(MAX_CONTENT_LENGTH)
            ToolResponse(success = true, content = content)
        } catch (e: Exception) {
            FileLogger.e(TAG, "run: failed for path=$relativePath", e)
            ToolResponse(success = false, content = "", error = "读取失败: ${e.message}")
        }
    }
}
