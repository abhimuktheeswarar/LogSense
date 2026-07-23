package com.msabhi.logsense.internal.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.msabhi.logsense.R
import com.msabhi.logsense.internal.ui.LogSenseActivity

internal object Notifications {

    private const val CHANNEL_CAPTURE = "logsense_capture"
    private const val CHANNEL_CRASH = "logsense_crash"
    private const val ID_CAPTURE = 0x10905E
    private const val ID_CRASH_BASE = 0x10906E

    const val ACTION_PAUSE = "com.msabhi.logsense.action.PAUSE"
    const val ACTION_RESUME = "com.msabhi.logsense.action.RESUME"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CAPTURE, "LogSense capture", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CRASH, "LogSense crashes", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun postCapture(context: Context, paused: Boolean) {
        val textRes =
            if (paused) R.string.logsense_notification_paused else R.string.logsense_notification_capture
        val actionLabel = if (paused) "Resume" else "Pause"
        val notification = NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.logsense_ic_notification)
            .setContentTitle(context.getString(R.string.logsense_name))
            .setContentText(context.getString(textRes))
            .setOngoing(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setContentIntent(launchPendingIntent(context, crashId = null))
            .addAction(0, actionLabel, capturePendingIntent(context, resume = paused))
            .build()
        notify(context, ID_CAPTURE, notification)
    }

    private fun capturePendingIntent(context: Context, resume: Boolean): PendingIntent {
        val intent = Intent(context, CaptureActionReceiver::class.java)
            .setAction(if (resume) ACTION_RESUME else ACTION_PAUSE)
        return PendingIntent.getBroadcast(
            context,
            if (resume) 1 else 2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun postCrash(context: Context, crashId: Long, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_CRASH)
            .setSmallIcon(R.drawable.logsense_ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setContentIntent(launchPendingIntent(context, crashId))
            .build()
        notify(context, ID_CRASH_BASE + (crashId % 100).toInt(), notification)
    }

    private fun launchPendingIntent(context: Context, crashId: Long?): PendingIntent {
        val intent = Intent(context, LogSenseActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        crashId?.let { intent.putExtra(LogSenseActivity.EXTRA_CRASH_ID, it) }
        return PendingIntent.getActivity(
            context,
            crashId?.toInt() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled + catch
    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — UI stays reachable via launcher icon
        }
    }
}
