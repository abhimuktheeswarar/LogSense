import XCTest
@testable import LogSense

final class SessionGroupingTests: XCTestCase {

    private struct Row {
        let id: Int64
        let sid: String
        let ts: Int64
    }

    private let started: [String: Int64] = ["cur": 1_000, "prev": 500, earlierSessionId: 0]

    private func group(_ rows: [Row]) -> [(SessionMeta, [Row])] {
        groupBySession(
            rows,
            currentSessionId: "cur",
            startedAtOf: { started[$0] ?? 0 },
            sessionOf: \.sid,
            timeOf: \.ts
        )
    }

    func testOrdersCurrentFirstThenNewestRunEarlierLast() {
        let rows = [
            Row(id: 1, sid: "cur", ts: 1_100), Row(id: 2, sid: "cur", ts: 1_050),
            Row(id: 3, sid: "prev", ts: 560),
            Row(id: 4, sid: earlierSessionId, ts: 20),
        ]
        XCTAssertEqual(["cur", "prev", earlierSessionId], group(rows).map(\.0.id))
    }

    func testCurrentSessionFlaggedCountsAndRangeComputed() {
        let rows = [Row(id: 1, sid: "cur", ts: 1_100), Row(id: 2, sid: "cur", ts: 1_050), Row(id: 3, sid: "cur", ts: 1_200)]
        let groups = group(rows)
        XCTAssertEqual(1, groups.count)
        let (meta, items) = groups[0]
        XCTAssertTrue(meta.isCurrent)
        XCTAssertEqual(3, meta.count)
        XCTAssertEqual(3, items.count)
        XCTAssertEqual(1_200, meta.newestTs)
        XCTAssertEqual(1_050, meta.oldestTs)
    }

    func testPreviousSessionIsNotCurrent() {
        let groups = group([Row(id: 1, sid: "prev", ts: 560)])
        XCTAssertFalse(groups[0].0.isCurrent)
    }

    func testEmptyInputYieldsNoGroups() {
        XCTAssertTrue(group([]).isEmpty)
    }
}
