package com.msabhi.logsense

import com.msabhi.logsense.internal.logs.LogFilter
import com.msabhi.logsense.internal.logs.LogQuery
import com.msabhi.logsense.internal.reader.LogEntry
import com.msabhi.logsense.internal.reader.LogLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogQueryTest {

    private fun entry(level: LogLevel = LogLevel.DEBUG, tag: String = "T", message: String = "m") =
        LogEntry(0, 0, "0.0", 1, 1, level, tag, message)

    private fun pred(query: String, minLevel: LogLevel = LogLevel.VERBOSE) =
        LogQuery.compile(LogFilter(minLevel, query))

    @Test
    fun `empty query keeps everything above min level`() {
        val p = pred("", LogLevel.WARN)
        assertFalse(p(entry(LogLevel.INFO)))
        assertTrue(p(entry(LogLevel.ERROR)))
    }

    @Test
    fun `tag term matches by contains`() {
        val p = pred("tag:Analytics")
        assertTrue(p(entry(tag = "ANALYTICS ... ANALYTICS")))
        assertFalse(p(entry(tag = "Telemetry".replace("Analytics", "Engine"))))
    }

    @Test
    fun `negated tag excludes`() {
        val p = pred("-tag:metro")
        assertFalse(p(entry(tag = "metro_event")))
        assertTrue(p(entry(tag = "bus_event")))
    }

    @Test
    fun `message term`() {
        val p = pred("message:logEvent")
        assertTrue(p(entry(message = "logEvent = screen_view")))
        assertFalse(p(entry(message = "nothing here")))
    }

    @Test
    fun `level term raises minimum`() {
        val p = pred("level:E")
        assertFalse(p(entry(LogLevel.WARN)))
        assertTrue(p(entry(LogLevel.ERROR)))
    }

    @Test
    fun `bare word matches tag or message`() {
        val p = pred("home")
        assertTrue(p(entry(tag = "APP_HOME", message = "x")))
        assertTrue(p(entry(tag = "T", message = "home loaded")))
        assertFalse(p(entry(tag = "T", message = "bus")))
    }

    @Test
    fun `terms are ANDed`() {
        val p = pred("tag:Analytics purchase")
        assertTrue(p(entry(tag = "Analytics", message = "purchase done")))
        assertFalse(p(entry(tag = "Analytics", message = "screen_view")))
        assertFalse(p(entry(tag = "Other", message = "purchase done")))
    }

    @Test
    fun `quoted value keeps spaces`() {
        val p = pred("""message:"logEvent = screen_view"""")
        assertTrue(p(entry(message = "logEvent = screen_view -> {}")))
        assertFalse(p(entry(message = "logEvent screen_view")))
    }
}
