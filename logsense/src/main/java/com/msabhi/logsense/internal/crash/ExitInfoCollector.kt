package com.msabhi.logsense.internal.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.msabhi.logsense.internal.data.CrashDao
import com.msabhi.logsense.internal.data.CrashEntity

/**
 * Records ANRs and native crashes from [ApplicationExitInfo] on launch (API 30+).
 * JVM crashes are excluded — the uncaught-exception handler already captured those.
 */
internal object ExitInfoCollector {

    private const val PREFS = "logsense_prefs"
    private const val KEY_WATERMARK = "last_exit_ts"
    private const val MAX_TRACE_BYTES = 256 * 1024

    suspend fun collect(context: Context, dao: CrashDao, deviceInfo: String): List<CrashEntity> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val watermark = prefs.getLong(KEY_WATERMARK, 0L)

        val exits = runCatching { am.getHistoricalProcessExitReasons(null, 0, 10) }
            .getOrNull() ?: return emptyList()
        if (exits.isEmpty()) return emptyList()

        val ingested = exits
            .filter { it.timestamp > watermark && it.processName == context.packageName }
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
}
