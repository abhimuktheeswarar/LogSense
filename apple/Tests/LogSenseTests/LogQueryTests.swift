import XCTest
@testable import LogSense

final class LogQueryTests: XCTestCase {

    private func entry(level: LogLevel = .debug, tag: String = "T", message: String = "m") -> LogEntry {
        LogEntry(id: 0, timeMs: 0, pid: 1, tid: 1, level: level, subsystem: "com.demo.app", tag: tag, message: message)
    }

    private func pred(_ query: String, minLevel: LogLevel = .debug) -> (LogEntry) -> Bool {
        LogQuery.compile(LogFilter(minLevel: minLevel, query: query))
    }

    func testEmptyQueryKeepsEverythingAboveMinLevel() {
        let p = pred("", minLevel: .error)
        XCTAssertFalse(p(entry(level: .info)))
        XCTAssertTrue(p(entry(level: .fault)))
    }

    func testTagTermMatchesByContains() {
        let p = pred("tag:Analytics")
        XCTAssertTrue(p(entry(tag: "ANALYTICS ... ANALYTICS")))
        XCTAssertFalse(p(entry(tag: "Metrics")))
    }

    func testNegatedTagExcludes() {
        let p = pred("-tag:worker")
        XCTAssertFalse(p(entry(tag: "worker_pool")))
        XCTAssertTrue(p(entry(tag: "network_pool")))
    }

    func testMessageTerm() {
        let p = pred("message:trackEvent")
        XCTAssertTrue(p(entry(message: "trackEvent = screen_view")))
        XCTAssertFalse(p(entry(message: "nothing here")))
    }

    func testSubsystemTerm() {
        let p = pred("sub:demo")
        XCTAssertTrue(p(entry()))
        let other = LogEntry(id: 0, timeMs: 0, pid: 1, tid: 1, level: .debug, subsystem: "CFNetwork", tag: "T", message: "m")
        XCTAssertFalse(p(other))
    }

    func testLevelTermRaisesMinimum() {
        let p = pred("level:E")
        XCTAssertFalse(p(entry(level: .notice)))
        XCTAssertTrue(p(entry(level: .error)))
    }

    func testLevelTermAcceptsFullName() {
        let p = pred("level:fault")
        XCTAssertFalse(p(entry(level: .error)))
        XCTAssertTrue(p(entry(level: .fault)))
    }

    func testBareWordMatchesTagOrMessage() {
        let p = pred("home")
        XCTAssertTrue(p(entry(tag: "HomeScreen", message: "x")))
        XCTAssertTrue(p(entry(tag: "T", message: "home loaded")))
        XCTAssertFalse(p(entry(tag: "T", message: "login")))
    }

    func testTermsAreANDed() {
        let p = pred("tag:Analytics purchase")
        XCTAssertTrue(p(entry(tag: "Analytics", message: "purchase done")))
        XCTAssertFalse(p(entry(tag: "Analytics", message: "screen_view")))
        XCTAssertFalse(p(entry(tag: "Other", message: "purchase done")))
    }

    func testQuotedValueKeepsSpaces() {
        let p = pred(#"message:"trackEvent = screen_view""#)
        XCTAssertTrue(p(entry(message: "trackEvent = screen_view -> {}")))
        XCTAssertFalse(p(entry(message: "trackEvent screen_view")))
    }
}
