import Foundation
import Combine
import OSLog
#if os(iOS)
import UIKit
import UserNotifications
#endif

internal enum CaptureStatus {
    case waiting, live, paused
}

/// The high-churn log feed, split from `LogSenseState` on purpose: `snapshot` republishes at
/// the poll cadence, and ObservableObject invalidation is object-granular — every screen
/// observing the shared state would re-evaluate per publish. Only LogsScreen observes this.
@MainActor
internal final class LogsFeed: ObservableObject {
    @Published var snapshot: [LogEntry] = []
    @Published var totalReceived = 0
    @Published var bufferedWhilePaused = 0
    /// When paused, the wall-clock time of the newest published line ("Stream frozen at …").
    @Published var frozenAtMs: Int64 = 0
}

@MainActor
internal final class LogSenseState: ObservableObject {
    @Published var status: CaptureStatus = .waiting
    /// Stored crash reports, newest first, across sessions.
    @Published var crashes: [StoredCrash] = []
    /// Read crash reports (by `StoredCrash.readKey`); the tab badge counts the rest.
    @Published var readCrashKeys: Set<String> = Prefs.readCrashKeys()
    /// Bumped each time the UI is (re)opened — screens jump back to their latest content.
    @Published var uiEpoch = 0
    /// Signal hits, oldest first, in-memory — they die with the buffer they point into.
    @Published var signalHits: [SignalHit] = []
    /// Stored analytics events, newest first, across sessions.
    @Published var events: [StoredEvent] = []
    /// A tab something outside the UI asked for (a notification tap); consumed by RootView.
    @Published var requestedTab: RootTab?
    /// True when polling keeps returning nothing at all: the launch diverted the unified-log
    /// stream away from the store (an Xcode-launched run does this even with the debugger
    /// off, and such a launch is indistinguishable from a normal one from inside the process).
    @Published var storeSilent = false
    /// A log line the Signals tab asked to reveal; consumed by LogsScreen (jump + line sheet).
    @Published var revealEntryId: Int64?
}

internal enum RootTab {
    case logs, events, crashes, signals
}

/// The process singleton every part of LogSense hangs off. No DI framework — this is passed down
/// explicitly, mirroring the Android `LogSenseCore`.
internal final class LogSenseCore {

    private(set) static var shared: LogSenseCore?

    let config: LogSenseConfig
    let bufferLimit: Int
    let buffer: LogBuffer
    let state: LogSenseState
    let feed: LogsFeed
    let hostName: String
    /// The host executable's name — what its frames carry in a stack trace.
    let appBinary: String
    let sessionStore: SessionStore?

    private let logReader = LogReader()
    private let stdoutReader = StdoutReader()
    private(set) var signals: SignalDetector!
    private var analytics: AnalyticsDetector!
    #if os(iOS)
    private var metricKit: MetricKitCollector?
    private var lifecycle: LifecycleSignals?
    /// `LiveActivityController` behind an existential — the field must exist below iOS 16.2.
    private var liveActivityBox: AnyObject?
    #endif
    private var pollTask: Task<Void, Never>?

    /// Facts the Live Activity shows, written from several threads (poll, stdout, MetricKit).
    private let statsLock = NSLock()
    private var sessionEventCount = 0
    private var crashesSinceLaunch = 0
    private var newestCrashMs: Int64 = 0
    private var latestCrashSummary: (type: String, title: String, detail: String, topFrame: String, thread: String?, timeMs: Int64)?
    let sessionStartDate = Date()
    /// Read from the poll loop, written from the UI. Worst case a stale read delays one publish
    /// by a poll tick — not worth a lock.
    private var paused = false

    /// Idempotent; subsequent calls are ignored, like the Android `init`. Throwing so later
    /// phases' setup (storage directories, crash-file ingestion) reports through one path.
    @MainActor
    static func start(_ config: LogSenseConfig) throws {
        guard shared == nil else { return }
        let core = LogSenseCore(config: config)
        shared = core
        core.startCapture()
        #if os(iOS)
        core.installEntryPoints()
        #endif
    }

    @MainActor
    private init(config: LogSenseConfig) {
        self.config = config
        self.bufferLimit = Self.ramAwareBufferLimit(config.maxBufferedLines)
        self.buffer = LogBuffer(maxLines: bufferLimit, maxBytes: config.maxBufferedBytes)
        self.state = LogSenseState()
        self.feed = LogsFeed()
        self.hostName = Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String
            ?? Bundle.main.object(forInfoDictionaryKey: kCFBundleNameKey as String) as? String
            ?? ProcessInfo.processInfo.processName
        self.appBinary = Bundle.main.object(forInfoDictionaryKey: "CFBundleExecutable") as? String
            ?? ProcessInfo.processInfo.processName
        // Storage failing must not take capture down with it — crashes degrade, logs keep working.
        self.sessionStore = try? SessionStore(
            root: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("LogSense"),
            config: config,
            keepPastEvents: Prefs.keepPastEvents(),
            keepPastCrashes: Prefs.keepPastCrashes()
        )
    }

    /// A buffer of huge lines on a small phone is how a debug tool OOMs the app it's watching.
    private static func ramAwareBufferLimit(_ configured: Int) -> Int {
        let configured = max(1, configured)
        let ram = ProcessInfo.processInfo.physicalMemory
        if ram < 3 << 30 { return min(configured, 20_000) }
        if ram < 4 << 30 { return min(configured, 35_000) }
        return configured
    }

    private func startCapture() {
        analytics = AnalyticsDetector(config: config, settingsTagPatterns: { Prefs.analyticsTags() })
        signals = SignalDetector(config: config, muted: { Prefs.mutedSignals() })
        signals.onChange = { [weak self] hits in
            Task { @MainActor [weak self] in self?.state.signalHits = hits }
        }
        #if os(iOS)
        let lifecycle = LifecycleSignals()
        lifecycle.start { [weak self] signal, ts, detail in
            self?.signals.record(signal, timeMs: ts, detail: detail)
        }
        self.lifecycle = lifecycle
        #endif
        LeakWatch.onLeak = { [weak self] detail in
            self?.signals.record(
                BuiltInSignals.leakedScreen,
                timeMs: Int64(Date().timeIntervalSince1970 * 1000),
                detail: detail
            )
        }
        #if os(iOS)
        if config.detectLeakedScreens {
            Task { @MainActor in LeakWatch.installScreenWatch() }
        }
        #endif
        if config.captureStandardOutput {
            stdoutReader.onLines = { [weak self] lines in self?.ingestStdout(lines) }
            stdoutReader.start()
        }
        #if os(iOS)
        if #available(iOS 16.2, *), config.showLiveActivity {
            liveActivityBox = LiveActivityController(
                hostName: hostName,
                enabled: { Prefs.liveActivityEnabled() }
            )
        }
        #endif
        startCrashPipeline()
        // One guaranteed unified-log line: a healthy store returns it within a poll or two,
        // so a stream still empty after several polls is *diverted* (Xcode-launched run),
        // not merely a quiet host. `storeSilent` keys off exactly that.
        os_log("LogSense capture started")
        pollTask = Task.detached(priority: .utility) { [weak self] in
            // Opening the store and building its enumerator costs real time (~1s observed on
            // the simulator) regardless of how many lines are new, so cadence is the only
            // lever: back off while the host is quiet, snap back the moment lines arrive,
            // and never sleep less than twice what the poll itself cost.
            var quietPolls = 0
            while !Task.isCancelled {
                let started = DispatchTime.now().uptimeNanoseconds
                let sawLines = self?.pollOnce() ?? false
                let elapsed = DispatchTime.now().uptimeNanoseconds - started
                quietPolls = sawLines ? 0 : quietPolls + 1
                let idleStretch = LogSenseCore.pollIntervalNs * UInt64(min(5, 1 + quietPolls))
                // Nobody watching → poll lazily (quarter duty); UI open → stay responsive
                // (half duty). In a host whose store makes every poll cost ~1s, this is the
                // difference between a visible burden and background noise.
                let duty: UInt64 = self?.uiVisible == true ? 2 : 4
                var backoff = min(max(idleStretch, elapsed * duty), 5_000_000_000)
                // Someone staring at an empty screen is the one case the lazy cadence must
                // not protect the host in: poll tight until the store either delivers or is
                // diagnosed diverted — bounded, since one of the two happens within 5 polls.
                if self?.uiVisible == true, self?.logReader.entriesDelivered == 0,
                   self?.storeSilentSeen == false {
                    backoff = 250_000_000
                }
                // Sliced so opening the UI can cut a long sleep short — the screen should
                // fill now, not when the lazy cadence happens to come around.
                var remaining = backoff
                while remaining > 0, !Task.isCancelled, self?.pollWakeRequested != true {
                    let slice = min(remaining, 250_000_000)
                    try? await Task.sleep(nanoseconds: slice)
                    remaining -= slice
                }
                self?.pollWakeRequested = false
            }
        }
    }

    private func startCrashPipeline() {
        guard let store = sessionStore else { return }
        let deviceInfo = DeviceInfo.summary(sessionStartedAtMs: store.sessionStartedAt(store.currentSessionId))
        if config.captureCrashes {
            CrashHandler.install(CrashHandler.Context(
                pendingDir: store.pendingDir,
                buffer: buffer,
                sessionId: store.currentSessionId,
                contextLines: config.crashContextLines,
                deviceInfo: deviceInfo
            ))
        }
        #if os(iOS)
        let collector = MetricKitCollector(deviceInfo: deviceInfo)
        collector.onCrash = { [weak self] record in self?.storeCrash(record, notify: true) }
        collector.onResourceDiagnostic = { [weak self] signal, ts, detail in
            self?.signals.record(signal, timeMs: ts, detail: detail)
        }
        collector.start()
        metricKit = collector
        #endif
        // Ingest what the last run left behind, off the launch path.
        Task.detached(priority: .utility) { [weak self] in
            guard let self, let store = self.sessionStore else { return }
            let ingested = store.ingestPendingCrashes()
            let all = store.loadCrashes()
            let events = store.loadEvents()
            Task { @MainActor [state = self.state] in
                state.crashes = all
                // Live-appended events may already be on screen; keep them ahead of the reload.
                state.events = state.events + events.filter { loaded in
                    !state.events.contains(where: { $0.id == loaded.id })
                }
            }
            if let newest = ingested.first ?? all.first, !ingested.isEmpty {
                self.postCrashNotification(newest.record)
                #if os(iOS)
                self.noteCrashCaptured(newest.record)
                #endif
            }
        }
    }

    func deleteEvent(_ event: StoredEvent) {
        sessionStore?.deleteEvent(event)
        refreshEvents()
    }

    func deleteAllEvents() {
        sessionStore?.deleteAllEvents()
        refreshEvents()
    }

    private func refreshEvents() {
        guard let store = sessionStore else { return }
        let events = store.loadEvents()
        Task { @MainActor [state] in state.events = events }
    }

    private func storeCrash(_ record: CrashRecord, notify: Bool) {
        guard let store = sessionStore else { return }
        store.add(record)
        let all = store.loadCrashes()
        Task { @MainActor [state] in state.crashes = all }
        if notify { postCrashNotification(record) }
        #if os(iOS)
        noteCrashCaptured(record)
        #endif
    }

    /// Best-effort local alert, one stable identifier so alerts replace rather than stack. Only
    /// fires when the host already holds notification permission — a debug tool never prompts.
    /// Tapping opens the app normally; LogSense deliberately does not touch the host's
    /// UNUserNotificationCenter delegate, so there is no deep link — hijacking the delegate would
    /// break the host's own push handling, which is a far worse trade.
    private func postCrashNotification(_ record: CrashRecord) {
        #if os(iOS)
        guard config.showCrashNotification else { return }
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized else { return }
            let content = UNMutableNotificationContent()
            content.title = record.exceptionClass ?? (record.type == "HANG" ? "Hang" : "Crash")
            content.body = record.message ?? "Open LogSense for the report."
            content.subtitle = "LogSense"
            center.add(UNNotificationRequest(
                identifier: Self.crashNotificationId, content: content, trigger: nil
            ))
        }
        #endif
    }

    func deleteCrash(id: String) {
        sessionStore?.delete(id: id)
        refreshCrashes()
    }

    func deleteAllCrashes() {
        sessionStore?.deleteAll()
        refreshCrashes()
    }

    @MainActor
    func setCrashRead(_ crash: StoredCrash, _ read: Bool) {
        var keys = state.readCrashKeys
        if read { keys.insert(crash.readKey) } else { keys.remove(crash.readKey) }
        state.readCrashKeys = keys
        Prefs.setReadCrashKeys(keys)
    }

    private func refreshCrashes() {
        guard let store = sessionStore else { return }
        let all = store.loadCrashes()
        Task { @MainActor [state] in
            state.crashes = all
            // Read-keys hygiene: deleted or pruned reports take their keys with them.
            let live = state.readCrashKeys.intersection(all.map(\.readKey))
            if live != state.readCrashKeys {
                state.readCrashKeys = live
                Prefs.setReadCrashKeys(live)
            }
        }
    }

    @discardableResult
    private func pollOnce() -> Bool {
        // Publishing per chunk lets a long backlog walk fill the screen while it runs
        // instead of after — with the UI hidden publishIfNeeded skips, so no idle cost.
        let delivered = logReader.poll { [weak self] chunk in
            guard let self else { return }
            self.onBatch(self.buffer.append(chunk))
            self.publishIfNeeded()
        }
        publishIfNeeded()
        #if os(iOS)
        syncLiveActivity()
        #endif
        // Capture health beside the sessions: when the store yields nothing, this says why —
        // a capture that is silently blind can't be debugged from the outside.
        if let store = sessionStore {
            try? logReader.health.write(
                to: store.root.appendingPathComponent("capture-health.txt"),
                atomically: true, encoding: .utf8
            )
        }
        // The probe line above guarantees a healthy store is never this empty for this long.
        let silent = logReader.polls >= 5 && logReader.entriesDelivered == 0
        if silent != storeSilentSeen {
            storeSilentSeen = silent
            Task { @MainActor [weak self] in self?.state.storeSilent = silent }
        }
        return delivered > 0
    }

    /// Poll-thread mirror of `state.storeSilent`, so the main actor is only touched on change.
    private var storeSilentSeen = false

    private func ingestStdout(_ lines: [String]) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let entries = lines.map { line in
            // Unified-log entries are ~1KB-capped by the platform; stdout has no ceiling, and the
            // buffer counts lines, not bytes — one colossal print() must not own the heap.
            LogEntry(id: 0, timeMs: now, pid: getpid(), tid: 0,
                     level: .debug, subsystem: "", tag: "print",
                     message: Format.capped(line, at: 16_384))
        }
        onBatch(buffer.append(entries))
        // No publish here — the poll tick publishes, keeping one cadence for everything.
    }

    /// One hook fans out each batch to the detectors on the ingesting thread, so matching happens
    /// once per line rather than per view render.
    private func onBatch(_ batch: [LogEntry]) {
        signals?.process(batch)
        if let analytics, let store = sessionStore {
            let extracted = analytics.process(batch)
            if !extracted.isEmpty {
                let stored = store.appendEvents(extracted, maxPerSession: max(0, config.maxStoredEvents))
                statsLock.lock()
                sessionEventCount += stored.count
                statsLock.unlock()
                let cap = max(1, config.maxStoredEvents)
                Task { @MainActor [state] in
                    // Batch entries arrive oldest-first; the published list stays newest-first.
                    state.events.insert(contentsOf: stored.reversed(), at: 0)
                    // Same ceiling as the store: a chatty session must not grow the published
                    // list (and every screen diffing it) without bound.
                    if state.events.count > cap {
                        state.events.removeLast(state.events.count - cap)
                    }
                }
            }
        }
    }

    // MARK: - Live Activity feed

    #if os(iOS)
    @available(iOS 16.2, *)
    private var liveActivity: LiveActivityController? {
        liveActivityBox as? LiveActivityController
    }

    @available(iOS 16.2, *)
    private func activityContent() -> LogSenseActivityAttributes.ContentState {
        statsLock.lock()
        let events = sessionEventCount
        let crashes = crashesSinceLaunch
        let unseen = newestCrashMs > Prefs.lastSeenCrashMs()
        let crash = latestCrashSummary
        statsLock.unlock()
        let paused = self.paused
        let last = buffer.currentSnapshot().last
        // The crash takeover outranks paused: a crash can't scroll past unseen.
        let phase: LogSenseActivityAttributes.ContentState.Phase =
            unseen ? .crashed : (paused ? .paused : .recording)
        return LogSenseActivityAttributes.ContentState(
            lines: buffer.totalReceived,
            events: events,
            crashes: crashes,
            sessionStart: sessionStartDate,
            phase: phase,
            pausedBuffered: 0,
            latestLine: last.map { "\($0.level.letter) \($0.tag) · \($0.message.prefix(120))" } ?? "",
            crash: (unseen ? crash : nil).map {
                .init(type: $0.type, title: $0.title, detail: $0.detail,
                      topFrame: $0.topFrame, thread: $0.thread, timeMs: $0.timeMs)
            }
        )
    }

    private func syncLiveActivity(immediate: Bool = false, alert: (title: String, body: String)? = nil) {
        if #available(iOS 16.2, *), let liveActivity {
            var content = activityContent()
            if content.phase == .paused {
                content.pausedBuffered = max(0, buffer.totalReceived - lastPublishedTotal)
            }
            liveActivity.update(content, immediate: immediate, alert: alert)
        }
    }

    private func noteCrashCaptured(_ record: CrashRecord) {
        let frame = appFrame(stacktrace: record.stacktrace, appBinary: appBinary) ?? ""
        statsLock.lock()
        crashesSinceLaunch += 1
        newestCrashMs = max(newestCrashMs, record.timestamp)
        latestCrashSummary = (
            type: record.type,
            title: record.exceptionClass ?? (record.type == "HANG" ? "Hang" : "Crash"),
            detail: record.message ?? "",
            topFrame: frame,
            thread: record.threadName,
            timeMs: record.timestamp
        )
        statsLock.unlock()
        syncLiveActivity(
            immediate: true,
            alert: (
                title: record.exceptionClass ?? (record.type == "HANG" ? "Hang captured" : "Crash captured"),
                body: record.message ?? "Open LogSense for the report."
            )
        )
    }
    #endif

    /// The design's Clear Buffer: a real wipe. Signal hits die with the buffer they point into —
    /// keeping them would accumulate dead jump targets.
    @MainActor
    func clearBuffer() {
        buffer.clear()
        signals.clear()
        lastPublishedTotal = 0
        feed.snapshot = []
        feed.totalReceived = 0
    }

    /// Clears the crash takeover — the activity (and any future one) returns to blue.
    @MainActor
    func markCrashesSeen() {
        statsLock.lock()
        let newest = newestCrashMs
        statsLock.unlock()
        if newest > 0 { Prefs.setLastSeenCrashMs(newest) }
        #if os(iOS)
        syncLiveActivity(immediate: true)
        #endif
    }

    /// Stop from the Live Activity: capture pauses and the activity ends with final counts.
    @MainActor
    func stopCapture() {
        pause()
        #if os(iOS)
        if #available(iOS 16.2, *), let liveActivity {
            liveActivity.end(activityContent())
        }
        #endif
    }

    /// The last published total, for the "n lines buffered" paused copy.
    private var lastPublishedTotal = 0

    /// UI visibility gates the snapshot fan-out: with the window hidden nobody reads the
    /// published buffer, so shipping it to the main actor every publish is pure overhead in
    /// the host. Benign race (poll thread reads, main sets): worth at most one skipped
    /// publish, corrected on the next poll or on `setUIVisible(true)`'s catch-up.
    private var uiVisible = false
    /// Set to cut the poll loop's sleep short; same benign-race contract as `uiVisible`.
    private var pollWakeRequested = false

    @MainActor
    func setUIVisible(_ visible: Bool) {
        uiVisible = visible
        if visible {
            state.uiEpoch += 1
            pollWakeRequested = true
        }
        guard visible, !paused else { return }
        // Catch up immediately — the next poll may be seconds away on the stretched cadence.
        feed.snapshot = buffer.currentSnapshot()
        feed.totalReceived = buffer.totalReceived
        if state.status == .waiting && feed.totalReceived > 0 { state.status = .live }
    }

    private func publishIfNeeded() {
        let total = buffer.totalReceived
        if paused {
            Task { @MainActor [state] in
                feed.bufferedWhilePaused = max(0, total - feed.totalReceived)
            }
            return
        }
        // Skipping the flush too keeps the buffer's dirty state pending for the catch-up.
        guard uiVisible else { return }
        guard let snapshot = buffer.flush() else {
            // Nothing new; still promote waiting → live once anything has arrived.
            Task { @MainActor [state] in
                if state.status == .waiting && feed.totalReceived > 0 { state.status = .live }
            }
            return
        }
        lastPublishedTotal = total
        Task { @MainActor [state] in
            feed.snapshot = snapshot
            feed.totalReceived = total
            if state.status == .waiting { state.status = .live }
        }
    }

    @MainActor
    func pause() {
        paused = true
        state.status = .paused
        feed.frozenAtMs = feed.snapshot.last?.timeMs ?? Int64(Date().timeIntervalSince1970 * 1000)
        feed.bufferedWhilePaused = 0
        #if os(iOS)
        syncLiveActivity(immediate: true)
        #endif
    }

    @MainActor
    func resume() {
        paused = false
        state.status = .live
        feed.bufferedWhilePaused = 0
        if let snapshot = buffer.flush() {
            feed.snapshot = snapshot
            feed.totalReceived = buffer.totalReceived
            lastPublishedTotal = buffer.totalReceived
        }
        #if os(iOS)
        syncLiveActivity(immediate: true)
        #endif
    }


    private static let pollIntervalNs: UInt64 = 1_000_000_000 // tuning constant; measured in Phase 1

    static let shortcutType = "com.msabhi.logsense.open"
    static let crashNotificationId = "logsense.crash"

    #if os(iOS)
    /// The iOS stand-ins for Android's launcher icon and notification entry points. A second Home
    /// Screen icon is impossible on iOS, so the icon-based entry is a long-press quick action —
    /// registered here with no host code; the tap itself must be forwarded by the host's scene
    /// delegate (`LogSense.handleShortcut`), because iOS only delivers it there.
    @MainActor
    fileprivate func installEntryPoints() {
        assertShortcutPresence()
        // Hosts that manage their own quick actions overwrite the whole `shortcutItems` array,
        // taking ours with it. Shortcuts only matter once the app leaves the foreground, so
        // re-asserting at didEnterBackground makes ours the last write that wins.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.assertShortcutPresence()
        }
        // Notification taps go to the center's delegate. Most apps own that delegate — they forward
        // to LogSense.handleNotificationResponse. When nobody claims the slot by the time the app
        // is active, LogSense takes it so the crash alert deep-links with zero host code. Checked
        // at didBecomeActive, after any host setup has had its chance; the host setting a delegate
        // later still simply wins.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { _ in
            let center = UNUserNotificationCenter.current()
            if center.delegate == nil {
                center.delegate = LogSenseNotificationDelegate.shared
            }
        }
    }

    @MainActor
    private func assertShortcutPresence() {
        var items = UIApplication.shared.shortcutItems ?? []
        guard !items.contains(where: { $0.type == Self.shortcutType }) else { return }
        items.append(UIApplicationShortcutItem(
            type: Self.shortcutType,
            localizedTitle: "Open LogSense",
            localizedSubtitle: nil,
            icon: UIApplicationShortcutIcon(systemImageName: "list.bullet.rectangle"),
            userInfo: nil
        ))
        UIApplication.shared.shortcutItems = items
    }
    #endif
}

#if os(iOS)
/// Claimed only when the host never set a notification delegate. Foreign notifications keep the
/// no-delegate default behavior (silent in foreground); LogSense's crash alert banners and
/// deep-links.
internal final class LogSenseNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = LogSenseNotificationDelegate()

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let isOurs = notification.request.identifier == LogSenseCore.crashNotificationId
        completionHandler(isOurs ? [.banner, .list] : [])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        Task { @MainActor in
            LogSense.handleNotificationResponse(response)
            completionHandler()
        }
    }
}
#endif
