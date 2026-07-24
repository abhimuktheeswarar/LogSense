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
import com.msabhi.logsense.internal.ui.formatCount

internal object Notifications {

    private const val CHANNEL_CAPTURE = "logsense_capture"
    private const val CHANNEL_CRASH = "logsense_crash"
    private const val ID_CAPTURE = 0x10905E
    // Single id: crash alerts replace each other instead of stacking, so LogSense shows at most
    // one crash notification (plus the ongoing capture one), never a pile that grows per crash.
    private const val ID_CRASH = 0x10906E

    // Reserved requestCode for the "open Crashes tab" launch intent — large enough never to
    // collide with a crash row id or the capture launch intent (requestCode 0).
    private const val RC_OPEN_CRASHES = ID_CRASH

    const val ACTION_PAUSE = "com.msabhi.logsense.action.PAUSE"
    const val ACTION_RESUME = "com.msabhi.logsense.action.RESUME"
    const val ACTION_SHARE_CRASH = "com.msabhi.logsense.action.SHARE_CRASH"
    const val EXTRA_SHARE_CRASH_ID = "com.msabhi.logsense.extra.SHARE_CRASH_ID"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CAPTURE, "LogSense capture", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CRASH, "LogSense crashes", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun postCapture(context: Context, paused: Boolean, count: Int = 0) {
        val base = context.getString(
            if (paused) R.string.logsense_notification_paused else R.string.logsense_notification_capture,
        )
        val body = if (!paused && count > 0) "$base · ${formatCount(count)} lines" else base
        val actionLabel = if (paused) "Resume" else "Pause"
        val notification = NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.logsense_ic_notification)
            .setContentTitle(appName(context))
            .setContentText(body)
            .setSubText(context.getString(R.string.logsense_name))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
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

    /**
     * Best-effort crash alert posted from the crashing process itself, so it shows immediately
     * rather than only on next launch. It has no deep link yet (the row isn't ingested); tapping
     * opens the Crashes tab, and the next launch replaces it via [postCrash] with a real deep link.
     */
    fun postCrashAlert(context: Context, title: String, text: String) {
        createChannels(context) // idempotent; channel may not exist yet if init hasn't finished
        val notification = NotificationCompat.Builder(context, CHANNEL_CRASH)
            .setSmallIcon(R.drawable.logsense_ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(context.getString(R.string.logsense_name))
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true) // the post-ingestion [postCrash] updates this in place, silently
            .setContentIntent(launchPendingIntent(context, crashId = null, openCrashes = true))
            .build()
        notify(context, ID_CRASH, notification)
    }

    fun postCrash(context: Context, crashId: Long, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_CRASH)
            .setSmallIcon(R.drawable.logsense_ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(context.getString(R.string.logsense_name))
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true) // don't re-alert if the immediate crash alert already fired
            .setContentIntent(launchPendingIntent(context, crashId))
            .addAction(0, "Share", crashSharePendingIntent(context, crashId))
            .build()
        notify(context, ID_CRASH, notification)
    }

    /** Removes the crash notification — e.g. once the user has opened it and is viewing the crash. */
    fun cancelCrash(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_CRASH)
    }

    private fun crashSharePendingIntent(context: Context, crashId: Long): PendingIntent {
        val intent = Intent(context, CaptureActionReceiver::class.java)
            .setAction(ACTION_SHARE_CRASH)
            .putExtra(EXTRA_SHARE_CRASH_ID, crashId)
        return PendingIntent.getBroadcast(
            context,
            crashId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun appName(context: Context): String = runCatching {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }.getOrDefault(context.packageName)

    private fun launchPendingIntent(context: Context, crashId: Long?, openCrashes: Boolean = false): PendingIntent {
        val intent = Intent(context, LogSenseActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        crashId?.let { intent.putExtra(LogSenseActivity.EXTRA_CRASH_ID, it) }
        if (openCrashes) intent.putExtra(LogSenseActivity.EXTRA_OPEN_CRASHES, true)
        return PendingIntent.getActivity(
            context,
            crashId?.toInt() ?: if (openCrashes) RC_OPEN_CRASHES else 0,
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
