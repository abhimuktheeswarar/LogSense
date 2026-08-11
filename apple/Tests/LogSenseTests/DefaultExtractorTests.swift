import XCTest
@testable import LogSense

final class DefaultExtractorTests: XCTestCase {

    private let extract = DefaultExtractor.extract

    func testJsonPayload() {
        let event = extract("Analytics", #"purchase {"sku":"pro","price":9.99}"#)!
        XCTAssertEqual("purchase", event.name)
        XCTAssertEqual("pro", event.params["sku"])
        XCTAssertEqual(9.99, Double(event.params["price"] ?? "") ?? .nan, accuracy: 0.0001)
    }

    func testBundlePayload() {
        let event = extract("Analytics", "add_to_cart Bundle[{item_id=42, qty=2}]")!
        XCTAssertEqual("add_to_cart", event.name)
        XCTAssertEqual("42", event.params["item_id"])
        XCTAssertEqual("2", event.params["qty"])
    }

    func testKeyValuePayload() {
        let event = extract("Analytics", "screen_view screen=Home, source=tab")!
        XCTAssertEqual("screen_view", event.name)
        XCTAssertEqual("Home", event.params["screen"])
        XCTAssertEqual("tab", event.params["source"])
    }

    func testNameOnlyFallback() {
        let event = extract("Analytics", "app_opened")!
        XCTAssertEqual("app_opened", event.name)
        XCTAssertTrue(event.params.isEmpty)
    }

    func testNameWithTrailingColonBeforeJson() {
        let event = extract("Analytics", #"login: {"method":"google"}"#)!
        XCTAssertEqual("login", event.name)
        XCTAssertEqual("google", event.params["method"])
    }

    func testParamsWithoutNameFallsBackToTag() {
        let event = extract("Analytics", "screen=Home, source=tab")!
        XCTAssertEqual("Analytics", event.name)
        XCTAssertEqual("Home", event.params["screen"])
    }

    func testMalformedJsonFallsThroughWithoutCrashing() {
        let event = extract("Analytics", "broken {not-json")!
        XCTAssertEqual("broken {not-json", event.name)
    }

    func testNestedJsonKeptAsText() {
        let event = extract("Analytics", #"checkout {"items":[{"id":1}],"total":5}"#)!
        XCTAssertEqual("checkout", event.name)
        XCTAssertEqual(#"[{"id":1}]"#, event.params["items"])
        XCTAssertEqual("5", event.params["total"])
    }

    // --- generic shapes: arrow separator, verb prefix, brace-wrapped k=v, commas inside values ---

    func testVerbPrefixAndArrowNameIsTheIdentifierBeforeThePayload() {
        let event = extract("Telemetry", "logEvent = purchase -> {sku=pro, region=US}")!
        XCTAssertEqual("purchase", event.name) // "logEvent" verb is skipped
        XCTAssertEqual("pro", event.params["sku"])
        XCTAssertEqual("US", event.params["region"])
    }

    func testValueContainingACommaIsKeptWhole() {
        let event = extract("Telemetry", "track = search -> {origin=Paris, France, dest=Rome, Italy}")!
        XCTAssertEqual("search", event.name)
        XCTAssertEqual("Paris, France", event.params["origin"])
        XCTAssertEqual("Rome, Italy", event.params["dest"])
    }

    func testNoStrayClosingBraceOnTheLastValue() {
        let event = extract("Telemetry", "send = view -> {a=1, screen=Home}")!
        XCTAssertEqual("view", event.name)
        XCTAssertEqual("Home", event.params["screen"]) // not "Home}"
    }

    func testArrowThenBundleNameIsTheIdentifierBeforeThePayload() {
        let event = extract("Telemetry", "GA -> ab_test_foo : Bundle[{lang=en, screen=Home}]")!
        XCTAssertEqual("ab_test_foo", event.name) // "GA ->" prefix skipped
        XCTAssertEqual("en", event.params["lang"])
        XCTAssertEqual("Home", event.params["screen"])
    }

    func testArrowWithoutBraces() {
        let event = extract("Telemetry", "GA -> screen=Splash, source=deeplink")!
        XCTAssertEqual("Splash", event.params["screen"])
        XCTAssertEqual("deeplink", event.params["source"])
    }

    // --- Swift Dictionary description payloads (how real apps mirror analytics dicts to os_log) ---

    func testSwiftDictionaryDescriptionPayload() {
        let event = extract("Analytics", #"logEvent = seat_selected -> ["seat": "L4", "fare": 849]"#)!
        XCTAssertEqual("seat_selected", event.name)
        XCTAssertEqual("L4", event.params["seat"])
        XCTAssertEqual("849", event.params["fare"])
    }

    func testSwiftDictionaryValueInsideKeyValuePairsIsFlattened() {
        let event = extract("Analytics", #"event name = COMMON_HOME , otherInfo = ["lob": "Bus", "position": 2]"#)!
        XCTAssertEqual("Bus", event.params["lob"])
        XCTAssertEqual("2", event.params["position"])
    }

    // MARK: ObjC plist-dict payloads — how Foundation prints NSDictionary through os_log

    func testPlistDictPayload() {
        let params = parseParams("{\n    page = commonHome;\n    screenName = home;\n}")
        XCTAssertEqual(["page": "commonHome", "screenName": "home"], params)
    }

    func testPlistDictQuotedKeysValuesAndAliases() {
        let payload = """
        {
            "creative_name" = "ACMEOFFER ";
            "creative_slot" = 36;
            screen_class (_sc) = HomeVC;
            location = "Common Home";
        }
        """
        let params = parseParams(payload)
        XCTAssertEqual("ACMEOFFER ", params["creative_name"], "quoted value keeps its spacing")
        XCTAssertEqual("36", params["creative_slot"])
        XCTAssertEqual("HomeVC", params["screen_class"], "parenthesised alias is not part of the key")
        XCTAssertEqual("Common Home", params["location"])
    }

    func testPlistDictTruncatedByEntryCapStillYieldsCompletePairs() {
        // The OS caps log entries (~1KB): the closing brace and the tail of the payload are gone.
        let params = parseParams("{\n    page = home;\n    userType = RETURNING;\n    langua")
        XCTAssertEqual(["page": "home", "userType": "RETURNING"], params)
    }

    func testConfiguredRegexWithPlistPayloadEndToEnd() {
        // The whole path a host configures: tag regex with name/params groups over a dispatch
        // mirror line, params in plist-dict form, no closing brace required.
        let extractor = extractorFor(#"\[Acme\] \[(?:GA|MRI)\] event name =\s+(?<name>\S+)\s*,\s*otherInfo = (?<params>\{[\s\S]*)"#)
        let message = "[Acme] [GA] event name =  view_promotion , otherInfo = {\n    \"promotion_id\" = NA;\n    slot = 12;\n}"
        let event = extractor("app", message)!
        XCTAssertEqual("view_promotion", event.name)
        XCTAssertEqual("NA", event.params["promotion_id"])
        XCTAssertEqual("12", event.params["slot"])
        XCTAssertNil(extractor("app", "[Acme] [GA] session refreshed"), "non-event lines are skipped")
    }
}
