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
 * A minimap of the signals in the whole captured stream: one dot per signalled row, placed by where
 * that row sits from oldest (top) to newest (bottom). Tapping a dot jumps to its line.
 *
 * The dots are fixed on purpose — they describe the buffer, not the viewport, so they don't move as
 * you scroll. Rows already carry their own gutter stripe and pill; dots that scrolled with the
 * content would only repeat that.
 *
 * Two things deliberately absent:
 * - **No track.** A full-height strip down the right edge reads as a scrollbar, which this is not.
 * - **No viewport indicator.** A proportional "you are here" marker is sized visible-rows over
 *   total-rows, and the buffer runs to [com.msabhi.logsense.LogSenseConfig.maxBufferedLines] — at
 *   the 50k default that is a fraction of a pixel. It degenerates by construction, so the rail says
 *   where the signals are and leaves where-you-are to the jump-to-latest control and the Signals tab.
 */
@Composable
internal fun SignalRail(
    marks: List<RailMark>,
    modifier: Modifier = Modifier,
    onSelect: (Long) -> Unit,
) {
    if (marks.isEmpty()) return
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
