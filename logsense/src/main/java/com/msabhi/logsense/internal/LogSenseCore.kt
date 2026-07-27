package com.msabhi.logsense.internal

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
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
import com.msabhi.logsense.internal.signals.LifecycleSignals
import com.msabhi.logsense.internal.signals.SignalDetector
import kotlinx.coroutines.CoroutineExceptionHandler
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

    /**
     * Every background job runs here, and the handler is not optional: without one an uncaught
     * exception in any `launch` reaches the thread's default handler — which by then is our own
     * crash handler — and takes the **host app** down over a fault in a debug tool. With it, the
     * failure is logged, the scope survives (SupervisorJob), and the app carries on.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            report("background work", error)
        },
    )

    /**
     * Reports an internal failure without ever becoming one. Bounded on purpose: LogSense reads its
     * own process's logcat, so a fault that recurs per batch could otherwise log itself into a loop.
     * The count races harmlessly across threads — it only decides how many lines get written.
     */
    @Volatile
    private var reported = 0

    private fun report(what: String, error: Throwable) {
        if (reported++ < MAX_REPORTED_FAILURES) Log.e(TAG, "LogSense $what failed", error)
    }

    /** Runs [block], swallowing anything it throws. For work that must never reach the host. */
    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            report(what, error)
        }
    }

    /*
     * Host-supplied numbers are clamped once, here, rather than trusted downstream. A negative value
     * is not hypothetical mischief — it is a typo in someone's config, and each of these reaches
     * arithmetic that would throw on one: `takeLast(-1)` fails outright, and a negative buffer cap
     * makes the ring buffer pop an empty deque on the very first batch.
     */
    private val crashContextLines = config.crashContextLines.coerceIn(0, MAX_CRASH_CONTEXT_LINES)
    private val retentionDays = config.retentionDays.coerceAtLeast(0)
    private val maxStoredCrashes = config.maxStoredCrashes.coerceAtLeast(0)
    private val maxSessions = config.maxSessions.coerceAtLeast(1)

    /** Effective in-memory buffer cap: the configured [LogSenseConfig.maxBufferedLines], but lowered
     *  on low-RAM devices so LogSense never fills the host app's heap. Never raised above the config —
     *  spare RAM is not a reason to buffer more. */
    val bufferLimit = ramAwareBufferLimit(appContext, config.maxBufferedLines.coerceAtLeast(MIN_BUFFER_LINES))
    val buffer = LogBuffer(bufferLimit)
    val prefs = LogSensePrefs(appContext)

    /** Catalog matching over the live stream. Hits are in-memory and point into [buffer]. */
    val signals = SignalDetector(config) { prefs.mutedSignals.value }

    /** Set by the Signals tab to send the Logs tab to a line; cleared once the jump is consumed. */
    val jumpToLogId = MutableStateFlow<Long?>(null)

    /** Crashes ingested during *this* launch — i.e. what killed the previous run. The Signals tab
     *  shows these alongside this run's hits; the Crashes tab keeps the full history. */
    val launchCrashIds = MutableStateFlow<Set<Long>>(emptySet())

    /** Theme override; starts from the user's saved choice, else the config default. */
    val themeMode = MutableStateFlow(prefs.loadThemeMode() ?: config.theme)
    val database: LogSenseDatabase by lazy { LogSenseDatabase.create(appContext) }

    /** Global capture gate — flipped by the notification Pause/Resume actions. */
    val captureEnabled = MutableStateFlow(true)
    /** Set true once the user opens the app from the crash notification, so this launch's
     *  post-ingestion crash notification isn't posted again on top of what they're already viewing. */
    @Volatile
    var crashNotificationConsumed = false

    /** Id of the crash ingested this launch — the one the immediate alert is about — so the UI can
     *  deep-link straight to its detail when the app is opened from the crash notification. */
    val lastCrashId = MutableStateFlow<Long?>(null)

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
            CrashHandler(appContext, crashStore, crashContextLines, buffer).install()
        }
        Notifications.createChannels(appContext)
        (appContext as? Application)?.let { LifecycleSignals.install(it, signals) }

        detector = AnalyticsDetector(
            config,
            { database.eventDao() },
            scope,
            sessionId,
            settingsTagPatterns = { prefs.tagPatterns.value },
        )
        startReader()

        // Publish buffered lines to observers at a bounded rate — the reader keeps ingesting every
        // batch, but the UI re-renders at most ~10×/sec instead of once per batch under heavy logging.
        scope.launch {
            while (isActive) {
                delay(BUFFER_FLUSH_MS)
                buffer.flush()
            }
        }

        scope.launch {
            val sessionDao = database.sessionDao()
            // The "Earlier" bucket for pre-session and unattributable rows, then this run's session.
            sessionDao.insert(SessionEntity(EARLIER_SESSION_ID, startedAt = 0, endedAt = null, appVersion = ""))
            sessionDao.insert(SessionEntity(sessionId, sessionStartedAt, endedAt = null, appVersion = appVersion))

            val crashDao = database.crashDao()
            val fromFiles = crashStore.ingestInto(crashDao)
            val fromExitInfo = ExitInfoCollector.collect(appContext, crashDao, sessionDao, deviceInfo, signals)
            // One crash notification only — the newest, or a summary when several arrived at once.
            val ingested = (fromFiles + fromExitInfo).sortedByDescending { it.timestamp }
            launchCrashIds.value = ingested.mapTo(mutableSetOf()) { it.id }
            val newest = ingested.firstOrNull()
            if (newest != null) lastCrashId.value = newest.id
            if (newest != null && !crashNotificationConsumed) {
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
            val minTs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
            database.eventDao().trimAge(minTs)
            crashDao.trimAge(minTs)
            crashDao.trimCount(maxStoredCrashes)

            // Prune sessions older than the newest [maxSessions] runs. If the user disabled keeping
            // past-session events/crashes, that type is narrowed to this run only.
            val keep = sessionDao.recentIds(maxSessions)
            if (keep.isNotEmpty()) {
                database.eventDao().deleteNotInSessions(if (prefs.keepPastEvents.value) keep else listOf(sessionId))
                // A JVM crash is always ingested into the *next* run, so it looks like a "previous
                // session" even though it's the crash we just surfaced. Never let this launch's
                // retention delete the crashes it just ingested, whatever keepPastCrashes says.
                val crashKeep = (if (prefs.keepPastCrashes.value) keep else listOf(sessionId)) +
                    ingested.map { it.sessionId }
                crashDao.deleteNotInSessions(crashKeep.distinct())
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
                    val count = buffer.totalReceived.value
                    if (count != last) {
                        Notifications.postCapture(appContext, paused = false, count = count)
                        last = count
                    }
                }
            }
        }
    }

    private fun startReader() {
        val analytics = detector!!
        readerJob = scope.launch {
            LogcatReader(
                buffer = buffer,
                // Guarded separately so a fault in one detector can neither stop the other nor kill
                // the reader coroutine — losing capture entirely would be worse than losing a batch.
                onBatch = { batch ->
                    guard("analytics detection") { analytics.process(batch) }
                    guard("signal detection") { signals.process(batch) }
                },
                captureEnabled = captureEnabled,
            ).run()
        }
    }

    /** Reconnects logcat from scratch: stop the reader, clear the buffer, re-read from the top. */
    fun restartReader() {
        scope.launch {
            readerJob?.cancelAndJoin()
            clearLogs()
            startReader()
        }
    }

    /** Drops the captured stream. Signal hits point into the buffer, so they go with it — a hit
     *  whose line no longer exists is a jump that lands nowhere. */
    suspend fun clearLogs() {
        buffer.clear()
        signals.clear()
    }

    /** Pauses/resumes global capture and reflects the state in the ongoing notification. */
    fun setCaptureEnabled(enabled: Boolean) {
        captureEnabled.value = enabled
        if (config.showNotification) {
            Notifications.postCapture(appContext, paused = !enabled, count = buffer.totalReceived.value)
        }
    }

    /** Sets and persists the theme override chosen in Settings. */
    fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
        prefs.saveThemeMode(mode)
    }

    /** Persists the retention toggles; turning one off purges earlier runs' data right away. */
    fun setKeepPastEvents(enabled: Boolean) {
        prefs.setKeepPastEvents(enabled)
        if (!enabled) scope.launch { database.eventDao().deleteNotInSessions(listOf(sessionId)) }
    }

    fun setKeepPastCrashes(enabled: Boolean) {
        prefs.setKeepPastCrashes(enabled)
        if (!enabled) scope.launch { database.crashDao().deleteNotInSessions(listOf(sessionId)) }
    }

    companion object {
        private const val NOTIFICATION_REFRESH_MS = 4_000L
        private const val BUFFER_FLUSH_MS = 100L
        private const val TAG = "LogSense"
        private const val MAX_REPORTED_FAILURES = 5

        /** Floors/ceilings for host-supplied config; see the clamps at the top of the class. */
        private const val MIN_BUFFER_LINES = 100
        private const val MAX_CRASH_CONTEXT_LINES = 5_000

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

/**
 * Lowers the in-memory buffer cap on low-RAM devices. The buffer lives in the host app's heap, so
 * having spare RAM is never a reason to buffer more — [configured] is the ceiling, and we only cut
 * below it on constrained devices to avoid pressuring the host.
 */
private fun ramAwareBufferLimit(context: Context, configured: Int): Int {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return configured
    val ceiling = if (am.isLowRamDevice) {
        10_000
    } else {
        val totalGb = runCatching {
            ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem / (1024.0 * 1024 * 1024)
        }.getOrDefault(4.0)
        when {
            totalGb < 3 -> 20_000 // ~2GB devices
            totalGb < 4 -> 35_000 // ~3GB devices
            else -> configured // 4GB+ : keep the configured limit as-is (never more)
        }
    }
    return configured.coerceAtMost(ceiling)
}
