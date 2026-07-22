package com.msabhi.logsense.internal.crash

import android.util.Log
import com.msabhi.logsense.internal.data.CrashDao
import com.msabhi.logsense.internal.data.CrashEntity
import com.msabhi.logsense.internal.reader.LogEntry
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Crash reports are written as fsync'd files at crash time and ingested into Room on the
 * next healthy launch. SQLite in a dying process risks corruption; an atomic tmp-write →
 * fsync → rename never does.
 */
internal class CrashFileStore(filesDir: File, private val deviceInfo: String) {

    private val dir = File(filesDir, "logsense/crashes")

    /** Called on the crashing thread. Must be synchronous and must never throw. */
    fun writeCrash(thread: Thread, throwable: Throwable, logContext: List<LogEntry>) {
        try {
            dir.mkdirs()
            val timestamp = System.currentTimeMillis()
            val json = JSONObject()
                .put("timestamp", timestamp)
                .put("type", "JVM")
                .put("threadName", thread.name)
                .put("exceptionClass", throwable.javaClass.name)
                .put("message", throwable.message ?: "")
                .put("stacktrace", Log.getStackTraceString(throwable))
                .put("deviceInfo", deviceInfo)
                .put("logContext", logContext.joinToString("\n") { it.format() })
            val tmp = File(dir, "crash_$timestamp.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(json.toString().toByteArray())
                out.fd.sync()
            }
            tmp.renameTo(File(dir, "crash_$timestamp.json"))
        } catch (_: Throwable) {
            // never interfere with the crash in progress
        }
    }

    /** Moves pending crash files into Room. Returns the inserted rows (for notifications). */
    suspend fun ingestInto(dao: CrashDao): List<CrashEntity> {
        val files = dir.listFiles() ?: return emptyList()
        val ingested = mutableListOf<CrashEntity>()
        for (file in files) {
            if (file.extension != "json") {
                file.delete() // leftover .tmp from a death mid-write
                continue
            }
            val entity = runCatching {
                val json = JSONObject(file.readText())
                CrashEntity(
                    timestamp = json.getLong("timestamp"),
                    type = json.getString("type"),
                    threadName = json.optString("threadName"),
                    exceptionClass = json.optString("exceptionClass"),
                    message = json.optString("message"),
                    stacktrace = json.getString("stacktrace"),
                    deviceInfo = json.optString("deviceInfo"),
                    logContext = json.optString("logContext"),
                )
            }.getOrNull()
            if (entity != null) {
                ingested += entity.copy(id = dao.insert(entity))
            }
            file.delete()
        }
        return ingested
    }
}

private fun LogEntry.format(): String = "$epochRaw $pid $tid ${level.letter} $tag: $message"
