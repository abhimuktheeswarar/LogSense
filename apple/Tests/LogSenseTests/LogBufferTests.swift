import XCTest
@testable import LogSense

final class LogBufferTests: XCTestCase {

    private func lines(_ count: Int) -> [LogEntry] {
        (1...count).map {
            LogEntry(id: Int64($0), timeMs: Int64($0), pid: 1, tid: 1,
                     level: .info, subsystem: "", tag: "t", message: "line \($0)")
        }
    }

    func testAHostileCapCannotMakeTheBufferTrimPastEmpty() {
        // maxBufferedLines = 0 or a negative typo must keep the newest line, not corrupt storage.
        for cap in [0, -1, Int.min] {
            let buffer = LogBuffer(maxLines: cap)
            buffer.append(lines(5))
            buffer.flush()
            XCTAssertEqual(1, buffer.currentSnapshot().count, "cap \(cap) should keep the newest line")
        }
    }

    func testTheCapKeepsTheNewestLinesAndDropsTheOldest() {
        let buffer = LogBuffer(maxLines: 3)
        buffer.append(lines(10))
        buffer.flush()
        XCTAssertEqual([8, 9, 10], buffer.currentSnapshot().map(\.id))
    }

    func testTotalReceivedKeepsCountingPastTheCap() {
        let buffer = LogBuffer(maxLines: 2)
        buffer.append(lines(10))
        XCTAssertEqual(10, buffer.totalReceived)
        buffer.flush()
        XCTAssertEqual(2, buffer.currentSnapshot().count)
    }

    func testAContinuationWithNothingToAttachToIsIgnored() {
        let buffer = LogBuffer(maxLines: 10)
        buffer.appendContinuation("orphan line")
        buffer.flush()
        XCTAssertTrue(buffer.currentSnapshot().isEmpty)
    }

    func testAContinuationJoinsTheNewestEntry() {
        let buffer = LogBuffer(maxLines: 10)
        buffer.append(lines(1))
        buffer.appendContinuation("second line")
        buffer.flush()
        XCTAssertEqual("line 1\nsecond line", buffer.currentSnapshot().last?.message)
    }

    func testFlushIsANoOpWhenNothingChanged() {
        let buffer = LogBuffer(maxLines: 10)
        buffer.append(lines(2))
        XCTAssertNotNil(buffer.flush())
        XCTAssertNil(buffer.flush())
    }

    func testClearEmptiesTheBufferAndResetsTheCount() {
        let buffer = LogBuffer(maxLines: 10)
        buffer.append(lines(4))
        buffer.clear()
        XCTAssertTrue(buffer.currentSnapshot().isEmpty)
        XCTAssertEqual(0, buffer.totalReceived)
    }
}
