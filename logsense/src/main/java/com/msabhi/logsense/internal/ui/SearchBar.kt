package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.internal.search.SearchQuery
import com.msabhi.logsense.internal.search.TextMatcher

/**
 * Android-Studio-style find bar: text + match-case / whole-word / regex toggles, a match counter,
 * and prev/next navigation. It searches *within* the current content — narrowing is done by the
 * separate filter controls. Reused by the Logs and Events screens.
 */
@Composable
internal fun SearchBar(
    query: SearchQuery,
    onQueryChange: (SearchQuery) -> Unit,
    matchCount: Int,
    currentMatch: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = query.text,
            onValueChange = { onQueryChange(query.copy(text = it)) },
            modifier = Modifier.widthIn(min = 160.dp),
            placeholder = { Text("Find") },
            leadingIcon = { Icon(LogSenseIcons.Search, contentDescription = null) },
            singleLine = true,
        )
        if (query.isActive) {
            val position = if (matchCount == 0) 0 else currentMatch + 1
            Text(
                text = "$position/$matchCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onPrev, enabled = matchCount > 0) {
                Icon(LogSenseIcons.ArrowUp, contentDescription = "Previous match")
            }
            IconButton(onClick = onNext, enabled = matchCount > 0) {
                Icon(LogSenseIcons.ArrowDown, contentDescription = "Next match")
            }
        }
        FilterChip(
            selected = query.matchCase,
            onClick = { onQueryChange(query.copy(matchCase = !query.matchCase)) },
            label = { Text("Aa") },
        )
        FilterChip(
            selected = query.wholeWord,
            onClick = { onQueryChange(query.copy(wholeWord = !query.wholeWord)) },
            label = { Text("W") },
        )
        FilterChip(
            selected = query.regex,
            onClick = { onQueryChange(query.copy(regex = !query.regex)) },
            label = { Text(".*") },
        )
        IconButton(onClick = onClose) {
            Icon(LogSenseIcons.Close, contentDescription = "Close search")
        }
    }
}

/** Wraps every match of [matcher] in [text] with a highlight background. */
internal fun highlight(text: String, matcher: TextMatcher?, color: Color): AnnotatedString {
    val ranges = matcher?.ranges(text).orEmpty()
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { r ->
            val end = (r.last + 1).coerceAtMost(text.length)
            if (r.first in 0 until end) addStyle(SpanStyle(background = color), r.first, end)
        }
    }
}
