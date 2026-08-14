package com.msabhi.logsense

import com.msabhi.logsense.internal.reader.scanLogcatPid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanLogcatPidTest {

    @Test
    fun `extracts the pid from an epoch header line`() {
        assertEquals(1234, scanLogcatPid("         1737541357.983  1234  1300 D SomeTag : message"))
        assertEquals(7, scanLogcatPid("1.000 7 8 I t : m"))
    }

    @Test
    fun `continuation lines are not headers`() {
        assertNull(scanLogcatPid("    at com.example.Foo.bar(Foo.kt:42)"))
        assertNull(scanLogcatPid("second line of a multi-line message"))
        assertNull(scanLogcatPid(""))
    }

    @Test
    fun `numbers that are not epoch timestamps are not headers`() {
        assertNull(scanLogcatPid("1234 5678 D t : no fractional seconds"))
        assertNull(scanLogcatPid("1.234"))
        assertNull(scanLogcatPid("1.234  "))
    }

    @Test
    fun `agrees with the full parser on the pid`() {
        // The scan is a pre-filter for the regex parser — they must never disagree on a header.
        val line = "  1737541357.983  4321  99 W Tag : payload=1.2.3"
        assertEquals(4321, scanLogcatPid(line))
    }
}

class ShouldRetainLineTest {

    private val configPins = setOf("RailsWebViewHeaders")
    private val userPins = setOf("Perf")

    @Test
    fun `retention on keeps everything`() {
        assertEquals(true, com.msabhi.logsense.internal.reader.shouldRetainLine(false, configPins, userPins, "Anything"))
    }

    @Test
    fun `paused drops ordinary tags but never pinned ones`() {
        assertEquals(false, com.msabhi.logsense.internal.reader.shouldRetainLine(true, configPins, userPins, "Anything"))
        assertEquals(true, com.msabhi.logsense.internal.reader.shouldRetainLine(true, configPins, userPins, "RailsWebViewHeaders"))
        assertEquals(true, com.msabhi.logsense.internal.reader.shouldRetainLine(true, configPins, userPins, "Perf"))
    }
}
