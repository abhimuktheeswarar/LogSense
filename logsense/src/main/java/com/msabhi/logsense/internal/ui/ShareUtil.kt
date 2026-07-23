package com.msabhi.logsense.internal.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
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
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.com.msabhi.logsense.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser(intent))
    }

    /** Writes [json] to a .json file and shares it (application/json), for event export. */
    suspend fun shareJsonFile(context: Context, baseName: String, json: String) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "logsense/share").apply { mkdirs() }
        val file = File(dir, "${baseName}_${System.currentTimeMillis()}.json")
        file.writeText(json)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.com.msabhi.logsense.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser(intent))
    }

    private fun chooser(intent: Intent): Intent =
        Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
