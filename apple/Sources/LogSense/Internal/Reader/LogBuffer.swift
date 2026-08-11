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
    /// Byte ceiling for the buffered messages — the line cap alone lets a print-heavy host
    /// (16KB stdout lines) hold hundreds of MB. Floored at 1MB for the same defensive reason.
    private let maxBytes: Int

    private let lock = NSLock()
    private var storage: [LogEntry] = []
    private var dirty = false
    private var snapshotStorage: [LogEntry] = []
    private var totalReceivedStorage = 0
    private var bytesStored = 0

    init(maxLines: Int, maxBytes: Int = 50_000_000) {
        self.maxLines = Swift.max(1, maxLines)
        self.maxBytes = Swift.max(1_000_000, maxBytes)
    }

    /// Message bytes plus a fixed allowance for the entry's scalar fields.
    private func cost(of message: String) -> Int { message.utf8.count + 64 }

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

    /// Entry ids are (re)stamped here, under the lock — the one place both capture sources
    /// serialize through — so ids are monotonic in storage order by construction, which is what
    /// `since()`'s binary search and jump-to-line depend on. Returns the stamped entries so
    /// detectors downstream see the same ids the buffer holds.
    private var nextId: Int64 = 1

    @discardableResult
    func append(_ entries: [LogEntry]) -> [LogEntry] {
        guard !entries.isEmpty else { return [] }
        lock.lock(); defer { lock.unlock() }
        let stamped = entries.map { entry in
            let id = nextId
            nextId += 1
            return LogEntry(id: id, timeMs: entry.timeMs, pid: entry.pid, tid: entry.tid,
                            level: entry.level, subsystem: entry.subsystem, tag: entry.tag,
                            message: entry.message)
        }
        storage.append(contentsOf: stamped)
        for entry in stamped { bytesStored += cost(of: entry.message) }
        trimLocked()
        totalReceivedStorage += stamped.count
        dirty = true
        return stamped
    }

    /// Evicts oldest entries while either cap is exceeded. The newest line always survives,
    /// same as the line-cap floor — a single over-budget entry is kept, not vanished.
    /// Caller holds the lock.
    private func trimLocked() {
        var dropped = 0
        var freed = 0
        while (storage.count - dropped > maxLines) || (bytesStored - freed > maxBytes) {
            guard dropped < storage.count - 1 else { break }
            freed += cost(of: storage[dropped].message)
            dropped += 1
        }
        if dropped > 0 {
            storage.removeFirst(dropped)
            bytesStored -= freed
        }
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
        bytesStored += raw.utf8.count + 1
        trimLocked()
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
        bytesStored = 0
        dirty = false
    }
}
