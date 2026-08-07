#if canImport(ActivityKit) && os(iOS)
import ActivityKit
import Foundation

/// Owns the one Live Activity per capture session: started on the first captured line, updated in
/// place, ended on stop. Never more than one — leftovers from a previous run are ended at startup
/// (the stale card has already told their story).
///
/// Start failure is silently tolerated: a host without the widget extension, or with activities
/// switched off, loses the Island — nothing else.
@available(iOS 16.2, *)
internal final class LiveActivityController {

    private let hostName: String
    private let enabled: () -> Bool

    private let lock = NSLock()
    private var activity: Activity<LogSenseActivityAttributes>?
    private var lastUpdate = Date.distantPast
    private var lastState: LogSenseActivityAttributes.ContentState?

    /// Routine counter updates are silent and throttled — the Island doesn't need the 1 Hz poll.
    private static let minUpdateInterval: TimeInterval = 5

    init(hostName: String, enabled: @escaping () -> Bool) {
        self.hostName = hostName
        self.enabled = enabled
        for leftover in Activity<LogSenseActivityAttributes>.activities {
            Task { await leftover.end(nil, dismissalPolicy: .immediate) }
        }
    }

    /// `alert` breaks through (Island expands once, Lock Screen lights) — crash updates only.
    func update(
        _ state: LogSenseActivityAttributes.ContentState,
        immediate: Bool = false,
        alert: (title: String, body: String)? = nil
    ) {
        lock.lock()
        guard enabled() else {
            let current = activity
            activity = nil
            lock.unlock()
            if let current {
                Task { await current.end(nil, dismissalPolicy: .immediate) }
            }
            return
        }
        if !immediate {
            let unchanged = lastState.map {
                $0.lines == state.lines && $0.events == state.events
                    && $0.crashes == state.crashes && $0.phase == state.phase
            } ?? false
            if unchanged || Date().timeIntervalSince(lastUpdate) < Self.minUpdateInterval {
                lock.unlock()
                return
            }
        }
        lastUpdate = Date()
        lastState = state
        let current = activity
        lock.unlock()

        let content = ActivityContent(state: state, staleDate: Date().addingTimeInterval(600))
        if let current {
            Task {
                let alertConfig = alert.map {
                    AlertConfiguration(
                        title: .init(stringLiteral: $0.title),
                        body: .init(stringLiteral: $0.body),
                        sound: .default
                    )
                }
                await current.update(content, alertConfiguration: alertConfig)
            }
        } else {
            guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
            let started = try? Activity.request(
                attributes: LogSenseActivityAttributes(hostName: hostName),
                content: content
            )
            lock.lock()
            activity = started
            lock.unlock()
        }
    }

    /// Ends the activity with the final counts — the "Session ended" card, honestly non-live.
    func end(_ finalState: LogSenseActivityAttributes.ContentState) {
        lock.lock()
        let current = activity
        activity = nil
        lastState = nil
        lock.unlock()
        guard let current else { return }
        var ended = finalState
        ended.phase = .ended
        Task {
            await current.end(
                ActivityContent(state: ended, staleDate: nil),
                dismissalPolicy: .default
            )
        }
    }
}
#endif
