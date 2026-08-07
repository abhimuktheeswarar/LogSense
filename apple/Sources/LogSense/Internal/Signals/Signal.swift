/// What kind of trouble a `Signal` reports. CRASH / HANG are also produced by the crash pipeline
/// (`CrashHandler` + MetricKit) — those arrive as stored reports on the next launch, while the
/// patterns here fire immediately, in the run where the problem happens.
internal enum SignalCategory: Int, CaseIterable {
    // Declaration order is *display* order — the built-in categories first, the host's own signals
    // last, so a list of categories never buries ours among theirs. `severity` is deliberately
    // separate from it: a custom signal is something the host chose to watch for, so it outranks a
    // lifecycle note when deciding the colour of a count badge.
    case crash, hang, memory, ui, network, data, resource, lifecycle, custom

    var label: String {
        switch self {
        case .crash: return "Crash"
        case .hang: return "Hang"
        case .memory: return "Memory"
        case .ui: return "UI"
        case .network: return "Network"
        case .data: return "Data"
        case .resource: return "Resource"
        case .lifecycle: return "Lifecycle"
        case .custom: return "Custom"
        }
    }

    var severity: Int {
        switch self {
        case .crash: return 0
        case .hang: return 1
        case .memory: return 2
        case .custom: return 3
        case .ui: return 4
        case .network: return 5
        case .data: return 6
        case .resource: return 7
        case .lifecycle: return 8
        }
    }
}

/// One catalog entry: a label plus, usually, a filter query. The query is the same syntax the Logs
/// filter field takes (`LogQuery`) — `tag:`, `-tag:`, `msg:`, `sub:`, `level:`, bare words,
/// `"quoted phrases"`, all ANDed — so there is no second matching language to learn or maintain.
///
/// A **blank `query` means the signal is reported, not matched**: some conditions never appear as a
/// log line (a memory warning, a thermal spike, a first frame) and are recorded straight from the
/// platform API that does know about them. Those still carry a label, a category and a mute, they
/// just have no pattern and no log line to jump to.
///
/// `id` is stable across releases: it is what a mute is persisted under.
internal struct Signal: Equatable {
    let id: String
    let label: String
    let category: SignalCategory
    var query: String = ""
}

/// The built-in catalog.
///
/// Every *pattern* here matches lines from the app's own process — that is all `OSLogStore`
/// (scope `.currentProcessIdentifier`) can see. Conditions that never produce an in-process log
/// line are covered instead by the reported signals at the bottom, recorded from the platform
/// APIs that actually know: UIKit/Foundation notifications and MetricKit diagnostics.
///
/// **Order matters for patterns: a line produces at most one hit, the first rule that matches.**
/// Rules run specific-before-generic — the named troubles before the bare `level:F` catch-all —
/// so a line is reported once, as the most specific thing we know about it.
///
/// Pattern strings are iOS-version-sensitive; each needs on-device validation before shipping.
internal enum BuiltInSignals {

    /* Reported, not matched — see the `Signal` doc. Held as named constants because the code that
     * records them refers to them directly. */

    static let memoryWarning = Signal(id: "memory.warning", label: "Memory warning", category: .memory)
    static let thermalState = Signal(id: "memory.thermal", label: "Thermal pressure", category: .memory)
    static let foreground = Signal(id: "lifecycle.foreground", label: "Became active", category: .lifecycle)
    static let firstFrame = Signal(id: "lifecycle.first_frame", label: "First frame displayed", category: .lifecycle)
    static let cpuException = Signal(id: "resource.cpu", label: "Excessive CPU", category: .resource)
    static let diskWrites = Signal(id: "resource.disk", label: "Excessive disk writes", category: .resource)

    static let catalog: [Signal] = [
        // ---- UI --------------------------------------------------------------------------
        Signal(id: "ui.constraints", label: "Unsatisfiable constraints", category: .ui,
               query: #"msg:"Unable to simultaneously satisfy constraints""#),
        Signal(id: "ui.unbalanced", label: "Unbalanced appearance transitions", category: .ui,
               query: #"msg:"Unbalanced calls to begin/end appearance transitions""#),
        Signal(id: "ui.background_publish", label: "Publishing off the main thread", category: .ui,
               query: #"msg:"Publishing changes from background threads""#),
        Signal(id: "ui.cycle", label: "AttributeGraph cycle", category: .ui,
               query: #"msg:"AttributeGraph: cycle detected""#),
        Signal(id: "ui.main_thread_checker", label: "UI API off the main thread", category: .ui,
               query: #"msg:"Main Thread Checker""#),
        Signal(id: "ui.foreach_ids", label: "Duplicate ForEach IDs", category: .ui,
               query: #"msg:"occurs multiple times within the collection""#),
        Signal(id: "ui.state_during_update", label: "State modified during view update", category: .ui,
               query: #"msg:"Modifying state during view update""#),
        Signal(id: "ui.publish_during_update", label: "Publishing during view update", category: .ui,
               query: #"msg:"Publishing changes from within view updates""#),

        // ---- network ---------------------------------------------------------------------
        Signal(id: "net.ats", label: "Blocked by App Transport Security", category: .network,
               query: #"msg:"App Transport Security""#),
        Signal(id: "net.timeout", label: "Request timed out", category: .network,
               query: #"msg:"The request timed out""#),
        Signal(id: "net.offline", label: "Internet connection offline", category: .network,
               query: #"msg:"The Internet connection appears to be offline""#),
        Signal(id: "net.task_error", label: "URLSession task error", category: .network,
               query: #"sub:CFNetwork msg:"finished with error""#),

        // ---- data ------------------------------------------------------------------------
        Signal(id: "data.coredata", label: "Core Data error", category: .data,
               query: "sub:coredata level:E"),
        Signal(id: "data.keychain", label: "Keychain error", category: .data,
               query: #"msg:"SecOSStatusWith""#),

        // ---- lifecycle ---------------------------------------------------------------------
        Signal(id: "lifecycle.background_task", label: "Unbalanced background task", category: .lifecycle,
               query: #"msg:"Can't end BackgroundTask""#),

        // ---- crash (generic catch-all, after everything more specific) ---------------------
        Signal(id: "crash.fault", label: "Fault logged", category: .crash, query: "level:F"),

        // ---- reported by the platform, no pattern ------------------------------------------
        memoryWarning,
        thermalState,
        foreground,
        firstFrame,
        cpuException,
        diskWrites,
    ]

    /// Signals that are off until someone asks for them. Each fires on a healthy run — a scene
    /// becoming active, CFNetwork noting a cancelled task — so leaving them on meant the count
    /// never read zero, which teaches you to ignore the count. Off by default, a badge means
    /// "something happened"; they are one switch away in Settings.
    static let mutedByDefault: Set<String> = [
        foreground.id,
        "net.task_error",
    ]

    /// Built-ins plus the host's `customSignals`. Custom rules go first so they win a shared line.
    static func catalog(custom: [String: String]) -> [Signal] {
        custom
            .sorted { $0.key < $1.key }
            .map { Signal(id: "custom.\($0.key)", label: $0.key, category: .custom, query: $0.value) }
            + catalog
    }
}
