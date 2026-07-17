package com.cuefactory.app

import android.app.Application
import com.cuefactory.app.util.AppLog

class CueFactoryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AppLog.e("FATAL uncaught on ${t.name}: ${e.javaClass.simpleName}: ${e.message}", e)
            previous?.uncaughtException(t, e)
        }
        AppLog.i("Application onCreate")
        // Eager container so settings/ffmpeg path exist before UI
        AppContainer.get(this)
    }
}
