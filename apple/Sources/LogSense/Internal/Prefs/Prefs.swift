import Foundation

/// UserDefaults-backed preferences. Small, tolerant, and namespaced so LogSense never collides
/// with the host's own defaults.
internal enum Prefs {

    private static let mutedKey = "com.msabhi.logsense.mutedSignals"
    private static let filtersKey = "com.msabhi.logsense.savedFilters"

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
}
