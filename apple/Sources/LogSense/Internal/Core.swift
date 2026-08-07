import Foundation
import Combine
#if os(iOS)
import UserNotifications
#endif

internal enum CaptureStatus {
    case waiting, live, paused
}

/// The UI-facing state, published on the main actor at the poll cadence (~1 Hz) — the poll cadence
/// *is* the publish cadence, so there is no separate flush loop to tune.
@MainActor
internal final class LogSenseState: ObservableObject {
    @Published var snapshot: [LogEntry] = []
    @Published var totalReceived = 0
    @Published var status: CaptureStatus = .waiting
    @Published var bufferedWhilePaused = 0
    /// Newest entry id at the moment the stream was cleared; the UI shows only lines after this.
    @Published var clearedAtId: Int64 = 0
    /// When paused, the wall-clock time of the newest published line ("Stream frozen at …").
    @Published var frozenAtMs: Int64 = 0
    /// Stored crash reports, newest first, across sessions.
    @Published var crashes: [StoredCrash] = []
    /// Signal hits, oldest first, in-memory — they die with the buffer they point into.
    @Published var signalHits: [SignalHit] = []
    /// Stored analytics events, newest first, across sessions.
    @Published var events: [StoredEvent] = []
}

/// The process singleton every part of LogSense hangs off. No DI framework — this is passed down
/// explicitly, mirroring the Android `LogSenseCore`.
internal final class LogSenseCore {

    private(set) static var shared: LogSenseCore?

    let config: LogSenseConfig
    let bufferLimit: Int
    let buffer: LogBuffer
    let state: LogSenseState
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
    #endif
    private var pollTask: Task<Void, Never>?
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
    }

    @MainActor
    private init(config: LogSenseConfig) {
        self.config = config
        self.bufferLimit = Self.ramAwareBufferLimit(config.maxBufferedLines)
        self.buffer = LogBuffer(maxLines: bufferLimit)
        self.state = LogSenseState()
        self.hostName = Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String
            ?? Bundle.main.object(forInfoDictionaryKey: kCFBundleNameKey as String) as? String
            ?? ProcessInfo.processInfo.processName
        self.appBinary = Bundle.main.object(forInfoDictionaryKey: "CFBundleExecutable") as? String
            ?? ProcessInfo.processInfo.processName
        // Storage failing must not take capture down with it — crashes degrade, logs keep working.
        self.sessionStore = try? SessionStore(
            root: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("LogSense"),
            config: config
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
        if config.captureStandardOutput {
            stdoutReader.onLines = { [weak self] lines in self?.ingestStdout(lines) }
            stdoutReader.start()
        }
        startCrashPipeline()
        pollTask = Task.detached(priority: .utility) { [weak self] in
            while !Task.isCancelled {
                self?.pollOnce()
                try? await Task.sleep(nanoseconds: LogSenseCore.pollIntervalNs)
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
                identifier: "logsense.crash", content: content, trigger: nil
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

    private func refreshCrashes() {
        guard let store = sessionStore else { return }
        let all = store.loadCrashes()
        Task { @MainActor [state] in state.crashes = all }
    }

    private func pollOnce() {
        let batch = logReader.poll()
        if !batch.isEmpty {
            onBatch(buffer.append(batch))
        }
        publishIfNeeded()
    }

    private func ingestStdout(_ lines: [String]) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let entries = lines.map { line in
            LogEntry(id: 0, timeMs: now, pid: getpid(), tid: 0,
                     level: .debug, subsystem: "", tag: "print", message: line)
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
                Task { @MainActor [state] in
                    // Batch entries arrive oldest-first; the published list stays newest-first.
                    state.events.insert(contentsOf: stored.reversed(), at: 0)
                }
            }
        }
    }

    private func publishIfNeeded() {
        let total = buffer.totalReceived
        if paused {
            Task { @MainActor [state] in
                state.bufferedWhilePaused = max(0, total - state.totalReceived)
            }
            return
        }
        guard let snapshot = buffer.flush() else {
            // Nothing new; still promote waiting → live once anything has arrived.
            Task { @MainActor [state] in
                if state.status == .waiting && state.totalReceived > 0 { state.status = .live }
            }
            return
        }
        Task { @MainActor [state] in
            state.snapshot = snapshot
            state.totalReceived = total
            if state.status == .waiting { state.status = .live }
        }
    }

    @MainActor
    func pause() {
        paused = true
        state.status = .paused
        state.frozenAtMs = state.snapshot.last?.timeMs ?? Int64(Date().timeIntervalSince1970 * 1000)
        state.bufferedWhilePaused = 0
    }

    @MainActor
    func resume() {
        paused = false
        state.status = .live
        state.bufferedWhilePaused = 0
        if let snapshot = buffer.flush() {
            state.snapshot = snapshot
            state.totalReceived = buffer.totalReceived
        }
    }

    @MainActor
    func clear() {
        state.clearedAtId = state.snapshot.last?.id ?? 0
    }

    private static let pollIntervalNs: UInt64 = 1_000_000_000 // tuning constant; measured in Phase 1
}
