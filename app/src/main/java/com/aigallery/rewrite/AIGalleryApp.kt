package com.aigallery.rewrite

import android.app.Application
import com.aigallery.rewrite.util.FileLogger
import dagger.hilt.android.HiltAndroidApp

/**
 * AIGallery Application class with Hilt
 */
@HiltAndroidApp
class AIGalleryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        FileLogger.i("App", "AIGalleryApp onCreate")
        
        // 设置全局异常捕获（最高优先级）
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            FileLogger.e("CRASH", "Uncaught exception in thread: ${thread.name}", throwable)
            FileLogger.e("CRASH", "StackTrace: ${throwable.stackTraceToString()}")
            defaultHandler?.uncaughtException(thread, throwable)
        }
        FileLogger.i("App", "Global exception handler installed")
    }
}
