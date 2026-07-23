package com.msabhi.logsense.internal

import android.app.Application
import android.content.Context
import android.os.Build
import com.msabhi.logsense.LogSenseConfig
import com.msabhi.logsense.ThemeMode
import com.msabhi.logsense.internal.analytics.AnalyticsDetector
import com.msabhi.logsense.internal.crash.CrashFileStore
import com.msabhi.logsense.internal.crash.CrashHandler
import com.msabhi.logsense.internal.crash.DeviceInfo
import com.msabhi.logsense.internal.crash.ExitInfoCollector
import com.msabhi.logsense.internal.data.EARLIER_SESSION_ID
import com.msabhi.logsense.internal.data.LogSenseDatabase
import com.msabhi.logsense.internal.data.SessionEntity
import com.msabhi.logsense.internal.notify.Notifications
import com.msabhi.logsense.internal.prefs.LogSensePrefs
import com.msabhi.logsense.internal.reader.LogBuffer
import com.msabhi.logsense.internal.reader.LogcatReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Process-lifetime singleton owning capture, storage and state. Created by [init]. */
internal class LogSenseCore private constructor(
    val appContext: Context,
    val config: LogSenseConfig,
) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val buffer = LogBuffer(config.maxBufferedLines)
    val prefs = LogSensePrefs(appContext)

    /** Theme override; starts from the user's saved choice, else the config default. */
    val themeMode = MutableStateFlow(prefs.loadThemeMode() ?: config.theme)
    val database: LogSenseDatabase by lazy { LogSenseDatabase.create(appContext) }

    /** Global capture gate — flipped by the notification Pause/Resume actions. */
    val captureEnabled = MutableStateFlow(true)

    /** The host app's display name — shown prominently; LogSense stays a small subtitle. */
    val appName: String = runCatching {
        appContext.applicationInfo.loadLabel(appContext.packageManager).toString()
    }.getOrDefault(appContext.packageName)

    /** Identity of this process run. Events and crashes captured now belong to this session. */
    val sessionId: String = java.util.UUID.randomUUID().toString()
    private val sessionStartedAt: Long = System.currentTimeMillis()
    private val appVersion: String = runCatching {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: ""

    private val deviceInfo = DeviceInfo.collect(appContext)
    private val crashStore = CrashFileStore(appContext.filesDir, deviceInfo, sessionId)
    private var detector: AnalyticsDetector? = null
    private var readerJob: Job? = null

    private fun start() {
        // Crash handler first — nothing that runs later may crash uncaptured. Opt-out via config;
        // it always chains to the previously-installed handler, so the app's reporter still runs.
        if (config.captureJvmCrashes) {
            CrashHandler(crashStore, config.crashContextLines, buffer).install()
        }
        Notifications.createChannels(appContext)

        detector = AnalyticsDetector(config, { database.eventDao() }, scope, sessionId)
        startReader()

        scope.launch {
            val sessionDao = database.sessionDao()
            // The "Earlier" bucket for pre-session and unattributable rows, then this run's session.
            sessionDao.insert(SessionEntity(EARLIER_SESSION_ID, startedAt = 0, endedAt = null, appVersion = ""))
            sessionDao.insert(SessionEntity(sessionId, sessionStartedAt, endedAt = null, appVersion = appVersion))

            val crashDao = database.crashDao()
            val fromFiles = crashStore.ingestInto(crashDao)
            val fromExitInfo = ExitInfoCollector.collect(appContext, crashDao, sessionDao, deviceInfo)
            // One crash notification only — the newest, or a summary when several arrived at once.
            val ingested = (fromFiles + fromExitInfo).sortedByDescending { it.timestamp }
            val newest = ingested.firstOrNull()
            if (newest != null) {
                Notifications.postCrash(
                    appContext,
                    crashId = newest.id,
                    title = if (ingested.size > 1) {
                        "${ingested.size} crashes captured"
                    } else {
                        newest.exceptionClass?.substringAfterLast('.') ?: "${newest.type} detected"
                    },
                    text = newest.message?.takeIf { it.isNotBlank() } ?: "Tap to view details",
                )
            }

            // Age-based cleanup; event count is capped per-session by the detector (so old sessions
            // survive a busy run), and whole old sessions are pruned by maxSessions below.
            val minTs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(config.retentionDays.toLong())
            database.eventDao().trimAge(minTs)
            crashDao.trimAge(minTs)
            crashDao.trimCount(config.maxStoredCrashes)

            // Prune everything from sessions older than the newest [maxSessions] runs.
            val keep = sessionDao.recentIds(config.maxSessions)
            if (keep.isNotEmpty()) {
                database.eventDao().deleteNotInSessions(keep)
                crashDao.deleteNotInSessions(keep)
                sessionDao.deleteNotIn(keep)
            }

            if (config.showNotification) Notifications.postCapture(appContext, paused = false)
        }

        // Keep the capture notification's line count roughly fresh (silent, onlyAlertOnce).
        if (config.showNotification) {
            scope.launch {
                var last = -1
                while (isActive) {
                    delay(NOTIFICATION_REFRESH_MS)
                    if (!captureEnabled.value) continue
                    val count = buffer.currentSnapshot().size
                    if (count != last) {
                        Notifications.postCapture(appContext, paused = false, count = count)
                        last = count
                    }
                }
            }
        }
    }

    private fun startReader() {
        readerJob = scope.launch {
            LogcatReader(buffer, detector!!::process, captureEnabled).run()
        }
    }

    /** Reconnects logcat from scratch: stop the reader, clear the buffer, re-read from the top. */
    fun restartReader() {
        scope.launch {
            readerJob?.cancelAndJoin()
            buffer.clear()
            startReader()
        }
    }

    /** Pauses/resumes global capture and reflects the state in the ongoing notification. */
    fun setCaptureEnabled(enabled: Boolean) {
        captureEnabled.value = enabled
        if (config.showNotification) {
            Notifications.postCapture(appContext, paused = !enabled, count = buffer.currentSnapshot().size)
        }
    }

    /** Sets and persists the theme override chosen in Settings. */
    fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
        prefs.saveThemeMode(mode)
    }

    companion object {
        private const val NOTIFICATION_REFRESH_MS = 4_000L

        @Volatile
        var instance: LogSenseCore? = null
            private set

        fun init(context: Context, config: LogSenseConfig) {
            if (instance != null) return
            if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName() != context.packageName) {
                return // only capture in the main process
            }
            synchronized(this) {
                if (instance != null) return
                instance = LogSenseCore(context.applicationContext, config).also { it.start() }
            }
        }
    }
}
