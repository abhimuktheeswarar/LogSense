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
}
