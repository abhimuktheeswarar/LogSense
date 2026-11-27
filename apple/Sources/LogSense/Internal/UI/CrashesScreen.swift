#if os(iOS)
import SwiftUI

/// Crash reports grouped by session, per the design: type badge + time, exception class, message;
/// swipe-to-delete; the report screen pushed with device facts and the stacktrace kept monospace.
internal struct CrashesScreen: View {
    let core: LogSenseCore
    let onDone: (() -> Void)?
    @ObservedObject private var state: LogSenseState
    @State private var confirmClear = false
    @State private var showSettings = false
    /// The open report. Closed, it is pushed; on an open iPhone Duo it sits beside the list —
    /// the same selection either way, so folding keeps the report you were reading.
    @State private var selectedId: String?
    @Environment(\.duoPosture) private var duo

    init(core: LogSenseCore, onDone: (() -> Void)? = nil) {
        self.core = core
        self.onDone = onDone
        self.state = core.state
    }

    private var groups: [(SessionMeta, [StoredCrash])] {
        guard let store = core.sessionStore else { return [] }
        return groupBySession(
            state.crashes,
            currentSessionId: store.currentSessionId,
            startedAtOf: store.sessionStartedAt,
            sessionOf: { $0.record.sessionId },
            timeOf: { $0.record.timestamp }
        )
    }

    private var selectedCrash: StoredCrash? {
        selectedId.flatMap { id in state.crashes.first { $0.id == id } }
    }

    /// Closed: the selection is the pushed report. Open: nothing is pushed — the report pane shows it.
    private var path: Binding<[StoredCrash]> {
        Binding(
            get: { duo.isOpen ? [] : selectedCrash.map { [$0] } ?? [] },
            set: { selectedId = $0.last?.id }
        )
    }

    var body: some View {
        NavigationStack(path: path) {
            beside(list: VStack(spacing: 0) {
                // In-layout like Signals, not a top safeAreaInset — an inset swallows the large title.
                LiveStatusRow(status: state.status, detail: "\(state.crashes.count.formatted()) captured")
                if duo.hingeClosed, !state.crashes.isEmpty { unfoldHint }
                crashesList
            })
            .navigationTitle("Crashes")
            .onAppear { core.markCrashesSeen() }
            .navigationDestination(for: StoredCrash.self) { crash in
                report(crash)
            }
            .navigationDestination(isPresented: $showSettings) {
                SettingsScreen(core: core)
            }
            .toolbar {
                if let onDone {
                    ToolbarItem(placement: .topBarLeading) {
                        BackButton(label: core.hostName, action: onDone)
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        if !state.crashes.isEmpty {
                            Button("Clear All", role: .destructive) { confirmClear = true }
                            Divider()
                        }
                        Button {
                            showSettings = true
                        } label: {
                            Label("Settings…", systemImage: "gearshape")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .confirmationDialog(
                "Delete all crash reports?",
                isPresented: $confirmClear,
                titleVisibility: .visible
            ) {
                Button("Delete Reports", role: .destructive) { core.deleteAllCrashes() }
            } message: {
                Text("Stacktraces and device info are removed from this device. This can't be undone.")
            }
        }
    }

    /// Open iPhone Duo: the list on one side of the fold, the selected report on the other.
    @ViewBuilder
    private func beside(list: some View) -> some View {
        if duo.isOpen {
            FoldSplit {
                list
            } second: {
                if let crash = selectedCrash {
                    report(crash, embedded: true).id(crash.id)
                } else {
                    EmptyStateView(
                        icon: "doc.text.magnifyingglass",
                        title: "No report selected",
                        body: "Choose a crash on the left to read it here.",
                        actionLabel: nil, action: nil
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        } else {
            list
        }
    }

    private func report(_ crash: StoredCrash, embedded: Bool = false) -> some View {
        CrashReportScreen(crash: crash, appBinary: core.appBinary, showInLogs: showInLogs(crash), embedded: embedded)
            // Opening a report reads it, like mail; the swipe covers without opening.
            .onAppear { core.setCrashRead(crash, true) }
    }

    /// The lines around the crash, in the Logs tab — offered only while the buffer still holds
    /// that moment (crashes from earlier sessions keep their own log context in the report).
    private func showInLogs(_ crash: StoredCrash) -> (() -> Void)? {
        guard let oldest = core.feed.snapshot.first?.timeMs, oldest <= crash.record.timestamp else { return nil }
        return {
            state.revealTimeMs = crash.record.timestamp
            state.requestedTab = .logs
        }
    }

    /// Closed iPhone Duo: a quiet pointer to the open layout, where the report sits beside its list.
    private var unfoldHint: some View {
        Label("Open iPhone Duo to read a report beside this list", systemImage: "rectangle.split.2x1")
            .font(.system(size: 12.5))
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 6)
    }

    @ViewBuilder
    private var crashesList: some View {
        if state.crashes.isEmpty {
            EmptyStateView(
                icon: "checkmark.shield",
                title: "No crashes",
                body: "Reports that survive the process land here — exceptions immediately, signal crashes and hangs on the next launch.",
                actionLabel: nil, action: nil
            )
            .frame(maxHeight: .infinity)
        } else {
            ScrollViewReader { proxy in
            List {
                ForEach(groups, id: \.0.id) { meta, crashes in
                    Section {
                        ForEach(crashes) { crash in
                            let isRead = state.readCrashKeys.contains(crash.readKey)
                            NavigationLink(value: crash) {
                                CrashRow(crash: crash, unread: !isRead)
                            }
                            .listRowBackground(duo.isOpen && crash.id == selectedId
                                ? Color.accentColor.opacity(0.14) : nil)
                            // Custom swipe actions suppress onDelete's built-in swipe, so the
                            // delete lives here explicitly, on its usual trailing edge.
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    core.deleteCrash(id: crash.id)
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                            .swipeActions(edge: .leading) {
                                Button {
                                    core.setCrashRead(crash, !isRead)
                                } label: {
                                    Label(isRead ? "Mark as Unread" : "Mark as Read",
                                          systemImage: isRead ? "envelope.badge" : "envelope.open")
                                }
                                .tint(.blue)
                            }
                        }
                    } header: {
                        sessionHeader(meta)
                    }
                }
            }
            .listStyle(.plain)
            // Reopening the UI lands on the latest — the list is newest-first.
            .onChange(of: state.uiEpoch) { _ in
                if let top = state.crashes.first?.id { proxy.scrollTo(top, anchor: .top) }
            }
            }
        }
    }

    private func sessionHeader(_ meta: SessionMeta) -> some View {
        HStack {
            Text(meta.isCurrent ? "Current session" : (meta.id == earlierSessionId ? "Earlier" : "Previous session"))
            Spacer()
            if meta.isCurrent {
                Text("\(Format.time(meta.startedAt)) → now").textCase(nil)
            } else if meta.id != earlierSessionId, meta.startedAt > 0 {
                Text(Format.dayTime(meta.startedAt)).textCase(nil)
            }
        }
        .font(.system(size: 12.5, weight: .semibold))
    }
}

internal struct CrashRow: View {
    let crash: StoredCrash
    var unread: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 8) {
                if unread {
                    Circle().fill(Color.accentColor).frame(width: 8, height: 8)
                }
                TypeBadge(type: crash.record.type)
                Text(Format.time(crash.record.timestamp))
                    .font(.system(size: 11.5))
                    .foregroundStyle(.tertiary)
            }
            Text(crash.record.exceptionClass ?? defaultTitle)
                .font(.system(size: 15, weight: .semibold))
            if let message = crash.record.message, !message.isEmpty {
                Text(message)
                    .font(.system(size: 12.5))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 3)
    }

    private var defaultTitle: String {
        crash.record.type == "HANG" ? "Main thread hang" : "Crash"
    }
}

internal struct TypeBadge: View {
    let type: String

    var body: some View {
        Text(type)
            .font(.system(size: 10, weight: .bold, design: .monospaced))
            .kerning(0.4)
            .padding(.horizontal, 7)
            .padding(.vertical, 2.5)
            .background(color.opacity(0.18), in: RoundedRectangle(cornerRadius: 5))
            .foregroundStyle(color)
    }

    private var color: Color {
        switch type {
        case "EXCEPTION": return Color(hex: 0xFF453A)
        case "HANG": return Color(hex: 0xFF9F0A)
        default: return Color(hex: 0xBF5AF2) // CRASH
        }
    }
}

internal struct CrashReportScreen: View {
    let crash: StoredCrash
    let appBinary: String
    var showInLogs: (() -> Void)? = nil
    /// Beside the list on an open iPhone Duo: the list's stack owns the title.
    var embedded = false

    var body: some View {
        let record = crash.record
        let read = triage(record, appBinary: appBinary)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 9) {
                    TypeBadge(type: record.type)
                    Text(Format.time(record.timestamp) + (record.threadName.map { " · \($0) thread" } ?? ""))
                        .font(.system(size: 12.5))
                        .foregroundStyle(.secondary)
                }
                Text(record.exceptionClass ?? (record.type == "HANG" ? "Main thread hang" : "Crash"))
                    .font(.system(size: 25, weight: .bold))
                    .padding(.top, 9)
                if let message = record.message, !message.isEmpty {
                    Text(message)
                        .font(.system(size: 14.5))
                        .foregroundStyle(.secondary)
                        .padding(.top, 5)
                }

                if read.appFrame != nil || read.note != nil {
                    sectionTitle("Triage")
                    card {
                        VStack(alignment: .leading, spacing: 8) {
                            if let frame = read.appFrame {
                                Text("Your code: \(frame)")
                                    .font(.system(size: 12.5, weight: .medium, design: .monospaced))
                            }
                            if let note = read.note {
                                Text(note)
                                    .font(.system(size: 13.5))
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(13)
                    }
                }

                sectionTitle("Device")
                card {
                    VStack(spacing: 0) {
                        let facts = record.deviceInfo.split(separator: "\n").map(String.init)
                        ForEach(Array(facts.enumerated()), id: \.offset) { index, line in
                            let parts = line.split(separator: ":", maxSplits: 1)
                            HStack(alignment: .firstTextBaseline) {
                                Text(parts.first.map(String.init) ?? "")
                                    .font(.system(size: 13.5))
                                    .foregroundStyle(.secondary)
                                Spacer()
                                Text(parts.count > 1 ? parts[1].trimmingCharacters(in: .whitespaces) : "")
                                    .font(.system(size: 13.5, weight: .medium, design: .monospaced))
                                    .multilineTextAlignment(.trailing)
                            }
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            if index < facts.count - 1 { Divider().padding(.leading, 14) }
                        }
                    }
                }

                if !record.stacktrace.isEmpty {
                    HStack {
                        sectionTitle("Stacktrace")
                        Spacer()
                        Button("Copy") { UIPasteboard.general.string = record.stacktrace }
                            .font(.system(size: 12.5, weight: .medium))
                            .padding(.top, 20)
                    }
                    card {
                        ScrollView(.horizontal, showsIndicators: false) {
                            Text(record.stacktrace)
                                .font(.system(size: 11, design: .monospaced))
                                .padding(13)
                        }
                    }
                    if record.type != "EXCEPTION" {
                        Text("Stacks from MetricKit are unsymbolicated: binary + offset. Symbolicate against the build's dSYM if you need line numbers.")
                            .font(.system(size: 11.5))
                            .foregroundStyle(.tertiary)
                            .padding(.top, 6)
                    }
                }

                if !record.logContext.isEmpty {
                    HStack {
                        sectionTitle("Log context")
                        Spacer()
                        if let showInLogs {
                            Button("Show in Logs", action: showInLogs)
                                .font(.system(size: 12.5, weight: .medium))
                                .padding(.top, 20)
                        }
                    }
                    card {
                        ScrollView(.horizontal, showsIndicators: false) {
                            Text(record.logContext)
                                .font(.system(size: 11, design: .monospaced))
                                .padding(13)
                        }
                    }
                }
            }
            .padding(16)
        }
        .modifier(PushedTitle(enabled: !embedded))
        .toolbar {
            ShareLink(item: shareText(record, triage: read)) {
                Image(systemName: "square.and.arrow.up")
            }
        }
    }

    private func sectionTitle(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.system(size: 12.5, weight: .semibold))
            .kerning(0.6)
            .foregroundStyle(.secondary)
            .padding(.top, 20)
            .padding(.bottom, 7)
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        content()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func shareText(_ record: CrashRecord, triage read: Triage) -> String {
        var parts = [
            "\(record.type) · \(Format.time(record.timestamp))",
            record.exceptionClass ?? "",
            record.message ?? "",
        ]
        if let frame = read.appFrame { parts.append("Your code: \(frame)") }
        parts.append("")
        parts.append(record.deviceInfo)
        parts.append("")
        parts.append(record.stacktrace)
        if !record.logContext.isEmpty {
            parts.append("")
            parts.append("Log context:")
            parts.append(record.logContext)
        }
        return parts.filter { !$0.isEmpty || $0 == "" }.joined(separator: "\n")
    }
}

extension StoredCrash: Hashable {
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}
/// The pushed report's inline title; skipped beside the list, whose stack keeps "Crashes".
private struct PushedTitle: ViewModifier {
    let enabled: Bool

    func body(content: Content) -> some View {
        if enabled {
            content
                .navigationTitle("Crash Report")
                .navigationBarTitleDisplayMode(.inline)
        } else {
            content
        }
    }
}
#endif
