import Darwin
import Foundation

final class NativeStdioCapture {
    private let lock = NSLock()
    private var stdoutPipe: Pipe?
    private var stderrPipe: Pipe?
    private var originalStdout: Int32 = -1
    private var originalStderr: Int32 = -1
    private var stdoutRemainder = Data()
    private var stderrRemainder = Data()
    private var onLine: ((String, String) -> Void)?

    var isAvailable: Bool {
        ProcessInfo.processInfo.environment["OS_ACTIVITY_DT_MODE"] == nil
    }

    func start(onLine: @escaping (String, String) -> Void) throws {
        guard isAvailable else {
            throw NativeLoggerFailure("stdout/stderr capture is disabled while OS_ACTIVITY_DT_MODE is set")
        }
        guard originalStdout == -1, originalStderr == -1 else {
            throw NativeLoggerFailure("stdout/stderr capture is already active")
        }
        self.onLine = onLine
        originalStdout = dup(STDOUT_FILENO)
        originalStderr = dup(STDERR_FILENO)
        guard originalStdout >= 0, originalStderr >= 0 else {
            stop()
            throw NativeLoggerFailure("Unable to duplicate standard output descriptors")
        }

        let stdout = Pipe()
        let stderr = Pipe()
        stdoutPipe = stdout
        stderrPipe = stderr
        do {
            guard
                dup2(stdout.fileHandleForWriting.fileDescriptor, STDOUT_FILENO) >= 0,
                dup2(stderr.fileHandleForWriting.fileDescriptor, STDERR_FILENO) >= 0
            else {
                throw NativeLoggerFailure("Unable to redirect standard output descriptors")
            }
            try stdout.fileHandleForWriting.close()
            try stderr.fileHandleForWriting.close()
            stdout.fileHandleForReading.readabilityHandler = { [weak self] handle in
                self?.consume(handle.availableData, level: "I", original: self?.originalStdout ?? -1)
            }
            stderr.fileHandleForReading.readabilityHandler = { [weak self] handle in
                self?.consume(handle.availableData, level: "E", original: self?.originalStderr ?? -1)
            }
        } catch {
            stop()
            throw error
        }
    }

    func stop() {
        stdoutPipe?.fileHandleForReading.readabilityHandler = nil
        stderrPipe?.fileHandleForReading.readabilityHandler = nil
        if originalStdout >= 0 {
            _ = dup2(originalStdout, STDOUT_FILENO)
            _ = close(originalStdout)
            originalStdout = -1
        }
        if originalStderr >= 0 {
            _ = dup2(originalStderr, STDERR_FILENO)
            _ = close(originalStderr)
            originalStderr = -1
        }
        try? stdoutPipe?.fileHandleForReading.close()
        try? stderrPipe?.fileHandleForReading.close()
        stdoutPipe = nil
        stderrPipe = nil
        stdoutRemainder = Data()
        stderrRemainder = Data()
        onLine = nil
    }

    private func consume(_ data: Data, level: String, original: Int32) {
        guard !data.isEmpty else {
            return
        }
        if original >= 0 {
            data.withUnsafeBytes { bytes in
                if let baseAddress = bytes.baseAddress {
                    _ = Darwin.write(original, baseAddress, bytes.count)
                }
            }
        }

        lock.lock()
        let remainder = level == "I" ? stdoutRemainder : stderrRemainder
        var combined = remainder
        combined.append(data)
        var lines = [Data]()
        var lineStart = combined.startIndex
        for index in combined.indices where combined[index] == 0x0A {
            lines.append(combined.subdata(in: lineStart..<index))
            lineStart = combined.index(after: index)
        }
        let newRemainder = combined.subdata(in: lineStart..<combined.endIndex)
        if level == "I" {
            stdoutRemainder = newRemainder
        } else {
            stderrRemainder = newRemainder
        }
        lock.unlock()

        for line in lines {
            if let message = String(data: Data(line), encoding: .utf8), !message.isEmpty {
                onLine?(level, message)
            }
        }
    }
}
