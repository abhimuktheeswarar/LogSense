package com.msabhi.logsense

import com.msabhi.logsense.internal.data.CrashEntity
import com.msabhi.logsense.internal.signals.BuiltInSignals
import com.msabhi.logsense.internal.signals.Signal
import com.msabhi.logsense.internal.signals.SignalHit
import com.msabhi.logsense.internal.ui.SignalRow
import com.msabhi.logsense.internal.ui.collapseRepeats
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalRowsTest {

    private fun hit(signal: Signal, timeMs: Long) =
        SignalRow.Hit(SignalHit(signal, entryId = timeMs, timeMs = timeMs, tag = "t", preview = "p"))

    private fun crash(id: Long) = SignalRow.Crash(
        CrashEntity(
            id = id,
            timestamp = id,
            sessionId = "s",
            type = "JVM",
            threadName = null,
            exceptionClass = null,
            message = null,
            stacktrace = "",
            deviceInfo = "",
            logContext = "",
        ),
    )

    private val strict = BuiltInSignals.CATALOG.first { it.id == "anr.strictmode" }
    private val jank = BuiltInSignals.CATALOG.first { it.id == "anr.skipped_frames" }

    @Test
    fun `a run of the same signal folds into one row with a count`() {
        val rows = collapseRepeats(listOf(hit(strict, 5), hit(strict, 4), hit(strict, 3)))
        assertEquals(1, rows.size)
        assertEquals(3, rows.single().occurrences)
    }

    @Test
    fun `the newest of a run is the one kept, so a jump lands on it`() {
        val rows = collapseRepeats(listOf(hit(strict, 5), hit(strict, 4)))
        assertEquals(5L, rows.single().timeMs)
    }

    @Test
    fun `different signals are never folded together`() {
        val rows = collapseRepeats(listOf(hit(strict, 5), hit(jank, 4), hit(strict, 3)))
        assertEquals(listOf(1, 1, 1), rows.map { it.occurrences })
        assertEquals(3, rows.size)
    }

    @Test
    fun `a crash between two identical signals breaks the run`() {
        val rows = collapseRepeats(listOf(hit(strict, 5), crash(4), hit(strict, 3)))
        assertEquals(3, rows.size)
    }

    @Test
    fun `crashes are never folded, even back to back`() {
        val rows = collapseRepeats(listOf(crash(2), crash(1)))
        assertEquals(2, rows.size)
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(emptyList<SignalRow>(), collapseRepeats(emptyList()))
    }

    @Test
    fun `folding never loses an occurrence`() {
        val input = listOf(hit(strict, 6), hit(strict, 5), hit(jank, 4), crash(3), hit(jank, 2), hit(jank, 1))
        assertEquals(input.size, collapseRepeats(input).sumOf { it.occurrences })
    }
}
