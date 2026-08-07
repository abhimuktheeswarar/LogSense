import Foundation

/// Pure JSON (de)serialization for persisted preferences — no platform deps, so it's unit-testable.
/// Hand-rolled over JSONSerialization for the same reason the Android side is: decode must be
/// tolerant of garbage, missing keys and unknown enum values, never throwing.
internal enum PrefsCodec {

    static func encodeFilters(_ filters: [SavedFilter]) -> String {
        let array = filters.map { f -> [String: Any] in
            [
                "id": f.id,
                "name": f.name,
                "minLevel": f.filter.minLevel.name,
                "query": f.filter.query,
                "viewMode": f.viewMode.rawValue,
            ]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: array),
              let text = String(data: data, encoding: .utf8)
        else { return "[]" }
        return text
    }

    static func decodeFilters(_ json: String) -> [SavedFilter] {
        guard let data = json.data(using: .utf8),
              let array = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]]
        else { return [] }
        return array.compactMap { o -> SavedFilter? in
            guard let id = (o["id"] as? NSNumber)?.int64Value else { return nil }
            return SavedFilter(
                id: id,
                name: o["name"] as? String ?? "Filter",
                filter: LogFilter(
                    minLevel: LogLevel.fromName(o["minLevel"] as? String ?? "") ?? .debug,
                    query: o["query"] as? String ?? ""
                ),
                viewMode: ViewMode(rawValue: o["viewMode"] as? String ?? "") ?? .standard
            )
        }
    }
}
