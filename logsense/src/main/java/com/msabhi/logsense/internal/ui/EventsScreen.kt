package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.msabhi.logsense.internal.search.SearchQuery
import com.msabhi.logsense.internal.search.TextMatcher
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

    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) } // null = All tags
    var filterText by rememberSaveable { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(SearchQuery()) }

    // Union of configured analytics tags and tags actually seen, so a tab exists even before an event lands.
    val tags = remember(events) { (core.config.analyticsTags + events.map { it.tag }).toSortedSet().toList() }

    // Filter narrows the list (and keeps applying as new events arrive).
    val filtered = remember(events, selectedTag, filterText) {
        events.filter { e ->
            (selectedTag == null || e.tag == selectedTag) &&
                (
                    filterText.isEmpty() ||
                        e.name.contains(filterText, true) ||
                        e.paramsJson.contains(filterText, true) ||
                        e.tag.contains(filterText, true)
                    )
        }
    }

    val matcher = remember(search) { if (search.isActive) TextMatcher.from(search) else null }
    val matchIndices = remember(filtered, matcher) {
        if (matcher == null) emptyList()
        else filtered.indices.filter { matcher.matches("${filtered[it].name} ${filtered[it].paramsJson}") }
    }
    var matchPos by remember(matcher) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(matchPos, matchIndices) { matchIndices.getOrNull(matchPos)?.let { listState.scrollToItem(it) } }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Filter events") },
                    trailingIcon = {
                        if (filterText.isNotEmpty()) {
                            IconButton(onClick = { filterText = "" }) {
                                Icon(LogSenseIcons.Close, contentDescription = "Clear filter")
                            }
                        }
                    },
                    singleLine = true,
                )
                IconButton(onClick = { searchOpen = !searchOpen }) {
                    Icon(LogSenseIcons.Search, contentDescription = "Find")
                }
                IconButton(onClick = { core.scope.launch { dao.clear() } }) {
                    Icon(LogSenseIcons.Delete, contentDescription = "Delete all events")
                }
            }

            if (searchOpen) {
                SearchBar(
                    query = search,
                    onQueryChange = { search = it; matchPos = 0 },
                    matchCount = matchIndices.size,
                    currentMatch = matchPos,
                    onPrev = { if (matchIndices.isNotEmpty()) matchPos = (matchPos - 1 + matchIndices.size) % matchIndices.size },
                    onNext = { if (matchIndices.isNotEmpty()) matchPos = (matchPos + 1) % matchIndices.size },
                    onClose = { searchOpen = false; search = SearchQuery() },
                )
            }

            if (tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(selected = selectedTag == null, onClick = { selectedTag = null }, label = { Text("All") })
                    tags.forEach { tag ->
                        FilterChip(selected = selectedTag == tag, onClick = { selectedTag = tag }, label = { Text(tag) })
                    }
                }
            }

            if (filtered.isEmpty()) {
                EmptyState("No analytics events yet.\nConfigure analyticsTags in LogSenseConfig and fire an event.")
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { event ->
                        EventRow(event, selected = wide && event.id == selectedId, matcher) { onOpen(event.id) }
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
private fun EventRow(event: EventEntity, selected: Boolean, matcher: TextMatcher?, onClick: () -> Unit) {
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = highlight(event.name, matcher, highlightColor),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
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
                text = highlight(event.paramsJson, matcher, highlightColor),
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
