import Foundation

/// A narrowing filter over the live log stream: a min level plus a query string (`LogQuery`)
/// supporting `tag:`, `-tag:`, `msg:`, `sub:`, `level:` and bare words. This is the "filter" half
/// of the filter-vs-find split; it applies to incoming lines too.
internal struct LogFilter: Equatable {
    var minLevel: LogLevel = .debug
    var query: String = ""
}

/// Compiles an Android-Studio-style filter query into a predicate. Space-separated terms are ANDed:
///
/// - `tag:foo`        tag contains "foo" (case-insensitive)
/// - `-tag:foo`       tag does NOT contain "foo"
/// - `message:foo`    (or `msg:foo`) message contains "foo"; `-message:` negates
/// - `sub:foo`        (or `subsystem:foo`) subsystem contains "foo"
/// - `level:E`        (or `level:error`) raise the minimum level
/// - `foo`            bare word: tag OR message contains "foo"
/// - `"two words"`    quotes keep spaces together
///
/// A plain query with no `key:` is just a simple text filter, so `tag:xxx` and free text both work.
internal enum LogQuery {

    static func compile(_ filter: LogFilter) -> (LogEntry) -> Bool {
        let terms = parse(filter.query)
        var min = filter.minLevel
        var predicates: [Term] = []
        for term in terms {
            if case .minLevel(let level) = term {
                min = Swift.max(min, level)
            } else {
                predicates.append(term)
            }
        }
        return { e in e.level >= min && predicates.allSatisfy { $0.matches(e) } }
    }

    private enum Term {
        case field(value: String, negate: Bool, select: (LogEntry) -> String)
        case text(value: String, negate: Bool)
        case minLevel(LogLevel)

        func matches(_ e: LogEntry) -> Bool {
            switch self {
            case .field(let value, let negate, let select):
                let hit = select(e).range(of: value, options: .caseInsensitive) != nil
                return hit != negate
            case .text(let value, let negate):
                let hit = e.tag.range(of: value, options: .caseInsensitive) != nil
                    || e.message.range(of: value, options: .caseInsensitive) != nil
                return hit != negate
            case .minLevel:
                return true
            }
        }
    }

    private static func parse(_ query: String) -> [Term] {
        tokenize(query).compactMap { token -> Term? in
            let negate = token.count > 1 && token.hasPrefix("-")
            let body = negate ? String(token.dropFirst()) : token
            guard let colon = body.firstIndex(of: ":"), colon != body.startIndex else {
                return body.isEmpty ? nil : .text(value: body, negate: negate)
            }
            let key = body[..<colon].lowercased()
            let value = String(body[body.index(after: colon)...])
            if value.isEmpty { return nil }
            switch key {
            case "tag": return .field(value: value, negate: negate) { $0.tag }
            case "message", "msg": return .field(value: value, negate: negate) { $0.message }
            case "sub", "subsystem": return .field(value: value, negate: negate) { $0.subsystem }
            case "level": return LogLevel.fromName(value).map { .minLevel($0) }
            default: return .text(value: body, negate: negate) // unknown key → treat the whole token as text
            }
        }
    }

    private static func tokenize(_ query: String) -> [String] {
        var out: [String] = []
        var current = ""
        var inQuote = false
        for c in query {
            if c == "\"" {
                inQuote.toggle()
            } else if c.isWhitespace && !inQuote {
                if !current.isEmpty { out.append(current); current = "" }
            } else {
                current.append(c)
            }
        }
        if !current.isEmpty { out.append(current) }
        return out
    }
}
