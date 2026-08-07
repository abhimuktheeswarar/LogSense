#if os(iOS)
import Foundation
import MetricKit

/// The `ApplicationExitInfo` analogue: MetricKit delivers crash and hang diagnostics **on the next
/// launch** (iOS 15+), each payload exactly once — no watermark to keep. This is what catches the
/// crashes the in-process exception handler can't: Swift traps (fatalError, force-unwraps,
/// out-of-bounds → SIGTRAP), memory faults, watchdog kills, and hangs.
///
/// Stacks come as binary + offset, unsymbolicated — stated plainly in the UI rather than dressed up.
internal final class MetricKitCollector: NSObject, MXMetricManagerSubscriber {

    /// A crash/hang record ready to store. Called on MetricKit's queue.
    var onCrash: ((CrashRecord) -> Void)?
    /// CPU/disk-write exceptions are worth surfacing but aren't faults to file — they land as
    /// signals (wired in the signals phase). (signalId, timestampMs, detail).
    var onResourceDiagnostic: ((Signal, Int64, String) -> Void)?

    private let deviceInfo: String

    init(deviceInfo: String) {
        self.deviceInfo = deviceInfo
    }

    func start() {
        MXMetricManager.shared.add(self)
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for payload in payloads {
            let ts = Int64(payload.timeStampEnd.timeIntervalSince1970 * 1000)
            for crash in payload.crashDiagnostics ?? [] {
                onCrash?(record(crash, ts: ts))
            }
            for hang in payload.hangDiagnostics ?? [] {
                onCrash?(record(hang, ts: ts))
            }
            for cpu in payload.cpuExceptionDiagnostics ?? [] {
                let detail = "Total CPU \(cpu.totalCPUTime), sampled \(cpu.totalSampledTime)"
                onResourceDiagnostic?(BuiltInSignals.cpuException, ts, detail)
            }
            for disk in payload.diskWriteExceptionDiagnostics ?? [] {
                let detail = "Total writes \(disk.totalWritesCaused)"
                onResourceDiagnostic?(BuiltInSignals.diskWrites, ts, detail)
            }
        }
    }

    private func record(_ crash: MXCrashDiagnostic, ts: Int64) -> CrashRecord {
        let termination = crash.terminationReason ?? ""
        let exceptionClass = watchdogCode(in: termination)
            ?? crash.signal.flatMap { signalName($0.int32Value) }
            ?? crash.exceptionType.flatMap { machExceptionName($0.int32Value) }
            ?? "CRASH"
        var messageParts: [String] = []
        if !termination.isEmpty { messageParts.append(termination) }
        if let code = crash.exceptionCode { messageParts.append("code \(code)") }
        if let region = crash.virtualMemoryRegionInfo { messageParts.append(region) }
        return CrashRecord(
            timestamp: ts,
            sessionId: "", // attributed by timestamp at store time
            type: "CRASH",
            threadName: nil,
            exceptionClass: exceptionClass,
            message: messageParts.isEmpty
                ? "Signal-level crash (unsymbolicated stack from MetricKit)"
                : messageParts.joined(separator: " · "),
            stacktrace: format(crash.callStackTree),
            deviceInfo: deviceInfo,
            logContext: "" // the log buffer died with the crashed process
        )
    }

    private func record(_ hang: MXHangDiagnostic, ts: Int64) -> CrashRecord {
        CrashRecord(
            timestamp: ts,
            sessionId: "",
            type: "HANG",
            threadName: "main",
            exceptionClass: nil,
            message: "Main thread unresponsive for \(hang.hangDuration)",
            stacktrace: format(hang.callStackTree),
            deviceInfo: deviceInfo,
            logContext: ""
        )
    }

    /// `MXCallStackTree` only speaks JSON. Flatten to the same `idx binary 0xaddr binary + offset`
    /// shape `Thread.callStackSymbols` produces, so triage's app-frame scan reads both.
    private func format(_ tree: MXCallStackTree) -> String {
        guard let json = try? JSONSerialization.jsonObject(with: tree.jsonRepresentation()) as? [String: Any],
              let stacks = json["callStacks"] as? [[String: Any]]
        else { return String(decoding: tree.jsonRepresentation(), as: UTF8.self) }

        var lines: [String] = []
        for (stackIndex, stack) in stacks.enumerated() {
            if stacks.count > 1 {
                let attributed = stack["threadAttributed"] as? Bool == true ? " (attributed)" : ""
                lines.append("Thread \(stackIndex)\(attributed):")
            }
            var frameIndex = 0
            var frames = (stack["callStackRootFrames"] as? [[String: Any]]) ?? []
            while !frames.isEmpty {
                let frame = frames.removeFirst()
                let binary = frame["binaryName"] as? String ?? "?"
                let address = (frame["address"] as? NSNumber)?.uint64Value ?? 0
                let offset = (frame["offsetIntoBinaryTextSegment"] as? NSNumber)?.uint64Value ?? 0
                lines.append(String(format: "%-3d %@ 0x%016llx %@ + %llu", frameIndex, binary, address, binary, offset))
                frameIndex += 1
                if let sub = frame["subFrames"] as? [[String: Any]] {
                    frames.insert(contentsOf: sub, at: 0)
                }
            }
        }
        return lines.joined(separator: "\n")
    }

    private func watchdogCode(in termination: String) -> String? {
        if termination.localizedCaseInsensitiveContains("8badf00d") { return "0x8badf00d" }
        if termination.localizedCaseInsensitiveContains("dead10cc") { return "0xdead10cc" }
        return nil
    }

    private func signalName(_ signal: Int32) -> String? {
        switch signal {
        case SIGILL: return "SIGILL"
        case SIGTRAP: return "SIGTRAP"
        case SIGABRT: return "SIGABRT"
        case SIGFPE: return "SIGFPE"
        case SIGBUS: return "SIGBUS"
        case SIGSEGV: return "SIGSEGV"
        case SIGPIPE: return "SIGPIPE"
        case SIGKILL: return "SIGKILL"
        default: return nil
        }
    }

    private func machExceptionName(_ type: Int32) -> String? {
        switch type {
        case 1: return "EXC_BAD_ACCESS"
        case 2: return "EXC_BAD_INSTRUCTION"
        case 3: return "EXC_ARITHMETIC"
        case 6: return "EXC_BREAKPOINT"
        default: return nil
        }
    }
}
#endif
