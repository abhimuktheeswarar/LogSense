package com.msabhi.logsense

import android.content.Context
import android.content.Intent

public object LogSense {

    /** No-op. */
    public fun init(context: Context, config: LogSenseConfig = LogSenseConfig()) {
        // no-op
    }

    /** No-op: returns the host app's own launch intent so it never throws if started. */
    public fun getLaunchIntent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
}
