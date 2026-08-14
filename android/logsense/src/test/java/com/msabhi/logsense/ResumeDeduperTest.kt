package com.msabhi.logsense

import com.msabhi.logsense.internal.reader.LogEntry
import com.msabhi.logsense.internal.reader.LogLevel
import com.msabhi.logsense.internal.reader.ResumeDeduper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeDeduperTest {

    private var nextId = 0L

    private fun entry(timeMs: Long, message: String, tid: Int = 1) = LogEntry(
        id = nextId++,
        timeMs = timeMs,
        epochRaw = "${timeMs / 1000}.${(timeMs % 1000).toString().padStart(3, '0')}",
        pid = 1,
        tid = tid,
        level = LogLevel.DEBUG,
        tag = "t",
        message = message,
    )

    @Test
    fun `drops nothing before anything was captured`() {
        val deduper = ResumeDeduper()
        deduper.arm()
        assertFalse(deduper.shouldDrop(entry(100, "a")))
    }

    @Test
    fun `drops nothing without a reconnect`() {
        val deduper = ResumeDeduper()
        val a = entry(100, "a")
        deduper.remember(listOf(a))
        // No arm(): the stream is continuous, an identical-looking new line must be kept.
        assertFalse(deduper.shouldDrop(entry(100, "a")))
    }

    @Test
    fun `after reconnect only the re-emitted lines drop, unseen same-millisecond lines survive`() {
        val deduper = ResumeDeduper()
        val a = entry(100, "a")
        val b = entry(100, "b")
        deduper.remember(listOf(a, b))

        deduper.arm() // logcat restarted with -T; the resume millisecond is re-emitted in full
        assertTrue(deduper.shouldDrop(entry(100, "a")))
        assertTrue(deduper.shouldDrop(entry(100, "b")))
        // The old dedupe dropped everything <= the resume millisecond, losing these two:
        assertFalse(deduper.shouldDrop(entry(100, "c")))
        assertFalse(deduper.shouldDrop(entry(101, "d")))
    }

    @Test
    fun `lines strictly before the resume millisecond always drop`() {
        val deduper = ResumeDeduper()
        deduper.remember(listOf(entry(100, "a")))
        deduper.arm()
        assertTrue(deduper.shouldDrop(entry(99, "never remembered")))
    }

    @Test
    fun `dedupe disarms once the stream moves past the resume millisecond`() {
        val deduper = ResumeDeduper()
        val a = entry(100, "a")
        deduper.remember(listOf(a))
        deduper.arm()
        assertFalse(deduper.shouldDrop(entry(101, "b")))
        // Past the resume point the window is over — even an identical key must be kept.
        assertFalse(deduper.shouldDrop(entry(100, "a")))
    }

    @Test
    fun `remember tracks only the newest millisecond`() {
        val deduper = ResumeDeduper()
        deduper.remember(listOf(entry(100, "a")))
        deduper.remember(listOf(entry(200, "b")))
        deduper.arm()
        assertTrue(deduper.shouldDrop(entry(100, "a"))) // before the tail: seen by definition
        assertTrue(deduper.shouldDrop(entry(200, "b")))
        assertFalse(deduper.shouldDrop(entry(200, "new")))
    }

    @Test
    fun `a merged multi-line entry still matches its re-emitted header line`() {
        val deduper = ResumeDeduper()
        // Continuations were glued on with newlines before remember() saw the batch...
        deduper.remember(listOf(entry(100, "first line\ncontinuation")))
        deduper.arm()
        // ...but the re-emission arrives as the bare header line and must still be recognized.
        assertTrue(deduper.shouldDrop(entry(100, "first line")))
    }

    @Test
    fun `same message on different threads are different lines`() {
        val deduper = ResumeDeduper()
        deduper.remember(listOf(entry(100, "a", tid = 1)))
        deduper.arm()
        assertFalse(deduper.shouldDrop(entry(100, "a", tid = 2)))
    }

    @Test
    fun `arm reports whether an overlap is expected`() {
        val deduper = ResumeDeduper()
        assertFalse(deduper.arm()) // nothing captured yet: no resume point to re-emit
        deduper.remember(listOf(entry(100, "a")))
        assertTrue(deduper.arm())
    }

    @Test
    fun `tookOverlap is true once any re-emission was dropped`() {
        val deduper = ResumeDeduper()
        deduper.remember(listOf(entry(100, "a")))
        deduper.arm()
        assertFalse(deduper.tookOverlap())
        assertTrue(deduper.shouldDrop(entry(100, "a")))
        assertTrue(deduper.tookOverlap())
    }

    @Test
    fun `a session with only fresh lines never trips tookOverlap`() {
        // The gap signature: armed, lines delivered, but the resume point never re-appeared —
        // logd's ring rotated past it (or was cleared) between sessions.
        val deduper = ResumeDeduper()
        deduper.remember(listOf(entry(100, "a")))
        deduper.arm()
        assertFalse(deduper.shouldDrop(entry(500, "later line")))
        assertFalse(deduper.tookOverlap())
    }

    @Test
    fun `arm resets the previous session's overlap`() {
        val deduper = ResumeDeduper()
        deduper.remember(listOf(entry(100, "a")))
        deduper.arm()
        deduper.shouldDrop(entry(100, "a"))
        deduper.arm()
        assertFalse(deduper.tookOverlap())
    }
}
