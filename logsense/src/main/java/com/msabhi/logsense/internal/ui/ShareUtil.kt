package com.msabhi.logsense.internal.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.msabhi.logsense.internal.data.CrashEntity
import com.msabhi.logsense.internal.reader.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal object ShareUtil {

    fun shareText(context: Context, subject: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(chooser(intent))
    }

    /** Human-readable crash report — shared from both the detail screen and the crash notification. */
    fun crashToText(crash: CrashEntity): String = buildString {
        appendLine("${crash.type}: ${crash.exceptionClass ?: ""}")
        crash.message?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        appendLine(crash.timestamp.asDateTime())
        crash.threadName?.let { appendLine("Thread: $it") }
        appendLine()
        appendLine(crash.deviceInfo)
        appendLine()
        appendLine(crash.stacktrace)
        if (crash.logContext.isNotBlank()) {
            appendLine()
            appendLine("--- Log context ---")
            appendLine(crash.logContext)
        }
    }

    fun shareCrash(context: Context, crash: CrashEntity) =
        shareText(context, "Crash report", crashToText(crash))

    /** One "time pid-tid L tag: message" line per entry. */
    private fun logsToText(entries: List<LogEntry>): String = buildString {
        entries.forEach { e ->
            append("${e.timeMs.asTime()} ${e.pid}-${e.tid} ${e.level.letter} ${e.tag}: ${e.message}")
            append('\n')
        }
    }

    /** Shares the (already filtered) logs inline as plain text. */
    fun shareLogText(context: Context, entries: List<LogEntry>) =
        shareText(context, "logs", logsToText(entries))

    /** Shares the (already filtered) logs as a .txt file. */
    suspend fun shareLogFile(context: Context, entries: List<LogEntry>) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "logsense/share").apply { mkdirs() }
        val file = File(dir, "logsense_logs_${System.currentTimeMillis()}.txt")
        file.writeText(logsToText(entries))
        sendFile(context, fileUri(context, file), "text/plain")
    }

    /** Writes [json] to a .json file and shares it (application/json), for event export. */
    suspend fun shareJsonFile(context: Context, baseName: String, json: String) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "logsense/share").apply { mkdirs() }
        val file = File(dir, "${baseName}_${System.currentTimeMillis()}.json")
        file.writeText(json)
        sendFile(context, fileUri(context, file), "application/json")
    }

    private fun fileUri(context: Context, file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.com.msabhi.logsense.fileprovider",
        file,
    )

    private fun sendFile(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData carries the grant to the sharesheet itself, so it can render a file preview.
        intent.clipData = ClipData.newRawUri(null, uri)
        context.startActivity(chooser(intent))
    }

    private fun chooser(intent: Intent): Intent =
        Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
