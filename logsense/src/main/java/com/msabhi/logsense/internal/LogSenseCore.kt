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
import com.msabhi.logsense.internal.data.LogSenseDatabase
import com.msabhi.logsense.internal.notify.Notifications
import com.msabhi.logsense.internal.prefs.LogSensePrefs
import com.msabhi.logsense.internal.reader.LogBuffer
import com.msabhi.logsense.internal.reader.LogcatReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Process-lifetime singleton owning capture, storage and state. Created by [init]. */
internal class LogSenseCore private constructor(
    val appContext: Context,
    val config: LogSenseConfig,
) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val buffer = LogBuffer(config.maxBufferedLines)
    val themeMode = MutableStateFlow(config.theme)
    val prefs = LogSensePrefs(appContext)
    val database: LogSenseDatabase by lazy { LogSenseDatabase.create(appContext) }

    /** Global capture gate — flipped by the notification Pause/Resume actions. */
    val captureEnabled = MutableStateFlow(true)

    private val deviceInfo = DeviceInfo.collect(appContext)
    private val crashStore = CrashFileStore(appContext.filesDir, deviceInfo)
    private var detector: AnalyticsDetector? = null
    private var readerJob: Job? = null

    private fun start() {
        // Crash handler first — nothing that runs later may crash uncaptured.
        CrashHandler(crashStore, config.crashContextLines, buffer).install()
        Notifications.createChannels(appContext)

        detector = AnalyticsDetector(config, { database.eventDao() }, scope)
        startReader()

        scope.launch {
            val crashDao = database.crashDao()
            val fromFiles = crashStore.ingestInto(crashDao)
            val fromExitInfo = ExitInfoCollector.collect(appContext, crashDao, deviceInfo)
            (fromFiles + fromExitInfo).forEach { crash ->
                Notifications.postCrash(
                    appContext,
                    crashId = crash.id,
                    title = crash.exceptionClass?.substringAfterLast('.') ?: "${crash.type} detected",
                    text = crash.message?.takeIf { it.isNotBlank() } ?: "Tap to view details",
                )
            }

            val minTs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(config.retentionDays.toLong())
            database.eventDao().trimAge(minTs)
            database.eventDao().trimCount(config.maxStoredEvents)
            crashDao.trimAge(minTs)
            crashDao.trimCount(config.maxStoredCrashes)

            if (config.showNotification) Notifications.postCapture(appContext, paused = false)
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
        if (config.showNotification) Notifications.postCapture(appContext, paused = !enabled)
    }

    companion object {
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
