package com.msabhi.lsapp

import android.app.Application
import com.msabhi.logsense.LogSense
import com.msabhi.logsense.LogSenseConfig

class LsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // null regex = built-in parser (the sample logs plain name/json/bundle events).
        LogSense.init(this, LogSenseConfig(analyticsTagPatterns = mapOf("Analytics" to null)))
    }
}
