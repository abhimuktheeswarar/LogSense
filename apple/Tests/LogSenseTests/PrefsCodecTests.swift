import XCTest
@testable import LogSense

final class PrefsCodecTests: XCTestCase {

    func testFiltersRoundTrip() {
        let filters = [
            SavedFilter(id: 0, name: "All"),
            SavedFilter(
                id: 1,
                name: "Errors",
                filter: LogFilter(minLevel: .error, query: #"tag:"ANALYTICS ... ANALYTICS" metro"#),
                viewMode: .compact
            ),
        ]
        let decoded = PrefsCodec.decodeFilters(PrefsCodec.encodeFilters(filters))
        XCTAssertEqual(filters, decoded)
    }

    func testEmptyQuerySurvivesRoundTrip() {
        let filters = [SavedFilter(id: 7, name: "Filter", filter: LogFilter(query: ""))]
        XCTAssertEqual("", PrefsCodec.decodeFilters(PrefsCodec.encodeFilters(filters))[0].filter.query)
    }

    func testUnknownEnumValuesDecodeToDefaults() {
        let json = #"[{"id": 3, "name": "X", "minLevel": "chartreuse", "query": "q", "viewMode": "holographic"}]"#
        let decoded = PrefsCodec.decodeFilters(json)
        XCTAssertEqual(.debug, decoded[0].filter.minLevel)
        XCTAssertEqual(.standard, decoded[0].viewMode)
    }

    func testGarbageDecodesToEmptyNotACrash() {
        XCTAssertTrue(PrefsCodec.decodeFilters("not json").isEmpty)
        XCTAssertTrue(PrefsCodec.decodeFilters("").isEmpty)
        XCTAssertTrue(PrefsCodec.decodeFilters("{\"an\": \"object\"}").isEmpty)
    }
}
