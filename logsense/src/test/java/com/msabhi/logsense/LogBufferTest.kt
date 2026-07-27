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
            buffer.flush()
            assertEquals("cap $cap should keep the newest line", 1, buffer.currentSnapshot().size)
        }
    }

    @Test
    fun `the cap keeps the newest lines and drops the oldest`() = runBlocking {
        val buffer = LogBuffer(3)
        buffer.append(lines(10))
        buffer.flush()
        assertEquals(listOf(8L, 9L, 10L), buffer.currentSnapshot().map { it.id })
    }

    @Test
    fun `total received keeps counting past the cap`() = runBlocking {
        val buffer = LogBuffer(2)
        buffer.append(lines(10))
        assertEquals(10, buffer.totalReceived.value)
        assertEquals(2, buffer.currentSnapshot().size.let { buffer.flush(); buffer.currentSnapshot().size })
    }

    @Test
    fun `a continuation with nothing to attach to is ignored`() = runBlocking {
        val buffer = LogBuffer(10)
        buffer.appendContinuation("orphan line")
        buffer.flush()
        assertTrue(buffer.currentSnapshot().isEmpty())
    }

    @Test
    fun `clear empties the buffer and resets the count`() = runBlocking {
        val buffer = LogBuffer(10)
        buffer.append(lines(4))
        buffer.clear()
        assertTrue(buffer.currentSnapshot().isEmpty())
        assertEquals(0, buffer.totalReceived.value)
    }
}
