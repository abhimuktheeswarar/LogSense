import Foundation

/// Whether a debugger is attached (sysctl `P_TRACED`). With a debugger attached, the system
/// diverts this process's unified-log stream to the debugger's console instead of the log
/// store — so log-store capture sees little to nothing. The UI says so rather than looking
/// broken; capture itself keeps working for everything that still reaches the store.
internal enum DebuggerState {
    static let isAttached: Bool = {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        let result = sysctl(&mib, UInt32(mib.count), &info, &size, nil, 0)
        return result == 0 && (info.kp_proc.p_flag & P_TRACED) != 0
    }()

    /// `IDEPreferLogStreaming=YES` in the Run scheme makes the IDE subscribe to the log store
    /// instead of taking the direct pipe — entries then reach both the IDE console and the
    /// store, so capture keeps working under the debugger. The IDE consumes that variable
    /// itself; what the child process actually sees (verified) is
    /// `IDE_DISABLED_OS_ACTIVITY_DT_MODE=1` when the pipe is off, or `OS_ACTIVITY_DT_MODE`
    /// truthy when the diversion is on.
    static let logsDiverted: Bool = {
        guard isAttached else { return false }
        let env = ProcessInfo.processInfo.environment
        if env["IDE_DISABLED_OS_ACTIVITY_DT_MODE"] != nil { return false }
        if let mode = env["OS_ACTIVITY_DT_MODE"], mode != "0", mode.uppercased() != "NO" {
            return true
        }
        return env["IDEPreferLogStreaming"]?.uppercased() != "YES"
    }()
}
