package com.msabhi.logsense.internal.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.ui.ShareUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Handles the crash notification's Share action and the capture notification's Pause / Resume. */
internal class CaptureActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val core = LogSenseCore.instance ?: return
        when (intent.action) {
            Notifications.ACTION_PAUSE -> core.setCaptureEnabled(false)
            Notifications.ACTION_RESUME -> core.setCaptureEnabled(true)
            // Swiped away: stop reposting it. Capture carries on — this is the indicator, not the
            // switch — and an explicit pause/resume brings it back.
            Notifications.ACTION_DISMISS -> core.captureNotificationDismissed = true
            Notifications.ACTION_SHARE_CRASH -> {
                val crashId = intent.getLongExtra(Notifications.EXTRA_SHARE_CRASH_ID, -1L)
                if (crashId < 0) return
                // ponytail: startActivity from a user-tapped notification action is BAL-exempt; if an
                // OEM ever blocks it, route through a launcher activity instead.
                val pending = goAsync()
                val app = context.applicationContext
                core.scope.launch {
                    try {
                        val crash = core.database.crashDao().get(crashId) ?: return@launch
                        withContext(Dispatchers.Main) { ShareUtil.shareCrash(app, crash) }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
