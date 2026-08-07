#if canImport(ActivityKit) && os(iOS)
import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// The Live Activity contract — the iOS stand-in for Android's ongoing capture notification.
/// LogSense manages the activity's lifecycle from inside the app; **the UI must live in your
/// widget extension** (an OS rule, not a LogSense one). Add one line to your bundle:
///
/// ```swift
/// @main
/// struct MyWidgets: WidgetBundle {
///     var body: some Widget {
///         MyExistingWidgets()
///         LogSenseLiveActivity()
///     }
/// }
/// ```
///
/// …and set `NSSupportsLiveActivities` to YES in the app target. Without the extension, every
/// other part of LogSense still works — the activity simply never appears.
public struct LogSenseActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        public var lines: Int
        public var events: Int
        public var crashes: Int
        public var sessionStart: Date
        public var phase: Phase
        /// Lines captured while paused, shown on the amber presentation.
        public var pausedBuffered: Int
        /// The newest captured line, pre-formatted ("D AnalyticsEngine · message"), for the ticker.
        public var latestLine: String
        public var crash: CrashSummary?

        public enum Phase: String, Codable, Hashable {
            case recording, paused, crashed, ended
        }

        public struct CrashSummary: Codable, Hashable {
            public var type: String
            public var title: String
            public var detail: String
            public var topFrame: String
            public var timeMs: Int64
        }
    }

    /// Fixed for the activity's lifetime.
    public var hostName: String

    public init(hostName: String) {
        self.hostName = hostName
    }
}

// MARK: - Widget

@available(iOS 16.2, *)
public struct LogSenseLiveActivity: Widget {

    public init() {}

    public var body: some WidgetConfiguration {
        ActivityConfiguration(for: LogSenseActivityAttributes.self) { context in
            LockScreenCard(context: context)
                .activityBackgroundTint(context.state.phase == .crashed ? Color(hex: 0xFF453A).opacity(0.2) : nil)
        } dynamicIsland: { context in
            // The expanded island has a hard height budget (~160pt): glyph/name/clock in the slim
            // top regions, tiles + buttons below. The latest-line ticker is Lock-Screen-only.
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    GlyphSquare(phase: context.state.phase)
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.attributes.hostName)
                        .font(.system(size: 14, weight: .semibold))
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    ExpandedTrailing(context: context)
                        .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    ExpandedBottom(context: context)
                }
            } compactLeading: {
                if context.state.phase == .crashed {
                    HStack(spacing: 4) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 11))
                        Text("Crash").font(.system(size: 13, weight: .semibold))
                    }
                    .foregroundStyle(Color(hex: 0xFF453A))
                } else {
                    HStack(spacing: 5) {
                        Circle()
                            .fill(context.state.phase == .paused ? Color(hex: 0xFF9F0A) : Color(hex: 0x30D158))
                            .frame(width: 7, height: 7)
                        Text(context.state.lines.formatted())
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                }
            } compactTrailing: {
                if context.state.phase == .crashed, let crash = context.state.crash {
                    Text(crash.title)
                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                        .foregroundStyle(Color(hex: 0xFF453A))
                        .lineLimit(1)
                        .frame(maxWidth: 72)
                } else {
                    SessionClock(start: context.state.sessionStart)
                        .font(.system(size: 13, weight: .medium, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.65))
                        .frame(maxWidth: 44)
                }
            } minimal: {
                // Contested Island: the glyph is the only affordance needed — blue healthy, red crash.
                Image(systemName: context.state.phase == .crashed ? "exclamationmark.triangle.fill" : "list.bullet.rectangle.fill")
                    .font(.system(size: 13))
                    .foregroundStyle(context.state.phase == .crashed ? Color(hex: 0xFF453A) : Color(hex: 0x0A84FF))
            }
        }
    }
}

// MARK: - shared pieces

@available(iOS 16.2, *)
private struct SessionClock: View {
    let start: Date

    var body: some View {
        // Auto-updating without activity updates; the far bound is the system's own 8 h ceiling.
        Text(timerInterval: start...start.addingTimeInterval(8 * 3600), countsDown: false)
            .multilineTextAlignment(.trailing)
            .monospacedDigit()
    }
}

@available(iOS 16.2, *)
private struct ExpandedHeader: View {
    let context: ActivityViewContext<LogSenseActivityAttributes>

    var body: some View {
        HStack(spacing: 10) {
            GlyphSquare(phase: context.state.phase)
            VStack(alignment: .leading, spacing: 2) {
                Text(context.attributes.hostName)
                    .font(.system(size: 14, weight: .semibold))
                    .lineLimit(1)
                Text(subtitle)
                    .font(.system(size: 11.5))
                    .foregroundStyle(subtitleColor)
                    .lineLimit(1)
            }
        }
    }

    private var subtitle: String {
        switch context.state.phase {
        case .recording: return "LogSense recording"
        case .paused: return "Paused · \(context.state.pausedBuffered.formatted()) lines buffered"
        case .crashed: return "Crash captured" + (context.state.crash.map { " · \(Format.time($0.timeMs))" } ?? "")
        case .ended: return "Session ended"
        }
    }

    private var subtitleColor: Color {
        switch context.state.phase {
        case .crashed: return Color(hex: 0xFF453A).opacity(0.9)
        case .paused: return Color(hex: 0xFF9F0A)
        default: return .secondary
        }
    }
}

/// The trailing slot is narrow on the expanded island — a dot and the clock, nothing more.
@available(iOS 16.2, *)
private struct ExpandedTrailing: View {
    let context: ActivityViewContext<LogSenseActivityAttributes>

    var body: some View {
        switch context.state.phase {
        case .crashed:
            if let crash = context.state.crash {
                Text(crash.type)
                    .font(.system(size: 10.5, weight: .bold, design: .monospaced))
                    .kerning(0.5)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color(hex: 0xFF453A).opacity(0.18), in: RoundedRectangle(cornerRadius: 7))
                    .foregroundStyle(Color(hex: 0xFF453A))
            }
        default:
            HStack(spacing: 5) {
                Circle()
                    .fill(context.state.phase == .paused ? Color(hex: 0xFF9F0A) : Color(hex: 0x30D158))
                    .frame(width: 7, height: 7)
                SessionClock(start: context.state.sessionStart)
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(.secondary)
                    // Natural width, not a capped frame — a squeezed timer Text renders dashes.
                    .fixedSize(horizontal: true, vertical: false)
            }
        }
    }
}

/// Fits the island's height budget: tiles + buttons, or the crash headline + buttons. The frame
/// ticker belongs to the Lock Screen card, where there is room.
@available(iOS 16.2, *)
private struct ExpandedBottom: View {
    let context: ActivityViewContext<LogSenseActivityAttributes>

    var body: some View {
        VStack(spacing: 8) {
            if context.state.phase == .crashed, let crash = context.state.crash {
                VStack(alignment: .leading, spacing: 3) {
                    Text(crash.title)
                        .font(.system(size: 17, weight: .bold))
                        .lineLimit(1)
                    if !crash.detail.isEmpty {
                        Text(crash.detail)
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack(spacing: 8) {
                    StatTile(value: context.state.lines, label: "lines", slim: true)
                    StatTile(value: context.state.events, label: "events", slim: true)
                    StatTile(value: context.state.crashes, label: "crashes", slim: true)
                }
            }
            ActivityButtons(context: context, slim: true)
        }
    }
}

@available(iOS 16.2, *)
internal struct LockScreenCard: View {
    let context: ActivityViewContext<LogSenseActivityAttributes>

    var body: some View {
        // Once the system stales the card (the app was killed and can no longer update it),
        // pretending to be live would lie — state the final counts instead.
        if context.isStale || context.state.phase == .ended {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    GlyphSquare(phase: .ended)
                    Text("LogSense")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.secondary)
                    Spacer()
                }
                Text("Session ended")
                    .font(.system(size: 14.5, weight: .semibold))
                Text("\(context.state.lines.formatted()) lines · \(context.state.events.formatted()) events · \(context.state.crashes.formatted()) crashes kept on device")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 16)
            .padding(.top, 14)
            .padding(.bottom, 16)
            .opacity(0.75)
        } else {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    ExpandedHeader(context: context)
                    Spacer()
                    ExpandedTrailing(context: context)
                }
                if context.state.phase == .crashed, let crash = context.state.crash {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(crash.title)
                            .font(.system(size: 19, weight: .bold))
                            .lineLimit(1)
                        if !crash.detail.isEmpty {
                            Text(crash.detail)
                                .font(.system(size: 13))
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        if !crash.topFrame.isEmpty {
                            Ticker(text: crash.topFrame, tint: .black.opacity(0.25))
                        }
                    }
                } else {
                    Text(context.state.phase == .paused ? "Capture paused" : "Recording logs")
                        .font(.system(size: 16, weight: .semibold))
                    HStack(spacing: 8) {
                        StatTile(value: context.state.lines, label: "lines")
                        StatTile(value: context.state.events, label: "events")
                        StatTile(value: context.state.crashes, label: "crashes")
                    }
                }
                ActivityButtons(context: context)
                    .padding(.top, 2)
            }
            .padding(.horizontal, 16)
            .padding(.top, 14)
            .padding(.bottom, 16)
        }
    }
}

@available(iOS 16.2, *)
private struct GlyphSquare: View {
    let phase: LogSenseActivityAttributes.ContentState.Phase

    var body: some View {
        Image(systemName: phase == .crashed ? "exclamationmark.triangle.fill" : "list.bullet.rectangle.fill")
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: 26, height: 26)
            .background(color, in: RoundedRectangle(cornerRadius: 7))
    }

    private var color: Color {
        switch phase {
        case .crashed: return Color(hex: 0xFF453A)
        case .paused: return Color(hex: 0xFF9F0A)
        case .ended: return Color(.systemGray)
        case .recording: return Color(hex: 0x0A84FF)
        }
    }
}

@available(iOS 16.2, *)
private struct StatTile: View {
    let value: Int
    let label: String
    /// The island's height budget is tight; the Lock Screen card has room.
    var slim: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: slim ? 2 : 3) {
            Text(value.formatted())
                .font(.system(size: slim ? 15 : 17, weight: .bold))
                .minimumScaleFactor(0.7)
                .lineLimit(1)
            Text(label)
                .font(.system(size: 10.5))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.vertical, slim ? 5 : 8)
        .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 11))
    }
}

@available(iOS 16.2, *)
private struct Ticker: View {
    let text: String
    let tint: Color

    var body: some View {
        Text(text)
            .font(.system(size: 11, design: .monospaced))
            .foregroundStyle(.primary.opacity(0.75))
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 11)
            .padding(.vertical, 8)
            .background(tint, in: RoundedRectangle(cornerRadius: 11))
    }
}

/// Buttons are iOS 17+ (`Button(intent:)` in Live Activities); below that the surfaces stay
/// informational and tapping the activity opens the app.
@available(iOS 16.2, *)
private struct ActivityButtons: View {
    let context: ActivityViewContext<LogSenseActivityAttributes>
    var slim: Bool = false

    var body: some View {
        if #available(iOS 17.0, *) {
            HStack(spacing: 9) {
                switch context.state.phase {
                case .crashed:
                    Button(intent: LogSenseKeepRecordingIntent()) { Label01("Keep recording", slim: slim) }
                        .buttonStyle(.plain)
                    Button(intent: LogSenseOpenIntent(destination: .crashes)) { Label01("Open report", prominent: Color(hex: 0xFF453A), slim: slim) }
                        .buttonStyle(.plain)
                case .paused:
                    Button(intent: LogSenseStopCaptureIntent()) { Label01("Stop", slim: slim) }
                        .buttonStyle(.plain)
                    Button(intent: LogSenseResumeCaptureIntent()) { Label01("Resume", prominent: Color(hex: 0xFF9F0A), dark: true, slim: slim) }
                        .buttonStyle(.plain)
                case .recording:
                    Button(intent: LogSensePauseCaptureIntent()) { Label01("Pause", slim: slim) }
                        .buttonStyle(.plain)
                    Button(intent: LogSenseOpenIntent(destination: .logs)) { Label01("Open logs", prominent: Color(hex: 0x0A84FF), slim: slim) }
                        .buttonStyle(.plain)
                case .ended:
                    EmptyView()
                }
            }
        }
    }
}

@available(iOS 16.2, *)
private struct Label01: View {
    let text: String
    let prominent: Color?
    let dark: Bool
    let slim: Bool

    init(_ text: String, prominent: Color? = nil, dark: Bool = false, slim: Bool = false) {
        self.text = text
        self.prominent = prominent
        self.dark = dark
        self.slim = slim
    }

    var body: some View {
        Text(text)
            .font(.system(size: slim ? 13 : 13.5, weight: .semibold))
            .foregroundStyle(prominent != nil ? (dark ? .black : .white) : .white)
            .lineLimit(1)
            .frame(maxWidth: .infinity)
            .padding(.vertical, slim ? 7 : 9)
            .background(prominent ?? .white.opacity(0.18), in: RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - App Intents (iOS 17+, run in the app's process)

@available(iOS 17.0, *)
public struct LogSensePauseCaptureIntent: LiveActivityIntent {
    public static var title: LocalizedStringResource = "Pause LogSense capture"
    public init() {}
    public func perform() async throws -> some IntentResult {
        await MainActor.run { LogSenseCore.shared?.pause() }
        return .result()
    }
}

@available(iOS 17.0, *)
public struct LogSenseResumeCaptureIntent: LiveActivityIntent {
    public static var title: LocalizedStringResource = "Resume LogSense capture"
    public init() {}
    public func perform() async throws -> some IntentResult {
        await MainActor.run { LogSenseCore.shared?.resume() }
        return .result()
    }
}

@available(iOS 17.0, *)
public struct LogSenseStopCaptureIntent: LiveActivityIntent {
    public static var title: LocalizedStringResource = "Stop LogSense capture"
    public init() {}
    public func perform() async throws -> some IntentResult {
        await MainActor.run { LogSenseCore.shared?.stopCapture() }
        return .result()
    }
}

/// Dismisses the crash takeover without ending the session — the activity returns to blue.
@available(iOS 17.0, *)
public struct LogSenseKeepRecordingIntent: LiveActivityIntent {
    public static var title: LocalizedStringResource = "Keep recording"
    public init() {}
    public func perform() async throws -> some IntentResult {
        await MainActor.run { LogSenseCore.shared?.markCrashesSeen() }
        return .result()
    }
}

@available(iOS 17.0, *)
public struct LogSenseOpenIntent: LiveActivityIntent {
    public static var title: LocalizedStringResource = "Open LogSense"
    public static var openAppWhenRun: Bool = true

    @Parameter(title: "Destination")
    public var destinationRaw: String

    public init() {
        self.destinationRaw = "logs"
    }

    init(destination: RootTab) {
        self.destinationRaw = destination == .crashes ? "crashes" : "logs"
    }

    public func perform() async throws -> some IntentResult {
        await MainActor.run {
            LogSense.present()
            LogSenseCore.shared?.state.requestedTab = destinationRaw == "crashes" ? .crashes : .logs
            if destinationRaw == "crashes" { LogSenseCore.shared?.markCrashesSeen() }
        }
        return .result()
    }
}
#endif
