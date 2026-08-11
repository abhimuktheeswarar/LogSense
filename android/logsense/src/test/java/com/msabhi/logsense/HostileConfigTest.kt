package com.msabhi.logsense

import com.msabhi.logsense.internal.data.CrashEntity
import com.msabhi.logsense.internal.logs.since
import com.msabhi.logsense.internal.reader.LogEntry
import com.msabhi.logsense.internal.reader.LogLevel
import com.msabhi.logsense.internal.signals.SignalDetector
import com.msabhi.logsense.internal.signals.appFrame
import com.msabhi.logsense.internal.signals.triage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LogSense is a library: whatever a host passes in, it must not throw. Everything here feeds
 * deliberately awkward input through the code that a host's configuration reaches.
 */
class HostileConfigTest {

    private fun entry(tag: String, message: String) = LogEntry(
        id = 1,
        timeMs = 1,
        epochRaw = "0.0",
        pid = 1,
        tid = 1,
        level = LogLevel.INFO,
        tag = tag,
        message = message,
    )

    private fun feed(custom: Map<String, String>, vararg lines: LogEntry): SignalDetector =
        SignalDetector(LogSenseConfig(customSignals = custom)) { emptySet() }
            .also { it.process(lines.toList()) }

    @Test
    fun `custom signal queries made of punctuation do not throw`() {
        val nasty = mapOf(
            "colon" to ":",
            "only quotes" to "\"\"",
            "unbalanced quote" to "\"open",
            "dashes" to "---",
            "empty key value" to "tag:",
            "unknown key" to "banana:split",
            "regex-looking" to "(?<name>.*)[a-z]+\\d{2,}",
            "newlines" to "tag:foo\nmsg:bar",
            "tab" to "\ttag:foo\t",
        )
        val detector = feed(nasty, entry("Any", "any message at all"))
        // The point is that we got here; the queries are nonsense but must be inert, not fatal.
        assertNotNull(detector.hits.value)
    }

    @Test
    fun `a custom label colliding with a built-in id does not break the catalog`() {
        val detector = feed(mapOf("crash.fatal" to "msg:whatever"), entry("t", "whatever"))
        assertTrue(detector.hits.value.isNotEmpty())
    }

    @Test
    fun `a very long query and a very long line are handled`() {
        val longQuery = "msg:" + "x".repeat(10_000)
        val longLine = entry("t", "y".repeat(200_000))
        val detector = feed(mapOf("huge" to longQuery), longLine)
        assertTrue(detector.hits.value.isEmpty())
    }

    @Test
    fun `a line matching a custom signal is previewed without overrunning`() {
        val detector = feed(mapOf("big" to "msg:needle"), entry("t", "needle " + "z".repeat(50_000)))
        val hit = detector.hits.value.single()
        assertTrue(hit.preview.length <= 200)
    }

    @Test
    fun `unicode and control characters in queries and lines are safe`() {
        val detector = feed(
            mapOf("emoji" to "msg:🔥", "rtl" to "msg:\u202Eabc"),
            entry("t", "burning 🔥 here"),
            entry("t", "\u0000\u0001 control chars"),
        )
        assertEquals(listOf("custom.emoji"), detector.hits.value.map { it.signal.id })
    }

    @Test
    fun `an empty custom map behaves like no custom signals`() {
        val detector = feed(emptyMap(), entry("Choreographer", "Skipped 30 frames!"))
        assertEquals(listOf("anr.skipped_frames"), detector.hits.value.map { it.signal.id })
    }

    @Test
    fun `triage survives a crash row with everything missing`() {
        val bare = CrashEntity(
            timestamp = 0,
            sessionId = "",
            type = "",
            threadName = null,
            exceptionClass = null,
            message = null,
            stacktrace = "",
            deviceInfo = "",
            logContext = "",
        )
        val read = triage(bare, "")
        assertNull(read.appFrame)
        assertNull(read.note)
    }

    @Test
    fun `appFrame survives odd package names`() {
        val trace = "java.lang.Error\n\tat com.foo.Bar.baz(Bar.kt:1)"
        // No dots, empty, trailing dot — none of these may throw.
        appFrame(trace, "")
        appFrame(trace, "single")
        appFrame(trace, "com.")
        appFrame(trace, ".".repeat(50))
        assertEquals("com.foo.Bar.baz(Bar.kt:1)", appFrame(trace, "com.foo"))
    }

    @Test
    fun `clearing slices safely at every boundary including nonsense watermarks`() {
        val lines = (0L..4L).map { id -> entry("t", "m").copy(id = id) }
        assertEquals(5, lines.since(Long.MIN_VALUE).size)
        assertEquals(5, lines.since(-1L).size)
        assertEquals(5, lines.since(Long.MAX_VALUE).size) // stale watermark, self-heals
        assertTrue(lines.since(4L).isEmpty())
    }

    @Test
    fun `a detector whose muted-set lookup throws does not take the batch down`() {
        // The muted set is read through a lambda owned by prefs; if that ever fails, the reader
        // coroutine must not die with it. LogSenseCore guards the call — assert the throw is real
        // so that guard is not silently unnecessary.
        val detector = SignalDetector(LogSenseConfig()) { error("prefs unavailable") }
        val threw = runCatching { detector.process(listOf(entry("t", "x"))) }.isFailure
        assertTrue(threw)
    }
}
