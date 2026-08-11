package com.msabhi.logsense

import com.msabhi.logsense.internal.analytics.DefaultExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultExtractorTest {

    @Test
    fun `json payload`() {
        val event = DefaultExtractor("Analytics", """purchase {"sku":"pro","price":9.99}""")
        assertEquals("purchase", event.name)
        assertEquals("pro", event.params["sku"])
        // desktop org.json yields BigDecimal here, Android's yields Double — compare as Number
        assertEquals(9.99, (event.params["price"] as Number).toDouble(), 0.0001)
    }

    @Test
    fun `bundle payload`() {
        val event = DefaultExtractor("Analytics", "add_to_cart Bundle[{item_id=42, qty=2}]")
        assertEquals("add_to_cart", event.name)
        assertEquals("42", event.params["item_id"])
        assertEquals("2", event.params["qty"])
    }

    @Test
    fun `key value payload`() {
        val event = DefaultExtractor("Analytics", "screen_view screen=Home, source=tab")
        assertEquals("screen_view", event.name)
        assertEquals("Home", event.params["screen"])
        assertEquals("tab", event.params["source"])
    }

    @Test
    fun `name only fallback`() {
        val event = DefaultExtractor("Analytics", "app_opened")
        assertEquals("app_opened", event.name)
        assertTrue(event.params.isEmpty())
    }

    @Test
    fun `name with trailing colon before json`() {
        val event = DefaultExtractor("Analytics", """login: {"method":"google"}""")
        assertEquals("login", event.name)
        assertEquals("google", event.params["method"])
    }

    @Test
    fun `params without name falls back to tag`() {
        val event = DefaultExtractor("Analytics", "screen=Home, source=tab")
        assertEquals("Analytics", event.name)
        assertEquals("Home", event.params["screen"])
    }

    @Test
    fun `malformed json falls through without crashing`() {
        val event = DefaultExtractor("Analytics", "broken {not-json")
        assertEquals("broken {not-json", event.name)
    }

    @Test
    fun `nested json kept as text`() {
        val event = DefaultExtractor("Analytics", """checkout {"items":[{"id":1}],"total":5}""")
        assertEquals("checkout", event.name)
        assertTrue(event.params["items"] is String)
    }

    // --- generic shapes: arrow separator, verb prefix, brace-wrapped k=v, commas inside values ---

    @Test
    fun `verb prefix and arrow - name is the identifier before the payload`() {
        val event = DefaultExtractor("Telemetry", "logEvent = purchase -> {sku=pro, region=US}")
        assertEquals("purchase", event.name) // "logEvent" verb is skipped
        assertEquals("pro", event.params["sku"])
        assertEquals("US", event.params["region"])
    }

    @Test
    fun `value containing a comma is kept whole`() {
        val event = DefaultExtractor("Telemetry", "track = search -> {origin=Paris, France, dest=Rome, Italy}")
        assertEquals("search", event.name)
        assertEquals("Paris, France", event.params["origin"])
        assertEquals("Rome, Italy", event.params["dest"])
    }

    @Test
    fun `no stray closing brace on the last value`() {
        val event = DefaultExtractor("Telemetry", "send = view -> {a=1, screen=Home}")
        assertEquals("view", event.name)
        assertEquals("Home", event.params["screen"]) // not "Home}"
    }

    @Test
    fun `arrow then bundle - name is the identifier before the payload`() {
        val event = DefaultExtractor("Telemetry", "GA -> ab_test_foo : Bundle[{lang=en, screen=Home}]")
        assertEquals("ab_test_foo", event.name) // "GA ->" prefix skipped
        assertEquals("en", event.params["lang"])
        assertEquals("Home", event.params["screen"])
    }

    @Test
    fun `arrow without braces`() {
        val event = DefaultExtractor("Telemetry", "GA -> screen=Splash, source=deeplink")
        assertEquals("Splash", event.params["screen"])
        assertEquals("deeplink", event.params["source"])
    }
}
