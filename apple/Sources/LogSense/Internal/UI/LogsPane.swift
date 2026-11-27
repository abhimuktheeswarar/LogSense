#if os(iOS)
import SwiftUI

/// The live stream, per the design: LOGSENSE caption over the host's name, Live pulse + counts,
/// filter field with a min-level menu, Wrap/Autoscroll pills, Standard/Compact/Raw density, find
/// bar above the keyboard, paused banner, and the waiting/no-match states.
internal struct LogsPane: View {
    let core: LogSenseCore
    let onDone: (() -> Void)?
    let role: LogsPaneRole
    /// One of two halves on an open iPhone Duo: a tap syncs the other half to that moment and
    /// the inspector opens as a sheet, since there is no room beside a half.
    let isSplit: Bool
    /// The split on/off switch, offered in the primary half's overflow menu while open.
    let splitEnabled: Binding<Bool>?

    @ObservedObject private var state: LogSenseState
    @ObservedObject private var feed: LogsFeed
    @ObservedObject private var store: LogTabsStore
    @Environment(\.duoPosture) private var duo
    @Environment(\.colorScheme) private var scheme
    @Environment(\.horizontalSizeClass) private var hSize

    @State private var query = ""
    /// `query` debounced — what the list actually filters by. Re-filtering the whole buffer and
    /// re-diffing the list on every keystroke is what made typing lag in chatty hosts.
    @State private var appliedQuery = ""
    @State private var minLevel: LogLevel = .debug
    @State private var viewMode: ViewMode = .standard
    @State private var wrap = true
    @State private var autoscroll = true
    @State private var findQuery = SearchQuery()
    @State private var findActive = false
    @State private var findIndex = 0
    @State private var selectedEntry: LogEntry?
    @State private var showSettings = false
    /// An entry id the Signals tab asked to scroll to; consumed by the list's scroll proxy.
    @State private var revealTarget: Int64?

    /// Per-tab scroll positions — "a tab keeps its own filter, scroll position and pause state."
    /// The list doesn't expose its viewport, so rows report themselves in and out of it; the
    /// tracker is a class behind @State on purpose: identity survives re-renders, and mutations
    /// don't invalidate the view (a Set<Int64> churning per scroll must not re-render the screen).
    @State private var visibleRows = VisibleRows()
    @State private var scrollAnchor: [Int64: Int64] = [:]
    /// The anchor to restore after a tab switch; consumed by the list's scroll proxy.
    @State private var restoreAnchor: Int64?

    private final class VisibleRows {
        var ids: Set<Int64> = []
    }

    // Android's tab model: each tab owns its filter, min level and density, persisted across runs.
    // The tabs live in `LogTabsStore`, shared by both halves of an open Duo.
    private var activeTabId: Int64 { store.activeTab[role] ?? 0 }
    @State private var tabForm: TabFormMode?
    /// The other half asked this one to show a moment; the banner offers the way back to live.
    @State private var jump: Jump?
    /// The beside-the-host event card the developer closed.
    @State private var dismissedEventId: String?
    /// The split button's one-time explanation, shown the first time the Duo is open.
    @AppStorage(Prefs.duoSplitHintShownKey) private var splitHintShown = false

    private struct Jump: Equatable {
        let timeMs: Int64
        let fromTab: String
        let anchorId: Int64
    }

    private enum TabFormMode: Identifiable {
        case new
        case edit(Int64)

        var id: Int64 {
            switch self {
            case .new: return -1
            case .edit(let tabId): return tabId
            }
        }
    }

    init(
        core: LogSenseCore,
        onDone: (() -> Void)?,
        store: LogTabsStore,
        role: LogsPaneRole = .primary,
        isSplit: Bool = false,
        splitEnabled: Binding<Bool>? = nil
    ) {
        self.core = core
        self.onDone = onDone
        self.state = core.state
        self.feed = core.feed
        self.store = store
        self.role = role
        self.isSplit = isSplit
        self.splitEnabled = splitEnabled
        // A fold or unfold rebuilds the pane; it comes back with what it had, at the line it was on.
        let memory = store.memory[role] ?? LogTabsStore.PaneMemory()
        let tab = store.tab(for: role)
        _query = State(initialValue: tab?.filter.query ?? "")
        _appliedQuery = State(initialValue: tab?.filter.query ?? "")
        _minLevel = State(initialValue: tab?.filter.minLevel ?? .debug)
        _viewMode = State(initialValue: tab?.viewMode ?? .standard)
        _findActive = State(initialValue: memory.findActive)
        _findQuery = State(initialValue: memory.findQuery)
        _autoscroll = State(initialValue: memory.autoscroll)
        _wrap = State(initialValue: memory.wrap)
        _restoreAnchor = State(initialValue: memory.autoscroll ? nil : memory.topLineId)
    }

    // ponytail: filtering runs on the main thread over the whole buffer, on each ~1 Hz publish
    // and once per debounced query. Rendering is windowed (renderSlice), so this scan is the
    // only full-buffer cost left; move it off-main behind a task if it ever shows in a profile.
    private func filteredRows(minLevel: LogLevel, query: String) -> [LogEntry] {
        let visible = feed.snapshot
        if query.isEmpty && minLevel == .debug { return visible }
        let predicate = LogQuery.compile(LogFilter(minLevel: minLevel, query: query))
        return visible.filter(predicate)
    }

    /// The active tab's live view of the stream, ignoring any freeze.
    private var liveFiltered: [LogEntry] {
        filteredRows(minLevel: minLevel, query: appliedQuery)
    }

    private var displayed: [LogEntry] {
        if store.pausedTabs.contains(activeTabId) { return store.frozen[activeTabId] ?? [] }
        return liveFiltered
    }

    private var isActiveTabPaused: Bool { store.pausedTabs.contains(activeTabId) }

    private var matcher: TextMatcher { TextMatcher.from(findQuery) }

    private var matchIds: [Int64] {
        guard findActive, findQuery.isActive else { return [] }
        let m = matcher
        // Android's tested rule: match against "tag message" joined by one space — per entry,
        // not per occurrence.
        return displayed.filter { m.matches("\($0.tag) \($0.message)") }.map(\.id)
    }

    /// Regular width shows the line inspector beside the stream; compact presents a sheet.
    private var isRegular: Bool { hSize == .regular }

    /// The line inspector sits beside the stream only with room to spare: one regular-width pane.
    /// Halves and the seated layout open it as a sheet.
    private var sideInspector: Bool { isRegular && !isSplit && !duo.seated }

    private var sheetSelection: Binding<LogEntry?> {
        Binding(
            get: { sideInspector ? nil : selectedEntry },
            set: { selectedEntry = $0 }
        )
    }

    /// Hits keyed by the line they matched, for the gutter/pill on rows. Muted signals are
    /// dropped here too, like Android — a mute hides pills and stripes, not just tab rows.
    private var hitsByEntryId: [Int64: SignalHit] {
        let muted = Prefs.mutedSignals()
        var out: [Int64: SignalHit] = [:]
        for hit in state.signalHits where !muted.contains(hit.signal.id) {
            if let id = hit.entryId { out[id] = hit }
        }
        return out
    }

    /// SwiftUI's List re-diffs every identified row on each publish — at the 50k buffer cap
    /// that alone is the lag. Rendering is windowed to the newest rows, filtered or not (a
    /// one-letter filter matches nearly the whole buffer); the full buffer still feeds the
    /// status counts, filter, find, export and crash context. Find stays unwindowed so every
    /// match it steps to is in the list.
    private static let renderWindow = 2_000

    var body: some View {
        let all = displayed
        let matches = matchIds
        let slice = all.renderSlice(window: Self.renderWindow, findActive: findActive, anchorId: jump?.anchorId)
        stack {
            arranged(
                stream: VStack(spacing: 0) {
                    // Seated, the upper half is for reading: one header row, and the deck below
                    // carries find, level and pause — so the filter field steps aside too.
                    if duo.seated, role == .primary {
                        seatedHeader(rows: all)
                    } else {
                        header(rows: all)
                    }
                    if wideOpen {
                        // Open and full width: tabs and filter share one bar, the pills before
                        // the crease and the filter after it, so no control sits on the fold.
                        FoldSplit(showsCrease: false) { tabsRow } second: { filterBand }
                            .frame(height: 52)
                            .background(bandTint)
                            .overlay(alignment: .bottom) { hairline }
                        if !splitHintShown { splitHint.padding(.top, 8) }
                    } else {
                        tabsRow
                        if !(duo.seated && role == .primary) { filterBand }
                    }
                    if DebuggerState.logsDiverted || state.storeSilent { debuggerBanner.padding(.top, 8) }
                    if isActiveTabPaused { frozenTabBanner.padding(.top, 8) }
                    if state.status == .paused { pausedBanner.padding(.top, 8) }
                    if let jump { jumpBanner(jump).padding(.top, 8) }
                    content(rows: slice.rows, hiddenOlder: slice.hiddenOlder, matches: matches)
                },
                deck: SeatedDeck(
                    core: core,
                    findQuery: $findQuery,
                    findIndex: $findIndex,
                    matchCount: matches.count,
                    minLevel: $minLevel,
                    autoscroll: $autoscroll,
                    onClear: clearBuffer
                )
            )
            // The custom header replaces the bar visually, but the title still feeds the pushed
            // screen's "‹ Logs" back item — continuing the task pushes, per the design.
            // With the bar shown only to host rail items, a title would repeat the header's large
            // one; the cost is a plain "Back" on pushed screens while on the rail.
            // Halves share the host's bar, which is there only for the rail's Single Stream button.
            .navigationTitle(railActions || isSplit ? "" : "Logs")
            .navigationBarTitleDisplayMode(railActions || isSplit ? .inline : .automatic)
            .toolbar { if railActions { railToolbar(rows: all) } }
            .toolbar(railActions ? .automatic : .hidden, for: .navigationBar)
            .navigationDestination(isPresented: $showSettings) {
                SettingsScreen(core: core)
            }
            .safeAreaInset(edge: .bottom) {
                // Seated, find lives on the deck and stays open.
                if findActive, !duo.seated {
                    findBar(matches: matches)
                } else if let event = freshEvent {
                    eventCard(event)
                }
            }
        }
        .onAppear { selectTab(activeTabId) }
        // The other half swapped tabs with this one: take on the new tab's filter and density.
        .onChange(of: activeTabId) { _ in loadActiveTab() }
        .onChange(of: duo.seated) { seated in
            // The deck is a find bar that stays open; leaving the desk keeps the query.
            if seated { findActive = true }
        }
        .onChange(of: store.sync) { request in
            guard let request, request.target == role else { return }
            store.sync = nil
            show(moment: request.timeMs, from: request.fromTab)
        }
        .onChange(of: autoscroll) { on in
            if on { jump = nil }
        }
        .onDisappear {
            // A fold rebuilds the panes; this is what the rebuilt one starts from.
            store.memory[role] = LogTabsStore.PaneMemory(
                findActive: findActive,
                findQuery: findQuery,
                autoscroll: autoscroll,
                wrap: wrap,
                topLineId: visibleRows.ids.min()
            )
        }
        .onChange(of: state.revealTimeMs) { timeMs in
            // A crash report's "Show in Logs": everything around that moment, on All.
            guard let timeMs, role == .primary else { return }
            state.revealTimeMs = nil
            selectTab(0)
            show(moment: timeMs, from: "Crash report")
        }
        .onChange(of: state.revealEntryId) { id in
            // A Signals-tab jump: land on All (it shows everything), stop following the tail,
            // and open the line's sheet/inspector. One pane answers it — the primary one.
            guard let id, role == .primary else { return }
            state.revealEntryId = nil
            selectTab(0)
            autoscroll = false
            revealTarget = id
            if let entry = feed.snapshot.first(where: { $0.id == id }) {
                jump = Jump(timeMs: entry.timeMs, fromTab: "Signals", anchorId: id)
                selectedEntry = entry
            }
        }
        .task(id: query) {
            // Clearing applies at once (it's the cheap direction); typing settles first.
            if !query.isEmpty {
                do { try await Task.sleep(nanoseconds: 150_000_000) } catch { return }
            }
            appliedQuery = query
        }
        .onChange(of: appliedQuery) { _ in writeBackActiveTab() }
        .onChange(of: minLevel) { _ in writeBackActiveTab() }
        .onChange(of: viewMode) { _ in writeBackActiveTab() }
        .sheet(item: sheetSelection) { entry in
            LogLineSheet(entry: entry, hit: hitsByEntryId[entry.id]) { tag in
                query = "tag:\"\(tag)\""
            }
        }
        .sheet(item: $tabForm) { mode in
            switch mode {
            case .new:
                TabFormSheet(title: "New Tab", confirm: "Add", initial: nil, defaultName: nextDefaultTabName()) { draft in
                    let next = SavedFilter(
                        id: (store.tabs.map(\.id).max() ?? 0) + 1,
                        name: draft.name,
                        filter: LogFilter(minLevel: draft.minLevel, query: draft.query),
                        viewMode: draft.viewMode
                    )
                    store.tabs.append(next)
                    store.save()
                    selectTab(next.id)
                }
            case .edit(let tabId):
                TabFormSheet(
                    title: "Tab Settings",
                    confirm: "Done",
                    initial: store.tabs.first { $0.id == tabId }
                ) { draft in
                    guard let index = store.tabs.firstIndex(where: { $0.id == tabId }) else { return }
                    store.tabs[index].name = draft.name
                    store.tabs[index].filter = LogFilter(minLevel: draft.minLevel, query: draft.query)
                    store.tabs[index].viewMode = draft.viewMode
                    store.save()
                    if tabId == activeTabId { selectTab(tabId) }
                }
            }
        }
    }

    /// Halves share the host's navigation stack: one stack reserves the side rail's column once,
    /// at the display's edge — two would put the left half's in the middle of the display.
    @ViewBuilder
    private func stack(@ViewBuilder _ content: () -> some View) -> some View {
        if isSplit {
            content()
        } else {
            NavigationStack { content() }
        }
    }

    /// Seated (half-open on a desk, fold running across): the stream on the upper half, the
    /// controls on the flat lower half. Everywhere else, just the stream.
    @ViewBuilder
    private func arranged(stream: some View, deck: SeatedDeck) -> some View {
        if duo.seated, role == .primary {
            FoldSplit {
                stream
            } second: {
                deck
            }
        } else {
            stream
        }
    }

    /// Scrolls this half to the line nearest `timeMs` — another half's tap, or a crash report's
    /// "Show in Logs" — and holds it there until Back to live.
    private func show(moment timeMs: Int64, from source: String) {
        let rows = displayed
        guard let index = rows.nearestIndex(toTimeMs: timeMs) else { return }
        autoscroll = false
        jump = Jump(timeMs: timeMs, fromTab: source, anchorId: rows[index].id)
        revealTarget = rows[index].id
    }

    /// Beside the host: the newest analytics event captured since docking — tap in the app, see
    /// that it fired, without opening the Events tab.
    private var freshEvent: StoredEvent? {
        guard state.besideHost, role == .primary, let event = state.events.first,
              event.record.timestamp >= state.besideSinceMs, event.id != dismissedEventId
        else { return nil }
        return event
    }

    private func eventCard(_ event: StoredEvent) -> some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(event.record.name)
                    .font(.system(size: 14, weight: .semibold, design: .monospaced))
                    .lineLimit(1)
                Text(event.record.params.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: ", "))
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 6)
            Button("Open") { state.requestedTab = .events }
                .font(.system(size: 13, weight: .semibold))
            Button {
                dismissedEventId = event.id
            } label: {
                Image(systemName: "xmark").font(.system(size: 12, weight: .semibold))
            }
            .foregroundStyle(.secondary)
            .accessibilityLabel("Dismiss event")
        }
        .padding(12)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(.white.opacity(0.16), lineWidth: 0.5))
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }

    private func jumpBanner(_ jump: Jump) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "clock.arrow.circlepath").foregroundStyle(Color.accentColor)
            Text("At \(Format.time(jump.timeMs)), from \(jump.fromTab)")
                .font(.system(size: 13, weight: .semibold))
                .lineLimit(1)
            Spacer(minLength: 8)
            Button {
                autoscroll = true
            } label: {
                Text("Back to live")
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.accentColor, in: Capsule())
            }
        }
        .padding(12)
        .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
    }

    /// A tap in one half shows the same moment in the other; elsewhere it opens the line.
    private func tapped(_ entry: LogEntry) {
        if isSplit {
            store.sync = LogTabsStore.SyncRequest(target: role.other, timeMs: entry.timeMs, fromTab: activeTabName)
        } else {
            selectedEntry = entry
        }
    }

    private func loadActiveTab() {
        guard let tab = store.tab(for: role) else { return }
        query = tab.filter.query
        appliedQuery = tab.filter.query
        minLevel = tab.filter.minLevel
        viewMode = tab.viewMode
    }

    /// A real wipe, per the design: signal hits die with the buffer they point into, and frozen
    /// tabs thaw — their snapshots would lie.
    private func clearBuffer() {
        core.clearBuffer()
        store.pausedTabs = []
        store.frozen = [:]
        jump = nil
    }

    // MARK: tabs

    private func selectTab(_ id: Int64) {
        guard let tab = store.tabs.first(where: { $0.id == id }) else { return }
        let switching = id != activeTabId
        if switching {
            // Remember where the outgoing tab was; the topmost visible row is its position.
            if let top = visibleRows.ids.min() {
                scrollAnchor[activeTabId] = top
            }
            visibleRows.ids.removeAll()
        }
        store.select(id, for: role, split: isSplit)
        query = tab.filter.query
        appliedQuery = tab.filter.query
        minLevel = tab.filter.minLevel
        viewMode = tab.viewMode
        if switching, !autoscroll, let anchor = scrollAnchor[id] {
            restoreAnchor = anchor
        }
    }

    /// The active tab owns the filter state — edits write through and persist, like Android.
    private func writeBackActiveTab() {
        guard let index = store.tabs.firstIndex(where: { $0.id == activeTabId }) else { return }
        store.tabs[index].filter = LogFilter(minLevel: minLevel, query: appliedQuery)
        store.tabs[index].viewMode = viewMode
        store.save()
    }

    private func closeTab(_ id: Int64) {
        guard id != 0 else { return }
        store.tabs.removeAll { $0.id == id }
        store.pausedTabs.remove(id)
        store.frozen[id] = nil
        store.save()
        if activeTabId == id { selectTab(0) }
    }

    /// Freezes what that tab shows right now; capture keeps running for every tab.
    private func pauseTab(_ id: Int64) {
        guard let tab = store.tabs.first(where: { $0.id == id }) else { return }
        store.frozen[id] = id == activeTabId
            ? liveFiltered
            : filteredRows(minLevel: tab.filter.minLevel, query: tab.filter.query)
        store.pausedTabs.insert(id)
    }

    private func resumeTab(_ id: Int64) {
        store.pausedTabs.remove(id)
        store.frozen[id] = nil
    }

    /// "Log 1", "Log 2", … — the first number not already taken, so the name is never mandatory.
    private func nextDefaultTabName() -> String {
        let names = Set(store.tabs.map(\.name))
        var n = 1
        while names.contains("Log \(n)") { n += 1 }
        return "Log \(n)"
    }

    /// "Starting a custom tab from everything is the common way to make one."
    private func duplicateTab(_ tab: SavedFilter) {
        // Duplicating a duplicate strips the suffix back to the base first, so names count
        // up ("All copy", "All copy 2", …) instead of stacking ("All copy copy copy").
        let source = tab.id == 0 ? "All" : tab.name
        let base = source.replacingOccurrences(
            of: #"\s+copy(\s+\d+)?$"#, with: "", options: .regularExpression
        )
        var name = "\(base) copy"
        var n = 2
        while store.tabs.contains(where: { $0.name == name }) {
            name = "\(base) copy \(n)"
            n += 1
        }
        let next = SavedFilter(
            id: (store.tabs.map(\.id).max() ?? 0) + 1,
            name: name,
            filter: tab.filter,
            viewMode: tab.viewMode
        )
        store.tabs.append(next)
        store.save()
        selectTab(next.id)
    }

    /// A full-bleed band, per rev 4.1: the tab strip reads as its own bar — tinted, hairlined —
    /// not as pills floating in the header.
    private var tabsRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 7) {
                ForEach(store.tabs, id: \.id) { tab in
                    let isActive = tab.id == activeTabId
                    let isPaused = store.pausedTabs.contains(tab.id)
                    Button {
                        selectTab(tab.id)
                    } label: {
                        HStack(spacing: 5) {
                            if isPaused {
                                Image(systemName: "pause.fill").font(.system(size: 9, weight: .bold))
                            }
                            // Open in the other half — tapping it swaps the halves.
                            if isSplit, store.activeTab[role.other] == tab.id {
                                Image(systemName: "rectangle.split.2x1")
                                    .font(.system(size: 10, weight: .semibold))
                                    .accessibilityLabel("open in the other half")
                            }
                            // A tab holding a filter carries a small funnel, so narrowed tabs
                            // are visible without opening them.
                            if !tab.filter.query.isEmpty || tab.filter.minLevel != .debug {
                                Image(systemName: "line.3.horizontal.decrease")
                                    .font(.system(size: 9, weight: .bold))
                                    .opacity(0.55)
                            }
                            Text(tab.name)
                                .font(.system(size: 13, weight: isActive || isPaused ? .semibold : .medium))
                                .lineLimit(1)
                                .frame(maxWidth: 150)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.horizontal, 13)
                        .padding(.vertical, 6)
                        .background(
                            isPaused ? Color(hex: 0xFF9F0A) : (isActive ? Color.accentColor : Color(.tertiarySystemFill)),
                            in: Capsule()
                        )
                        .foregroundStyle(isPaused ? .black : (isActive ? .white : .primary))
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        // "All is the raw stream and has nothing to configure" — no settings,
                        // no rename, no Close for id 0.
                        if tab.id != 0 {
                            Button {
                                tabForm = .edit(tab.id)
                            } label: {
                                Label("Tab Settings…", systemImage: "line.3.horizontal")
                            }
                        }
                        if isPaused {
                            Button {
                                resumeTab(tab.id)
                            } label: {
                                Label("Resume This Tab", systemImage: "play.fill")
                            }
                        } else {
                            Button {
                                pauseTab(tab.id)
                            } label: {
                                Label("Pause This Tab", systemImage: "pause.fill")
                            }
                        }
                        Button {
                            duplicateTab(tab)
                        } label: {
                            Label(tab.id == 0 ? "Duplicate as New Tab" : "Duplicate", systemImage: "plus.square.on.square")
                        }
                        if tab.id != 0 {
                            Button(role: .destructive) {
                                closeTab(tab.id)
                            } label: {
                                Label("Close Tab", systemImage: "trash")
                            }
                        }
                    }
                }
                Button {
                    tabForm = .new
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 12, weight: .semibold))
                        .padding(.horizontal, 11)
                        .padding(.vertical, 7)
                        .background(Color(.tertiarySystemFill), in: Capsule())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)
            .padding(.bottom, 9)
        }
        .background(wideOpen ? Color.clear : bandTint)
        .overlay(alignment: .bottom) { if !wideOpen { hairline } }
    }

    /// The per-tab freeze banner: what is waiting, and the way back.
    private var frozenTabBanner: some View {
        let buffered = max(0, liveFiltered.count - (store.frozen[activeTabId]?.count ?? 0))
        return HStack(spacing: 10) {
            Image(systemName: "pause.fill").foregroundStyle(Color(hex: 0xFF9F0A))
            VStack(alignment: .leading, spacing: 2) {
                Text("Frozen — this tab is paused")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(Color(hex: 0xFF9F0A))
                // Android shows the buffered count only once there is one.
                Text(buffered > 0
                    ? "+\(buffered.formatted()) buffered · other store.tabs keep recording"
                    : "Other store.tabs keep recording")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                resumeTab(activeTabId)
            } label: {
                Text("Resume")
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color(hex: 0xFF9F0A), in: Capsule())
            }
        }
        .padding(12)
        .background(Color(hex: 0xFF9F0A).opacity(0.15), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Color(hex: 0xFF9F0A).opacity(0.4), lineWidth: 0.5))
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
    }

    // MARK: header

    /// iPhone Duo, open: one pane or two, and LogSense beside the host instead of over it.
    @ViewBuilder
    private var duoSection: some View {
        if splitEnabled != nil || duo.isOpen || state.besideHost {
            Divider()
            if let splitEnabled {
                Toggle(isOn: splitEnabled) {
                    Label("Split Across Fold", systemImage: "rectangle.split.2x1")
                }
            }
            if state.besideHost {
                Button {
                    LogSenseWindow.expand()
                } label: {
                    Label("Expand to Full Screen", systemImage: "arrow.up.left.and.arrow.down.right")
                }
            } else if duo.isOpen {
                Button {
                    LogSenseWindow.dockBeside(division: duo.divisionInWindow)
                } label: {
                    Label("Open Beside \(core.hostName)", systemImage: "rectangle.righthalf.inset.filled")
                }
            }
        }
    }

    /// Closed iPhone Duo: tabs and toolbars live on the right-hand rail, so the header's actions
    /// move there too and the stream starts right under the title. Halves keep their own buttons.
    private var railActions: Bool { duo.verticalRail && !isSplit }

    /// The tab strip's tint, shared by the whole tabs-and-filter bar when it spans the fold.
    private var bandTint: Color {
        scheme == .dark ? Color(hex: 0x1C1C1E).opacity(0.55) : Color(hex: 0xF2F2F7).opacity(0.7)
    }

    /// A bar's bottom edge. Not `Divider()`: inside the fold split's HStack a Divider turns vertical.
    private var hairline: some View {
        Rectangle().fill(Color(.separator)).frame(height: 0.5)
    }

    /// Open iPhone Duo, one stream across both sides of the fold — the default open layout.
    private var wideOpen: Bool { duo.isOpen && !isSplit && !duo.seated && role == .primary }

    /// Once, the first time the Duo is open: what the outlined split button on the rail does.
    private var splitHint: some View {
        HStack(spacing: 10) {
            Image(systemName: "rectangle.split.2x1").foregroundStyle(Color.accentColor)
            Text("Compare two tabs: the split button on the rail puts a second one on the other side of the fold.")
                .font(.system(size: 13))
            Spacer(minLength: 8)
            Button {
                splitHintShown = true
            } label: {
                Image(systemName: "xmark").font(.system(size: 12, weight: .semibold))
            }
            .foregroundStyle(.secondary)
            .accessibilityLabel("Dismiss tip")
        }
        .padding(12)
        .background(Color.accentColor.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
    }

    /// Like Android's app bar pause: global — every tab stops. Per-tab freezing lives in the
    /// overflow menu and the tab's long-press. Global, so on an open Duo it sits once, on the left.
    @ViewBuilder
    private var pauseButton: some View {
        if role == .secondary {
            EmptyView()
        } else if state.status == .paused {
            Button {
                core.resume()
            } label: {
                Image(systemName: "play.fill")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Color(hex: 0xFF9F0A))
                    .frame(width: 34, height: 34)
                    .background(Color(hex: 0xFF9F0A).opacity(0.22), in: Circle())
            }
            .accessibilityLabel("Resume capture")
        } else {
            headerButton("pause.fill", label: "Pause capture") { core.pause() }
        }
    }

    private func toggleFind() {
        findActive.toggle()
        if !findActive { findQuery = SearchQuery() }
    }

    @ViewBuilder
    private func overflowItems(rows: [LogEntry]) -> some View {
        Section(activeTabName) {
            Picker("Density", selection: $viewMode) {
                Text("Standard").tag(ViewMode.standard)
                Text("Compact").tag(ViewMode.compact)
                Text("Raw").tag(ViewMode.raw)
            }
            Toggle("Wrap Long Lines", isOn: $wrap)
            Toggle("Autoscroll", isOn: $autoscroll)
            if isActiveTabPaused {
                Button {
                    resumeTab(activeTabId)
                } label: {
                    Label("Resume This Tab", systemImage: "play.fill")
                }
            } else {
                Button {
                    pauseTab(activeTabId)
                } label: {
                    Label("Pause This Tab", systemImage: "pause.fill")
                }
            }
        }
        if role == .primary { duoSection }
        Divider()
        ShareLink(item: shareText(rows)) { Label("Export…", systemImage: "square.and.arrow.up") }
        Button(role: .destructive) {
            clearBuffer()
        } label: {
            Label("Clear Buffer", systemImage: "trash")
        }
        Divider()
        Button {
            showSettings = true
        } label: {
            Label("Settings…", systemImage: "gearshape")
        }
    }

    /// The same actions as rail items (icon-only, so each carries its label for VoiceOver).
    @ToolbarContentBuilder
    private func railToolbar(rows: [LogEntry]) -> some ToolbarContent {
        ToolbarItemGroup(placement: .primaryAction) {
            // Open iPhone Duo: the two Duo-only moves, one tap each.
            if let splitEnabled, duo.isOpen {
                Button {
                    splitEnabled.wrappedValue = true
                    splitHintShown = true
                } label: {
                    Label("Split Across Fold", systemImage: "rectangle.split.2x1")
                }
            }
            if state.besideHost {
                Button {
                    LogSenseWindow.expand()
                } label: {
                    Label("Expand to Full Screen", systemImage: "arrow.up.left.and.arrow.down.right")
                }
            }
            if state.status == .paused {
                Button { core.resume() } label: { Label("Resume capture", systemImage: "play.fill") }
            } else {
                Button { core.pause() } label: { Label("Pause capture", systemImage: "pause.fill") }
            }
            Button { toggleFind() } label: { Label("Find", systemImage: "magnifyingglass") }
            Menu {
                overflowItems(rows: rows)
            } label: {
                Label("More", systemImage: "ellipsis")
            }
        }
    }

    /// Seated: back, title, live status and the overflow menu in a single row.
    private func seatedHeader(rows: [LogEntry]) -> some View {
        HStack(spacing: 10) {
            if let onDone {
                BackButton(label: core.hostName, action: onDone)
            }
            Text("Logs").font(.system(size: 17, weight: .semibold))
            statusRow(rows: rows)
            Spacer(minLength: 8)
            Menu {
                overflowItems(rows: rows)
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 15, weight: .semibold))
                    .frame(width: 34, height: 34)
                    .background(Color(.tertiarySystemFill), in: Circle())
            }
            .accessibilityLabel("More")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }

    private func header(rows: [LogEntry]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            // One nav row: the labelled back item to the host and the trailing actions share it;
            // the large title sits alone beneath. Stacking a second header row would cost 44pt on
            // a screen whose job is fitting log lines.
            HStack(alignment: .center) {
                if let onDone {
                    BackButton(label: core.hostName, action: onDone)
                }
                Spacer()
                // On the Duo's side rail the system hosts these as toolbar items instead.
                if !railActions {
                    HStack(spacing: 8) {
                        if isSplit, role == .primary, !duo.verticalRail, let splitEnabled {
                            // Without a side rail there is nowhere else for the way back: the same
                            // glyph, filled, in the left half's header.
                            Button {
                                splitEnabled.wrappedValue = false
                            } label: {
                                Image(systemName: "rectangle.split.2x1.fill")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(.white)
                                    .frame(width: 34, height: 34)
                                    .background(Color.accentColor, in: Circle())
                            }
                            .accessibilityLabel("Single Stream")
                        }
                        pauseButton
                        headerButton("magnifyingglass", label: "Find") { toggleFind() }
                        // The menu titles itself with the tab's name — density and line behavior
                        // apply to this tab alone. Every root's overflow ends with LogSense Settings.
                        Menu {
                            overflowItems(rows: rows)
                        } label: {
                            Image(systemName: "ellipsis")
                                .font(.system(size: 15, weight: .semibold))
                                .frame(width: 34, height: 34)
                                .background(Color(.tertiarySystemFill), in: Circle())
                        }
                        .accessibilityLabel("More")
                    }
                }
            }
            // Root titled by tab: the back item carries the host name, freeing the title slot.
            // The right half is titled by the tab it shows. Its caption line stays, empty, so both
            // titles sit on one line across the fold.
            VStack(alignment: .leading, spacing: 5) {
                Text("LOGSENSE")
                    .font(.system(size: 11.5, weight: .semibold))
                    .kerning(1)
                    .foregroundStyle(.secondary)
                    .opacity(role == .secondary ? 0 : 1)
                    .accessibilityHidden(role == .secondary)
                if railActions {
                    // On the rail the live count rides beside the title — one row less above the stream.
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        Text("Logs").font(.system(size: 34, weight: .bold))
                        statusRow(rows: rows)
                    }
                } else {
                    Text(role == .secondary ? activeTabName : "Logs")
                        .font(.system(size: 34, weight: .bold))
                        .lineLimit(1)
                }
            }
            if !railActions { statusRow(rows: rows) }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 6)
    }

    private func headerButton(_ systemImage: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 14, weight: .semibold))
                .frame(width: 34, height: 34)
                .background(Color(.tertiarySystemFill), in: Circle())
        }
        .accessibilityLabel(label)
    }

    private func statusRow(rows: [LogEntry]) -> some View {
        HStack(spacing: 7) {
            switch state.status {
            case .waiting:
                dot(.orange, pulsing: false)
                Text("Connecting").foregroundStyle(.orange).fontWeight(.semibold)
                Text("· 0 lines")
            case .live:
                dot(.green, pulsing: true)
                Text("Live").foregroundStyle(.green).fontWeight(.semibold)
                if !store.pausedTabs.isEmpty {
                    let recording = store.tabs.count - store.tabs.filter { store.pausedTabs.contains($0.id) }.count
                    Text("· \(recording) of \(store.tabs.count) store.tabs recording")
                } else if isFiltering {
                    Text("· \(rows.count.formatted()) of \(feed.snapshot.count.formatted()) in “\(activeTabName)”")
                } else {
                    Text("· \(feed.totalReceived.formatted()) lines")
                }
            case .paused:
                dot(.orange, pulsing: false)
                Text("Paused").foregroundStyle(.orange).fontWeight(.semibold)
                Text("· \(feed.snapshot.count.formatted()) lines · \(feed.bufferedWhilePaused.formatted()) buffered")
            }
        }
        .font(.system(size: 12.5, weight: .medium))
        .foregroundStyle(.secondary)
    }

    private func dot(_ color: Color, pulsing: Bool) -> some View {
        PulsingDot(color: color, active: pulsing)
    }

    private var isFiltering: Bool { !appliedQuery.isEmpty || minLevel != .debug }

    private var activeTabName: String {
        store.tabs.first { $0.id == activeTabId }?.name ?? "All"
    }

    /// The filter belongs to a tab, so its field sits below the tab row, on the tab's own
    /// surface, and names the tab it narrows. The min-level chip sits beside it.
    private var filterBand: some View {
        HStack(spacing: 10) {
            HStack(spacing: 7) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                TextField("Filter “\(activeTabName)”", text: $query)
                    .font(.system(size: 15, design: query.isEmpty ? .default : .monospaced))
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                if !query.isEmpty {
                    Text("in “\(activeTabName)”")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Button {
                        query = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.horizontal, 10)
            .frame(height: 34)
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(isFiltering ? Color.accentColor.opacity(0.15) : Color(.tertiarySystemFill))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(isFiltering ? Color.accentColor.opacity(0.45) : .clear, lineWidth: 0.5)
            )
            Menu {
                Picker("Minimum level", selection: $minLevel) {
                    Text("All (D+)").tag(LogLevel.debug)
                    Text("Info (I+)").tag(LogLevel.info)
                    Text("Notice (N+)").tag(LogLevel.notice)
                    Text("Error (E+)").tag(LogLevel.error)
                    Text("Fault (F)").tag(LogLevel.fault)
                }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "line.3.horizontal.decrease")
                    Text(verbatim: minLevel == .debug ? "All levels" : "\(minLevel.letter)+")
                }
                .font(.system(size: 13, weight: .medium))
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 9)
        .padding(.bottom, 8)
        .overlay(alignment: .bottom) { if !wideOpen { hairline } }
    }

    // MARK: banners & states

    private var debuggerBanner: some View { DivertedStreamCard() }

    private var pausedBanner: some View {
        HStack(spacing: 10) {
            Image(systemName: "pause.fill").foregroundStyle(.orange)
            VStack(alignment: .leading, spacing: 2) {
                Text("Capture paused")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(.orange)
                Text("New lines are buffered, not shown. Resume to catch up.")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                core.resume()
            } label: {
                Text("Resume")
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.orange, in: Capsule())
            }
        }
        .padding(12)
        .background(Color.orange.opacity(0.15), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Color.orange.opacity(0.4), lineWidth: 0.5))
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
    }

    @ViewBuilder
    private func emptyState(rows: [LogEntry]) -> some View {
        if state.status == .waiting {
            // A spinner, not an empty state: the first walk of the log store takes seconds in
            // a chatty host, and "no data" during that window reads as broken.
            VStack(spacing: 14) {
                ProgressView()
                Text("Loading the log stream…")
                    .font(.system(size: 15, weight: .semibold))
                Text("The first pass over the process's log history can take a few seconds.")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 280)
            }
        } else if isFiltering {
            EmptyStateView(
                icon: "line.3.horizontal.decrease",
                title: "No lines match" + (appliedQuery.isEmpty ? "" : " “\(appliedQuery)”"),
                body: "Capture is still running. Clear the filter, or lower the minimum level.",
                actionLabel: "Clear filter",
                action: { query = ""; minLevel = .debug }
            )
        } else {
            EmptyStateView(
                icon: "list.bullet.rectangle",
                title: "Nothing here yet",
                body: "Cleared. New lines appear as they arrive.",
                actionLabel: nil, action: nil
            )
        }
    }

    // MARK: content

    @ViewBuilder
    private func content(rows: [LogEntry], hiddenOlder: Int, matches: [Int64]) -> some View {
        if rows.isEmpty {
            emptyState(rows: rows).frame(maxHeight: .infinity)
        } else {
            let hits = hitsByEntryId
            HStack(spacing: 0) {
                ScrollViewReader { proxy in
                    List {
                        if hiddenOlder > 0 {
                            Text(isFiltering
                                 ? "Latest \(rows.count.formatted()) of \((rows.count + hiddenOlder).formatted()) matches shown — narrow the filter to see older ones"
                                 : "Latest \(rows.count.formatted()) shown · \(hiddenOlder.formatted()) older lines stay in the buffer — filter to search them")
                                .font(.system(size: 11.5))
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .center)
                                .padding(.vertical, 6)
                                .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                                .listRowSeparator(.hidden)
                                .listRowBackground(Color.clear)
                        }
                        ForEach(rows) { entry in
                            row(entry, hit: hits[entry.id])
                                .id(entry.id)
                                .onAppear { visibleRows.ids.insert(entry.id) }
                                .onDisappear { visibleRows.ids.remove(entry.id) }
                                .contentShape(Rectangle())
                                .onTapGesture { tapped(entry) }
                                .modifier(InspectLineMenu(enabled: isSplit) { selectedEntry = entry })
                                .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                                .listRowSeparator(viewMode == .standard ? .visible : .hidden)
                                .listRowBackground(rowBackground(entry, matches: matches, hit: hits[entry.id]))
                        }
                        // Fixed-size sentinel: scrolling to the last real row is unreliable when
                        // rows wrap to variable heights — the sentinel always lands at the bottom.
                        Color.clear
                            .frame(height: 1)
                            .id(Self.bottomSentinel)
                            .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    }
                    .listStyle(.plain)
                    .opacity(isActiveTabPaused ? 0.62 : 1)
                    .environment(\.defaultMinListRowHeight, 10)
                    .onAppear {
                        // First layout pass hasn't happened yet; scroll on the next runloop turn.
                        if autoscroll {
                            DispatchQueue.main.async { proxy.scrollTo(Self.bottomSentinel, anchor: .bottom) }
                        } else if let anchor = restoreAnchor {
                            // Rebuilt by a fold: back to the line this pane was on. onChange never
                            // sees an initial value, so the first restore happens here.
                            DispatchQueue.main.async {
                                proxy.scrollTo(anchor, anchor: .top)
                                restoreAnchor = nil
                            }
                        }
                    }
                    // The last id, not the count: at the buffer cap the count stops changing while
                    // lines keep flowing.
                    .onChange(of: rows.last?.id) { _ in
                        if autoscroll, state.status != .paused {
                            proxy.scrollTo(Self.bottomSentinel, anchor: .bottom)
                        }
                    }
                    .onChange(of: autoscroll) { on in
                        if on {
                            withAnimation { proxy.scrollTo(Self.bottomSentinel, anchor: .bottom) }
                        }
                    }
                    .onChange(of: findIndex) { _ in
                        if findActive, matches.indices.contains(findIndex) {
                            withAnimation { proxy.scrollTo(matches[findIndex], anchor: .center) }
                        }
                    }
                    .onChange(of: revealTarget) { target in
                        if let target {
                            withAnimation { proxy.scrollTo(target, anchor: .center) }
                            revealTarget = nil
                        }
                    }
                    .onChange(of: restoreAnchor) { anchor in
                        // Restoring a tab's position — instant, no animation, like Android.
                        if let anchor {
                            proxy.scrollTo(anchor, anchor: .top)
                            restoreAnchor = nil
                        }
                    }
                    .overlay(alignment: .bottomTrailing) {
                        VStack(spacing: 10) {
                            fabButton("arrow.up") {
                                autoscroll = false
                                if let first = rows.first {
                                    withAnimation { proxy.scrollTo(first.id, anchor: .top) }
                                }
                            }
                            if !autoscroll {
                                fabButton("arrow.down") {
                                    // The onChange(of: autoscroll) hook does the scrolling.
                                    autoscroll = true
                                }
                                .transition(.scale(scale: 0.6).combined(with: .opacity))
                            }
                        }
                        // Value-scoped, one-shot — the safe kind of animation (see PulsingDot).
                        .animation(.spring(duration: 0.3), value: autoscroll)
                        .padding(.trailing, 18)
                        .padding(.bottom, 14)
                    }
                    .simultaneousGesture(DragGesture().onChanged { _ in autoscroll = false })
                    // A size change re-wraps every row, so a kept pixel offset lands on another
                    // line. Hold on to the top line instead and scroll back to it.
                    .background(GeometryReader { geometry in
                        Color.clear.onChange(of: geometry.size.width) { _ in
                            guard !autoscroll, let top = visibleRows.ids.min() else { return }
                            DispatchQueue.main.async { restoreAnchor = top }
                        }
                    })
                }
                if sideInspector, let selected = selectedEntry,
                   let index = rows.firstIndex(where: { $0.id == selected.id }) {
                    Divider()
                    LineInspector(
                        entry: selected,
                        hit: hitsByEntryId[selected.id],
                        neighbors: Array(rows[max(0, index - 2)...min(rows.count - 1, index + 2)]),
                        onFilterTag: { tag in query = "tag:\"\(tag)\"" },
                        onClose: { selectedEntry = nil }
                    )
                }
            }
        }
    }

    /// Android's tested precedence: selection/find first, then signal tint (which wins over
    /// fault/error), then fault, then error.
    private func rowBackground(_ entry: LogEntry, matches: [Int64], hit: SignalHit?) -> Color {
        if sideInspector, selectedEntry?.id == entry.id {
            return Color.accentColor.opacity(0.12)
        }
        if jump?.anchorId == entry.id {
            return Color.accentColor.opacity(0.12)
        }
        if findActive, matches.indices.contains(findIndex), matches[findIndex] == entry.id {
            return Color.accentColor.opacity(0.12)
        }
        if let hit {
            return hit.signal.category.color.opacity(0.10)
        }
        if entry.level == .fault {
            return entry.level.color(scheme).opacity(0.09)
        }
        if entry.level == .error {
            return entry.level.color(scheme).opacity(0.08)
        }
        return .clear
    }

    private static let bottomSentinel = "logsense.bottom"

    private func fabButton(_ systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .semibold))
                .frame(width: 44, height: 44)
                .background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().strokeBorder(.white.opacity(0.16), lineWidth: 0.5))
        }
    }

    @ViewBuilder
    private func row(_ entry: LogEntry, hit: SignalHit?) -> some View {
        // Seated, the upper half is read at a glance: one line per entry, as many as fit.
        let wrap = duo.seated ? false : self.wrap
        let base = Group {
            switch duo.seated ? .compact : viewMode {
            case .standard where isRegular && !isSplit && !duo.seated:
                // Regular width: the design's iPad stream row — time, chip, 92pt tag column, message.
                PadRow(entry: entry, wrap: wrap, highlight: findActive ? matcher : nil)
            case .standard:
                StandardRow(entry: entry, wrap: wrap, highlight: findActive ? matcher : nil, hit: hit)
            case .compact:
                CompactRow(entry: entry, wrap: wrap)
            case .raw:
                RawRow(entry: entry, wrap: wrap)
            }
        }
        if let hit {
            base.overlay(alignment: .leading) {
                Rectangle().fill(hit.signal.category.color).frame(width: 3)
            }
        } else if state.besideHost, let freshness = freshness(of: entry) {
            // Beside the host: what a tap on the other side just caused carries an accent edge
            // that fades over two seconds (it re-renders with each ~1 Hz publish).
            base.overlay(alignment: .leading) {
                Rectangle().fill(Color.accentColor.opacity(freshness)).frame(width: 3)
            }
        } else {
            base
        }
    }

    /// 1 for a line that just arrived, falling to 0 at two seconds; nil once it is old news.
    private func freshness(of entry: LogEntry) -> Double? {
        guard entry.timeMs >= state.besideSinceMs else { return nil }
        let age = Date().timeIntervalSince1970 * 1000 - Double(entry.timeMs)
        guard age < 2_000 else { return nil }
        return max(0.25, 1 - age / 2_000)
    }

    // MARK: find bar

    private func findBar(matches: [Int64]) -> some View {
        VStack(spacing: 6) {
            HStack(spacing: 10) {
                HStack(spacing: 8) {
                    TextField("Find in view", text: $findQuery.text)
                        .font(.system(size: 14, design: .monospaced))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onChange(of: findQuery.text) { _ in findIndex = 0 }
                    Text("\(matches.isEmpty ? 0 : findIndex + 1)/\(matches.count)")
                        .font(.system(size: 12, weight: .medium, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .layoutPriority(1)
                }
                .padding(.horizontal, 9)
                .frame(height: 34)
                .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 9))
                Button { step(-1, within: matches) } label: { Image(systemName: "chevron.up") }
                    .disabled(matches.isEmpty)
                Button { step(1, within: matches) } label: { Image(systemName: "chevron.down") }
                    .disabled(matches.isEmpty)
                Button("Done") {
                    findActive = false
                    findQuery = SearchQuery()
                }
                .fontWeight(.semibold)
            }
            HStack(spacing: 14) {
                findToggle("Aa", isOn: $findQuery.matchCase)
                findToggle("W", isOn: $findQuery.wholeWord)
                findToggle(".*", isOn: $findQuery.regex)
                Spacer()
            }
            .font(.system(size: 13, weight: .medium, design: .monospaced))
        }
        .padding(12)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(.white.opacity(0.16), lineWidth: 0.5))
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }

    private func findToggle(_ label: String, isOn: Binding<Bool>) -> some View {
        Button {
            isOn.wrappedValue.toggle()
            findIndex = 0
        } label: {
            Text(label)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(
                    isOn.wrappedValue ? Color.accentColor.opacity(0.25) : Color.clear,
                    in: RoundedRectangle(cornerRadius: 6)
                )
                .foregroundStyle(isOn.wrappedValue ? Color.accentColor : Color.secondary)
        }
    }

    private func step(_ delta: Int, within matches: [Int64]) {
        guard !matches.isEmpty else { return }
        findIndex = ((findIndex + delta) % matches.count + matches.count) % matches.count
    }

    /// Android's exact shared-line format: `HH:mm:ss.SSS pid-tid L tag: message`, every line
    /// newline-terminated including the last.
    private func shareText(_ rows: [LogEntry]) -> String {
        rows.map { entry in
            "\(Format.time(entry.timeMs)) \(entry.pid)-\(entry.tid) \(entry.level.letter) \(entry.tag): \(entry.message)\n"
        }
        .joined()
    }
}

/// "Inspect Line" on long-press — only where a tap does something else (the halves of an open Duo).
private struct InspectLineMenu: ViewModifier {
    let enabled: Bool
    let inspect: () -> Void

    func body(content: Content) -> some View {
        if enabled {
            content.contextMenu {
                Button(action: inspect) {
                    Label("Inspect Line", systemImage: "text.magnifyingglass")
                }
            }
        } else {
            content
        }
    }
}

// MARK: - tab form

/// One sheet for New Tab and Tab Settings — "two doors to the same place is a menu that has to
/// be read twice." Name, filter query, view and minimum level, every one scoped to this tab alone.
private struct TabFormSheet: View {
    let title: String
    let confirm: String
    let onSave: (Draft) -> Void
    @Environment(\.dismiss) private var dismiss

    struct Draft {
        var name: String
        var query: String
        var viewMode: ViewMode
        var minLevel: LogLevel
    }

    @State private var draft: Draft

    private let defaultName: String

    init(
        title: String,
        confirm: String,
        initial: SavedFilter?,
        defaultName: String = "",
        onSave: @escaping (Draft) -> Void
    ) {
        self.title = title
        self.confirm = confirm
        self.onSave = onSave
        self.defaultName = initial?.name ?? defaultName
        _draft = State(initialValue: Draft(
            name: initial?.name ?? defaultName,
            query: initial?.filter.query ?? "",
            viewMode: initial?.viewMode ?? .standard,
            minLevel: initial?.filter.minLevel ?? .debug
        ))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Name") {
                        TextField("Payments", text: $draft.name)
                            .multilineTextAlignment(.trailing)
                    }
                    LabeledContent("Filter") {
                        TextField("tag:Network timeout", text: $draft.query)
                            .multilineTextAlignment(.trailing)
                            .font(.system(size: 14, design: .monospaced))
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                    }
                } footer: {
                    Text("Same query language as the filter field. Leave it empty for an unfiltered tab.")
                }

                Section("View") {
                    Picker("View", selection: $draft.viewMode) {
                        Text("Standard").tag(ViewMode.standard)
                        Text("Compact").tag(ViewMode.compact)
                        Text("Raw").tag(ViewMode.raw)
                    }
                    .pickerStyle(.segmented)
                }

                Section("Minimum level") {
                    Picker("Minimum level", selection: $draft.minLevel) {
                        ForEach(LogLevel.allCases, id: \.self) { level in
                            Text(String(level.letter))
                                .font(.system(.body, design: .monospaced))
                                .tag(level)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section {
                } footer: {
                    Text("A tab keeps its own filter, scroll position and pause state. Capture keeps running for every tab, including the ones you are not looking at. Custom tabs can be closed at any time; All cannot.")
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(confirm) {
                        draft.name = draft.name.trimmingCharacters(in: .whitespaces)
                        // The name is never mandatory — a cleared field falls back to the default.
                        if draft.name.isEmpty { draft.name = defaultName }
                        onSave(draft)
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - rows

/// Android's LINE scroll mode: with wrap off, the message scrolls horizontally on its own —
/// the gutter, chip and metadata stay put.
private struct LineScrollable<Content: View>: View {
    let wrap: Bool
    @ViewBuilder let content: Content

    var body: some View {
        if wrap {
            content
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                content.fixedSize(horizontal: true, vertical: false)
            }
        }
    }
}

private struct StandardRow: View {
    let entry: LogEntry
    let wrap: Bool
    let highlight: TextMatcher?
    let hit: SignalHit?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text(String(entry.level.letter))
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundStyle(entry.level.color(scheme))
                .frame(width: 21, height: 21)
                .background(entry.level.chipFill(scheme), in: RoundedRectangle(cornerRadius: 6.5))
            VStack(alignment: .leading, spacing: 3) {
                LineScrollable(wrap: wrap) {
                    messageText
                        .font(.system(size: 12.5, design: .monospaced))
                        // Android's tested rule: error and fault messages read in their level color.
                        .foregroundStyle(entry.level >= .error ? entry.level.color(scheme) : Color.primary)
                        .lineLimit(wrap ? nil : 1)
                }
                HStack(spacing: 9) {
                    Text(Format.time(entry.timeMs))
                    Text(entry.tag)
                        // Android's tested rule: a stable hue per tag, so interleaved
                        // sources read apart at a glance; severity stays on the chip.
                        .foregroundStyle(TagColor.color(for: entry.tag, scheme: scheme))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    if let hit {
                        Text(hit.signal.label)
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(hit.signal.category.color)
                            .lineLimit(1)
                            .frame(maxWidth: 150)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 1.5)
                            .background(hit.signal.category.color.opacity(0.13), in: Capsule())
                    }
                }
                .font(.system(size: 10.5))
                .foregroundStyle(.tertiary)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private var messageText: Text {
        if let highlight {
            return Text(Format.highlighted(Format.rowDisplay(entry.message), matcher: highlight))
        }
        return Text(Format.rowDisplay(entry.message))
    }
}

/// The design's regular-width stream row: time, level chip, a fixed 92pt tag column, message.
private struct PadRow: View {
    let entry: LogEntry
    let wrap: Bool
    let highlight: TextMatcher?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        // With wrap off the WHOLE line scrolls (Android's ENTRY reading, not LINE): pinning
        // time+tag would leave the message a sliver of the viewport, and the fixed tag column
        // would stay truncated no matter how far you scroll. Wrapped keeps the tabular layout —
        // fixed 92pt tag column so the message column aligns across rows.
        LineScrollable(wrap: wrap) {
            HStack(alignment: .firstTextBaseline, spacing: 11) {
                Text(Format.time(entry.timeMs))
                    .font(.system(size: 11.5, design: .monospaced))
                    .foregroundStyle(.tertiary)
                Text(String(entry.level.letter))
                    .font(.system(size: 10.5, weight: .bold, design: .monospaced))
                    .foregroundStyle(entry.level.color(scheme))
                    .frame(width: 19, height: 19)
                    .background(entry.level.chipFill(scheme), in: RoundedRectangle(cornerRadius: 6))
                Text(entry.tag)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(TagColor.color(for: entry.tag, scheme: scheme))
                    .lineLimit(1)
                    .frame(minWidth: 92, alignment: .leading)
                    .frame(width: wrap ? 92 : nil, alignment: .leading)
                messageText
                    .font(.system(size: 12.5, design: .monospaced))
                    .foregroundStyle(entry.level >= .error ? entry.level.color(scheme) : Color.primary)
                    .lineLimit(wrap ? nil : 1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private var messageText: Text {
        if let highlight {
            return Text(Format.highlighted(Format.rowDisplay(entry.message), matcher: highlight))
        }
        return Text(Format.rowDisplay(entry.message))
    }
}

/// The design's line inspector: a 324pt panel beside the stream on regular width — the line in
/// full, its neighbors for context, and the two actions that follow from a line.
private struct LineInspector: View {
    let entry: LogEntry
    let hit: SignalHit?
    let neighbors: [LogEntry]
    let onFilterTag: (String) -> Void
    let onClose: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    caption("Line inspector")
                    Spacer()
                    Button(action: onClose) {
                        Image(systemName: "xmark")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(.secondary)
                            .frame(width: 26, height: 26)
                            .background(Color(.tertiarySystemFill), in: Circle())
                    }
                }
                HStack(spacing: 9) {
                    Text(String(entry.level.letter))
                        .font(.system(size: 10.5, weight: .bold, design: .monospaced))
                        .foregroundStyle(entry.level.color(scheme))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(entry.level.chipFill(scheme), in: RoundedRectangle(cornerRadius: 6))
                    Text("\(Format.time(entry.timeMs)) · pid \(entry.pid) · tid \(entry.tid)")
                        .font(.system(size: 12.5))
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 14)
                Text(entry.tag)
                    .font(.system(size: 19, weight: .semibold))
                    .padding(.top, 10)
                if let hit {
                    HStack(spacing: 6) {
                        Circle().fill(hit.signal.category.color).frame(width: 6, height: 6)
                        Text(hit.signal.label)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(hit.signal.category.color)
                    }
                    .padding(.top, 6)
                }
                Text(entry.message)
                    .font(.system(size: 12, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(13)
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                    .padding(.top, 12)

                caption("Around this line").padding(.top, 16)
                VStack(spacing: 0) {
                    ForEach(neighbors) { neighbor in
                        HStack(spacing: 9) {
                            Text(Format.time(neighbor.timeMs).suffix(6))
                                .foregroundStyle(.tertiary)
                            Text(String(neighbor.level.letter))
                                .fontWeight(.bold)
                                .foregroundStyle(neighbor.level.color(scheme))
                            Text(neighbor.message)
                                .lineLimit(1)
                                .foregroundStyle(neighbor.id == entry.id ? .primary : .secondary)
                        }
                        .font(.system(size: 11, design: .monospaced))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        if neighbor.id != neighbors.last?.id { Divider().padding(.leading, 12) }
                    }
                }
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                .padding(.top, 9)

                HStack(spacing: 9) {
                    inspectorButton("Copy line") { UIPasteboard.general.string = entry.message }
                    inspectorButton("Filter this tag") { onFilterTag(entry.tag) }
                }
                .padding(.top, 14)
            }
            .padding(18)
        }
        .frame(width: 324)
        .background(Color(.systemBackground))
    }

    private func caption(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.system(size: 12.5, weight: .semibold))
            .kerning(0.6)
            .foregroundStyle(.secondary)
    }

    private func inspectorButton(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 13.5, weight: .semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 11))
        }
    }
}

private struct CompactRow: View {
    let entry: LogEntry
    let wrap: Bool
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        // Android's tested rule, compact included: wrap ON really wraps the message across
        // lines; wrap OFF scrolls the WHOLE line horizontally (the ENTRY reading — pinning
        // time+tag would leave the message a sliver of the viewport, and the truncated tag
        // could never be revealed). Wrapped keeps the fixed tag column so rows stay tabular.
        LineScrollable(wrap: wrap) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(Format.time(entry.timeMs))
                    .foregroundStyle(.tertiary)
                Text(String(entry.level.letter))
                    .fontWeight(.bold)
                    .foregroundStyle(entry.level.color(scheme))
                Text(entry.tag)
                    .foregroundStyle(TagColor.color(for: entry.tag, scheme: scheme))
                    .lineLimit(1)
                    .frame(maxWidth: wrap ? 96 : nil, alignment: .leading)
                Text(Format.rowDisplay(entry.message))
                    .foregroundStyle(entry.level >= .error ? entry.level.color(scheme) : Color.primary)
                    .lineLimit(wrap ? nil : 1)
                Spacer(minLength: 0)
            }
        }
        .font(.system(size: 11.5, design: .monospaced))
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct RawRow: View {
    let entry: LogEntry
    let wrap: Bool

    var body: some View {
        LineScrollable(wrap: wrap) {
            Text("\(Format.time(entry.timeMs)) \(String(entry.level.letter)) \(entry.tag): \(Format.rowDisplay(entry.message))")
                .font(.system(size: 11.5, design: .monospaced))
                .lineLimit(wrap ? nil : 1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 3)
    }
}

// MARK: - shared bits

/// The way out of the overlay window: a standard back item **labelled with its destination** —
/// the host app's name — shown on every root. A ✕ says the thing disappears without saying where
/// you land; one step backwards to the host is what dismissal means here.
internal struct BackButton: View {
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 3) {
                Image(systemName: "chevron.backward")
                    .font(.system(size: 17, weight: .semibold))
                Text(label)
                    .font(.system(size: 17))
                    .lineLimit(1)
            }
        }
        .accessibilityLabel("Back to \(label)")
    }
}

internal struct EmptyStateView: View {
    let icon: String
    let title: String
    let body_: String
    let actionLabel: String?
    let action: (() -> Void)?

    init(icon: String, title: String, body: String, actionLabel: String?, action: (() -> Void)?) {
        self.icon = icon
        self.title = title
        self.body_ = body
        self.actionLabel = actionLabel
        self.action = action
    }

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 26))
                .foregroundStyle(.secondary)
                .frame(width: 56, height: 56)
                .background(Color(.tertiarySystemFill), in: Circle())
            Text(title)
                .font(.system(size: 17, weight: .semibold))
                .multilineTextAlignment(.center)
            Text(body_)
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 260)
            if let actionLabel, let action {
                Button(action: action) {
                    Text(actionLabel)
                        .font(.system(size: 14, weight: .semibold))
                        .padding(.horizontal, 18)
                        .padding(.vertical, 9)
                        .background(Color.accentColor.opacity(0.2), in: Capsule())
                }
            }
        }
        .padding(.horizontal, 44)
    }
}

/// The design's "capture continues" reassurance line for the Events, Crashes and Signals tabs:
/// status dot + a per-tab detail. Tracks the same global CaptureStatus as the Logs row — the
/// app-bar pause is global (Android behaviour), so no tab may claim Live while paused.
/// A debugger diverts the process's unified-log stream to its own console — and an
/// Xcode-launched run does the same even with the debugger off, while looking like a normal
/// launch from inside the process. Either way the store (and every screen it feeds) misses
/// those lines; say so, or a quiet stream reads as LogSense being broken. Shown when the
/// debugger is visibly attached OR the store has provably returned nothing (`storeSilent`).
internal struct DivertedStreamCard: View {
    // The simulator's logd can stay diverted for the whole boot after one Xcode run — an
    // icon relaunch alone doesn't recover it, so the simulator copy names the real fix.
    #if targetEnvironment(simulator)
    private static let silentCopy = "Nothing from this app is reaching the system log store — a run launched from Xcode diverts the stream even without the debugger. Build (⌘B), then launch from the app icon. If this card stays after an icon launch, reboot the simulator."
    #else
    private static let silentCopy = "Nothing from this app is reaching the system log store — a run launched from Xcode diverts the stream even without the debugger. Build (⌘B), then launch from the app icon for full capture."
    #endif

    var body: some View {
        let attached = DebuggerState.logsDiverted
        HStack(spacing: 10) {
            Image(systemName: "ant").foregroundStyle(.blue)
            VStack(alignment: .leading, spacing: 2) {
                Text(attached ? "Debugger attached" : "Log stream diverted")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(.blue)
                Text(attached
                     ? "The OS hands this app's log stream to the debugger, so only print() output lands here. Stop the debug session and launch from the app icon for full capture."
                     : Self.silentCopy)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(12)
        .background(Color.blue.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Color.blue.opacity(0.3), lineWidth: 0.5))
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
    }
}

internal struct LiveStatusRow: View {
    let status: CaptureStatus
    let detail: String

    var body: some View {
        HStack(spacing: 7) {
            switch status {
            case .waiting:
                PulsingDot(color: .orange, active: false)
                Text("Connecting").foregroundStyle(.orange).fontWeight(.semibold)
            case .live:
                PulsingDot(color: .green, active: true)
                Text("Live").foregroundStyle(.green).fontWeight(.semibold)
            case .paused:
                PulsingDot(color: .orange, active: false)
                Text("Paused").foregroundStyle(.orange).fontWeight(.semibold)
            }
            Text("· \(detail)")
        }
        .font(.system(size: 12.5, weight: .medium))
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }
}

/// TimelineView-driven blink instead of a `repeatForever` animation: a persistent repeating
/// animation on a view whose layout shifts (the status text next to it re-renders every second)
/// leaks into those position changes and the dot rides every reflow. Deriving opacity from the
/// clock animates nothing but opacity.
internal struct PulsingDot: View {
    let color: Color
    let active: Bool

    var body: some View {
        if active {
            TimelineView(.periodic(from: .now, by: 0.9)) { timeline in
                let on = Int(timeline.date.timeIntervalSinceReferenceDate / 0.9) % 2 == 0
                Circle()
                    .fill(color)
                    .frame(width: 7, height: 7)
                    .opacity(on ? 1 : 0.3)
                    .animation(.easeInOut(duration: 0.85), value: on)
            }
        } else {
            Circle()
                .fill(color)
                .frame(width: 7, height: 7)
        }
    }
}

#endif
