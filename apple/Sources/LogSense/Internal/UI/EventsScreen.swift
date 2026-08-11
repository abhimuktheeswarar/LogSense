#if os(iOS)
import SwiftUI

/// Analytics events grouped by session, per the design: search across name/params/tag, a tag-scope
/// pill row, rows with a tag chip and a monospace params preview, swipe-to-delete, and a detail
/// sheet with the parameters as an inset list and the raw line verbatim.
internal struct EventsScreen: View {
    let core: LogSenseCore
    let onDone: (() -> Void)?
    @ObservedObject private var state: LogSenseState
    @State private var query = ""
    @State private var tagScope: String?
    @State private var selected: StoredEvent?
    @State private var confirmClear = false
    @State private var showSettings = false

    init(core: LogSenseCore, onDone: (() -> Void)? = nil) {
        self.core = core
        self.onDone = onDone
        self.state = core.state
    }

    private var tags: [String] {
        Array(Set(state.events.map(\.record.tag))).sorted()
    }

    private var displayed: [StoredEvent] {
        state.events.filter { event in
            if let tagScope, event.record.tag != tagScope { return false }
            if query.isEmpty { return true }
            let haystacks = [event.record.name, event.record.tag]
                + event.record.params.flatMap { [$0.key, $0.value] }
            return haystacks.contains { $0.range(of: query, options: .caseInsensitive) != nil }
        }
    }

    private var groups: [(SessionMeta, [StoredEvent])] {
        guard let store = core.sessionStore else { return [] }
        return groupBySession(
            displayed,
            currentSessionId: store.currentSessionId,
            startedAtOf: store.sessionStartedAt,
            sessionOf: \.sessionId,
            timeOf: \.record.timestamp
        )
    }

    var body: some View {
        NavigationStack {
            // In-layout like Signals, not a top safeAreaInset — an always-present inset
            // swallows the large navigation title.
            VStack(spacing: 0) {
                LiveStatusRow(status: state.status, detail: "\(state.events.count.formatted()) captured · \(tags.count) \(tags.count == 1 ? "tag" : "tags")")
                if tags.count > 1 { tagPills }
                if state.events.isEmpty {
                    EmptyStateView(
                        icon: "chart.bar.xaxis",
                        title: "No events yet",
                        body: "Analytics events lifted out of the log stream land here, grouped by run. Configure captured tags in LogSenseConfig.",
                        actionLabel: nil, action: nil
                    )
                    .frame(maxHeight: .infinity)
                } else if displayed.isEmpty {
                    EmptyStateView(
                        icon: "line.3.horizontal.decrease",
                        title: "No events match",
                        body: "Capture is still running. Clear the search or the tag scope.",
                        actionLabel: "Clear filters",
                        action: { query = ""; tagScope = nil }
                    )
                    .frame(maxHeight: .infinity)
                } else {
                    ScrollViewReader { proxy in
                        List {
                            ForEach(groups, id: \.0.id) { meta, events in
                                Section {
                                    ForEach(events) { event in
                                        EventRow(event: event, dimmed: !meta.isCurrent)
                                            .contentShape(Rectangle())
                                            .onTapGesture { selected = event }
                                    }
                                    .onDelete { offsets in
                                        for offset in offsets { core.deleteEvent(events[offset]) }
                                    }
                                } header: {
                                    sessionHeader(meta)
                                }
                            }
                        }
                        .listStyle(.plain)
                        // Reopening the UI lands on the latest — the list is newest-first.
                        .onChange(of: state.uiEpoch) { _ in
                            if let top = displayed.first?.id { proxy.scrollTo(top, anchor: .top) }
                        }
                    }
                }
            }
            .navigationTitle("Events")
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: "Name, params or tag")
            .toolbar {
                if let onDone {
                    ToolbarItem(placement: .topBarLeading) {
                        BackButton(label: core.hostName, action: onDone)
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    // Settings must stay reachable even with nothing captured, so the overflow
                    // is always there; the event actions come and go with the events.
                    Menu {
                        if !state.events.isEmpty {
                            ShareLink(item: exportJson(displayed)) {
                                Label("Share shown as JSON", systemImage: "square.and.arrow.up")
                            }
                            Button("Delete all", role: .destructive) { confirmClear = true }
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
                "Delete all events?", isPresented: $confirmClear, titleVisibility: .visible
            ) {
                Button("Delete Events", role: .destructive) { core.deleteAllEvents() }
            } message: {
                Text("Events from every session are removed from this device. This can't be undone.")
            }
            .sheet(item: $selected) { event in
                EventDetailSheet(event: event)
            }
            .navigationDestination(isPresented: $showSettings) {
                SettingsScreen(core: core)
            }
        }
    }

    private var tagPills: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 7) {
                pill("All", isOn: tagScope == nil) { tagScope = nil }
                ForEach(tags, id: \.self) { tag in
                    pill(tag, isOn: tagScope == tag) { tagScope = tagScope == tag ? nil : tag }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
        .background(.bar)
    }

    private func pill(_ label: String, isOn: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 13, weight: isOn ? .semibold : .medium))
                .padding(.horizontal, 13)
                .padding(.vertical, 6)
                .background(isOn ? Color.accentColor : Color(.tertiarySystemFill), in: Capsule())
                .foregroundStyle(isOn ? .white : .primary)
        }
        .buttonStyle(.plain)
    }

    private func sessionHeader(_ meta: SessionMeta) -> some View {
        HStack {
            Text(meta.isCurrent ? "Current session" : "Previous session")
            Spacer()
            if meta.isCurrent {
                Text("\(Format.time(meta.startedAt)) → now").textCase(nil)
            } else if meta.startedAt > 0 {
                Text(Format.dayTime(meta.startedAt)).textCase(nil)
            }
        }
        .font(.system(size: 12.5, weight: .semibold))
    }

    private func exportJson(_ events: [StoredEvent]) -> String {
        let array: [[String: Any]] = events.map { event in
            [
                "time": Format.time(event.record.timestamp),
                "timestamp": event.record.timestamp,
                "session": event.sessionId,
                "tag": event.record.tag,
                "name": event.record.name,
                "params": event.record.params,
            ]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: array, options: [.prettyPrinted, .sortedKeys]),
              let text = String(data: data, encoding: .utf8)
        else { return "[]" }
        return text
    }
}

private struct EventRow: View {
    let event: StoredEvent
    let dimmed: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 8) {
                Text(event.record.name)
                    .font(.system(size: 15, weight: .semibold))
                    .lineLimit(1)
                TagChip(tag: event.record.tag, dimmed: dimmed)
            }
            if !event.record.params.isEmpty {
                Text(paramsPreview)
                    .font(.system(size: 11.5, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Text(Format.time(event.record.timestamp))
                .font(.system(size: 11))
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 3)
        .opacity(dimmed ? 0.72 : 1)
    }

    private var paramsPreview: String {
        "{" + event.record.params.sorted(by: { $0.key < $1.key })
            .map { "\($0.key)=\($0.value)" }
            .joined(separator: ", ") + "}"
    }
}

private struct TagChip: View {
    let tag: String
    let dimmed: Bool

    var body: some View {
        Text(tag)
            .font(.system(size: 10, weight: .semibold, design: .monospaced))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(
                (dimmed ? Color(.systemGray) : Color(hex: 0x64D2FF)).opacity(0.16),
                in: RoundedRectangle(cornerRadius: 5)
            )
            .foregroundStyle(dimmed ? Color.secondary : Color(hex: 0x64D2FF))
    }
}

private struct EventDetailSheet: View {
    let event: StoredEvent
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text(event.record.name)
                        .font(.system(size: 26, weight: .bold))
                    HStack(spacing: 8) {
                        TagChip(tag: event.record.tag, dimmed: false)
                        Text(Format.time(event.record.timestamp))
                            .font(.system(size: 13))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.top, 7)

                    if !event.record.params.isEmpty {
                        sectionTitle("Parameters · \(event.record.params.count)")
                        VStack(spacing: 0) {
                            let params = event.record.params.sorted { $0.key < $1.key }
                            ForEach(Array(params.enumerated()), id: \.offset) { index, pair in
                                HStack(alignment: .firstTextBaseline, spacing: 12) {
                                    Text(pair.key)
                                        .font(.system(size: 12.5, design: .monospaced))
                                        .foregroundStyle(.secondary)
                                        .frame(width: 112, alignment: .leading)
                                    Text(pair.value)
                                        .font(.system(size: 13, weight: .medium, design: .monospaced))
                                        .multilineTextAlignment(.trailing)
                                        .frame(maxWidth: .infinity, alignment: .trailing)
                                }
                                .padding(.horizontal, 14)
                                .padding(.vertical, 11)
                                if index < params.count - 1 { Divider().padding(.leading, 14) }
                            }
                        }
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                    }

                    // The JSON block previews exactly what Copy/Share produce — one source of
                    // truth, like Android's detail. The line it was lifted from sits below.
                    sectionTitle("JSON")
                    Text(eventJson)
                        .font(.system(size: 11.5, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(13)
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))

                    Button {
                        UIPasteboard.general.string = eventJson
                    } label: {
                        Text("Copy JSON")
                            .font(.system(size: 15, weight: .semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 13))
                    }
                    .padding(.top, 12)

                    sectionTitle("Raw log line")
                    Text(event.record.raw)
                        .font(.system(size: 11.5, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(13)
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                }
                .padding(16)
            }
            .navigationTitle("Event")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    ShareLink(item: eventJson) { Image(systemName: "square.and.arrow.up") }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func sectionTitle(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.system(size: 12.5, weight: .semibold))
            .kerning(0.6)
            .foregroundStyle(.secondary)
            .padding(.top, 20)
            .padding(.bottom, 7)
    }

    /// The same envelope as the list-level export and Android's single-event share.
    private var eventJson: String {
        let object: [String: Any] = [
            "time": Format.time(event.record.timestamp),
            "timestamp": event.record.timestamp,
            "session": event.sessionId,
            "tag": event.record.tag,
            "name": event.record.name,
            "params": event.record.params,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]),
              let text = String(data: data, encoding: .utf8)
        else { return "{}" }
        return text
    }
}
#endif
