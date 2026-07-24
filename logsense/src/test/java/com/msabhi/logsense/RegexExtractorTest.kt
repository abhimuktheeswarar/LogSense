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

    @Test
    fun `multiple patterns are tried in order, first match wins`() {
        val e = RegexExtractor.of(
            """
            (?<name>\w+)\s*->\s*\{(?<params>.*)\}
            GA -> (?<name>\w+)
            """.trimIndent(),
        )!!
        // matches line 1
        assertEquals("purchase", e("T", "purchase -> {a=1}")!!.name)
        // no braces -> falls to line 2
        assertEquals("home_view", e("T", "GA -> home_view")!!.name)
        // matches neither
        assertNull(e("T", "unrelated log"))
    }

    @Test
    fun `name inside the payload, params is a clean JSON object`() {
        // Some SDKs put the event name inside the JSON and the attributes in a nested object.
        val e = RegexExtractor.of(
            """LOG -> .*"event":"(?<name>[^"]+)".*"data":(?<params>\{.*\})\}""",
        )!!
        val event = e(
            "T",
            """LOG -> {"reqTime":1784900140986,"event":"close_screen","data":{"screen":"Home"}}""",
        )!!
        assertEquals("close_screen", event.name)
        assertEquals("Home", event.params["screen"])
    }

    @Test
    fun `a field holding double-escaped JSON is unwrapped into flat params`() {
        // Some SDKs cram the whole attribute set into one string field as escaped JSON; capturing the
        // enclosing object lets JSON parsing un-escape it, and the string-JSON unwrap flattens those
        // attributes into their own rows.
        val e = RegexExtractor.of("""evt=(?<name>\w+) (?<params>\{.*\})""")!!
        val event = e("T", """evt=purchase {"attrs":"{\"sku\":\"pro\",\"qty\":2}","ts":"123"}""")!!
        assertEquals("purchase", event.name)
        assertEquals("pro", event.params["sku"]) // unwrapped, un-escaped
        assertEquals(2, event.params["qty"])
        assertEquals("123", event.params["ts"])  // sibling scalar kept as-is
        assertTrue(event.params.containsKey("attrs").not()) // wrapper key gone, flattened
    }

    @Test
    fun `blank and invalid lines are skipped, valid ones still work`() {
        val e = RegexExtractor.of(
            """
            (?<name>

            EV:(?<name>\w+)
            """.trimIndent(),
        )!! // first line is an invalid regex, second blank, third valid
        assertEquals("app_open", e("T", "EV:app_open")!!.name)
    }
}
