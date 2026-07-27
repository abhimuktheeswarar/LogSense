package com.msabhi.logsense.internal.signals

/**
 * What kind of trouble a [Signal] reports. CRASH / ANR / NATIVE are also produced by the crash
 * pipeline (`CrashHandler` + `ExitInfoCollector`) — those arrive as `CrashEntity` rows on the next
 * launch, while the patterns here fire immediately, in the run where the problem happens.
 */
internal enum class SignalCategory(val label: String) {
    CRASH("Crash"),
    ANR("ANR"),
    NATIVE("Native"),
    MEMORY("Memory"),
    LIFECYCLE("Lifecycle"),
    CUSTOM("Custom"),
}

/**
 * One catalog entry: a label plus, usually, a filter query. The query is the same
 * Android-Studio-style syntax the Logs filter field takes
 * ([com.msabhi.logsense.internal.logs.LogQuery]) — `tag:`, `-tag:`, `msg:`, `level:`, bare words,
 * `"quoted phrases"`, all ANDed — so there is no second matching language to learn or maintain.
 *
 * A **blank [query] means the signal is reported, not matched**: some conditions never appear as a
 * log line in our own process (a force-stop, a low-memory kill, a first frame) and are recorded
 * straight from the platform API that does know about them. Those still carry a label, a category
 * and a mute, they just have no pattern and no log line to jump to.
 *
 * [id] is stable across releases: it is what a mute is persisted under.
 */
internal data class Signal(
    val id: String,
    val label: String,
    val category: SignalCategory,
    val query: String = "",
)

/**
 * The built-in catalog.
 *
 * Every *pattern* here is emitted **by the app's own process**, because LogSense reads
 * `logcat --pid=<myPid>`: `system_server`'s own lines about us can never reach the process buffer.
 * The conditions those lines would have reported — the app being force-stopped, killed by a signal,
 * reaped for low memory, or an activity coming up — are covered instead by the reported signals at
 * the bottom, which read the platform APIs that actually know: `ApplicationExitInfo` and the
 * activity lifecycle callbacks. No permission, and more accurate than scraping a log line.
 *
 * **Order matters for patterns: a line produces at most one hit, the first rule that matches.** So
 * rules run specific-before-generic — `SIGSEGV` before the bare `Fatal signal`, `FATAL EXCEPTION`
 * before the individual exception types it contains — and a crashing line is reported once, as the
 * crash.
 */
internal object BuiltInSignals {

    /* Reported, not matched — see the [Signal] doc. Held as named constants because the code that
     * detects them refers to them directly. */

    val PROCESS_SIGNALED = Signal("exit.signaled", "Process killed by signal", SignalCategory.LIFECYCLE)
    val FORCE_STOPPED = Signal("exit.force_stopped", "Force stopped", SignalCategory.LIFECYCLE)
    val USER_STOPPED = Signal("exit.user_stopped", "Stopped by user", SignalCategory.LIFECYCLE)
    val PERMISSION_CHANGE = Signal("exit.permission_change", "Killed by permission change", SignalCategory.LIFECYCLE)
    val INIT_FAILURE = Signal("exit.init_failure", "Initialization failure", SignalCategory.LIFECYCLE)
    val DEPENDENCY_DIED = Signal("exit.dependency_died", "Dependency process died", SignalCategory.LIFECYCLE)
    val LOW_MEMORY_KILL = Signal("exit.low_memory", "Low memory kill", SignalCategory.MEMORY)
    val EXCESSIVE_RESOURCE = Signal("exit.excessive_resource", "Excessive resource usage", SignalCategory.MEMORY)
    val ACTIVITY_START = Signal("lifecycle.activity_start", "Activity started", SignalCategory.LIFECYCLE)
    val FIRST_FRAME = Signal("lifecycle.first_frame", "First frame displayed", SignalCategory.LIFECYCLE)

    val CATALOG: List<Signal> = listOf(
        // ---- crash ------------------------------------------------------------------------
        Signal("crash.fatal", "FATAL EXCEPTION", SignalCategory.CRASH, """msg:"FATAL EXCEPTION""""),
        Signal("crash.network_main", "Network on main thread", SignalCategory.CRASH, "msg:NetworkOnMainThreadException"),
        Signal("crash.security", "SecurityException", SignalCategory.CRASH, "msg:SecurityException"),
        Signal("crash.sqlite", "SQLiteException", SignalCategory.CRASH, "msg:SQLiteException"),
        Signal("crash.ssl", "TLS handshake failed", SignalCategory.CRASH, "msg:SSLHandshakeException"),

        // ---- native fault -----------------------------------------------------------------
        Signal("native.sigsegv", "SIGSEGV", SignalCategory.NATIVE, """msg:"Fatal signal" msg:SIGSEGV"""),
        Signal("native.sigabrt", "SIGABRT", SignalCategory.NATIVE, """msg:"Fatal signal" msg:SIGABRT"""),
        Signal("native.sigbus", "SIGBUS", SignalCategory.NATIVE, """msg:"Fatal signal" msg:SIGBUS"""),
        Signal("native.sigill", "SIGILL", SignalCategory.NATIVE, """msg:"Fatal signal" msg:SIGILL"""),
        Signal("native.fatal_signal", "Fatal signal", SignalCategory.NATIVE, """msg:"Fatal signal""""),
        Signal("native.jni", "JNI error", SignalCategory.NATIVE, """msg:"JNI DETECTED ERROR""""),
        Signal("native.abort", "Abort message", SignalCategory.NATIVE, """msg:"Abort message""""),

        // ---- memory -----------------------------------------------------------------------
        Signal("memory.oom", "OutOfMemoryError", SignalCategory.MEMORY, "msg:OutOfMemoryError"),
        Signal("memory.blocking_gc", "Blocking GC", SignalCategory.MEMORY, """msg:"Waiting for a blocking GC""""),
        Signal("memory.soft_refs", "SoftReferences collected", SignalCategory.MEMORY, """msg:"Forcing collection of SoftReferences""""),
        Signal("memory.clamp_heap", "Heap clamped", SignalCategory.MEMORY, """msg:"Clamp target GC heap""""),
        Signal("memory.alloc_fail", "Allocation failure", SignalCategory.MEMORY, """msg:"Failed to allocate a""""),
        Signal("memory.big_bitmap", "Bitmap too large", SignalCategory.MEMORY, """msg:"trying to draw too large""""),
        Signal("memory.cursor_window", "Cursor row too big", SignalCategory.MEMORY, """msg:"Row too big to fit into CursorWindow""""),

        // ---- ANR precursors (the ANR itself comes from ApplicationExitInfo) ----------------
        Signal("anr.skipped_frames", "Skipped frames", SignalCategory.ANR, "tag:Choreographer msg:Skipped"),
        Signal("anr.davey", "Long frame", SignalCategory.ANR, """msg:"Davey!""""),
        Signal("anr.monitor", "Long monitor contention", SignalCategory.ANR, """msg:"Long monitor contention""""),
        Signal("anr.suspend", "Slow thread suspension", SignalCategory.ANR, """msg:"Suspending all threads took""""),
        // "policy violation", not just "violation": StrictMode prints its stack frames as separate
        // StrictMode-tagged lines, and a frame like readAndHandleBinderCallViolations would match.
        Signal("anr.strictmode", "StrictMode violation", SignalCategory.ANR, """tag:StrictMode msg:"policy violation""""),

        // ---- lifecycle & leaks ------------------------------------------------------------
        Signal("lifecycle.leak_window", "Leaked window", SignalCategory.LIFECYCLE, """msg:"has leaked window""""),
        Signal("lifecycle.leak_service", "Leaked ServiceConnection", SignalCategory.LIFECYCLE, """msg:"has leaked ServiceConnection""""),
        Signal("lifecycle.leak_receiver", "Leaked IntentReceiver", SignalCategory.LIFECYCLE, """msg:"has leaked IntentReceiver""""),
        Signal("lifecycle.leak_resource", "Resource never released", SignalCategory.LIFECYCLE, """msg:"but never released""""),
        Signal("lifecycle.cursor", "Cursor not closed", SignalCategory.LIFECYCLE, """msg:"Finalizing a Cursor that has not been deactivated""""),
        Signal("lifecycle.bad_token", "Bad window token", SignalCategory.LIFECYCLE, "msg:BadTokenException"),
        Signal("lifecycle.save_state", "Action after onSaveInstanceState", SignalCategory.LIFECYCLE, """msg:"after onSaveInstanceState""""),
        Signal("lifecycle.service_unreg", "Service not registered", SignalCategory.LIFECYCLE, """msg:"Service not registered""""),
        Signal("lifecycle.process_start", "Process start", SignalCategory.LIFECYCLE, """msg:"Late-enabling""""),

        // ---- reported by the platform, no pattern ------------------------------------------
        PROCESS_SIGNALED,
        FORCE_STOPPED,
        USER_STOPPED,
        PERMISSION_CHANGE,
        INIT_FAILURE,
        DEPENDENCY_DIED,
        LOW_MEMORY_KILL,
        EXCESSIVE_RESOURCE,
        ACTIVITY_START,
        FIRST_FRAME,
    )

    /** Built-ins plus the host's `customSignals`. Custom rules go first so they win a shared line. */
    fun catalog(custom: Map<String, String>): List<Signal> =
        custom.map { (label, query) -> Signal("custom.$label", label, SignalCategory.CUSTOM, query) } + CATALOG
}
