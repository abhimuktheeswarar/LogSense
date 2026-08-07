import XCTest
@testable import LogSense

final class TextMatcherTests: XCTestCase {

    private func matcher(
        _ text: String, matchCase: Bool = false, wholeWord: Bool = false, regex: Bool = false
    ) -> TextMatcher {
        TextMatcher.from(SearchQuery(text: text, matchCase: matchCase, wholeWord: wholeWord, regex: regex))
    }

    func testEmptyQueryMatchesEverything() {
        let m = matcher("")
        XCTAssertTrue(m.matches("anything"))
        XCTAssertTrue(m.ranges("anything").isEmpty)
    }

    func testLiteralIsCaseInsensitiveByDefault() {
        XCTAssertTrue(matcher("home").matches("APP_HOME"))
        XCTAssertTrue(matcher("HOME").matches("app_home"))
    }

    func testMatchCaseIsRespected() {
        let m = matcher("Home", matchCase: true)
        XCTAssertTrue(m.matches("APP_Home"))
        XCTAssertFalse(m.matches("app_home"))
    }

    func testWholeWordDoesNotMatchSubstrings() {
        let m = matcher("home", wholeWord: true)
        XCTAssertTrue(m.matches("go home now"))
        XCTAssertFalse(m.matches("homepage"))
    }

    func testRegexMatches() {
        let m = matcher("metro_.*", regex: true)
        XCTAssertTrue(m.matches("metro_event"))
        XCTAssertFalse(m.matches("bus_event"))
    }

    func testInvalidRegexMatchesNothingInsteadOfThrowing() {
        let m = matcher("metro_[", regex: true) // unbalanced bracket
        XCTAssertFalse(m.matches("metro_event"))
        XCTAssertTrue(m.ranges("metro_event").isEmpty)
    }

    func testRangesLocateEveryOccurrence() {
        let ranges = matcher("ab").ranges("ab_xx_AB_ab")
        XCTAssertEqual([0..<2, 6..<8, 9..<11], ranges)
    }
}
