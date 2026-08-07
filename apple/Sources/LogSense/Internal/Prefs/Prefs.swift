import Foundation

/// UserDefaults-backed preferences. Small, tolerant, and namespaced so LogSense never collides
/// with the host's own defaults.
internal enum Prefs {

    private static let mutedKey = "com.msabhi.logsense.mutedSignals"
    private static let filtersKey = "com.msabhi.logsense.savedFilters"
    private static let tagsKey = "com.msabhi.logsense.analyticsTags"
    static let themeKey = "com.msabhi.logsense.theme"

    /// Muted signal ids; the built-in default set until the user touches it.
    static func mutedSignals() -> Set<String> {
        guard let stored = UserDefaults.standard.stringArray(forKey: mutedKey) else {
            return BuiltInSignals.mutedByDefault
        }
        return Set(stored)
    }

    static func setMutedSignals(_ muted: Set<String>) {
        UserDefaults.standard.set(Array(muted).sorted(), forKey: mutedKey)
    }

    static func savedFilters() -> [SavedFilter] {
        PrefsCodec.decodeFilters(UserDefaults.standard.string(forKey: filtersKey) ?? "")
    }

    static func setSavedFilters(_ filters: [SavedFilter]) {
        UserDefaults.standard.set(PrefsCodec.encodeFilters(filters), forKey: filtersKey)
    }

    /// QA-added analytics tags: tag → optional regex. An empty-string pattern means "built-in
    /// parser" (JSON has no nil map values worth fighting over in UserDefaults).
    static func analyticsTags() -> [String: String?] {
        guard let stored = UserDefaults.standard.dictionary(forKey: tagsKey) as? [String: String]
        else { return [:] }
        return stored.mapValues { $0.isEmpty ? nil : $0 }
    }

    static func setAnalyticsTags(_ tags: [String: String?]) {
        UserDefaults.standard.set(tags.mapValues { $0 ?? "" }, forKey: tagsKey)
    }
}
