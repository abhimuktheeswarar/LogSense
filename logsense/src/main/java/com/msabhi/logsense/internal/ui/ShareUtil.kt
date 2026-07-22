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

    suspend fun shareLogFile(context: Context, entries: List<LogEntry>) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "logsense/share").apply { mkdirs() }
        val file = File(dir, "logsense_logs_${System.currentTimeMillis()}.txt")
        file.bufferedWriter().use { writer ->
            entries.forEach { e ->
                writer.write("${e.timeMs.asTime()} ${e.pid}-${e.tid} ${e.level.letter} ${e.tag}: ${e.message}")
                writer.newLine()
            }
        }
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

    private fun chooser(intent: Intent): Intent =
        Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
