import XCTest
@testable import LogSense

final class RegexExtractorTests: XCTestCase {

    private let arrow = RegexExtractor.of(#"(?<name>\w+)\s*->\s*\{(?<params>.*)\}"#)!

    func testNamedGroupsPullNameAndParams() {
        let event = arrow("Tag", "purchase -> {sku=pro, qty=2}")!
        XCTAssertEqual("purchase", event.name)
        XCTAssertEqual("pro", event.params["sku"])
        XCTAssertEqual("2", event.params["qty"])
    }

    func testValueWithACommaIsPreservedViaTheSharedParser() {
        let event = arrow("Tag", "search -> {origin=Paris, France, dest=Rome}")!
        XCTAssertEqual("Paris, France", event.params["origin"])
        XCTAssertEqual("Rome", event.params["dest"])
    }

    func testNoMatchReturnsNil() {
        XCTAssertNil(arrow("Tag", "nothing to see here"))
    }

    func testNameGroupWithoutAParamsGroupYieldsEmptyParams() {
        let e = RegexExtractor.of(#"EVENT:(?<name>\w+)"#)!
        let event = e("Tag", "EVENT:app_open extra stuff")!
        XCTAssertEqual("app_open", event.name)
        XCTAssertTrue(event.params.isEmpty)
    }

    func testBlankOrInvalidPatternCompilesToNil() {
        XCTAssertNil(RegexExtractor.of(""))
        XCTAssertNil(RegexExtractor.of("   "))
        XCTAssertNil(RegexExtractor.of("(?<name>")) // unbalanced group
    }

    func testPatternWithoutANameGroupProducesNoEvent() {
        let e = RegexExtractor.of(#"foo=(?<params>.*)"#)!
        XCTAssertNil(e("Tag", "foo=a=1, b=2"))
    }

    func testMultiplePatternsAreTriedInOrderFirstMatchWins() {
        let e = RegexExtractor.of(
            """
            (?<name>\\w+)\\s*->\\s*\\{(?<params>.*)\\}
            GA -> (?<name>\\w+)
            """
        )!
        // matches line 1
        XCTAssertEqual("purchase", e("T", "purchase -> {a=1}")!.name)
        // no braces -> falls to line 2
        XCTAssertEqual("home_view", e("T", "GA -> home_view")!.name)
        // matches neither
        XCTAssertNil(e("T", "unrelated log"))
    }

    func testNameInsideThePayloadParamsIsACleanJsonObject() {
        // Some SDKs put the event name inside the JSON and the attributes in a nested object.
        let e = RegexExtractor.of(
            #"LOG -> .*"event":"(?<name>[^"]+)".*"data":(?<params>\{.*\})\}"#
        )!
        let event = e(
            "T",
            #"LOG -> {"reqTime":1784900140986,"event":"close_screen","data":{"screen":"Home"}}"#
        )!
        XCTAssertEqual("close_screen", event.name)
        XCTAssertEqual("Home", event.params["screen"])
    }

    func testAFieldHoldingDoubleEscapedJsonIsUnwrappedIntoFlatParams() {
        // Some SDKs cram the whole attribute set into one string field as escaped JSON; capturing the
        // enclosing object lets JSON parsing un-escape it, and the string-JSON unwrap flattens those
        // attributes into their own rows.
        let e = RegexExtractor.of(#"evt=(?<name>\w+) (?<params>\{.*\})"#)!
        let event = e("T", #"evt=purchase {"attrs":"{\"sku\":\"pro\",\"qty\":2}","ts":"123"}"#)!
        XCTAssertEqual("purchase", event.name)
        XCTAssertEqual("pro", event.params["sku"]) // unwrapped, un-escaped
        XCTAssertEqual("2", event.params["qty"])
        XCTAssertEqual("123", event.params["ts"]) // sibling scalar kept as-is
        XCTAssertFalse(event.params.keys.contains("attrs")) // wrapper key gone, flattened
    }

    func testSwiftDictionaryParamsGroup() {
        let e = RegexExtractor.of(#"event name =\s*(?<name>\S+)\s*, otherInfo = (?<params>.*)"#)!
        let event = e("T", #"event name = COMMON_HOME , otherInfo = ["lob": "Bus", "clicks": "Offer"]"#)!
        XCTAssertEqual("COMMON_HOME", event.name)
        XCTAssertEqual("Bus", event.params["lob"])
        XCTAssertEqual("Offer", event.params["clicks"])
    }

    func testExtractorForRoutesNilOrBlankToTheBuiltInParserARegexToItself() {
        // nil / blank pattern -> built-in parser (handles name {json}, etc.)
        XCTAssertEqual("purchase", extractorFor(nil)("T", #"purchase {"sku":"pro"}"#)!.name)
        XCTAssertEqual("app_open", extractorFor("   ")("T", "app_open")!.name)
        // a real regex -> used for that tag; a non-matching line is skipped
        let rx = extractorFor(#"evt=(?<name>\w+)"#)
        XCTAssertEqual("login", rx("T", "evt=login extra")!.name)
        XCTAssertNil(rx("T", "unrelated line"))
        // invalid regex -> falls back to the built-in parser rather than dropping every event
        XCTAssertEqual("hello", extractorFor("(?<name>")("T", "hello")!.name)
    }

    func testBlankAndInvalidLinesAreSkippedValidOnesStillWork() {
        let e = RegexExtractor.of(
            """
            (?<name>

            EV:(?<name>\\w+)
            """
        )! // first line is an invalid regex, second blank, third valid
        XCTAssertEqual("app_open", e("T", "EV:app_open")!.name)
    }
}
