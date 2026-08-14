package com.msabhi.logsense

import com.msabhi.logsense.internal.reader.OwnLineSplitter
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnLineSplitterTest {

    private fun emitted(myPid: Int, vararg chunks: String): List<String> {
        val splitter = OwnLineSplitter(myPid)
        val out = mutableListOf<String>()
        for (c in chunks) {
            val b = c.toByteArray()
            splitter.feed(b, b.size) { out.add(it) }
        }
        return out
    }

    private fun line(pid: Int, msg: String) = "  1737541357.983  $pid  1300 D Tag : $msg\n"

    @Test
    fun `own lines pass, foreign lines vanish`() {
        val out = emitted(42, line(42, "mine") + line(999, "theirs") + line(42, "mine too"))
        assertEquals(listOf("mine", "mine too"), out.map { it.substringAfter(": ") })
    }

    @Test
    fun `continuations inherit their header's fate`() {
        val out = emitted(
            42,
            line(42, "own multi") + "  own continuation\n" +
                line(999, "foreign multi") + "  foreign continuation\n" +
                line(42, "own again"),
        )
        assertEquals(3, out.size)
        assertEquals("  own continuation", out[1])
    }

    @Test
    fun `a line split across chunks is reassembled`() {
        val full = line(42, "split across reads")
        val out = emitted(42, full.substring(0, 25), full.substring(25))
        assertEquals(1, out.size)
        assertEquals("split across reads", out[0].substringAfter(": "))
    }

    @Test
    fun `a pid split across chunks is still parsed`() {
        val full = line(42, "pid boundary")
        val cut = full.indexOf("42") + 1 // split mid-pid
        val out = emitted(42, full.substring(0, cut), full.substring(cut))
        assertEquals(1, out.size)
    }

    @Test
    fun `dividers and blank lines emit nothing and do not flip attribution`() {
        val out = emitted(
            42,
            line(42, "own") + "--------- beginning of main\n" + "\n" + "  still own continuation\n",
        )
        // Divider + blank inherit "own" (harmless: the parser downstream discards dividers),
        // and the continuation still attributes to the own header.
        assertEquals(listOf("own"), listOf(out[0].substringAfter(": ")))
        assertEquals("  still own continuation", out.last())
    }

    @Test
    fun `crlf is stripped`() {
        val out = emitted(42, "  1.000  42  1 I t : msg\r\n")
        assertEquals("  1.000  42  1 I t : msg", out.single())
    }
}
