import XCTest
@testable import LogSense

final class SessionStoreTests: XCTestCase {

    private var root: URL!

    override func setUp() {
        super.setUp()
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("logsense-tests-\(UUID().uuidString)")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
        super.tearDown()
    }

    private func store(
        maxSessions: Int = 10, retentionDays: Int = 7, maxCrashes: Int = 50, now: Date = Date()
    ) throws -> SessionStore {
        try SessionStore(
            root: root,
            config: LogSenseConfig(
                retentionDays: retentionDays, maxStoredCrashes: maxCrashes, maxSessions: maxSessions
            ),
            now: now
        )
    }

    private func crash(ts: Int64, sessionId: String = "", type: String = "EXCEPTION") -> CrashRecord {
        CrashRecord(
            timestamp: ts, sessionId: sessionId, type: type, threadName: "main",
            exceptionClass: "NSRangeException", message: "boom",
            stacktrace: "", deviceInfo: "", logContext: ""
        )
    }

    func testEachLaunchCreatesASessionWhoseNameCarriesItsStart() throws {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let s = try store(now: now)
        XCTAssertTrue(s.currentSessionId.hasPrefix("1700000000000-"))
        XCTAssertEqual(1_700_000_000_000, s.sessionStartedAt(s.currentSessionId))
        XCTAssertEqual([s.currentSessionId], s.sessionIds())
    }

    func testAPendingCrashLandsInItsOwnSessionDir() throws {
        let first = try store()
        let dying = crash(ts: Int64(Date().timeIntervalSince1970 * 1000), sessionId: first.currentSessionId)
        CrashFileStore.write(dying, into: first.pendingDir)

        let second = try store() // "next launch"
        let ingested = second.ingestPendingCrashes()
        XCTAssertEqual(1, ingested.count)
        XCTAssertEqual(first.currentSessionId, ingested[0].record.sessionId)
        XCTAssertEqual(1, second.loadCrashes().count)
        // pending dir drained
        XCTAssertTrue(second.ingestPendingCrashes().isEmpty)
    }

    func testATimestampedRecordWithNoSessionIsAttributedToTheSessionRunningThen() throws {
        let first = try store(now: Date(timeIntervalSince1970: 1_000))
        _ = first
        let second = try store(now: Date(timeIntervalSince1970: 2_000))
        // A MetricKit diagnostic from the first run: timestamp inside [first, second).
        let stored = second.add(crash(ts: 1_500_000, sessionId: "", type: "CRASH"))
        XCTAssertEqual(first.currentSessionId, stored.record.sessionId)
        // One from before any known session lands in the earlier bucket.
        let ancient = second.add(crash(ts: 5, sessionId: "", type: "CRASH"))
        XCTAssertEqual(earlierSessionId, ancient.record.sessionId)
    }

    func testAKillMidWriteLeavesOnlyATmpFileAndIngestionCleansIt() throws {
        let s = try store()
        let tmp = s.pendingDir.appendingPathComponent("crash_123_ab.tmp")
        try Data("half-written".utf8).write(to: tmp)
        let garbage = s.pendingDir.appendingPathComponent("crash_456_cd.json")
        try Data("not json".utf8).write(to: garbage)

        XCTAssertTrue(s.ingestPendingCrashes().isEmpty)
        let left = try FileManager.default.contentsOfDirectory(atPath: s.pendingDir.path)
        XCTAssertTrue(left.isEmpty, "leftovers: \(left)")
    }

    func testSessionsBeyondTheCapArePrunedOldestFirst() throws {
        for second in 1...4 {
            _ = try store(maxSessions: 3, now: Date(timeIntervalSince1970: Double(second)))
        }
        let latest = try store(maxSessions: 3, now: Date(timeIntervalSince1970: 5))
        let ids = latest.sessionIds()
        XCTAssertEqual(3, ids.count)
        XCTAssertEqual(latest.currentSessionId, ids.last)
        // The survivors are the newest previous runs, not the oldest.
        XCTAssertEqual([3_000, 4_000], ids.dropLast().map(SessionStore.startedAt(of:)))
    }

    func testSessionsOlderThanRetentionArePrunedEvenUnderTheCap() throws {
        let old = Date(timeIntervalSince1970: 1_000_000)
        _ = try store(retentionDays: 7, now: old)
        let fresh = try store(retentionDays: 7, now: old.addingTimeInterval(8 * 86_400))
        XCTAssertEqual([fresh.currentSessionId], fresh.sessionIds())
    }

    func testTheCrashCapKeepsTheNewestReports() throws {
        let s = try store(maxCrashes: 3)
        for i in 1...5 { s.add(crash(ts: Int64(i) * 1_000_000_000_000, sessionId: s.currentSessionId)) }
        XCTAssertEqual([5, 4, 3].map { $0 * 1_000_000_000_000 }, s.loadCrashes().map(\.record.timestamp))
    }

    func testDeleteRemovesExactlyThatReport() throws {
        let s = try store()
        let a = s.add(crash(ts: 1_000_000_000_000, sessionId: s.currentSessionId))
        let b = s.add(crash(ts: 2_000_000_000_000, sessionId: s.currentSessionId))
        s.delete(id: a.id)
        XCTAssertEqual([b.id], s.loadCrashes().map(\.id))
        s.deleteAll()
        XCTAssertTrue(s.loadCrashes().isEmpty)
    }

    func testACrashForAPrunedSessionFallsBackToTimestampAttribution() throws {
        let s = try store()
        let stored = s.add(crash(ts: Int64(Date().timeIntervalSince1970 * 1000), sessionId: "9999-gone"))
        XCTAssertEqual(s.currentSessionId, stored.record.sessionId)
    }
}
