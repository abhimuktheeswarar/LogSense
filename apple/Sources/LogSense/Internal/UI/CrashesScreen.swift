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

    var body: some View {
        NavigationStack {
            // In-layout like Signals, not a top safeAreaInset — an inset swallows the large title.
            VStack(spacing: 0) {
                LiveStatusRow(status: state.status, detail: "\(state.crashes.count.formatted()) captured")
                crashesList
            }
            .navigationTitle("Crashes")
            .onAppear { core.markCrashesSeen() }
            .navigationDestination(for: StoredCrash.self) { crash in
                CrashReportScreen(crash: crash, appBinary: core.appBinary)
                    // Opening a report reads it, like mail; the swipe covers without opening.
                    .onAppear { core.setCrashRead(crash, true) }
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
                    sectionTitle("Log context")
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
        .navigationTitle("Crash Report")
        .navigationBarTitleDisplayMode(.inline)
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
#endif
