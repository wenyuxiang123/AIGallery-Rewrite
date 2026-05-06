package com.aigallery.rewrite.util

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 文件日志工具 - 写入到 /sdcard/Android/data/com.aigallery.rewrite/files/logs/
 * 可通过文件管理器直接查看
 */
object FileLogger {

    private const val TAG = "FileLogger"
    private const val LOG_DIR = "logs"
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024 // 2MB

    private var logFile: File? = null
    private var dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var fileDateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    fun init(context: Context) {
        try {
            // 优先使用外部存储（用户可直接查看）
            val dir = context.getExternalFilesDir(LOG_DIR)
                ?: File(context.filesDir, LOG_DIR)
            if (!dir.exists()) dir.mkdirs()

            val fileName = "aigallery_${fileDateFormat.format(Date())}.log"
            logFile = File(dir, fileName)

            // 写入启动标记
            writeLog("SYSTEM", "=== App Started ===")
            writeLog("SYSTEM", "Log file: ${logFile?.absolutePath}")
            writeLog("SYSTEM", "Device: ${android.os.Build.MANUFACTERER} ${android.os.Build.MODEL}")
            writeLog("SYSTEM", "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to init FileLogger", e)
        }
    }

    fun d(tag: String, msg: String) {
        writeLog(tag, msg)
        android.util.Log.d(tag, msg)
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) "$msg\n${throwable.stackTraceToString()}" else msg
        writeLog(tag, "ERROR: $fullMsg")
        if (throwable != null) android.util.Log.e(tag, msg, throwable) else android.util.Log.e(tag, msg)
    }

    fun w(tag: String, msg: String) {
        writeLog(tag, "WARN: $msg")
        android.util.Log.w(tag, msg)
    }

    fun i(tag: String, msg: String) {
        writeLog(tag, msg)
        android.util.Log.i(tag, msg)
    }

    private fun writeLog(tag: String, msg: String) {
        try {
            val file = logFile ?: return

            // 检查文件大小，超过限制则清空
            if (file.length() > MAX_LOG_SIZE) {
                file.writeText("")
                writeLog("SYSTEM", "=== Log rotated ===")
            }

            val timestamp = dateFormat.format(Date())
            val line = "$timestamp [$tag] $msg\n"

            PrintWriter(FileWriter(file, true)).use { writer ->
                writer.write(line)
                writer.flush()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to write log", e)
        }
    }

    /**
     * 获取日志文件路径（供 UI 展示）
     */
    fun getLogFilePath(): String = logFile?.absolutePath ?: "未初始化"

    /**
     * 读取当前日志内容
     */
    fun readLog(maxLines: Int = 200): String {
        return try {
            val file = logFile ?: return "日志文件未初始化"
            if (!file.exists()) return "日志文件不存在"
            file.readLines().takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }

    /**
     * 清空日志
     */
    fun clearLog() {
        try {
            logFile?.writeText("")
            writeLog("SYSTEM", "=== Log cleared ===")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to clear log", e)
        }
    }
}
