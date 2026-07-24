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
    fun `params group holding a JSON object is parsed as JSON`() {
        // MRI_LOGS style: name lives inside the JSON, params is a clean {json} object.
        val e = RegexExtractor.of(
            """MRI_LOGS_UX -> .*"event":"(?<name>[^"]+)".*"data":(?<params>\{.*\})\}""",
        )!!
        val event = e(
            "MRI_LOGS",
            """MRI_LOGS_UX -> {"reqTime":1784900140986,"vndr_type":"APP","event":"current_close_screen","data":{"screenName":"LogSenseActivity"}}""",
        )!!
        assertEquals("current_close_screen", event.name)
        assertEquals("LogSenseActivity", event.params["screenName"])
    }

    @Test
    fun `MoEngage double-escaped EVENT_ATTRS is unwrapped into flat params`() {
        // The event's real attributes are crammed into EVENT_ATTRS as escaped JSON; capturing the
        // whole `attributes` object lets JSON parsing un-escape it, and the string-JSON unwrap then
        // flattens the attributes into their own rows.
        val e = RegexExtractor.of(
            """trackEvent\(\):\s*\{\s*Event:\s*\{"name":"(?<name>[^"]+)","attributes":(?<params>\{.*\}),"time"""",
        )!!
        val line = """VG18 Core_EventHandler trackEvent(): { Event: {"name":"bus_home_screen_launch","attributes":{"EVENT_ATTRS":"{\"business_unit\":\"Bus\",\"is_logged_in\":\"Yes\"}","EVENT_G_TIME":"1784899961735","EVENT_L_TIME":"24:7:2026:19:2:41"},"time":1784899961736,"isInteractiveEvent":true}} """
        val event = e("MoEngage", line)!!
        assertEquals("bus_home_screen_launch", event.name)
        assertEquals("Bus", event.params["business_unit"])   // unwrapped, un-escaped
        assertEquals("Yes", event.params["is_logged_in"])
        assertEquals("1784899961735", event.params["EVENT_G_TIME"]) // sibling scalar kept as-is
        assertTrue(event.params.containsKey("EVENT_ATTRS").not()) // wrapper key gone, flattened
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
