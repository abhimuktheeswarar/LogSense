import Foundation

/// A first read of a crash.
///
/// `appFrame` is the part that always earns its place: the topmost frame in *your* binary, lifted
/// out of forty lines of framework trace. `note` is deliberately rare — it appears only for faults
/// whose remedy is not obvious from the exception name. If an entry would only restate the
/// exception, it does not belong.
internal struct Triage: Equatable {
    /// The topmost frame belonging to the host app, or nil when the trace has none.
    let appFrame: String?
    /// What to check, when that isn't already obvious from the exception. Usually nil.
    let note: String?
}

internal func triage(_ crash: CrashRecord, appBinary: String) -> Triage {
    let full = crash.exceptionClass ?? ""
    let afterDot = full.split(separator: ".").last ?? ""
    let simpleName = String(afterDot.split(separator: "$").last ?? "")
    return Triage(
        appFrame: appFrame(stacktrace: crash.stacktrace, appBinary: appBinary),
        note: triageNotes[simpleName] ?? note(forType: crash.type)
    )
}

/// The topmost frame whose binary image is the host app's executable — frame lines look like
/// `4  MyApp  0x000000010245cd2c ViewModel.load() + 84` (`Thread.callStackSymbols`, and the shape
/// MetricKit stacks are formatted into). LogSense is statically linked into the host binary, so its
/// own symbols are excluded by name — they are never the bug being triaged.
internal func appFrame(stacktrace: String, appBinary: String) -> String? {
    guard !appBinary.isEmpty else { return nil }
    for line in stacktrace.split(separator: "\n") {
        let tokens = line.split(separator: " ", omittingEmptySubsequences: true)
        guard tokens.count >= 4,
              tokens[0].allSatisfy(\.isNumber),
              tokens[1] == appBinary[...]
        else { continue }
        let symbol = tokens[3...].joined(separator: " ")
        if symbol.contains("LogSense") { continue }
        return symbol
    }
    return nil
}

/// Hangs carry a stack but no exception, so a pointer is worth more than nothing.
private func note(forType type: String) -> String? {
    switch type {
    case "HANG":
        return "The main thread was unresponsive — the stack shows what held it. Check the signals "
            + "logged just before this for the work that piled up."
    default:
        return nil
    }
}

/// Only faults whose remedy isn't obvious from the name. Writing "something was null" under a crash
/// that already names the nil would be noise dressed as help — such entries do not belong here.
private let triageNotes: [String: String] = [
    "NSInvalidArgumentException":
        "Usually an unrecognized selector or a nil passed where an object was required — check the "
        + "types crossing the Objective-C bridge at the app frame.",
    "NSRangeException":
        "An index outside the collection's bounds — check the index math at the app frame.",
    "NSInternalInconsistencyException":
        "A framework assertion — most often a table/collection view update whose inserted and deleted "
        + "counts don't match the data source, or UIKit touched off the main thread.",
    "NSUnknownKeyException":
        "Key-value coding hit a key the object doesn't have — classically a renamed or disconnected "
        + "outlet still referenced from a storyboard or nib.",
    "NSGenericException":
        "Usually a collection mutated while being enumerated — copy it before the loop, or collect "
        + "changes and apply them after.",
    "NSMallocException":
        "An allocation failed — the process was effectively out of memory. Check the memory signals "
        + "just before this.",
    "SIGTRAP":
        "A Swift runtime trap: a force-unwrapped nil, an out-of-range index, or an explicit "
        + "precondition/fatalError. The app frame is the line that trapped.",
    "EXC_BREAKPOINT":
        "A Swift runtime trap: a force-unwrapped nil, an out-of-range index, or an explicit "
        + "precondition/fatalError. The app frame is the line that trapped.",
    "SIGABRT":
        "An abort — usually an uncaught exception (check for an EXCEPTION report just before this) "
        + "or a failed assertion.",
    "EXC_BAD_ACCESS":
        "A dangling or over-released pointer, or unsafe-pointer misuse — rare in pure Swift; check "
        + "C/Objective-C interop and Unmanaged references.",
    "SIGSEGV":
        "A dangling or over-released pointer, or unsafe-pointer misuse — rare in pure Swift; check "
        + "C/Objective-C interop and Unmanaged references.",
    "SIGBUS":
        "A misaligned or unmapped memory access — usually unsafe-pointer arithmetic or a truncated "
        + "memory-mapped file.",
    "0x8badf00d":
        "The watchdog killed the app: the main thread blocked too long during launch or a foreground/"
        + "background transition — move the work off the main thread.",
    "0xdead10cc":
        "Suspended while holding a file or database lock in a shared container — release it in the "
        + "background-transition handler.",
]
