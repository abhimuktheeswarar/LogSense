import Foundation

/// UserDefaults-backed preferences. Small, tolerant, and namespaced so LogSense never collides
/// with the host's own defaults.
internal enum Prefs {

    private static let mutedKey = "com.msabhi.logsense.mutedSignals"
    private static let filtersKey = "com.msabhi.logsense.savedFilters"
    private static let tagsKey = "com.msabhi.logsense.analyticsTags"
    static let themeKey = "com.msabhi.logsense.theme"
    static let liveActivityKey = "com.msabhi.logsense.liveActivity"
    private static let lastSeenCrashKey = "com.msabhi.logsense.lastSeenCrashMs"

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

    /// The Settings "Show while recording" toggle; on until turned off.
    static func liveActivityEnabled() -> Bool {
        UserDefaults.standard.object(forKey: liveActivityKey) as? Bool ?? true
    }

    /// The newest crash timestamp the user has seen — the activity stays red past this.
    static func lastSeenCrashMs() -> Int64 {
        Int64(UserDefaults.standard.double(forKey: lastSeenCrashKey))
    }

    static func setLastSeenCrashMs(_ ms: Int64) {
        UserDefaults.standard.set(Double(ms), forKey: lastSeenCrashKey)
    }
}
