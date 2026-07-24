package com.msabhi.logsense

import com.msabhi.logsense.internal.analytics.RegexExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexExtractorTest {

    private val arrow = RegexExtractor.of("""(?<name>\w+)\s*->\s*\{(?<params>.*)\}""")!!

    @Test
    fun `named groups pull name and params`() {
        val event = arrow("Tag", "purchase -> {sku=pro, qty=2}")!!
        assertEquals("purchase", event.name)
        assertEquals("pro", event.params["sku"])
        assertEquals("2", event.params["qty"])
    }

    @Test
    fun `value with a comma is preserved via the shared parser`() {
        val event = arrow("Tag", "search -> {origin=Paris, France, dest=Rome}")!!
        assertEquals("Paris, France", event.params["origin"])
        assertEquals("Rome", event.params["dest"])
    }

    @Test
    fun `no match returns null`() {
        assertNull(arrow("Tag", "nothing to see here"))
    }

    @Test
    fun `name group without a params group yields empty params`() {
        val e = RegexExtractor.of("""EVENT:(?<name>\w+)""")!!
        val event = e("Tag", "EVENT:app_open extra stuff")!!
        assertEquals("app_open", event.name)
        assertTrue(event.params.isEmpty())
    }

    @Test
    fun `blank or invalid pattern compiles to null`() {
        assertNull(RegexExtractor.of(""))
        assertNull(RegexExtractor.of("   "))
        assertNull(RegexExtractor.of("(?<name>")) // unbalanced group
    }

    @Test
    fun `pattern without a name group produces no event`() {
        val e = RegexExtractor.of("""foo=(?<params>.*)""")!!
        assertNull(e("Tag", "foo=a=1, b=2"))
    }
}
