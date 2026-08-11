/// An analytics event extracted from a log line.
///
/// Param values are strings: numbers and booleans from a JSON payload are stringified
/// (`2`, `9.99`, `true`), which keeps events trivially Codable for storage and export.
public struct AnalyticsEvent: Equatable, Sendable {
    public let name: String
    public let params: [String: String]
    /// Overrides the log tag as the event's displayed tag. Useful when several analytics SDKs
    /// funnel through one log tag (e.g. an app-level dispatcher logging every SDK's events from
    /// the same file) and the line itself names the real source.
    public let tag: String?

    public init(name: String, params: [String: String] = [:], tag: String? = nil) {
        self.name = name
        self.params = params
        self.tag = tag
    }
}
