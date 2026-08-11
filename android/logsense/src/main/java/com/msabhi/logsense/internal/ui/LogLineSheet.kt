package com.msabhi.logsense.internal.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.internal.reader.LogEntry
import com.msabhi.logsense.internal.signals.Signal
import com.msabhi.logsense.internal.ui.theme.color
import com.msabhi.logsense.internal.ui.theme.tagColor
import org.json.JSONArray
import org.json.JSONObject

/**
 * The whole of one log line, on tap: nothing is truncated here, JSON is offered pretty-printed, and
 * the text can be selected, copied or shared. The list rows stay dense precisely because this exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogLineSheet(
    entry: LogEntry,
    signal: Signal?,
    onFilterByTag: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val levelColor = entry.level.color()
    val pretty = remember(entry.id) { prettyJson(entry.message) }
    var showPretty by remember(entry.id) { mutableStateOf(pretty != null) }
    val body = if (showPretty && pretty != null) pretty else entry.message

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 3.dp, height = 17.dp).clip(RoundedCornerShape(2.dp)).background(levelColor))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.level.letter.toString(),
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                    color = levelColor,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = entry.tag,
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                    color = tagColor(entry.tag),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = entry.timeMs.asTime(),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = cs.onSurfaceVariant,
                )
            }

            if (signal != null) {
                Spacer(Modifier.height(10.dp))
                SignalPill(signal)
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "pid ${entry.pid} · tid ${entry.tid}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = cs.onSurfaceVariant.copy(alpha = 0.7f),
            )

            if (pretty != null) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectPill("Pretty", showPretty) { showPretty = true }
                    SelectPill("Raw", !showPretty) { showPretty = false }
                }
            }

            Spacer(Modifier.height(12.dp))
            Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                MonoBlock(body)
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { copyToClipboard(context, entry.tag, body) }) {
                    Icon(LogSenseIcons.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
                TextButton(onClick = { ShareUtil.shareText(context, entry.tag, body) }) {
                    Icon(LogSenseIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
                TextButton(onClick = { onFilterByTag(entry.tag); onDismiss() }) {
                    Icon(LogSenseIcons.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("tag:${entry.tag}")
                }
            }
        }
    }
}

/** A small colored chip naming the signal a line matched. */
@Composable
internal fun SignalPill(signal: Signal, modifier: Modifier = Modifier) {
    val color = signal.category.color()
    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = signal.label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}

/**
 * The message re-printed as indented JSON, or null when it holds no JSON worth expanding. Looks for
 * the outermost `{…}` / `[…]` in the line, so `Response: {"a":1}` prettifies too.
 */
internal fun prettyJson(message: String): String? {
    val start = message.indexOfFirst { it == '{' || it == '[' }
    if (start < 0) return null
    val open = message[start]
    val close = if (open == '{') '}' else ']'
    val end = message.lastIndexOf(close)
    if (end <= start) return null
    val candidate = message.substring(start, end + 1)
    val formatted = runCatching {
        if (open == '{') JSONObject(candidate).toString(2) else JSONArray(candidate).toString(2)
    }.getOrNull() ?: return null
    val prefix = message.take(start).trim()
    return if (prefix.isEmpty()) formatted else "$prefix\n$formatted"
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
