package com.msabhi.logsense

import com.msabhi.logsense.internal.logs.LevelColorOverride
import com.msabhi.logsense.internal.logs.LogFilter
import com.msabhi.logsense.internal.logs.LogTab
import com.msabhi.logsense.internal.logs.ViewMode
import com.msabhi.logsense.internal.prefs.PrefsCodec
import com.msabhi.logsense.internal.reader.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsCodecTest {

    @Test
    fun `tabs round-trip`() {
        val tabs = listOf(
            LogTab(0, "All"),
            LogTab(
                id = 1,
                name = "Errors",
                filter = LogFilter(minLevel = LogLevel.ERROR, tag = "ANALYTICS ... ANALYTICS", text = "metro"),
                viewMode = ViewMode.COMPACT,
                softWrap = true,
            ),
        )
        val decoded = PrefsCodec.decodeTabs(PrefsCodec.encodeTabs(tabs))
        assertEquals(tabs, decoded)
    }

    @Test
    fun `null tag survives round-trip`() {
        val tabs = listOf(LogTab(7, "Tab", LogFilter(tag = null)))
        assertEquals(null, PrefsCodec.decodeTabs(PrefsCodec.encodeTabs(tabs)).single().filter.tag)
    }

    @Test
    fun `colors round-trip including nulls`() {
        val colors = mapOf(
            LogLevel.DEBUG to LevelColorOverride(light = 0xFF112233.toInt(), dark = 0xFF445566.toInt()),
            LogLevel.ERROR to LevelColorOverride(light = null, dark = 0xFF778899.toInt()),
        )
        assertEquals(colors, PrefsCodec.decodeColors(PrefsCodec.encodeColors(colors)))
    }

    @Test
    fun `garbage decodes to empty, not a crash`() {
        assertTrue(PrefsCodec.decodeTabs("not json").isEmpty())
        assertTrue(PrefsCodec.decodeColors("not json").isEmpty())
    }
}
