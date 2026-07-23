package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.analytics.toMap
import com.msabhi.logsense.internal.data.CrashEntity
import com.msabhi.logsense.internal.data.EARLIER_SESSION_ID
import com.msabhi.logsense.internal.data.EventEntity
import com.msabhi.logsense.internal.ui.theme.liveColor
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun EventDetailPane(core: LogSenseCore, id: Long) {
    val event by produceState<EventEntity?>(initialValue = null, id) {
        value = core.database.eventDao().get(id)
    }
    event?.let { EventDetailContent(core, it) }
}

@Composable
private fun EventDetailContent(core: LogSenseCore, event: EventEntity) {
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
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SessionLine(core, event.sessionId)
            }
            ShareMenuButton(
                contentDescription = "Share event",
                fileLabel = "JSON file",
                onText = { ShareUtil.shareText(context, event.name, EventExport.toJsonString(event)) },
                onFile = { core.scope.launch { ShareUtil.shareJsonFile(context, event.name, EventExport.toJsonString(event)) } },
            )
        }

        if (params.isEmpty()) {
            Section("Parameters · 0")
            Text("No parameters", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Section("Parameters · ${params.size}")
            params.entries.sortedBy { it.key }.forEach { (key, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(0.4f),
                    )
                    SelectionContainer(Modifier.weight(0.6f)) {
                        Text(
                            text = value?.toString() ?: "null",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Section("Raw")
            val raw = remember(event.paramsJson) {
                runCatching { JSONObject(event.paramsJson).toString(2) }.getOrDefault(event.paramsJson)
            }
            MonoBlock(raw)
        }
    }
}

@Composable
internal fun CrashDetailPane(core: LogSenseCore, id: Long) {
    val crash by produceState<CrashEntity?>(initialValue = null, id) {
        value = core.database.crashDao().get(id)
    }
    crash?.let { CrashDetailContent(core, it) }
}

@Composable
private fun CrashDetailContent(core: LogSenseCore, crash: CrashEntity) {
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
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = crash.exceptionClass?.substringAfterLast('.') ?: crash.type,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                crash.message?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                }
                Text(
                    text = crash.timestamp.asDateTime() +
                        (crash.threadName?.let { "  ·  thread: $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SessionLine(core, crash.sessionId)
            }
            IconButton(onClick = { ShareUtil.shareText(context, "Crash report", crash.asShareText()) }) {
                Icon(LogSenseIcons.Share, contentDescription = "Share crash")
            }
        }

        Section("Device")
        MonoBlock(crash.deviceInfo)
        Section("Stacktrace")
        MonoBlock(crash.stacktrace, horizontallyScrollable = true)
        if (crash.logContext.isNotBlank()) {
            Section("Log context · last lines before crash")
            MonoBlock(crash.logContext, horizontallyScrollable = true)
        }
    }
}

/** "● CURRENT · started 14:06" or "PREVIOUS · Jul 23, 09:12" for the row's source session. */
@Composable
private fun SessionLine(core: LogSenseCore, sessionId: String) {
    val current = sessionId == core.sessionId
    val startedAt by produceState<Long?>(initialValue = null, sessionId) {
        value = if (current) null else core.database.sessionDao().get(sessionId)?.startedAt
    }
    val label = when {
        current -> "CURRENT · this run"
        sessionId == EARLIER_SESSION_ID -> "PREVIOUS · earlier run"
        (startedAt ?: 0L) > 0L -> "PREVIOUS · ${startedAt!!.asDateTime()}"
        else -> "PREVIOUS run"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.4.sp),
        color = if (current) liveColor() else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp),
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun MonoBlock(text: String, horizontallyScrollable: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val inner = if (horizontallyScrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceContainerLowest)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        SelectionContainer(inner) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = cs.onSurface,
            )
        }
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
