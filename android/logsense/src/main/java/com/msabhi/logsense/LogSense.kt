package com.msabhi.logsense

import android.content.Context
import android.content.Intent
import android.util.Log
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.ui.LogSenseActivity

public object LogSense {

    /**
     * Starts LogSense: log capture, analytics detection and crash reporting.
     * Call once from [android.app.Application.onCreate] of the main process.
     * Subsequent calls are ignored.
     */
    public fun init(context: Context, config: LogSenseConfig = LogSenseConfig()) {
        // This runs inside the host's Application.onCreate, so a throw here would stop their app
        // from starting at all. A debug tool must never be the reason an app won't launch: if setup
        // fails for any reason, LogSense stays off and the app carries on without it.
        try {
            LogSenseCore.init(context, config)
        } catch (error: Throwable) {
            Log.e("LogSense", "LogSense failed to start; the app continues without it", error)
        }
    }

    /** Intent that opens the LogSense UI. [Intent.FLAG_ACTIVITY_NEW_TASK] is set. */
    public fun getLaunchIntent(context: Context): Intent =
        Intent(context, LogSenseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
