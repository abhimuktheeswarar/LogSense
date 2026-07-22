package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.analytics.toMap
import com.msabhi.logsense.internal.data.CrashEntity
import com.msabhi.logsense.internal.data.EventEntity
import org.json.JSONObject

@Composable
internal fun EventDetailPane(core: LogSenseCore, id: Long) {
    val event by produceState<EventEntity?>(initialValue = null, id) {
        value = core.database.eventDao().get(id)
    }
    event?.let { EventDetailContent(it) }
}

@Composable
private fun EventDetailContent(event: EventEntity) {
    val context = LocalContext.current
    val params = remember(event.paramsJson) {
        runCatching { JSONObject(event.paramsJson).toMap() }.getOrDefault(emptyMap())
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(event.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "${event.timestamp.asDateTime()}  ·  ${event.tag}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = {
                val pretty = runCatching { JSONObject(event.paramsJson).toString(2) }
                    .getOrDefault(event.paramsJson)
                ShareUtil.shareText(context, event.name, "${event.name}\n${event.timestamp.asDateTime()}\n$pretty")
            }) {
                Icon(LogSenseIcons.Share, contentDescription = "Share event")
            }
        }
        Spacer(Modifier.height(16.dp))
        if (params.isEmpty()) {
            Text(
                text = "No parameters",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            params.entries.sortedBy { it.key }.forEach { (key, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(0.4f),
                    )
                    SelectionContainer(Modifier.weight(0.6f)) {
                        Text(
                            text = value?.toString() ?: "null",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
internal fun CrashDetailPane(core: LogSenseCore, id: Long) {
    val crash by produceState<CrashEntity?>(initialValue = null, id) {
        value = core.database.crashDao().get(id)
    }
    crash?.let { CrashDetailContent(it) }
}

@Composable
private fun CrashDetailContent(crash: CrashEntity) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CrashTypeBadge(crash.type)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = crash.exceptionClass?.substringAfterLast('.') ?: crash.type,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                crash.message?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = crash.timestamp.asDateTime() +
                        (crash.threadName?.let { "  ·  thread: $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { ShareUtil.shareText(context, "Crash report", crash.asShareText()) }) {
                Icon(LogSenseIcons.Share, contentDescription = "Share crash")
            }
        }

        Section("Device") { MonoText(crash.deviceInfo) }
        Section("Stacktrace") { MonoText(crash.stacktrace, horizontallyScrollable = true) }
        if (crash.logContext.isNotBlank()) {
            Section("Log context (last lines before crash)") {
                MonoText(crash.logContext, horizontallyScrollable = true)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
    content()
}

@Composable
private fun MonoText(text: String, horizontallyScrollable: Boolean = false) {
    val modifier = if (horizontallyScrollable) {
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    } else {
        Modifier.fillMaxWidth()
    }
    SelectionContainer(modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun CrashEntity.asShareText(): String = buildString {
    appendLine("${type}: ${exceptionClass ?: ""}")
    message?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
    appendLine(timestamp.asDateTime())
    threadName?.let { appendLine("Thread: $it") }
    appendLine()
    appendLine(deviceInfo)
    appendLine()
    appendLine(stacktrace)
    if (logContext.isNotBlank()) {
        appendLine()
        appendLine("--- Log context ---")
        appendLine(logContext)
    }
}
