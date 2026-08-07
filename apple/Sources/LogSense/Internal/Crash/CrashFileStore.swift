import Foundation

/// Writes a crash record from a **dying process**: fsync'd temp file, then rename. The process is
/// about to abort, so the write must be durable *now* — and a kill mid-write must leave only a
/// `.tmp` file the next launch's ingestion silently discards, never a half-written `.json`.
internal enum CrashFileStore {

    static func write(_ record: CrashRecord, into dir: URL) {
        guard let data = try? JSONEncoder().encode(record) else { return }
        let name = "crash_\(record.timestamp)_\(UUID().uuidString.prefix(4))"
        let tmp = dir.appendingPathComponent(name + ".tmp")
        let final = dir.appendingPathComponent(name + ".json")

        let fd = open(tmp.path, O_WRONLY | O_CREAT | O_TRUNC, 0o600)
        guard fd >= 0 else { return }
        let written = data.withUnsafeBytes { bytes in
            Foundation.write(fd, bytes.baseAddress, bytes.count)
        }
        fsync(fd)
        close(fd)
        guard written == data.count else {
            unlink(tmp.path)
            return
        }
        rename(tmp.path, final.path)
    }
}
