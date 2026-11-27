import Foundation

/// Which captured crashes deserve to interrupt the developer — kept pure so it is testable.
///
/// MetricKit hands over everything since its last delivery in one go; on a device that has run the
/// host for a while (or the first time LogSense starts there) that is dozens of reports, many of
/// them the same hang delivered again. Alerting per report buzzed the phone for minutes. So: a
/// whole delivery raises at most one alert, duplicates are dropped, hangs are recorded quietly,
/// reports older than LogSense's first start on the device are history (stored, never announced),
/// and alerts are spaced at least a minute apart.
internal enum CrashAlertPolicy {

    struct Alert: Equatable {
        let title: String
        let body: String
    }

    static let minAlertIntervalMs: Int64 = 60_000

    /// Identity of a report for de-duplication: the same kind, message and stack is the same report.
    static func key(_ record: CrashRecord) -> String {
        "\(record.type)\u{1F}\(record.message ?? "")\u{1F}\(record.stacktrace)"
    }

    /// The reports in `records` not already stored and not repeated within the batch, in order.
    static func fresh(_ records: [CrashRecord], existingKeys: Set<String>) -> [CrashRecord] {
        var seen = existingKeys
        return records.filter { seen.insert(key($0)).inserted }
    }

    /// Reports from LogSense's own time on this device — the ones counted and shown as new.
    static func current(_ records: [CrashRecord], firstStartMs: Int64) -> [CrashRecord] {
        records.filter { $0.timestamp >= firstStartMs }
    }

    /// At most one alert for a batch: only real crashes (not hangs) from LogSense's own time, and
    /// never sooner than `minAlertIntervalMs` after the previous alert.
    static func alert(
        for records: [CrashRecord],
        firstStartMs: Int64,
        nowMs: Int64,
        lastAlertMs: Int64?
    ) -> Alert? {
        let crashes = current(records, firstStartMs: firstStartMs).filter { $0.type != "HANG" }
        guard let newest = crashes.max(by: { $0.timestamp < $1.timestamp }) else { return nil }
        if let lastAlertMs, nowMs - lastAlertMs < minAlertIntervalMs { return nil }
        let title = newest.exceptionClass ?? "Crash"
        if crashes.count == 1 {
            return Alert(title: title, body: newest.message ?? "Open LogSense for the report.")
        }
        return Alert(title: "\(crashes.count) crashes captured", body: "Latest: \(title). Open LogSense for the reports.")
    }
}
