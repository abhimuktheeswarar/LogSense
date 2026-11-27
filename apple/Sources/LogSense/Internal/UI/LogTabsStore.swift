#if os(iOS)
import SwiftUI

/// Which half a Logs pane is. A single pane and the left half of an open Duo are both `.primary`,
/// so folding and unfolding hand the same pane memory back and forth.
internal enum LogsPaneRole: Hashable {
    case primary, secondary

    var other: LogsPaneRole { self == .primary ? .secondary : .primary }
}

/// The Logs tabs, shared by every pane: saved tabs, which tab each half shows, per-tab freezes,
/// and what each pane should come back to after a fold rebuilds it. One owner, so two halves never
/// race on `Prefs.setSavedFilters`.
@MainActor
internal final class LogTabsStore: ObservableObject {

    /// "Moment" sync between halves: the other half scrolls to the line nearest `timeMs`.
    struct SyncRequest: Equatable {
        let target: LogsPaneRole
        let timeMs: Int64
        let fromTab: String
        let token = UUID()
    }

    /// What a pane needs back after it is rebuilt (fold, unfold, layout switch).
    struct PaneMemory {
        var findActive = false
        var findQuery = SearchQuery()
        var autoscroll = true
        var wrap = true
        var topLineId: Int64?
    }

    @Published var tabs: [SavedFilter]
    @Published var activeTab: [LogsPaneRole: Int64] = [.primary: 0]
    /// Per-tab pause: a **view freeze**, not a capture pause — runtime-only, like Android's
    /// `LogTab.paused`. The frozen rows are value copies, so they outlive buffer eviction.
    @Published var pausedTabs: Set<Int64> = []
    @Published var frozen: [Int64: [LogEntry]] = [:]
    @Published var sync: SyncRequest?
    /// iPhone Duo, open: two tabs side by side. Asked for, never the default — and kept only for
    /// as long as this screen lives (the session), never persisted.
    @Published var splitRequested = false
    var memory: [LogsPaneRole: PaneMemory] = [:]

    init() {
        var loaded = Prefs.savedFilters()
        // "All" (id 0) is the one tab that can't be closed — a stable home to come back to.
        if !loaded.contains(where: { $0.id == 0 }) {
            loaded.insert(SavedFilter(id: 0, name: "All"), at: 0)
        }
        // Scripted runs: LOGSENSE_START_FILTER=<query> starts "All" filtered, as if typed.
        if let query = ProcessInfo.processInfo.environment["LOGSENSE_START_FILTER"],
           let all = loaded.firstIndex(where: { $0.id == 0 }) {
            loaded[all].filter.query = query
        }
        tabs = loaded
    }

    func save() { Prefs.setSavedFilters(tabs) }

    func tab(for role: LogsPaneRole) -> SavedFilter? {
        let id = activeTab[role] ?? 0
        return tabs.first { $0.id == id } ?? tabs.first
    }

    /// The tab the second half opens on: the first tab that isn't the primary's. With only "All",
    /// an "Errors" tab is made once — the design's errors-beside-everything pairing.
    func ensureSecondaryTab() {
        if let id = activeTab[.secondary], tabs.contains(where: { $0.id == id }), id != activeTab[.primary] {
            return
        }
        let primary = activeTab[.primary] ?? 0
        if let other = tabs.first(where: { $0.id != primary }) {
            activeTab[.secondary] = other.id
        } else {
            let errors = SavedFilter(
                id: (tabs.map(\.id).max() ?? 0) + 1,
                name: "Errors",
                filter: LogFilter(minLevel: .error, query: "")
            )
            tabs.append(errors)
            save()
            activeTab[.secondary] = errors.id
        }
    }

    /// Selecting the tab the other half is showing swaps the halves rather than doubling a tab:
    /// two panes editing one tab's filter would fight over it.
    func select(_ id: Int64, for role: LogsPaneRole, split: Bool) {
        if split, activeTab[role.other] == id {
            activeTab[role.other] = activeTab[role] ?? 0
        }
        activeTab[role] = id
    }
}
#endif
