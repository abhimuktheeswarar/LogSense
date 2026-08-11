package com.msabhi.lsapp

import android.app.Application
import com.msabhi.logsense.LogSense
import com.msabhi.logsense.LogSenseConfig

class LsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LogSense.init(
            this,
            LogSenseConfig(
                // null regex = built-in parser (the sample logs plain name/json/bundle events).
                analyticsTagPatterns = mapOf("Analytics" to null),
                // On top of the built-in catalog; same query syntax as the Logs filter field.
                customSignals = mapOf("Payment declined" to "tag:Checkout msg:declined"),
            ),
        )
    }
}
