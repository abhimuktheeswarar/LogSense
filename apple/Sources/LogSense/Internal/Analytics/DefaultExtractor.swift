import Foundation

/// The shape every extractor has: (tag, message) → event, or nil to skip the line.
internal typealias Extractor = (_ tag: String, _ message: String) -> AnalyticsEvent?

/// Built-in extractor for common analytics log shapes — no per-app configuration needed. It reads,
/// most specific first:
///
/// 1. `<name> Bundle[{key=value, ...}]`   — Firebase-style Bundle payload
/// 2. `<name> {"key": "value", ...}`      — JSON payload
/// 3. `<name> {key=value, ...}`           — brace-wrapped key=value (JSON-ish, unquoted values)
/// 4. `<verb> = <name> -> <payload>`      — arrow-separated (e.g. `logEvent = purchase -> {...}`)
/// 5. `<name> key=value, k2=v2`           — plain key=value pairs
/// 6. anything else                       — the whole message as the event name
///
/// Payloads shaped like a Swift `Dictionary` description — `["k": v, "k2": v2]` — are understood
/// wherever a payload is parsed, since that's how real apps mirror analytics dicts into os_log.
///
/// The event **name** is the last identifier before the payload, so a leading verb (`logEvent`,
/// `track`, `GA ->` …), arrows and separators are skipped automatically. A value may
/// itself contain commas — pairs are only split before the next `key=`, and any wrapping braces or
/// brackets are stripped. Falls back to the log **tag** when no name is present.
///
/// Not every app's format fits these shapes; supply `LogSenseConfig.analyticsExtractor`
/// for anything bespoke.
internal enum DefaultExtractor {

    static let extract: Extractor = { tag, message in
        let msg = message.trimmingCharacters(in: .whitespacesAndNewlines)
        let ns = msg as NSString

        // 1. Bundle[{ ... }]
        let bundleRange = ns.range(of: bundlePrefix)
        if bundleRange.location != NSNotFound {
            let end = ns.range(of: "}]", options: .backwards)
            if end.location != NSNotFound, end.location > bundleRange.location {
                let start = bundleRange.location + bundleRange.length
                let inner = ns.substring(with: NSRange(location: start, length: end.location - start))
                return AnalyticsEvent(
                    name: nameBefore(msg, payloadStart: bundleRange.location, tag: tag),
                    params: parseKeyValues(inner)
                )
            }
        }

        // 2 & 3. { ... } — real JSON if it parses, otherwise brace-wrapped key=value.
        let open = ns.range(of: "{")
        let close = ns.range(of: "}", options: .backwards)
        if open.location != NSNotFound, close.location != NSNotFound, open.location < close.location {
            let inner = ns.substring(with: NSRange(location: open.location, length: close.location - open.location + 1))
            return AnalyticsEvent(
                name: nameBefore(msg, payloadStart: open.location, tag: tag),
                params: parseParams(inner)
            )
        }

        // 4. arrow-separated: `<verb> = <name> -> <payload>`  /  `<name> => <payload>`
        let arrows = ["->", "=>"].map { ns.range(of: $0) }.filter { $0.location != NSNotFound }
        if let arrow = arrows.min(by: { $0.location < $1.location }) {
            return AnalyticsEvent(
                name: nameBefore(msg, payloadStart: arrow.location, tag: tag),
                // parseParams, not parseKeyValues: an arrow payload may be a Swift-Dictionary
                // description (`-> ["k": v]`), which the k=v parser can't see.
                params: parseParams(ns.substring(from: arrow.location + 2))
            )
        }

        // 5. plain key=value pairs, optionally prefixed by a name.
        if msg.contains("=") {
            let tokens = msg.split(separator: " ", maxSplits: 1, omittingEmptySubsequences: true)
            if tokens.count >= 1, tokens[0].contains("=") {
                return AnalyticsEvent(name: tag, params: parseKeyValues(msg)) // params only, no name
            }
            if tokens.count == 2 {
                return AnalyticsEvent(name: cleanName(String(tokens[0]), tag: tag), params: parseKeyValues(String(tokens[1])))
            }
            return AnalyticsEvent(name: msg)
        }

        // 6. plain text.
        return AnalyticsEvent(name: msg.isEmpty ? tag : msg)
    }

    private static let bundlePrefix = "Bundle[{"
    private static let nameToken = try! NSRegularExpression(pattern: #"[\w.]+"#)

    /// Event name = the last identifier in the text before the payload (skips verbs/arrows/colons).
    private static func nameBefore(_ msg: String, payloadStart: Int, tag: String) -> String {
        let ns = msg as NSString
        let head = NSRange(location: 0, length: payloadStart)
        let last = nameToken.matches(in: msg, range: head).last
        return last.map { ns.substring(with: $0.range) } ?? tag
    }

    private static func cleanName(_ prefix: String, tag: String) -> String {
        var name = prefix.trimmingCharacters(in: .whitespaces)
        while let c = name.last, c == ":" || c == "," || c == "-" { name.removeLast() }
        name = name.trimmingCharacters(in: .whitespaces)
        return name.isEmpty ? tag : name
    }
}

/// A `key = value` pair, where the value runs until the next `, key=` or the end of the text.
private let kvPattern = try! NSRegularExpression(pattern: #"([\w.]+)\s*=\s*(.*?)(?=,\s*[\w.]+\s*=|$)"#)

/// A `"key": value` pair from a Swift `Dictionary` description, same run-until-next-key rule.
private let swiftDictPattern = try! NSRegularExpression(pattern: #""?([\w.]+)"?\s*:\s*(.*?)(?=,\s*"?[\w.]+"?\s*:|$)"#)

/// Params from a captured payload: real JSON if it parses to a non-empty object (many SDKs log the
/// event's attributes as JSON), a Swift-`Dictionary` description if it looks like one, otherwise
/// `key=value`. Shared by `DefaultExtractor` and the user-supplied `RegexExtractor` so a regex whose
/// `params` group grabs a structured payload gets the same handling as the built-in parser.
internal func parseParams(_ text: String) -> [String: String] {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    if let json = jsonObject(from: trimmed) {
        let flat = flattenJson(json)
        if !flat.isEmpty { return flat }
    }
    if trimmed.hasPrefix("["), trimmed.contains(":") {
        let pairs = parseSwiftDict(trimmed)
        if !pairs.isEmpty { return pairs }
    }
    if trimmed.hasPrefix("{"), trimmed.contains(";") {
        let pairs = parsePlistDict(trimmed)
        if !pairs.isEmpty { return pairs }
    }
    return parseKeyValues(text)
}

/// A pair from an ObjC plist/`NSDictionary` description — how Foundation prints dictionaries
/// through `os_log`/`NSLog`: one `key = value;` per line, keys and values quoted only when they
/// need it, keys sometimes carrying a parenthesised alias (`screen_class (_sc) = HomeVC;`).
private let plistPairPattern = try! NSRegularExpression(
    pattern: #""?([\w.\-]+)"?(?:\s*\([^)]*\))?\s*=\s*"?(.*?)"?;"#
)

/// Pairs from an ObjC plist-dict description: `{ key = value; "key 2" = "v 2"; }`. `.` staying
/// line-bound makes each pair self-contained; nested dicts contribute their inner pairs (the
/// `key = {` opener has no `;` and is skipped). The closing brace is never required — the OS
/// truncates long log entries at ~1KB, and a cut payload should still yield what it kept.
private func parsePlistDict(_ text: String) -> [String: String] {
    let ns = text as NSString
    var out: [String: String] = [:]
    for match in plistPairPattern.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
        out[ns.substring(with: match.range(at: 1))] = ns.substring(with: match.range(at: 2))
    }
    return out
}

/// Parses `key=value, k2=v2` into a map, tolerating wrapping braces/brackets and commas *inside*
/// values. A value that is itself a Swift-`Dictionary` description is flattened into the result,
/// mirroring the escaped-JSON unwrap.
internal func parseKeyValues(_ text: String) -> [String: String] {
    let body = text.trimmingCharacters(in: CharacterSet(charactersIn: "{}[] "))
    let ns = body as NSString
    var out: [String: String] = [:]
    for match in kvPattern.matches(in: body, range: NSRange(location: 0, length: ns.length)) {
        let key = ns.substring(with: match.range(at: 1)).trimmingCharacters(in: .whitespaces)
        let value = ns.substring(with: match.range(at: 2)).trimmingCharacters(in: .whitespaces)
        if value.hasPrefix("["), value.contains(":") {
            let nested = parseSwiftDict(value)
            if !nested.isEmpty {
                out.merge(nested) { current, _ in current }
                continue
            }
        }
        out[key] = value
    }
    return out
}

/// Pairs from a Swift `Dictionary` description: `["k": v, "k2": "v2"]`. Key order in that format is
/// non-deterministic and values may contain delimiters — this parser is deliberately tolerant.
private func parseSwiftDict(_ text: String) -> [String: String] {
    var body = text.trimmingCharacters(in: .whitespaces)
    if body.hasPrefix("[") { body.removeFirst() }
    if body.hasSuffix("]") { body.removeLast() }
    let ns = body as NSString
    var out: [String: String] = [:]
    for match in swiftDictPattern.matches(in: body, range: NSRange(location: 0, length: ns.length)) {
        let key = ns.substring(with: match.range(at: 1))
        var value = ns.substring(with: match.range(at: 2)).trimmingCharacters(in: .whitespaces)
        if value.count >= 2, value.hasPrefix("\""), value.hasSuffix("\"") {
            value = String(value.dropFirst().dropLast())
        }
        out[key] = value
    }
    return out
}

/// Stringified params from a JSON object. Nested objects/arrays are kept as JSON text — deliberate.
/// A string field that itself holds JSON is an SDK cramming a whole attribute set through one string
/// as (double-)escaped JSON: unwrap it so those attributes become their own rows, un-escaped,
/// instead of one unreadable `{\"k\":v}` blob.
internal func flattenJson(_ json: [String: Any]) -> [String: String] {
    var out: [String: String] = [:]
    for (key, value) in json {
        switch value {
        case is NSNull:
            out[key] = "null"
        case let text as String:
            if let unwrapped = jsonObject(from: text), !unwrapped.isEmpty {
                out.merge(flattenJson(unwrapped)) { current, _ in current }
            } else {
                out[key] = text
            }
        case let nested as [String: Any]:
            out[key] = jsonText(nested)
        case let array as [Any]:
            out[key] = jsonText(array)
        case let number as NSNumber:
            out[key] = CFGetTypeID(number) == CFBooleanGetTypeID()
                ? (number.boolValue ? "true" : "false")
                : number.stringValue
        default:
            out[key] = "\(value)"
        }
    }
    return out
}

/// The string parsed as a JSON object, or nil if it isn't one.
private func jsonObject(from text: String) -> [String: Any]? {
    guard text.hasPrefix("{"), let data = text.data(using: .utf8) else { return nil }
    return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
}

private func jsonText(_ value: Any) -> String {
    guard JSONSerialization.isValidJSONObject(value),
          let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]),
          let text = String(data: data, encoding: .utf8)
    else { return "\(value)" }
    return text
}
