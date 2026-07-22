package com.msabhi.logsense

import android.content.Context
import android.content.Intent
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.ui.LogSenseActivity

public object LogSense {

    /**
     * Starts LogSense: log capture, analytics detection and crash reporting.
     * Call once from [android.app.Application.onCreate] of the main process.
     * Subsequent calls are ignored.
     */
    public fun init(context: Context, config: LogSenseConfig = LogSenseConfig()) {
        LogSenseCore.init(context, config)
    }

    /** Intent that opens the LogSense UI. [Intent.FLAG_ACTIVITY_NEW_TASK] is set. */
    public fun getLaunchIntent(context: Context): Intent =
        Intent(context, LogSenseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
