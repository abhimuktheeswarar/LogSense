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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.data.EventEntity
import kotlinx.coroutines.launch

@Composable
internal fun EventsScreen(
    core: LogSenseCore,
    wide: Boolean,
    selectedId: Long?,
    onOpen: (Long) -> Unit,
) {
    val dao = remember { core.database.eventDao() }
    val events by remember { dao.observeAll() }.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(events, query) {
        if (query.isEmpty()) events else events.filter { it.name.contains(query, true) }
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search events") },
                    leadingIcon = { Icon(LogSenseIcons.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(LogSenseIcons.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                )
                IconButton(onClick = { core.scope.launch { dao.clear() } }) {
                    Icon(LogSenseIcons.Delete, contentDescription = "Delete all events")
                }
            }
            if (filtered.isEmpty()) {
                EmptyState("No analytics events yet.\nConfigure analyticsTags in LogSenseConfig and fire an event.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { event ->
                        EventRow(event, selected = wide && event.id == selectedId) { onOpen(event.id) }
                        HorizontalDivider()
                    }
                }
            }
        }
        if (wide && selectedId != null) {
            VerticalDivider()
            Box(Modifier.weight(1.2f)) { EventDetailPane(core, selectedId) }
        }
    }
}

@Composable
private fun EventRow(event: EventEntity, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(event.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row {
            Text(
                text = event.timestamp.asDateTime(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = event.tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (event.paramsJson.length > 2) { // not "{}"
            Text(
                text = event.paramsJson,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}
