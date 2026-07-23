package com.msabhi.logsense

import com.msabhi.logsense.internal.logs.LogFilter
import com.msabhi.logsense.internal.reader.LogEntry
import com.msabhi.logsense.internal.reader.LogLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFilterTest {

    private fun entry(level: LogLevel, tag: String, message: String) =
        LogEntry(0, 0, "0.0", 1, 1, level, tag, message)

    @Test
    fun `min level excludes lower levels`() {
        val f = LogFilter(minLevel = LogLevel.WARN)
        assertFalse(f.matches(entry(LogLevel.INFO, "T", "m")))
        assertTrue(f.matches(entry(LogLevel.WARN, "T", "m")))
        assertTrue(f.matches(entry(LogLevel.ERROR, "T", "m")))
    }

    @Test
    fun `tag filter is exact`() {
        val f = LogFilter(tag = "Telemetry")
        assertTrue(f.matches(entry(LogLevel.DEBUG, "Telemetry", "m")))
        assertFalse(f.matches(entry(LogLevel.DEBUG, "Analytics", "m")))
    }

    @Test
    fun `text matches message or tag case-insensitively`() {
        val f = LogFilter(text = "metro")
        assertTrue(f.matches(entry(LogLevel.DEBUG, "T", "logEvent = METRO_event")))
        assertTrue(f.matches(entry(LogLevel.DEBUG, "metro_tag", "m")))
        assertFalse(f.matches(entry(LogLevel.DEBUG, "T", "bus event")))
    }

    @Test
    fun `default filter matches all`() {
        val f = LogFilter()
        assertTrue(f.matches(entry(LogLevel.VERBOSE, "any", "any")))
    }
}
