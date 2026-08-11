package com.msabhi.logsense.internal.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.msabhi.logsense.internal.data.CrashDao
import com.msabhi.logsense.internal.data.CrashEntity
import com.msabhi.logsense.internal.data.EARLIER_SESSION_ID
import com.msabhi.logsense.internal.data.SessionDao
import com.msabhi.logsense.internal.signals.BuiltInSignals
import com.msabhi.logsense.internal.signals.Signal
import com.msabhi.logsense.internal.signals.SignalDetector

/**
 * Records ANRs and native crashes from [ApplicationExitInfo] on launch (API 30+).
 * JVM crashes are excluded — the uncaught-exception handler already captured those.
 *
 * Every other exit reason — force-stopped, killed by signal, reaped for low memory — becomes a
 * signal instead of a crash report: worth surfacing, but not a fault to file.
 */
internal object ExitInfoCollector {

    private const val PREFS = "logsense_prefs"
    private const val KEY_WATERMARK = "last_exit_ts"
    private const val MAX_TRACE_BYTES = 256 * 1024

    suspend fun collect(
        context: Context,
        dao: CrashDao,
        sessionDao: SessionDao,
        deviceInfo: String,
        signals: SignalDetector,
    ): List<CrashEntity> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val watermark = prefs.getLong(KEY_WATERMARK, 0L)

        val exits = runCatching { am.getHistoricalProcessExitReasons(null, 0, 10) }
            .getOrNull() ?: return emptyList()
        if (exits.isEmpty()) return emptyList()

        val fresh = exits.filter { it.timestamp > watermark && it.processName == context.packageName }

        fresh.forEach { info ->
            val signal = signalFor(info.reason) ?: return@forEach
            signals.record(signal, info.timestamp, info.description ?: "exit status ${info.status}")
        }

        val ingested = fresh
            .filter { it.reason == ApplicationExitInfo.REASON_ANR || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
            .map { info ->
                val type = if (info.reason == ApplicationExitInfo.REASON_ANR) "ANR" else "NATIVE"
                val trace = runCatching {
                    info.traceInputStream?.bufferedReader()?.use { reader ->
                        val buf = CharArray(MAX_TRACE_BYTES)
                        val read = reader.read(buf)
                        if (read > 0) String(buf, 0, read) else null
                    }
                }.getOrNull()
                val entity = CrashEntity(
                    timestamp = info.timestamp,
                    sessionId = sessionDao.sessionActiveAt(info.timestamp) ?: EARLIER_SESSION_ID,
                    type = type,
                    threadName = null,
                    exceptionClass = null,
                    message = info.description,
                    stacktrace = trace ?: info.description ?: "No trace available",
                    deviceInfo = deviceInfo,
                    logContext = "",
                )
                entity.copy(id = dao.insert(entity))
            }

        prefs.edit().putLong(KEY_WATERMARK, exits.maxOf { it.timestamp }).apply()
        return ingested
    }

    /**
     * Exit reasons worth reporting as signals. ANR and both crash reasons are absent on purpose:
     * they become crash reports. So are EXIT_SELF and OTHER — an orderly shutdown is not news.
     */
    private fun signalFor(reason: Int): Signal? = when (reason) {
        ApplicationExitInfo.REASON_SIGNALED -> BuiltInSignals.PROCESS_SIGNALED
        ApplicationExitInfo.REASON_LOW_MEMORY -> BuiltInSignals.LOW_MEMORY_KILL
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> BuiltInSignals.INIT_FAILURE
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> BuiltInSignals.PERMISSION_CHANGE
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> BuiltInSignals.EXCESSIVE_RESOURCE
        ApplicationExitInfo.REASON_USER_REQUESTED -> BuiltInSignals.FORCE_STOPPED
        ApplicationExitInfo.REASON_USER_STOPPED -> BuiltInSignals.USER_STOPPED
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> BuiltInSignals.DEPENDENCY_DIED
        else -> null
    }
}
