package com.msabhi.logsense

import com.msabhi.logsense.internal.search.SearchQuery
import com.msabhi.logsense.internal.search.TextMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatcherTest {

    private fun matcher(
        text: String,
        matchCase: Boolean = false,
        wholeWord: Boolean = false,
        regex: Boolean = false,
    ) = TextMatcher.from(SearchQuery(text, matchCase, wholeWord, regex))

    @Test
    fun `empty query matches everything`() {
        val m = matcher("")
        assertTrue(m.matches("anything"))
        assertTrue(m.ranges("anything").isEmpty())
    }

    @Test
    fun `literal is case-insensitive by default`() {
        assertTrue(matcher("home").matches("APP_HOME"))
        assertTrue(matcher("HOME").matches("app_home"))
    }

    @Test
    fun `match case is respected`() {
        val m = matcher("Home", matchCase = true)
        assertTrue(m.matches("APP_Home"))
        assertFalse(m.matches("app_home"))
    }

    @Test
    fun `whole word does not match substrings`() {
        val m = matcher("home", wholeWord = true)
        assertTrue(m.matches("go home now"))
        assertFalse(m.matches("homepage"))
    }

    @Test
    fun `regex matches`() {
        val m = matcher("metro_.*", regex = true)
        assertTrue(m.matches("metro_event"))
        assertFalse(m.matches("bus_event"))
    }

    @Test
    fun `invalid regex matches nothing instead of throwing`() {
        val m = matcher("metro_[", regex = true) // unbalanced bracket
        assertFalse(m.matches("metro_event"))
        assertTrue(m.ranges("metro_event").isEmpty())
    }

    @Test
    fun `ranges locate every occurrence`() {
        val ranges = matcher("ab").ranges("ab_xx_AB_ab")
        assertEquals(listOf(0 until 2, 6 until 8, 9 until 11), ranges)
    }
}
