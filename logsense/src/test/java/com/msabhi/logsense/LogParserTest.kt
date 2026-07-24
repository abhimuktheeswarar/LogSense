package com.msabhi.logsense

import com.msabhi.logsense.internal.reader.LogLevel
import com.msabhi.logsense.internal.reader.LogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogParserTest {

    private val parser = LogParser()

    @Test
    fun `parses epoch format line`() {
        val entry = parser.parse("         1737541357.983  1234  1300 D SomeTag : hello world")
        assertNotNull(entry)
        entry!!
        assertEquals(1737541357_983, entry.timeMs)
        assertEquals("1737541357.983", entry.epochRaw)
        assertEquals(1234, entry.pid)
        assertEquals(1300, entry.tid)
        assertEquals(LogLevel.DEBUG, entry.level)
        assertEquals("SomeTag", entry.tag)
        assertEquals("hello world", entry.message)
    }

    @Test
    fun `tag with spaces, and a colon inside the message`() {
        val entry = parser.parse("1784000000.842  9135  9135 I APP ... METRICS: GA -> ab_test_foo : Bundle[{lang=en}]")
        assertNotNull(entry)
        entry!!
        assertEquals("APP ... METRICS", entry.tag) // spaces preserved, not truncated at first space
        assertEquals("GA -> ab_test_foo : Bundle[{lang=en}]", entry.message) // colon in message doesn't leak into tag
    }

    @Test
    fun `parses all levels`() {
        for ((letter, level) in mapOf(
            "V" to LogLevel.VERBOSE, "D" to LogLevel.DEBUG, "I" to LogLevel.INFO,
            "W" to LogLevel.WARN, "E" to LogLevel.ERROR, "F" to LogLevel.FATAL,
        )) {
            val entry = parser.parse("1737541357.983  1 1 $letter T : m")
            assertEquals(level, entry?.level)
        }
    }

    @Test
    fun `detects buffer divider`() {
        assertTrue(parser.isDivider("--------- beginning of main"))
        assertTrue(parser.isDivider("--------- beginning of crash"))
    }

    @Test
    fun `continuation line does not parse`() {
        assertNull(parser.parse("    at com.example.Foo.bar(Foo.kt:42)"))
        assertNull(parser.parse(""))
    }

    @Test
    fun `tag with spaces and empty message parse`() {
        val entry = parser.parse("1737541357.983  1 1 I ActivityManager : ")
        assertEquals("ActivityManager", entry?.tag)
        assertEquals("", entry?.message)
    }

    @Test
    fun `ids are monotonic`() {
        val a = parser.parse("1737541357.983  1 1 I T : one")!!
        val b = parser.parse("1737541357.984  1 1 I T : two")!!
        assertTrue(b.id > a.id)
    }
}
