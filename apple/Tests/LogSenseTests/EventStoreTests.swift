import XCTest
@testable import LogSense

final class EventStoreTests: XCTestCase {

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

    private func store(now: Date = Date()) throws -> SessionStore {
        try SessionStore(root: root, config: LogSenseConfig(), now: now)
    }

    private func event(_ name: String, ts: Int64) -> EventRecord {
        EventRecord(timestamp: ts, tag: "Analytics", name: name,
                    params: ["screen": "Home"], raw: "\(name) screen=Home")
    }

    func testEventsRoundTripThroughTheSessionFile() throws {
        let s = try store()
        let stored = s.appendEvents([event("open", ts: 1), event("tap", ts: 2)], maxPerSession: 100)
        XCTAssertEqual(2, stored.count)
        XCTAssertEqual(s.currentSessionId, stored[0].sessionId)
        let loaded = s.loadEvents()
        XCTAssertEqual(["tap", "open"], loaded.map(\.record.name)) // newest first
        XCTAssertEqual("Home", loaded[0].record.params["screen"])
    }

    func testThePerSessionCapKeepsTheNewest() throws {
        let s = try store()
        for i in 1...7 { _ = s.appendEvents([event("e\(i)", ts: Int64(i))], maxPerSession: 3) }
        XCTAssertEqual(["e7", "e6", "e5"], s.loadEvents().map(\.record.name))
    }

    func testEventsSurviveARestartAndStayInTheirSession() throws {
        let first = try store(now: Date(timeIntervalSince1970: 1_000))
        _ = first.appendEvents([event("old", ts: 1_000_500)], maxPerSession: 100)
        let second = try store(now: Date(timeIntervalSince1970: 2_000))
        _ = second.appendEvents([event("new", ts: 2_000_500)], maxPerSession: 100)
        let loaded = second.loadEvents()
        XCTAssertEqual(["new", "old"], loaded.map(\.record.name))
        XCTAssertEqual([second.currentSessionId, first.currentSessionId], loaded.map(\.sessionId))
    }

    func testDeleteRemovesExactlyThatEventAndDeleteAllEverything() throws {
        let s = try store()
        let stored = s.appendEvents([event("a", ts: 1), event("b", ts: 2)], maxPerSession: 100)
        s.deleteEvent(stored[0])
        XCTAssertEqual(["b"], s.loadEvents().map(\.record.name))
        s.deleteAllEvents()
        XCTAssertTrue(s.loadEvents().isEmpty)
    }

    func testKeepPastTogglesPruneEarlierRunsAtLaunch() throws {
        // Android's defaults: past events dropped, past crashes kept — applied by launch pruning.
        let first = try SessionStore(root: root, config: LogSenseConfig(), now: Date(timeIntervalSince1970: 1_000))
        _ = first.appendEvents([event("past", ts: 1_000_500)], maxPerSession: 100)
        first.add(CrashRecord(
            timestamp: 1_000_600, sessionId: first.currentSessionId, type: "EXCEPTION",
            threadName: nil, exceptionClass: "NSRangeException", message: nil,
            stacktrace: "", deviceInfo: "", logContext: ""
        ))

        let second = try SessionStore(
            root: root, config: LogSenseConfig(),
            keepPastEvents: false, keepPastCrashes: true,
            now: Date(timeIntervalSince1970: 2_000)
        )
        XCTAssertTrue(second.loadEvents().isEmpty, "past events dropped by default")
        XCTAssertEqual(1, second.loadCrashes().count, "past crashes kept by default")

        let third = try SessionStore(
            root: root, config: LogSenseConfig(),
            keepPastEvents: false, keepPastCrashes: false,
            now: Date(timeIntervalSince1970: 3_000)
        )
        XCTAssertTrue(third.loadCrashes().isEmpty, "switching crashes off prunes them too")
    }

    func testACorruptLineIsSkippedNotFatal() throws {
        let s = try store()
        _ = s.appendEvents([event("good", ts: 1)], maxPerSession: 100)
        let file = root.appendingPathComponent("sessions/\(s.currentSessionId)/events.jsonl")
        var data = try Data(contentsOf: file)
        data.append(Data("not json\n".utf8))
        try data.write(to: file)
        XCTAssertEqual(["good"], s.loadEvents().map(\.record.name))
    }
}
