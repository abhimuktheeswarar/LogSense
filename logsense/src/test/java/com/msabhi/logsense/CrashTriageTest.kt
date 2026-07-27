package com.msabhi.logsense

import com.msabhi.logsense.internal.data.CrashEntity
import com.msabhi.logsense.internal.signals.appFrame
import com.msabhi.logsense.internal.signals.triage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashTriageTest {

    private fun crash(
        type: String = "JVM",
        exceptionClass: String? = null,
        message: String? = null,
        stacktrace: String = "",
    ) = CrashEntity(
        timestamp = 0L,
        sessionId = "s",
        type = type,
        threadName = "main",
        exceptionClass = exceptionClass,
        message = message,
        stacktrace = stacktrace,
        deviceInfo = "",
        logContext = "",
    )

    private val trace = """
        java.lang.NullPointerException: Attempt to invoke virtual method 'int com.foo.Cart.size()'
            at com.foo.app.cart.CartViewModel.load(CartViewModel.kt:42)
            at com.foo.app.cart.CartFragment.onViewCreated(CartFragment.kt:18)
            at android.app.Activity.performCreate(Activity.java:8051)
    """.trimIndent()

    @Test
    fun `an exception that explains itself gets no note`() {
        // Writing "something was null" under a NullPointerException is noise dressed as help.
        val result = triage(crash(exceptionClass = "java.lang.NullPointerException", stacktrace = trace), "com.foo.app")
        assertNull(result.note)
        assertNotNull(result.appFrame)
    }

    @Test
    fun `an exception with a non-obvious remedy gets a note`() {
        val result = triage(
            crash(exceptionClass = "android.app.RemoteServiceException\$ForegroundServiceDidNotStartInTimeException"),
            "com.foo.app",
        )
        assertTrue(result.note!!.contains("startForeground"))
    }

    @Test
    fun `an unknown exception gets no note either`() {
        assertNull(triage(crash(exceptionClass = "com.foo.WeirdBespokeException"), "com.foo.app").note)
    }

    @Test
    fun `an ANR points at the signals before it, having no usable trace`() {
        val result = triage(crash(type = "ANR", exceptionClass = null), "com.foo.app")
        assertTrue(result.note!!.contains("skipped frames"))
    }

    @Test
    fun `a native fault explains its signal`() {
        assertTrue(triage(crash(type = "NATIVE"), "com.foo.app").note!!.contains("SIGSEGV"))
    }

    @Test
    fun `an inner-class exception name still resolves`() {
        val result = triage(
            crash(exceptionClass = "android.view.WindowManager\$BadTokenException"),
            "com.foo.app",
        )
        assertTrue(result.note!!.contains("window added"))
    }

    @Test
    fun `the app frame is the topmost host frame, not the framework one`() {
        assertEquals("com.foo.app.cart.CartViewModel.load(CartViewModel.kt:42)", appFrame(trace, "com.foo.app"))
    }

    @Test
    fun `a suffixed application id still finds its own frames`() {
        // applicationIdSuffix ".debug" — the running id doesn't match the class package.
        assertEquals(
            "com.foo.app.cart.CartViewModel.load(CartViewModel.kt:42)",
            appFrame(trace, "com.foo.app.debug"),
        )
    }

    @Test
    fun `a trace with no host frame yields null`() {
        val frameworkOnly = """
            java.lang.IllegalStateException: nope
                at android.app.Activity.performCreate(Activity.java:8051)
                at android.os.Handler.dispatchMessage(Handler.java:106)
        """.trimIndent()
        assertNull(appFrame(frameworkOnly, "com.foo.app"))
    }

    @Test
    fun `LogSense's own frames are never offered as the app frame`() {
        val ourTrace = """
            java.lang.IllegalStateException: nope
                at com.msabhi.logsense.internal.LogSenseCore.start(LogSenseCore.kt:81)
                at android.app.Activity.performCreate(Activity.java:8051)
        """.trimIndent()
        assertNull(appFrame(ourTrace, "com.msabhi.lsapp"))
    }

    @Test
    fun `an empty trace yields null`() {
        assertNull(appFrame("", "com.foo.app"))
    }
}
