package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** One dot: [fraction] is its position down the stream (0 = oldest, 1 = newest). */
internal data class RailMark(val fraction: Float, val color: Color, val entryId: Long)

/**
 * A minimap of the signals in the current view: a thin strip down the right edge with one dot per
 * hit, positioned by where that line sits in the filtered stream. Tapping a dot jumps to its line.
 *
 * ponytail: the position is the line's index among filtered *entries*, while the list also renders a
 * tag band every so often — so a dot can sit a row or two off. It's a minimap; being within a screen
 * of the line is the whole job, and the tap uses the entry id, not the position.
 */
@Composable
internal fun SignalRail(
    marks: List<RailMark>,
    modifier: Modifier = Modifier,
    onSelect: (Long) -> Unit,
) {
    if (marks.isEmpty()) return
    // No track behind the dots on purpose: a full-height strip down the right edge reads as a
    // scrollbar, which this is not. The dots alone read as markers.
    Box(
        modifier
            .fillMaxHeight()
            .width(SIGNAL_RAIL_WIDTH)
            .pointerInput(marks) {
                detectTapGestures { offset: Offset ->
                    val height = size.height.toFloat()
                    if (height <= 0f) return@detectTapGestures
                    val nearest = marks.minByOrNull { abs(it.fraction * height - offset.y) }
                    if (nearest != null) onSelect(nearest.entryId)
                }
            },
    ) {
        Canvas(Modifier.fillMaxHeight().width(SIGNAL_RAIL_WIDTH).padding(vertical = DOT_RADIUS)) {
            val usable = size.height
            marks.forEach { mark ->
                drawCircle(
                    color = mark.color,
                    radius = DOT_RADIUS.toPx(),
                    center = Offset(x = size.width / 2f, y = mark.fraction.coerceIn(0f, 1f) * usable),
                )
            }
        }
    }
}

/** Also the width the log list reserves on its right edge so text never runs under the rail. */
internal val SIGNAL_RAIL_WIDTH = 12.dp
private val DOT_RADIUS = 3.dp
