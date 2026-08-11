import Foundation

/// One reported signal. `entryId` is the `LogEntry` it was matched on, so the UI can jump straight
/// to that line — nil for signals reported by the platform rather than matched in the log, which
/// have no line to jump to.
internal struct SignalHit: Equatable {
    let signal: Signal
    let entryId: Int64?
    let timeMs: Int64
    let tag: String
    /// First line of the message, truncated — the full text lives in the buffer entry.
    let preview: String
}

/// Matches incoming log batches against the `BuiltInSignals` catalog. Fed from the same batch hook
/// as `AnalyticsDetector`, so matching happens once per line on the reader's thread rather than
/// per view on every render.
///
/// Hits are in-memory and die with the process, exactly like the log buffer they point into — that's
/// what keeps jump-to-line honest.
internal final class SignalDetector {

    private let config: LogSenseConfig
    private let muted: () -> Set<String>

    // Predicates are rebuilt only when the muted set changes (the catalog and config are constant),
    // mirroring AnalyticsDetector's extractor cache.
    private var cachedMuted: Set<String>?
    private var compiled: [(Signal, (LogEntry) -> Bool)] = []

    /// Hits arrive from the reader thread, the main thread and platform callbacks — guard the append.
    private let lock = NSLock()
    private var hitsStorage: [SignalHit] = []

    /// Called (on the appending thread) whenever the hit list changes; the owner publishes to UI.
    var onChange: (([SignalHit]) -> Void)?

    init(config: LogSenseConfig, muted: @escaping () -> Set<String> = { [] }) {
        self.config = config
        self.muted = muted
    }

    var hits: [SignalHit] {
        lock.lock(); defer { lock.unlock() }
        return hitsStorage
    }

    private func rules() -> [(Signal, (LogEntry) -> Bool)] {
        let mutedNow = muted()
        if mutedNow != cachedMuted {
            cachedMuted = mutedNow
            compiled = BuiltInSignals.catalog(custom: config.customSignals)
                // A blank query compiles to "no terms", which matches every line — never a real rule.
                .filter { !$0.query.trimmingCharacters(in: .whitespaces).isEmpty && !mutedNow.contains($0.id) }
                .map { ($0, LogQuery.compile(LogFilter(minLevel: .debug, query: $0.query))) }
        }
        return compiled
    }

    /// Signals scan only the head of each message: every catalog pattern's evidence leads the
    /// line, and ~15 rules × full scans over 16KB print() lines is real CPU for zero extra
    /// matches. A pattern whose marker starts past this many characters is deliberately missed.
    static let scanChars = 1_024

    /// ponytail: ~15 predicates × 1–2 substring scans per line, first match wins. Fine for a debug
    /// tool at real log rates; if it ever shows in a profile, bucket the rules by subsystem.
    func process(_ batch: [LogEntry]) {
        let rules = rules()
        if rules.isEmpty { return }
        let found = batch.compactMap { entry -> SignalHit? in
            let scanned = entry.message.count <= Self.scanChars
                ? entry
                : LogEntry(id: entry.id, timeMs: entry.timeMs, pid: entry.pid, tid: entry.tid,
                           level: entry.level, subsystem: entry.subsystem, tag: entry.tag,
                           message: String(entry.message.prefix(Self.scanChars)))
            guard let (signal, _) = rules.first(where: { $0.1(scanned) }) else { return nil }
            return SignalHit(
                signal: signal,
                entryId: entry.id,
                timeMs: entry.timeMs,
                tag: entry.tag,
                preview: String(entry.message.split(separator: "\n", omittingEmptySubsequences: false)
                    .first.map(String.init)?.prefix(Self.previewChars) ?? "")
            )
        }
        add(found)
    }

    /// Records a signal the log stream can't show us — a memory warning, a thermal spike.
    /// Safe to call from any thread; `process` runs on the reader's.
    func record(_ signal: Signal, timeMs: Int64, detail: String) {
        if muted().contains(signal.id) { return }
        add([SignalHit(signal: signal, entryId: nil, timeMs: timeMs, tag: signal.category.label,
                       preview: String(detail.prefix(Self.previewChars)))])
    }

    func clear() {
        lock.lock()
        hitsStorage = []
        let snapshot = hitsStorage
        lock.unlock()
        onChange?(snapshot)
    }

    private func add(_ found: [SignalHit]) {
        if found.isEmpty { return }
        lock.lock()
        hitsStorage.append(contentsOf: found)
        if hitsStorage.count > Self.maxHits {
            // The buffer evicts too, so keeping more hits than this only accumulates dead pointers.
            hitsStorage.removeFirst(hitsStorage.count - Self.maxHits)
        }
        let snapshot = hitsStorage
        lock.unlock()
        onChange?(snapshot)
    }

    private static let maxHits = 500
    private static let previewChars = 200
}
