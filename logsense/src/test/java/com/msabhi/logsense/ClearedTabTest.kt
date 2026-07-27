package com.msabhi.logsense

import com.msabhi.logsense.internal.logs.since
import com.msabhi.logsense.internal.reader.LogEntry
import com.msabhi.logsense.internal.reader.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearedTabTest {

    private fun entries(ids: LongRange) = ids.map {
        LogEntry(
            id = it,
            timeMs = it,
            epochRaw = "0.0",
            pid = 1,
            tid = 1,
            level = LogLevel.INFO,
            tag = "t",
            message = "line $it",
        )
    }

    @Test
    fun `an uncleared tab sees the whole buffer`() {
        val all = entries(0L..9L)
        assertSame(all, all.since(0L))
    }

    @Test
    fun `clearing hides everything up to and including the watermark`() {
        val all = entries(0L..9L)
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L), all.since(4L).map { it.id })
    }

    @Test
    fun `clearing at the newest line leaves the tab empty`() {
        val all = entries(0L..9L)
        assertTrue(all.since(9L).isEmpty())
    }

    @Test
    fun `a watermark before the oldest buffered line hides nothing`() {
        // The buffer evicts as it fills, so the watermark can fall off the front.
        val all = entries(100L..109L)
        assertEquals(10, all.since(50L).size)
    }

    @Test
    fun `a stale watermark from a previous reader is ignored`() {
        // The reader restarts its id counter, so a watermark can end up ahead of every line.
        // Without the guard the tab would show nothing until ids climbed past it again.
        val afterRestart = entries(0L..4L)
        assertEquals(5, afterRestart.since(9_999L).size)
    }

    @Test
    fun `an empty buffer stays empty`() {
        assertTrue(emptyList<LogEntry>().since(7L).isEmpty())
    }

    @Test
    fun `a single remaining line is kept or dropped on the right side of the boundary`() {
        val one = entries(7L..7L)
        assertEquals(1, one.since(6L).size)
        assertTrue(one.since(7L).isEmpty())
    }

    @Test
    fun `every boundary in a buffer with gaps lands correctly`() {
        // Ids skip: continuation lines fold into the previous entry, so the buffer isn't contiguous.
        val sparse = listOf(2L, 5L, 9L, 14L).map { id -> entries(id..id).single() }
        assertEquals(listOf(5L, 9L, 14L), sparse.since(2L).map { it.id })
        assertEquals(listOf(5L, 9L, 14L), sparse.since(4L).map { it.id })
        assertEquals(listOf(9L, 14L), sparse.since(5L).map { it.id })
        assertEquals(listOf(14L), sparse.since(13L).map { it.id })
    }
}
