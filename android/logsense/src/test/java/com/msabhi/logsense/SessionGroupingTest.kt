package com.msabhi.logsense

import com.msabhi.logsense.internal.ui.groupBySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGroupingTest {

    private data class Row(val id: Long, val sid: String, val ts: Long)

    private val started = mapOf("cur" to 1_000L, "prev" to 500L, "earlier" to 0L)

    private fun group(rows: List<Row>) =
        groupBySession(rows, currentSessionId = "cur", startedAtOf = { started[it] ?: 0L }, sessionOf = { it.sid }, timeOf = { it.ts })

    @Test
    fun `orders current first then newest run, earlier last`() {
        val rows = listOf(
            Row(1, "cur", 1_100), Row(2, "cur", 1_050),
            Row(3, "prev", 560),
            Row(4, "earlier", 20),
        )
        val groups = group(rows)
        assertEquals(listOf("cur", "prev", "earlier"), groups.map { it.first.id })
    }

    @Test
    fun `current session flagged, counts and range computed`() {
        val rows = listOf(Row(1, "cur", 1_100), Row(2, "cur", 1_050), Row(3, "cur", 1_200))
        val (meta, items) = group(rows).single()
        assertTrue(meta.isCurrent)
        assertEquals(3, meta.count)
        assertEquals(3, items.size)
        assertEquals(1_200L, meta.newestTs)
        assertEquals(1_050L, meta.oldestTs)
    }

    @Test
    fun `previous session is not current`() {
        val groups = group(listOf(Row(1, "prev", 560)))
        assertFalse(groups.single().first.isCurrent)
    }

    @Test
    fun `empty input yields no groups`() {
        assertTrue(group(emptyList()).isEmpty())
    }
}
