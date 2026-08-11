import Foundation

/// A user-supplied extractor built from a regex entered in Settings — the escape hatch for log
/// formats the `DefaultExtractor` can't infer. The pattern is expected to expose:
///
/// - a named group **`name`** — the event name (required; a line with no match, or an empty name, is
///   treated as "not an event" and skipped),
/// - an optional named group **`params`** — a JSON object, a Swift-`Dictionary` description or a
///   `key=value, …` blob, parsed by `parseParams` (commas inside a value and wrapping braces are
///   handled either way),
/// - an optional named group **`tag`** — replaces the log tag as the event's tag, for lines that
///   name their real source (several SDKs funneling through one dispatcher, one log tag).
///
/// Example for `logEvent = purchase -> {sku=pro, qty=2}`:
/// `(?<name>\w+)\s*->\s*\{(?<params>.*)\}`
internal struct RegexExtractor {

    private let regex: NSRegularExpression
    private let declaresName: Bool
    private let declaresParams: Bool
    private let declaresTag: Bool

    init?(pattern: String) {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        self.regex = regex
        // range(withName:) on a group the pattern never declares is undefined territory —
        // only ask for groups the pattern spells out.
        self.declaresName = pattern.contains("(?<name>")
        self.declaresParams = pattern.contains("(?<params>")
        self.declaresTag = pattern.contains("(?<tag>")
    }

    func extract(tag: String, message: String) -> AnalyticsEvent? {
        guard let match = regex.firstMatch(in: message, range: NSRange(message.startIndex..., in: message))
        else { return nil }
        guard let name = group(match, named: "name", declared: declaresName, in: message)?
            .trimmingCharacters(in: .whitespaces), !name.isEmpty
        else { return nil }
        let params = group(match, named: "params", declared: declaresParams, in: message)
            .map(parseParams) ?? [:]
        let capturedTag = group(match, named: "tag", declared: declaresTag, in: message)?
            .trimmingCharacters(in: .whitespaces)
        return AnalyticsEvent(name: name, params: params, tag: capturedTag?.isEmpty == false ? capturedTag : nil)
    }

    private func group(_ match: NSTextCheckingResult, named: String, declared: Bool, in text: String) -> String? {
        guard declared else { return nil }
        let range = match.range(withName: named)
        guard range.location != NSNotFound else { return nil }
        return (text as NSString).substring(with: range)
    }

    /// Builds an extractor from newline-separated `patterns` — each non-blank line is one regex,
    /// tried in order (first match wins), so multiple log formats can be handled at once. Invalid
    /// lines are skipped; returns nil when no usable pattern remains, so callers can fall back.
    static func of(_ patterns: String) -> Extractor? {
        let extractors = patterns
            .split(separator: "\n", omittingEmptySubsequences: true)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .compactMap { RegexExtractor(pattern: $0) }
        if extractors.isEmpty { return nil }
        return { tag, message in
            for extractor in extractors {
                if let event = extractor.extract(tag: tag, message: message) { return event }
            }
            return nil
        }
    }
}

/// A tag's extractor: its regex (unusable/empty falls back), or the built-in parser when nil.
internal func extractorFor(_ pattern: String?) -> Extractor {
    if let pattern, !pattern.trimmingCharacters(in: .whitespaces).isEmpty,
       let regex = RegexExtractor.of(pattern) {
        return regex
    }
    return DefaultExtractor.extract
}
