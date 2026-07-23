package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msabhi.logsense.internal.search.SearchQuery
import com.msabhi.logsense.internal.search.TextMatcher

/**
 * Android-Studio-style find bar: a distinct band with a text field, a match counter, prev/next
 * navigation, and match-case / whole-word / regex toggles. It searches *within* the current view
 * (highlight, don't remove) — narrowing is the separate filter's job. Reused by Logs and Events.
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
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // find field (primary-bordered)
        Row(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(cs.surface)
                .border(1.dp, cs.primary, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(LogSenseIcons.Search, contentDescription = null, tint = cs.primary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.text.isEmpty()) {
                    Text("Find", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                BasicTextField(
                    value = query.text,
                    onValueChange = { onQueryChange(query.copy(text = it)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                )
            }
        }
        if (query.isActive) {
            val pos = if (matchCount == 0) 0 else currentMatch + 1
            Text(
                "$pos/$matchCount",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = cs.onSurfaceVariant,
            )
            NavIcon(LogSenseIcons.ArrowUp, "Previous match", enabled = matchCount > 0, onClick = onPrev)
            NavIcon(LogSenseIcons.ArrowDown, "Next match", enabled = matchCount > 0, onClick = onNext)
        }
        FindChip("Aa", query.matchCase) { onQueryChange(query.copy(matchCase = !query.matchCase)) }
        FindChip("W", query.wholeWord) { onQueryChange(query.copy(wholeWord = !query.wholeWord)) }
        FindChip(".*", query.regex) { onQueryChange(query.copy(regex = !query.regex)) }
        NavIcon(LogSenseIcons.Close, "Close search", enabled = true, onClick = onClose)
    }
}

@Composable
private fun NavIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f)
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun FindChip(label: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .height(30.dp)
            .defaultMinSize(minWidth = 30.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (on) Modifier.background(cs.secondaryContainer)
                else Modifier.border(1.dp, cs.outline, RoundedCornerShape(8.dp)),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
            color = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant,
        )
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
