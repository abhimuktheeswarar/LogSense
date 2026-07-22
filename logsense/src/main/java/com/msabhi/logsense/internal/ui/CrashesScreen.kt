package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.data.CrashEntity
import com.msabhi.logsense.internal.reader.LogLevel
import com.msabhi.logsense.internal.ui.theme.color
import kotlinx.coroutines.launch

@Composable
internal fun CrashesScreen(
    core: LogSenseCore,
    wide: Boolean,
    selectedId: Long?,
    onOpen: (Long) -> Unit,
) {
    val dao = remember { core.database.crashDao() }
    val crashes by remember { dao.observeAll() }.collectAsState(initial = emptyList())

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${crashes.size} crash report(s)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = { core.scope.launch { dao.clear() } }) {
                    Icon(LogSenseIcons.Delete, contentDescription = "Delete all crashes")
                }
            }
            if (crashes.isEmpty()) {
                EmptyState("No crashes captured. That's a good thing.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(crashes, key = { it.id }) { crash ->
                        CrashRow(crash, selected = wide && crash.id == selectedId) { onOpen(crash.id) }
                        HorizontalDivider()
                    }
                }
            }
        }
        if (wide && selectedId != null) {
            VerticalDivider()
            Box(Modifier.weight(1.2f)) { CrashDetailPane(core, selectedId) }
        }
    }
}

@Composable
internal fun CrashTypeBadge(type: String) {
    val color = when (type) {
        "ANR" -> LogLevel.WARN.color()
        "NATIVE" -> LogLevel.FATAL.color()
        else -> LogLevel.ERROR.color()
    }
    Text(
        text = type,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun CrashRow(crash: CrashEntity, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CrashTypeBadge(crash.type)
            Spacer(Modifier.width(8.dp))
            Text(
                text = crash.exceptionClass?.substringAfterLast('.') ?: crash.type,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        crash.message?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = crash.timestamp.asDateTime(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}