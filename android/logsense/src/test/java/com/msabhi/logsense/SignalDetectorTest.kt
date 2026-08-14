package com.msabhi.logsense

import com.msabhi.logsense.internal.reader.LogEntry
import com.msabhi.logsense.internal.reader.LogLevel
import com.msabhi.logsense.internal.signals.SignalCategory
import com.msabhi.logsense.internal.signals.SignalDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalDetectorTest {

    private var nextId = 0L

    private fun entry(tag: String, message: String, level: LogLevel = LogLevel.INFO) = LogEntry(
        id = nextId++,
        timeMs = 1_000L + nextId,
        epochRaw = "0.0",
        pid = 42,
        tid = 42,
        level = level,
        tag = tag,
        message = message,
    )

    private fun detector(
        config: LogSenseConfig = LogSenseConfig(),
        muted: Set<String> = emptySet(),
    ) = SignalDetector(config, muted = { muted })

    private fun idsFor(vararg entries: LogEntry): List<String> =
        detector().let { d -> d.process(entries.toList()); d.hits.value.map { it.signal.id } }

    @Test
    fun `self jank is suppressed while the LogSense UI is visible, other signals are not`() {
        val d = SignalDetector(LogSenseConfig(), muted = { emptySet() }, ownUiVisible = { true })
        d.process(
            listOf(
                entry("Choreographer", "Skipped 41 frames!  The application may be doing too much work on its main thread."),
                entry("OpenGLRenderer", "Davey! duration=726ms; Flags=0"),
                entry("AndroidRuntime", "FATAL EXCEPTION: main", LogLevel.ERROR),
            ),
        )
        d.publish()
        // The two jank signals measure LogSense's own foreground rendering; the crash still lands.
        assertEquals(listOf("crash.fatal"), d.hits.value.map { it.signal.id })
    }

    @Test
    fun `rapid batches defer publication until the ticker's publish`() {
        val d = detector()
        val line = { entry("Choreographer", "Skipped 41 frames!  The application may be doing too much work.") }
        d.process(listOf(line())) // quiet period: publishes immediately
        assertEquals(1, d.hits.value.size)
        d.process(listOf(line())) // within the rate-limit window: deferred
        assertEquals(1, d.hits.value.size)
        d.publish() // the core ticker's trailing edge
        assertEquals(2, d.hits.value.size)
    }

    @Test
    fun `detects a fatal exception`() {
        assertEquals(
            listOf("crash.fatal"),
            idsFor(entry("AndroidRuntime", "FATAL EXCEPTION: main", LogLevel.ERROR)),
        )
    }

    @Test
    fun `detects skipped frames only for the Choreographer tag`() {
        assertEquals(
            listOf("anr.skipped_frames"),
            idsFor(
                entry("Choreographer", "Skipped 41 frames!  The application may be doing too much work on its main thread."),
                entry("MyTag", "Skipped the cache lookup"),
            ),
        )
    }

    @Test
    fun `a native fault reports the specific signal, not the generic one`() {
        assertEquals(
            listOf("native.sigsegv"),
            idsFor(entry("libc", "Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 4242")),
        )
    }

    @Test
    fun `an unrecognised fatal signal still reports the generic one`() {
        assertEquals(
            listOf("native.fatal_signal"),
            idsFor(entry("libc", "Fatal signal 31 (SIGSYS), code 1 in tid 4242")),
        )
    }

    @Test
    fun `detects memory pressure`() {
        assertEquals(
            listOf("memory.blocking_gc", "memory.oom"),
            idsFor(
                entry("art", "Waiting for a blocking GC Alloc"),
                entry("art", """Throwing OutOfMemoryError "Failed to allocate a 4194320 byte allocation""""),
            ),
        )
    }

    @Test
    fun `detects leaks`() {
        assertEquals(
            listOf("lifecycle.leak_window", "lifecycle.leak_service"),
            idsFor(
                entry("WindowManager", "android.view.WindowLeaked: Activity com.foo.Main has leaked window DecorView@ab12"),
                entry("ActivityThread", "Activity com.foo.Main has leaked ServiceConnection com.foo.Binder@cd34"),
            ),
        )
    }

    @Test
    fun `a StrictMode trace reports the violation, not every frame of it`() {
        // StrictMode prints its stack as separate StrictMode-tagged lines, and a frame named
        // readAndHandleBinderCallViolations contains "violation" — only the header line counts.
        assertEquals(
            listOf("anr.strictmode"),
            idsFor(
                entry("StrictMode", "StrictMode policy violation; ~duration=17 ms: android.os.strictmode.DiskReadViolation"),
                entry("StrictMode", "\tat android.os.StrictMode.readAndHandleBinderCallViolations(StrictMode.java:2104)"),
                entry("StrictMode", "\tat android.os.StrictMode\$AndroidBlockGuardPolicy.onReadFromDisk(StrictMode.java:1608)"),
            ),
        )
    }

    @Test
    fun `a line produces at most one hit`() {
        // The crash line names an exception that has its own rule; the crash wins because it is first.
        assertEquals(
            listOf("crash.fatal"),
            idsFor(entry("AndroidRuntime", "FATAL EXCEPTION: main\njava.lang.SecurityException: denied", LogLevel.ERROR)),
        )
    }

    @Test
    fun `ordinary lines produce nothing`() {
        assertEquals(emptyList<String>(), idsFor(entry("OkHttp", "--> GET /v1/cart"), entry("CartVM", "state=Loading")))
    }

    @Test
    fun `a muted signal stops matching`() {
        val d = detector(muted = setOf("anr.skipped_frames"))
        d.process(listOf(entry("Choreographer", "Skipped 41 frames!")))
        assertEquals(emptyList<String>(), d.hits.value.map { it.signal.id })
    }

    @Test
    fun `unmuting takes effect without restarting`() {
        var muted = setOf("anr.skipped_frames")
        val d = SignalDetector(LogSenseConfig(), muted = { muted })
        d.process(listOf(entry("Choreographer", "Skipped 41 frames!")))
        assertTrue(d.hits.value.isEmpty())
        muted = emptySet()
        d.process(listOf(entry("Choreographer", "Skipped 60 frames!")))
        assertEquals(listOf("anr.skipped_frames"), d.hits.value.map { it.signal.id })
    }

    @Test
    fun `custom signals fire and win over built-ins`() {
        val config = LogSenseConfig(
            customSignals = mapOf(
                "Payment declined" to "tag:Checkout msg:declined",
                "Our own GC note" to """msg:"Waiting for a blocking GC"""",
            ),
        )
        val d = detector(config)
        d.process(
            listOf(
                entry("Checkout", "payment declined: insufficient funds"),
                entry("art", "Waiting for a blocking GC Alloc"),
            ),
        )
        assertEquals(listOf("custom.Payment declined", "custom.Our own GC note"), d.hits.value.map { it.signal.id })
        assertTrue(d.hits.value.all { it.signal.category == SignalCategory.CUSTOM })
    }

    @Test
    fun `a blank custom query never matches`() {
        val d = detector(LogSenseConfig(customSignals = mapOf("Everything" to "   ")))
        d.process(listOf(entry("OkHttp", "--> GET /v1/cart")))
        assertTrue(d.hits.value.isEmpty())
    }

    @Test
    fun `a hit points at the line it matched`() {
        val line = entry("Choreographer", "Skipped 41 frames!\ncontinuation line")
        val d = detector()
        d.process(listOf(line))
        val hit = d.hits.value.single()
        assertEquals(line.id, hit.entryId)
        assertEquals(line.timeMs, hit.timeMs)
        assertEquals("Choreographer", hit.tag)
        assertEquals("Skipped 41 frames!", hit.preview) // first line only
    }

    @Test
    fun `hits are capped, oldest evicted`() {
        val d = detector()
        repeat(600) { d.process(listOf(entry("Choreographer", "Skipped $it frames!"))) }
        d.publish() // rapid batches defer publication to the ticker's trailing edge
        val hits = d.hits.value
        assertEquals(500, hits.size)
        assertEquals("Skipped 100 frames!", hits.first().preview)
        assertEquals("Skipped 599 frames!", hits.last().preview)
    }

    @Test
    fun `recorded signals carry no line to jump to`() {
        val d = detector()
        d.record(com.msabhi.logsense.internal.signals.BuiltInSignals.FORCE_STOPPED, 123L, "stopped by user")
        val hit = d.hits.value.single()
        assertNull(hit.entryId)
        assertEquals(123L, hit.timeMs)
        assertEquals("stopped by user", hit.preview)
    }

    @Test
    fun `muting also silences recorded signals`() {
        val d = detector(muted = setOf("exit.force_stopped"))
        d.record(com.msabhi.logsense.internal.signals.BuiltInSignals.FORCE_STOPPED, 123L, "stopped by user")
        assertTrue(d.hits.value.isEmpty())
    }

    @Test
    fun `clear drops every hit`() {
        val d = detector()
        d.process(listOf(entry("Choreographer", "Skipped 41 frames!")))
        d.clear()
        assertTrue(d.hits.value.isEmpty())
    }

    @Test
    fun `every default-muted id exists in the catalog`() {
        // A typo here would silently mute nothing, and the noisy signal would still fire.
        val ids = com.msabhi.logsense.internal.signals.BuiltInSignals.CATALOG.map { it.id }.toSet()
        val defaults = com.msabhi.logsense.internal.signals.BuiltInSignals.MUTED_BY_DEFAULT
        assertTrue("unknown ids: ${defaults - ids}", ids.containsAll(defaults))
        assertTrue(defaults.isNotEmpty())
    }

    @Test
    fun `the signals muted by default are the ones that fire on a healthy run`() {
        val d = detector(muted = com.msabhi.logsense.internal.signals.BuiltInSignals.MUTED_BY_DEFAULT)
        d.process(
            listOf(
                entry("om.msabhi.lsapp", "Late-enabling -Xcheck:jni"),
                entry("art", "Clamp target GC heap from 100MB to 200MB"),
            ),
        )
        assertTrue("a healthy run should flag nothing", d.hits.value.isEmpty())
    }

    @Test
    fun `built-in ids are unique`() {
        val ids = com.msabhi.logsense.internal.signals.BuiltInSignals.CATALOG.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
