/// An analytics event extracted from a log line.
///
/// Param values are strings: numbers and booleans from a JSON payload are stringified
/// (`2`, `9.99`, `true`), which keeps events trivially Codable for storage and export.
public struct AnalyticsEvent: Equatable, Sendable {
    public let name: String
    public let params: [String: String]

    public init(name: String, params: [String: String] = [:]) {
        self.name = name
        self.params = params
    }
}
