import SwiftUI

/// Configuration for `LogSense.start`. All parameters have sensible defaults;
/// construct with named arguments for just what you need.
public struct LogSenseConfig {
    /// Log tags (unified-log **categories**; the sender binary name for `NSLog` and bare `os_log`
    /// lines) to capture as analytics events, each mapped to an **optional regex** that extracts the
    /// event from that tag's lines. Empty map = analytics disabled.
    ///
    /// - value `nil` → use the built-in parser (understands `name {json}`, `name Bundle[{k=v}]`,
    ///   `name k=v, k2=v2` and Swift-`Dictionary`-description payloads like `["k": v, "k2": v2]`;
    ///   a `params` group capturing a `{json}` object is parsed as JSON, and string fields that
    ///   themselves hold escaped JSON are unwrapped into flat params).
    /// - value a regex → use it for *that tag only*, exposing a named group `name` (required) and an
    ///   optional `params`. Use this for SDKs the built-in parser can't infer (e.g. those that bury
    ///   the event name inside a JSON payload). A line that doesn't match is skipped.
    ///
    /// QA can add further tags (with their own optional regex) in Settings, but the ones set here are
    /// locked and can't be edited in-app. `analyticsExtractor`, if set, overrides the regex for every
    /// captured tag.
    public var analyticsTagPatterns: [String: String?]

    /// Converts a matching log line into an `AnalyticsEvent`. Return nil to skip the line. When nil,
    /// each tag in `analyticsTagPatterns` uses its own regex (or the built-in parser). When set, this
    /// overrides those and runs for every captured tag.
    public var analyticsExtractor: ((_ tag: String, _ message: String) -> AnalyticsEvent?)?

    /// Max log lines kept in the in-memory buffer (clamped down on low-RAM devices).
    public var maxBufferedLines: Int

    /// Whether LogSense installs an uncaught-exception handler to capture NSException crashes.
    /// It always **chains to any handler already installed**, so your existing crash reporter
    /// still runs. Signal-level crashes (fatalError, force-unwraps) and hangs are captured via
    /// MetricKit regardless. Set false to leave the exception handler untouched.
    public var captureCrashes: Bool

    /// Whether LogSense intercepts stdout so `print(...)` output appears in the stream (unified
    /// logging never sees stdout). The original stdout still works — output is teed through, so
    /// the Xcode console is unaffected.
    public var captureStandardOutput: Bool

    /// Flag screens that outlive their dismissal — a dismissed/popped view controller still in
    /// memory seconds later means a retain cycle or a lingering strong reference; it lands as a
    /// "Screen leaked" signal. This is the one feature that swizzles (`viewDidDisappear`,
    /// observe-only), which is why it has its own switch. Screens the host deliberately caches
    /// will be flagged too — mute the signal or turn this off if that's your architecture.
    public var detectLeakedScreens: Bool

    /// Number of most recent log lines attached to a crash report as context.
    public var crashContextLines: Int

    /// Stored events/crashes older than this are deleted on start.
    public var retentionDays: Int

    /// Max analytics events kept in storage per session.
    public var maxStoredEvents: Int

    /// Max crash reports kept in storage.
    public var maxStoredCrashes: Int

    /// Max number of recent sessions (process runs) whose events/crashes are kept; older are pruned.
    public var maxSessions: Int

    /// Post a local notification on the launch after a crash, deep-linking to the report
    /// (inert until the host has notification permission).
    public var showCrashNotification: Bool

    /// Show capture state as a Live Activity (Dynamic Island + Lock Screen; red on a crash).
    /// iOS 16.2+, and requires the host's widget extension to include `LogSenseLiveActivity` —
    /// without it this is inert. QA can also switch it off in Settings.
    public var showLiveActivity: Bool

    /// Force light/dark, or follow the system.
    public var theme: ThemeMode

    /// Accent color for the LogSense UI; nil = system default.
    public var accentColor: Color?

    /// Extra signals to watch for, as `label -> filter query`, on top of the built-in catalog.
    /// Matches show up highlighted in the log stream.
    ///
    /// The query is the same syntax as the Logs filter field — `tag:`, `-tag:`, `msg:`, `sub:`,
    /// `level:`, bare words and `"quoted phrases"`, all ANDed:
    ///
    /// ```swift
    /// customSignals = [
    ///     "Payment declined": #"tag:Checkout msg:declined"#,
    ///     "Token refresh failed": #"level:E msg:"refresh token""#,
    /// ]
    /// ```
    ///
    /// Custom signals are checked before the built-ins, so they win a line both could match.
    public var customSignals: [String: String]

    public init(
        analyticsTagPatterns: [String: String?] = [:],
        analyticsExtractor: ((_ tag: String, _ message: String) -> AnalyticsEvent?)? = nil,
        maxBufferedLines: Int = 50_000,
        captureCrashes: Bool = true,
        captureStandardOutput: Bool = true,
        detectLeakedScreens: Bool = true,
        crashContextLines: Int = 200,
        retentionDays: Int = 7,
        maxStoredEvents: Int = 1_000,
        maxStoredCrashes: Int = 50,
        maxSessions: Int = 10,
        showCrashNotification: Bool = true,
        showLiveActivity: Bool = true,
        theme: ThemeMode = .system,
        accentColor: Color? = nil,
        customSignals: [String: String] = [:]
    ) {
        self.analyticsTagPatterns = analyticsTagPatterns
        self.analyticsExtractor = analyticsExtractor
        self.maxBufferedLines = maxBufferedLines
        self.captureCrashes = captureCrashes
        self.captureStandardOutput = captureStandardOutput
        self.detectLeakedScreens = detectLeakedScreens
        self.crashContextLines = crashContextLines
        self.retentionDays = retentionDays
        self.maxStoredEvents = maxStoredEvents
        self.maxStoredCrashes = maxStoredCrashes
        self.maxSessions = maxSessions
        self.showCrashNotification = showCrashNotification
        self.showLiveActivity = showLiveActivity
        self.theme = theme
        self.accentColor = accentColor
        self.customSignals = customSignals
    }
}
