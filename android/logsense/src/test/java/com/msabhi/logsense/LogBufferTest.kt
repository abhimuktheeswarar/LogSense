package com.msabhi.logsense

import com.msabhi.logsense.internal.reader.LogBuffer
import com.msabhi.logsense.internal.reader.LogEntry
import com.msabhi.logsense.internal.reader.LogLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBufferTest {

    private fun lines(count: Int) = (1..count).map {
        LogEntry(
            id = it.toLong(),
            timeMs = it.toLong(),
            epochRaw = "0.0",
            pid = 1,
            tid = 1,
            level = LogLevel.INFO,
            tag = "t",
            message = "line $it",
        )
    }

    @Test
    fun `a hostile cap cannot make the buffer pop an empty deque`() = runBlocking {
        // maxBufferedLines = 0 or a negative typo previously trimmed past empty on the first batch.
        listOf(0, -1, Int.MIN_VALUE).forEach { cap ->
            val buffer = LogBuffer(cap)
            buffer.append(lines(5))
            assertEquals("cap $cap should keep the newest line", 1, buffer.crashSnapshot().size)
        }
    }

    @Test
    fun `the cap keeps the newest lines and drops the oldest`() = runBlocking {
        val buffer = LogBuffer(3)
        buffer.append(lines(10))
        assertEquals(listOf(8L, 9L, 10L), buffer.crashSnapshot().map { it.id })
    }

    @Test
    fun `total received keeps counting past the cap`() = runBlocking {
        val buffer = LogBuffer(2)
        buffer.append(lines(10))
        assertEquals(10, buffer.totalReceivedNow)
        assertEquals(2, buffer.crashSnapshot().size)
    }

    @Test
    fun `held reports lines actually kept, not lines seen`() = runBlocking {
        val buffer = LogBuffer(3)
        buffer.append(lines(10))
        buffer.flush() // counters publish on the flush tick
        assertEquals(3, buffer.held.value)
        buffer.clear()
        assertEquals(0, buffer.held.value)
    }

    @Test
    fun `held includes the pinned side-store beyond the cap`() = runBlocking {
        val buffer = LogBuffer(2, pinnedTags = { setOf("t") }) // every test line has tag t
        buffer.append(lines(10))
        buffer.flush()
        // Ring holds 2; the 8 evicted lines all moved to the pinned store — none were lost.
        assertEquals(10, buffer.held.value)
    }

    @Test
    fun `rate reflects lines captured over the window`() = runBlocking {
        val buffer = LogBuffer(100)
        buffer.flush(now = 1_000) // opens the window
        buffer.append(lines(50))
        buffer.flush(now = 3_000) // 50 lines over 2s
        assertEquals(25, buffer.ratePerSec.value)
        buffer.flush(now = 5_000) // nothing new over the next window
        assertEquals(0, buffer.ratePerSec.value)
        buffer.clear()
        assertEquals(0, buffer.ratePerSec.value)
    }

    @Test
    fun `clear drops pinned lines too — pins guard rotation, not a deliberate clear`() = runBlocking {
        val buffer = LogBuffer(3, pinnedTags = { setOf("keep") })
        buffer.append(listOf(lines(1).first().copy(tag = "keep")))
        buffer.append(lines(5)) // rotates the pinned line into the side-store
        buffer.clear()
        assertTrue(buffer.crashSnapshot().isEmpty())
    }

    @Test
    fun `a continuation with nothing to attach to is ignored`() = runBlocking {
        val buffer = LogBuffer(10)
        buffer.appendContinuation("orphan line")
        assertTrue(buffer.crashSnapshot().isEmpty())
    }

    @Test
    fun `clear empties the buffer and resets the count`() = runBlocking {
        val buffer = LogBuffer(10)
        buffer.append(lines(4))
        buffer.clear()
        assertTrue(buffer.crashSnapshot().isEmpty())
        assertEquals(0, buffer.totalReceivedNow)
    }

    @Test
    fun `flush skips materializing with no subscribers, crashSnapshot still sees everything`() = runBlocking {
        val buffer = LogBuffer(10)
        buffer.append(lines(4))
        buffer.flush() // no subscribers -> must not materialize
        assertTrue(buffer.currentSnapshot().isEmpty())
        assertEquals(4, buffer.crashSnapshot().size) // crash context bypasses the gating
    }

    @Test
    fun `pinning applies dynamically - a tag pinned mid-run protects later evictions`() = runBlocking {
        val pins = mutableSetOf<String>()
        val buffer = LogBuffer(2, pinnedTags = { pins })
        buffer.append(lines(4)) // evicts 1,2 unpinned
        pins.add("t") // every test line has tag "t"
        buffer.append(lines(8)) // evictions from here on are pinned
        assertTrue(buffer.crashSnapshot().size > 2) // pinned store holds what the ring evicted
        buffer.flush()
        assertEquals(2, buffer.evicted.value) // only the two pre-pin evictions were lost
    }

    @Test
    fun `pinned tags survive eviction, in order, ahead of the ring`() = runBlocking {
        val buffer = LogBuffer(3, pinnedTags = { setOf("keep") })
        val entries = (1..10).map {
            LogEntry(
                id = it.toLong(), timeMs = it.toLong(), epochRaw = "0.0", pid = 1, tid = 1,
                level = LogLevel.INFO, tag = if (it % 2 == 0) "keep" else "noise", message = "m$it",
            )
        }
        buffer.append(entries)
        // Ring keeps 8..10; evicted 1..7 -> pinned keeps the "keep"-tagged 2,4,6; noise drops.
        assertEquals(listOf(2L, 4L, 6L, 8L, 9L, 10L), buffer.crashSnapshot().map { it.id })
        buffer.flush()
        assertEquals(4, buffer.evicted.value) // only the unpinned 1,3,5,7 count as lost
        buffer.clear()
        assertTrue(buffer.crashSnapshot().isEmpty())
    }

    @Test
    fun `eviction is counted, accumulates across appends, and resets on clear`() = runBlocking {
        val buffer = LogBuffer(3)
        buffer.append(lines(3))
        buffer.flush()
        assertEquals(0, buffer.evicted.value)
        buffer.append(lines(5))
        buffer.flush()
        assertEquals(5, buffer.evicted.value)
        buffer.append(lines(2))
        buffer.flush()
        assertEquals(7, buffer.evicted.value)
        buffer.clear()
        assertEquals(0, buffer.evicted.value)
    }
}
