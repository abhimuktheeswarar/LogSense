import SwiftUI

internal enum Format {
    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    private static let dayTimeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMM d, HH:mm"
        return f
    }()

    static func time(_ ms: Int64) -> String {
        timeFormatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    static func dayTime(_ ms: Int64) -> String {
        dayTimeFormatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    /// The message's embedded JSON pretty-printed, with any leading text kept as a prefix line —
    /// or nil when the message holds no parseable JSON (ported from Android's `prettyJson`).
    static func prettyJson(in message: String) -> String? {
        guard let start = message.firstIndex(where: { $0 == "{" || $0 == "[" }) else { return nil }
        let closer: Character = message[start] == "{" ? "}" : "]"
        guard let end = message.lastIndex(of: closer), end > start else { return nil }
        let body = String(message[start...end])
        guard let data = body.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let pretty = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]),
              let text = String(data: pretty, encoding: .utf8)
        else { return nil }
        let prefix = message[..<start].trimmingCharacters(in: .whitespaces)
        return prefix.isEmpty ? text : prefix + "\n" + text
    }

    /// The message with find-bar matches marked, for `Text(AttributedString)`.
    static func highlighted(_ text: String, matcher: TextMatcher) -> AttributedString {
        var attributed = AttributedString(text)
        for utf16Range in matcher.ranges(text) {
            guard let lower = text.utf16.index(text.utf16.startIndex, offsetBy: utf16Range.lowerBound, limitedBy: text.utf16.endIndex),
                  let upper = text.utf16.index(text.utf16.startIndex, offsetBy: utf16Range.upperBound, limitedBy: text.utf16.endIndex),
                  let stringRange = Range<String.Index>(uncheckedBounds: (lower, upper)) as Range<String.Index>?,
                  let range = Range<AttributedString.Index>(stringRange, in: attributed)
            else { continue }
            attributed[range].backgroundColor = .yellow
            attributed[range].foregroundColor = .black
        }
        return attributed
    }
}
