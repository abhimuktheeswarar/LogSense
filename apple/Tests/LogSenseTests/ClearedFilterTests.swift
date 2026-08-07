import XCTest
@testable import LogSense

final class ClearedFilterTests: XCTestCase {

    private func entries(_ ids: ClosedRange<Int64>) -> [LogEntry] {
        ids.map {
            LogEntry(id: $0, timeMs: $0, pid: 1, tid: 1,
                     level: .info, subsystem: "", tag: "t", message: "line \($0)")
        }
    }

    func testAnUnclearedViewSeesTheWholeBuffer() {
        let all = entries(0...9)
        XCTAssertEqual(10, all.since(0).count)
    }

    func testClearingHidesEverythingUpToAndIncludingTheWatermark() {
        let all = entries(0...9)
        XCTAssertEqual([5, 6, 7, 8, 9], all.since(4).map(\.id))
    }

    func testClearingAtTheNewestLineLeavesTheViewEmpty() {
        let all = entries(0...9)
        XCTAssertTrue(all.since(9).isEmpty)
    }

    func testAWatermarkBeforeTheOldestBufferedLineHidesNothing() {
        // The buffer evicts as it fills, so the watermark can fall off the front.
        let all = entries(100...109)
        XCTAssertEqual(10, all.since(50).count)
    }

    func testAStaleWatermarkFromAPreviousReaderIsIgnored() {
        // The reader restarts its id counter, so a watermark can end up ahead of every line.
        // Without the guard the view would show nothing until ids climbed past it again.
        let afterRestart = entries(0...4)
        XCTAssertEqual(5, afterRestart.since(9_999).count)
    }

    func testAnEmptyBufferStaysEmpty() {
        XCTAssertTrue([LogEntry]().since(7).isEmpty)
    }

    func testASingleRemainingLineIsKeptOrDroppedOnTheRightSideOfTheBoundary() {
        let one = entries(7...7)
        XCTAssertEqual(1, one.since(6).count)
        XCTAssertTrue(one.since(7).isEmpty)
    }

    func testEveryBoundaryInABufferWithGapsLandsCorrectly() {
        // Ids skip: continuation lines fold into the previous entry, so the buffer isn't contiguous.
        let sparse = [Int64(2), 5, 9, 14].flatMap { entries($0...$0) }
        XCTAssertEqual([5, 9, 14], sparse.since(2).map(\.id))
        XCTAssertEqual([5, 9, 14], sparse.since(4).map(\.id))
        XCTAssertEqual([9, 14], sparse.since(5).map(\.id))
        XCTAssertEqual([14], sparse.since(13).map(\.id))
    }
}
