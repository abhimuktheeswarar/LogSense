import XCTest
@testable import LogSense

final class LeakWatchTests: XCTestCase {

    private final class Victim {}

    override func tearDown() {
        LeakWatch.onLeak = nil
        super.tearDown()
    }

    func testAnObjectThatDeallocatesOnTimeReportsNothing() {
        var reported: [String] = []
        LeakWatch.onLeak = { reported.append($0) }

        var victim: Victim? = Victim()
        LeakWatch.expectDealloc(of: victim!, name: "Victim", timeout: 0.1)
        victim = nil // dies before the deadline — the healthy outcome

        let done = expectation(description: "deadline passed")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { done.fulfill() }
        wait(for: [done], timeout: 2)
        XCTAssertTrue(reported.isEmpty)
    }

    func testAnObjectThatSurvivesItsDeadlineIsReportedOnce() {
        var reported: [String] = []
        LeakWatch.onLeak = { reported.append($0) }

        let victim = Victim() // kept alive by this strong reference — the leak
        LeakWatch.expectDealloc(of: victim, name: "Victim", timeout: 0.1)
        // Watching the same instance again must not double-report.
        LeakWatch.expectDealloc(of: victim, name: "Victim", timeout: 0.1)

        let done = expectation(description: "deadline passed")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { done.fulfill() }
        wait(for: [done], timeout: 2)
        XCTAssertEqual(1, reported.count)
        XCTAssertTrue(reported[0].contains("Victim"), "names the leaked object: \(reported)")
        withExtendedLifetime(victim) {}
    }
}
