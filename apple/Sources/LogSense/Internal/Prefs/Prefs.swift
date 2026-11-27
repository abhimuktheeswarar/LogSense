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
    static let keepPastEventsKey = "com.msabhi.logsense.keepPastEvents"
    static let keepPastCrashesKey = "com.msabhi.logsense.keepPastCrashes"
    /// iPhone Duo, beside the host: true = LogSense overlays the host's far half; false (default) =
    /// the host shrinks to the near half and the two sit side by side.
    static let besideOverlayKey = "com.msabhi.logsense.besideOverlay"
    /// iPhone Duo: the one-time hint explaining the split button has been seen.
    static let duoSplitHintShownKey = "com.msabhi.logsense.duoSplitHintShown"

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

    /// Whether events/crashes from earlier app runs are retained — same defaults as Android:
    /// crashes on (you rarely want to miss one); events off (high-volume, usually only this
    /// run matters). Applied at the next launch's pruning.
    static func keepPastEvents() -> Bool {
        UserDefaults.standard.object(forKey: keepPastEventsKey) as? Bool ?? false
    }

    static func keepPastCrashes() -> Bool {
        UserDefaults.standard.object(forKey: keepPastCrashesKey) as? Bool ?? true
    }

    /// The newest crash timestamp the user has seen — the activity stays red past this.
    private static let firstStartKey = "com.msabhi.logsense.firstStartMs"

    /// When LogSense first started on this install — reports older than this are history that
    /// MetricKit hands over late, stored but never announced. Set once, on first read.
    static func firstStartMs(now: Int64) -> Int64 {
        let stored = Int64(UserDefaults.standard.double(forKey: firstStartKey))
        if stored > 0 { return stored }
        UserDefaults.standard.set(Double(now), forKey: firstStartKey)
        return now
    }

    static func lastSeenCrashMs() -> Int64 {
        Int64(UserDefaults.standard.double(forKey: lastSeenCrashKey))
    }

    static func setLastSeenCrashMs(_ ms: Int64) {
        UserDefaults.standard.set(Double(ms), forKey: lastSeenCrashKey)
    }

    private static let readCrashesKey = "com.msabhi.logsense.readCrashes"

    /// Crash reports the user has read, keyed by crash file name — stable across launches
    /// while the container path prefix is not. Drives the tab badge (unread only).
    static func readCrashKeys() -> Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: readCrashesKey) ?? [])
    }

    static func setReadCrashKeys(_ keys: Set<String>) {
        UserDefaults.standard.set(Array(keys).sorted(), forKey: readCrashesKey)
    }
}
