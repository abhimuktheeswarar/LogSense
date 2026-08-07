#if os(iOS)
import SwiftUI

/// Grouped inset lists per the design: appearance, capture facts, events capture tags (config tags
/// locked, QA tags editable), the signal catalog with per-signal switches, saved filters.
/// Pushed from Logs ("‹ Logs" comes from the navigation title), never its own stack.
internal struct SettingsScreen: View {
    let core: LogSenseCore
    @AppStorage(Prefs.themeKey) private var themeRaw = ""
    @State private var muted = Prefs.mutedSignals()
    @State private var qaTags = Prefs.analyticsTags()
    @State private var savedFilters = Prefs.savedFilters()
    @State private var addingTag = false
    @State private var newTag = ""
    @State private var newTagPattern = ""

    var body: some View {
            List {
                Section {
                    Picker("Theme", selection: $themeRaw) {
                        Text("System").tag("")
                        Text("Light").tag(ThemeMode.light.rawValue)
                        Text("Dark").tag(ThemeMode.dark.rawValue)
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text("Appearance")
                }

                Section {
                    LabeledContent("Buffer limit", value: "\(core.bufferLimit.formatted()) lines")
                    LabeledContent("Capture print()", value: core.config.captureStandardOutput ? "On" : "Off")
                    LabeledContent("Capture crashes", value: core.config.captureCrashes ? "On" : "Off")
                } header: {
                    Text("Capture")
                } footer: {
                    Text("Set in LogSenseConfig at start. Older lines drop off the top once the buffer is full; nothing is persisted except events and crash reports.")
                }

                if #available(iOS 16.2, *) {
                    Section {
                        Toggle("Show while recording", isOn: liveActivityBinding)
                    } header: {
                        Text("Live Activity")
                    } footer: {
                        Text("Dynamic Island and Lock Screen presence while capture runs; red on a crash. Off, capture continues silently.")
                    }
                }

                Section {
                    ForEach(core.config.analyticsTagPatterns.keys.sorted(), id: \.self) { tag in
                        LabeledContent {
                            Text("Predefined").font(.footnote)
                        } label: {
                            Text(tag).font(.system(size: 15, design: .monospaced))
                        }
                    }
                    ForEach(qaTags.keys.sorted(), id: \.self) { tag in
                        LabeledContent {
                            Text("Custom").font(.footnote)
                        } label: {
                            Text(tag).font(.system(size: 15, design: .monospaced))
                        }
                        .swipeActions {
                            Button("Delete", role: .destructive) {
                                qaTags.removeValue(forKey: tag)
                                Prefs.setAnalyticsTags(qaTags)
                            }
                        }
                    }
                    Button {
                        addingTag = true
                    } label: {
                        Label("Add tag", systemImage: "plus")
                    }
                } header: {
                    Text("Events capture tags")
                } footer: {
                    Text("Predefined tags come from LogSenseConfig and are locked. Added tags can carry an optional regex with (?<name>) and (?<params>) groups; empty uses the built-in parser.")
                }

                Section {
                    ForEach(SignalCategory.allCases, id: \.self) { category in
                        let signals = catalog.filter { $0.category == category }
                        if !signals.isEmpty {
                            DisclosureGroup {
                                ForEach(signals, id: \.id) { signal in
                                    Toggle(isOn: binding(for: signal.id)) {
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(signal.label).font(.system(size: 15))
                                            if !signal.query.isEmpty {
                                                Text(signal.query)
                                                    .font(.system(size: 11, design: .monospaced))
                                                    .foregroundStyle(.tertiary)
                                                    .lineLimit(1)
                                            }
                                        }
                                    }
                                }
                            } label: {
                                HStack(spacing: 8) {
                                    Circle().fill(category.color).frame(width: 8, height: 8)
                                    Text(category.label)
                                    Spacer()
                                    let mutedCount = signals.filter { muted.contains($0.id) }.count
                                    if mutedCount > 0 {
                                        Text("\(mutedCount) muted")
                                            .font(.footnote)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                } header: {
                    Text("Signals")
                } footer: {
                    Text("A switched-off signal stops matching and stops being reported. The routine ones ship muted so a healthy run flags nothing.")
                }

                if !savedFilters.isEmpty {
                    Section("Saved filters") {
                        ForEach(savedFilters, id: \.id) { filter in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(filter.name).font(.system(size: 15))
                                Text(filterSummary(filter))
                                    .font(.system(size: 11, design: .monospaced))
                                    .foregroundStyle(.tertiary)
                            }
                            .swipeActions {
                                Button("Delete", role: .destructive) {
                                    savedFilters.removeAll { $0.id == filter.id }
                                    Prefs.setSavedFilters(savedFilters)
                                }
                            }
                        }
                    }
                }

                Section {
                } footer: {
                    Text("LogSense · Apache License 2.0")
                        .frame(maxWidth: .infinity)
                        .multilineTextAlignment(.center)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .alert("Add capture tag", isPresented: $addingTag) {
                TextField("Tag (log category)", text: $newTag)
                TextField("Regex (optional)", text: $newTagPattern)
                Button("Add") {
                    let tag = newTag.trimmingCharacters(in: .whitespaces)
                    guard !tag.isEmpty else { return }
                    qaTags[tag] = newTagPattern.isEmpty ? String?.none : newTagPattern
                    Prefs.setAnalyticsTags(qaTags)
                    newTag = ""
                    newTagPattern = ""
                }
                Button("Cancel", role: .cancel) {
                    newTag = ""
                    newTagPattern = ""
                }
            } message: {
                Text("Lines with this tag are captured as analytics events from now on.")
            }
    }

    private var catalog: [Signal] {
        BuiltInSignals.catalog(custom: core.config.customSignals)
    }

    private var liveActivityBinding: Binding<Bool> {
        Binding(
            get: { Prefs.liveActivityEnabled() },
            set: { UserDefaults.standard.set($0, forKey: Prefs.liveActivityKey) }
        )
    }

    private func binding(for signalId: String) -> Binding<Bool> {
        Binding(
            get: { !muted.contains(signalId) },
            set: { enabled in
                if enabled { muted.remove(signalId) } else { muted.insert(signalId) }
                Prefs.setMutedSignals(muted)
            }
        )
    }

    private func filterSummary(_ filter: SavedFilter) -> String {
        var parts: [String] = []
        if filter.filter.minLevel != .debug { parts.append("level:\(filter.filter.minLevel.letter)") }
        if !filter.filter.query.isEmpty { parts.append(filter.filter.query) }
        return parts.isEmpty ? "no filter" : parts.joined(separator: " ")
    }
}
#endif
