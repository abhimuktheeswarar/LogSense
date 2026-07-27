package com.msabhi.logsense.internal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** One dot: [fraction] is its position down the stream (0 = oldest, 1 = newest). */
internal data class RailMark(val fraction: Float, val color: Color, val entryId: Long)

/**
 * A minimap of the signals in the whole captured stream: one dot per signalled row, placed by where
 * that row sits from oldest (top) to newest (bottom). Read-only — a glance that answers "is there
 * anything wrong further down, and roughly where".
 *
 * The dots are fixed on purpose: they describe the buffer, not the viewport, so they don't move as
 * you scroll. Rows already carry their own gutter stripe and pill; dots that scrolled with the
 * content would only repeat that.
 *
 * Three things deliberately absent:
 * - **No track.** A full-height strip down the right edge reads as a scrollbar, which this is not.
 * - **No viewport indicator.** A proportional "you are here" marker is sized visible-rows over
 *   total-rows, and the buffer runs to [com.msabhi.logsense.LogSenseConfig.maxBufferedLines] — at
 *   the 50k default that is a fraction of a pixel.
 * - **No tap.** Not a touch-target problem — a resolution one. The rail is ~516dp tall, so it holds
 *   about 11 dots at the 48dp minimum target while the detector holds up to 500 hits; at a full
 *   buffer one pixel is ~45 rows, so signals less than ~750 rows apart share a dot. A tap could only
 *   pick one of an unknown number of merged signals, arbitrarily. Jumping to a specific signal
 *   belongs on the Signals tab, where the targets are full-width labelled rows.
 */
@Composable
internal fun SignalRail(
    marks: List<RailMark>,
    modifier: Modifier = Modifier,
) {
    if (marks.isEmpty()) return
    Box(modifier.fillMaxHeight().width(SIGNAL_RAIL_WIDTH)) {
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
