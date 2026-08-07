import Foundation

/// Find-bar options: match case / whole word / regex.
internal struct SearchQuery: Equatable {
    var text: String = ""
    var matchCase: Bool = false
    var wholeWord: Bool = false
    var regex: Bool = false

    var isActive: Bool { !text.isEmpty }
}

/// Compiles a `SearchQuery` into a reusable matcher — literal / match-case / whole-word / regex.
/// An invalid regex (or half-typed pattern) resolves to "matches nothing" instead of throwing.
internal struct TextMatcher {
    private let regex: NSRegularExpression?
    private let literal: String?
    private let ignoreCase: Bool
    private let matchNothing: Bool

    /// True when `input` contains at least one match. Empty query matches everything.
    func matches(_ input: String) -> Bool {
        if matchNothing { return false }
        if let regex {
            return regex.firstMatch(in: input, range: NSRange(input.startIndex..., in: input)) != nil
        }
        if let literal {
            return input.range(of: literal, options: ignoreCase ? [.caseInsensitive] : []) != nil
        }
        return true
    }

    /// Non-empty match ranges within `input` as UTF-16 offsets, for highlighting.
    /// Empty when the query is blank.
    func ranges(_ input: String) -> [Range<Int>] {
        if matchNothing { return [] }
        if let regex {
            return regex.matches(in: input, range: NSRange(input.startIndex..., in: input))
                .map(\.range)
                .filter { $0.length > 0 }
                .map { $0.location..<($0.location + $0.length) }
        }
        guard let literal, !literal.isEmpty else { return [] }
        var out: [Range<Int>] = []
        let haystack = input as NSString
        var search = NSRange(location: 0, length: haystack.length)
        let options: NSString.CompareOptions = ignoreCase ? [.caseInsensitive, .literal] : [.literal]
        while search.length > 0 {
            let found = haystack.range(of: literal, options: options, range: search)
            if found.location == NSNotFound { break }
            out.append(found.location..<(found.location + found.length))
            let next = found.location + found.length
            search = NSRange(location: next, length: haystack.length - next)
        }
        return out
    }

    static func from(_ query: SearchQuery) -> TextMatcher {
        let ignoreCase = !query.matchCase
        if query.text.isEmpty {
            return TextMatcher(regex: nil, literal: nil, ignoreCase: ignoreCase, matchNothing: false)
        }
        let options: NSRegularExpression.Options = ignoreCase ? [.caseInsensitive] : []
        if query.regex {
            return compile(query.text, options: options, ignoreCase: ignoreCase)
        }
        if query.wholeWord {
            let escaped = NSRegularExpression.escapedPattern(for: query.text)
            return compile("\\b\(escaped)\\b", options: options, ignoreCase: ignoreCase)
        }
        return TextMatcher(regex: nil, literal: query.text, ignoreCase: ignoreCase, matchNothing: false)
    }

    private static func compile(
        _ pattern: String, options: NSRegularExpression.Options, ignoreCase: Bool
    ) -> TextMatcher {
        if let regex = try? NSRegularExpression(pattern: pattern, options: options) {
            return TextMatcher(regex: regex, literal: nil, ignoreCase: ignoreCase, matchNothing: false)
        }
        return TextMatcher(regex: nil, literal: nil, ignoreCase: ignoreCase, matchNothing: true)
    }
}
