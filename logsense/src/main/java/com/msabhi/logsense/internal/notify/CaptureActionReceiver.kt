package com.msabhi.logsense.internal.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.msabhi.logsense.internal.LogSenseCore

/** Handles the notification's Pause / Resume actions by toggling the global capture gate. */
internal class CaptureActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val core = LogSenseCore.instance ?: return
        when (intent.action) {
            Notifications.ACTION_PAUSE -> core.setCaptureEnabled(false)
            Notifications.ACTION_RESUME -> core.setCaptureEnabled(true)
        }
    }
}
