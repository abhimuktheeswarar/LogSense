import XCTest
@testable import LogSense

final class FormatRulesTests: XCTestCase {

    // MARK: TagColor — must match Android's Java String.hashCode exactly

    func testJavaHashMatchesKnownValues() {
        XCTAssertEqual(0, TagColor.javaHash(""))
        XCTAssertEqual(97, TagColor.javaHash("a"))          // 'a'
        XCTAssertEqual(3105, TagColor.javaHash("ab"))       // 97*31 + 98
        XCTAssertEqual(96354, TagColor.javaHash("abc"))     // 3105*31 + 99
        // Longer strings overflow 32 bits — wraparound must match Java, not trap.
        // 1794106052 is the well-known Java hashCode of "hello world".
        XCTAssertEqual(1794106052, TagColor.javaHash("hello world"))
    }

    func testPaletteIndexIsStableAndInRange() {
        for tag in ["OkHttp", "Analytics", "🔥flames", "a b c", ""] {
            let index = TagColor.paletteIndex(for: tag)
            XCTAssertTrue((0..<8).contains(index), "\(tag) → \(index)")
            XCTAssertEqual(index, TagColor.paletteIndex(for: tag), "stable across calls")
        }
    }

    // MARK: prettyJson — Android's LogLineSheet rules

    func testPrettyJsonDetectsAndPrettyPrints() {
        let pretty = Format.prettyJson(in: #"{"b":1,"a":{"c":2}}"#)
        XCTAssertNotNil(pretty)
        XCTAssertTrue(pretty!.contains("\n"), "multi-line output")
        XCTAssertTrue(pretty!.contains("\"a\""))
    }

    func testPrettyJsonKeepsLeadingTextAsAPrefixLine() {
        let pretty = Format.prettyJson(in: #"Response: {"a":1}"#)
        XCTAssertNotNil(pretty)
        XCTAssertTrue(pretty!.hasPrefix("Response:\n"), "prefix on its own line: \(pretty!)")
    }

    func testPrettyJsonHandlesArraysAndRejectsNonJson() {
        XCTAssertNotNil(Format.prettyJson(in: #"items [1, 2, 3]"#))
        XCTAssertNil(Format.prettyJson(in: "no json here"))
        XCTAssertNil(Format.prettyJson(in: "broken {not-json"))
    }
}
