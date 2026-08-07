import Foundation

/// Capped in-memory buffer of log entries. Lost on process death by design.
///
/// Ingest (`append`/`appendContinuation`) only mutates storage and marks it dirty — the snapshot
/// copy happens in `flush()`, which the reader ticks once per poll. A lock, not an actor: the crash
/// handler must take a synchronous snapshot from a dying thread, where `await` is impossible.
internal final class LogBuffer {

    /// Floored at one line. A zero or negative cap would make the trim in `append` misbehave, so
    /// the buffer defends itself rather than trusting every caller to clamp.
    private let maxLines: Int

    private let lock = NSLock()
    private var storage: [LogEntry] = []
    private var dirty = false
    private var snapshotStorage: [LogEntry] = []
    private var totalReceivedStorage = 0

    init(maxLines: Int) {
        self.maxLines = Swift.max(1, maxLines)
    }

    /// Cumulative entries seen this run — keeps climbing past the buffer cap; reset by `clear()`.
    var totalReceived: Int {
        lock.lock(); defer { lock.unlock() }
        return totalReceivedStorage
    }

    /// Safe to call from any thread, including a crashing one (used for crash log-context).
    func currentSnapshot() -> [LogEntry] {
        lock.lock(); defer { lock.unlock() }
        return snapshotStorage
    }

    func append(_ entries: [LogEntry]) {
        guard !entries.isEmpty else { return }
        lock.lock(); defer { lock.unlock() }
        storage.append(contentsOf: entries)
        if storage.count > maxLines { storage.removeFirst(storage.count - maxLines) }
        totalReceivedStorage += entries.count
        dirty = true
    }

    /// Attaches a continuation line to the newest entry (multi-line log output).
    func appendContinuation(_ raw: String) {
        lock.lock(); defer { lock.unlock() }
        guard let last = storage.last else { return }
        storage[storage.count - 1] = LogEntry(
            id: last.id, timeMs: last.timeMs, pid: last.pid, tid: last.tid,
            level: last.level, subsystem: last.subsystem, tag: last.tag,
            message: last.message + "\n" + raw
        )
        dirty = true
    }

    /// Publishes accumulated changes into the snapshot. Cheap no-op when nothing changed.
    /// Returns the new snapshot when it changed, nil otherwise (the caller publishes to the UI).
    @discardableResult
    func flush() -> [LogEntry]? {
        lock.lock(); defer { lock.unlock() }
        guard dirty else { return nil }
        snapshotStorage = storage
        dirty = false
        return snapshotStorage
    }

    func clear() {
        lock.lock(); defer { lock.unlock() }
        storage.removeAll()
        snapshotStorage.removeAll()
        totalReceivedStorage = 0
        dirty = false
    }
}
