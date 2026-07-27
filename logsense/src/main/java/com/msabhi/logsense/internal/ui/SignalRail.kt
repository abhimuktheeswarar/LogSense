package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** One dot: [fraction] is its position down the stream (0 = oldest, 1 = newest). */
internal data class RailMark(val fraction: Float, val color: Color, val entryId: Long)

/**
 * A minimap of the signals in the whole captured stream: one dot per signalled line, placed by where
 * that line sits from oldest (top) to newest (bottom). Tapping a dot jumps to its line.
 *
 * The dots are fixed — they describe the buffer, not the viewport, so they don't move as you scroll.
 * That only reads as sensible next to the faint bracket showing the slice you're currently looking
 * at: without it a static overlay looks decorative, with it you can see at a glance whether a signal
 * is above or below you.
 *
 * Deliberately no full-height track behind the dots: a strip down the right edge reads as a
 * scrollbar, which this is not.
 */
@Composable
internal fun SignalRail(
    marks: List<RailMark>,
    rowCount: Int,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onSelect: (Long) -> Unit,
) {
    if (marks.isEmpty()) return
    val viewportColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
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

            // listState is read here, inside the draw lambda, so scrolling only redraws the rail —
            // reading it in the composable body would recompose the whole list on every frame.
            val visible = listState.layoutInfo.visibleItemsInfo
            val firstRow = visible.firstOrNull()?.index
            val lastRow = visible.lastOrNull()?.index
            if (firstRow != null && lastRow != null && rowCount > 1) {
                val span = (rowCount - 1).toFloat()
                val x = VIEWPORT_INSET.toPx()
                drawLine(
                    color = viewportColor,
                    start = Offset(x, (firstRow / span).coerceIn(0f, 1f) * usable),
                    end = Offset(x, (lastRow / span).coerceIn(0f, 1f) * usable),
                    strokeWidth = VIEWPORT_WIDTH.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            marks.forEach { mark ->
                drawCircle(
                    color = mark.color,
                    radius = DOT_RADIUS.toPx(),
                    center = Offset(x = size.width - DOT_RADIUS.toPx(), y = mark.fraction.coerceIn(0f, 1f) * usable),
                )
            }
        }
    }
}

/** Also the width the log list reserves on its right edge so text never runs under the rail. */
internal val SIGNAL_RAIL_WIDTH = 14.dp
private val DOT_RADIUS = 3.dp
private val VIEWPORT_WIDTH = 2.dp
private val VIEWPORT_INSET = 2.dp
