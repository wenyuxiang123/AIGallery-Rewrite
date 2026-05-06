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
    }
}
