package com.msabhi.lsapp

import android.app.Application
import com.msabhi.logsense.LogSense
import com.msabhi.logsense.LogSenseConfig

class LsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LogSense.init(this, LogSenseConfig(analyticsTags = setOf("Analytics")))
    }
}
