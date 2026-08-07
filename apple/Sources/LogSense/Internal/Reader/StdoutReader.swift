import Foundation

/// Intercepts stdout so `print(...)` output joins the stream — unified logging never sees stdout,
/// and real codebases log overwhelmingly through `print`. The original stdout is preserved by
/// teeing every byte back to a dup of the old fd, so the Xcode console keeps working.
///
/// stdout only, deliberately not stderr: NSLog and Xcode-attached os_log echo to stderr, so
/// capturing it would duplicate every line `LogReader` already delivers.
internal final class StdoutReader {

    private var savedFd: Int32 = -1
    private var readFd: Int32 = -1

    /// Called on the reader thread with each batch of complete lines.
    var onLines: (([String]) -> Void)?

    /// Idempotent per process; a second reader would loop the pipe into itself.
    private static var installed = false

    func start() {
        guard !Self.installed else { return }
        var fds: [Int32] = [0, 0]
        guard pipe(&fds) == 0 else { return }
        let saved = dup(STDOUT_FILENO)
        guard saved >= 0, dup2(fds[1], STDOUT_FILENO) >= 0 else {
            close(fds[0]); close(fds[1])
            if saved >= 0 { close(saved) }
            return
        }
        close(fds[1])
        savedFd = saved
        readFd = fds[0]
        // A pipe makes stdout fully buffered; line-buffer it so print() arrives per line, not per 4 KB.
        setvbuf(stdout, nil, _IOLBF, 0)
        Self.installed = true

        let thread = Thread { [weak self] in self?.readLoop() }
        thread.name = "LogSense.stdout"
        thread.start()
    }

    private func readLoop() {
        var pending: [UInt8] = []
        var buf = [UInt8](repeating: 0, count: 4096)
        while true {
            let n = read(readFd, &buf, buf.count)
            if n <= 0 { break }
            write(savedFd, buf, n) // tee back so the Xcode console still sees everything
            pending.append(contentsOf: buf[0..<n])
            var lines: [String] = []
            while let newline = pending.firstIndex(of: 0x0A) {
                if newline > 0 {
                    let line = String(decoding: pending[0..<newline], as: UTF8.self)
                    if !line.isEmpty { lines.append(line) }
                }
                pending.removeFirst(newline + 1)
            }
            if !lines.isEmpty { onLines?(lines) }
        }
    }
}
