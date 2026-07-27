package com.msabhi.logsense

/**
 * Configuration for [LogSense.init]. All parameters have sensible defaults;
 * construct with named arguments for just what you need.
 */
public class LogSenseConfig(
    /**
     * Log tags to capture as analytics events, each mapped to an **optional regex** that extracts the
     * event from that tag's lines. Empty map = analytics disabled.
     *
     * - value `null` → use the built-in parser (understands `name {json}`, `name Bundle[{k=v}]` and
     *   `name k=v, k2=v2`; a `params` group capturing a `{json}` object is parsed as JSON, and string
     *   fields that themselves hold escaped JSON are unwrapped into flat params).
     * - value a regex → use it for *that tag only*, exposing a named group `name` (required) and an
     *   optional `params`. Use this for SDKs the built-in parser can't infer (e.g. those that bury the
     *   event name inside a JSON payload). A line that doesn't match is skipped.
     *
     * QA can add further tags (with their own optional regex) in Settings, but the ones set here are
     * locked and can't be edited in-app. [analyticsExtractor], if set, overrides the regex for every
     * captured tag.
     */
    public val analyticsTagPatterns: Map<String, String?> = emptyMap(),
    /**
     * Converts a matching log line into an [AnalyticsEvent]. Return null to skip the line. When null,
     * each tag in [analyticsTagPatterns] uses its own regex (or the built-in parser). When set, this
     * overrides those and runs for every captured tag.
     */
    public val analyticsExtractor: ((tag: String, message: String) -> AnalyticsEvent?)? = null,
    /** Max log lines kept in the in-memory buffer. */
    public val maxBufferedLines: Int = 50_000,
    /**
     * Whether LogSense installs a `Thread.UncaughtExceptionHandler` to capture JVM crashes.
     * It always **chains to any handler already installed**, so your existing crash reporter
     * (Crashlytics, etc.) still runs. Set false to leave the uncaught-exception handler untouched
     * (ANRs and native crashes are still captured via `ApplicationExitInfo`).
     */
    public val captureJvmCrashes: Boolean = true,
    /** Number of most recent log lines attached to a crash report as context. */
    public val crashContextLines: Int = 200,
    /** Stored events/crashes older than this are deleted on init. */
    public val retentionDays: Int = 7,
    /** Max analytics events kept in storage. */
    public val maxStoredEvents: Int = 1_000,
    /** Max crash reports kept in storage. */
    public val maxStoredCrashes: Int = 50,
    /** Max number of recent sessions (process runs) whose events/crashes are kept; older are pruned. */
    public val maxSessions: Int = 10,
    /** Show the ongoing "capturing" notification (requires notifications enabled by the user). */
    public val showNotification: Boolean = true,
    /** Force light/dark, or follow the system. */
    public val theme: ThemeMode = ThemeMode.SYSTEM,
    /** ARGB color int used as the accent (Material primary) color; null = LogSense default. */
    public val accentColor: Int? = null,
    /**
     * Extra signals to watch for, as `label -> filter query`, on top of the built-in catalog
     * (crashes, ANRs, native faults, memory pressure and lifecycle events, all detected out of the
     * box). Matches show up in the Signals tab and are highlighted in the log stream.
     *
     * The query is the same syntax as the Logs filter field — `tag:`, `-tag:`, `msg:`, `level:`,
     * bare words and `"quoted phrases"`, all ANDed:
     *
     * ```
     * customSignals = mapOf(
     *     "Payment declined" to """tag:Checkout msg:declined""",
     *     "Token refresh failed" to """level:W msg:"refresh token"""",
     * )
     * ```
     *
     * Custom signals are checked before the built-ins, so they win a line both could match.
     */
    public val customSignals: Map<String, String> = emptyMap(),
)
