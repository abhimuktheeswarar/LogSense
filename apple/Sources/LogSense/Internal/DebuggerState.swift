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
}
