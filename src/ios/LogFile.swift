import Foundation

final class NativeLogFile {
    static let defaultMaxAgeMs: Double = 2 * 24 * 60 * 60 * 1000
    static let defaultMaxFileSizeBytes: UInt64 = 5 * 1024 * 1024

    private let fileManager = FileManager.default
    private let directoryURL: URL
    private let currentURL: URL
    private let previousURL: URL
    private let maxFileSizeBytes: UInt64
    private var handle: FileHandle?

    var storageDirectory: URL {
        directoryURL
    }

    init(options: [String: Any]) throws {
        let supportURL = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        directoryURL = supportURL.appendingPathComponent("native-logger", isDirectory: true)
        currentURL = directoryURL.appendingPathComponent("native.log")
        previousURL = directoryURL.appendingPathComponent("native.log.1")
        maxFileSizeBytes = max(
            1024,
            (options["maxFileSizeBytes"] as? NSNumber)?.uint64Value ?? Self.defaultMaxFileSizeBytes
        )

        try fileManager.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        var resourceValues = URLResourceValues()
        resourceValues.isExcludedFromBackup = true
        var protectedDirectory = directoryURL
        try protectedDirectory.setResourceValues(resourceValues)

        if !fileManager.fileExists(atPath: currentURL.path) {
            guard fileManager.createFile(atPath: currentURL.path, contents: nil) else {
                throw NativeLoggerFailure("Unable to create native log file")
            }
        }
        try setProtection(for: currentURL)
        try pruneOlderThan((options["maxAgeMs"] as? NSNumber)?.doubleValue ?? Self.defaultMaxAgeMs)
        try openWriter()
    }

    func append(_ line: String) throws {
        guard let data = (line + "\n").data(using: .utf8) else {
            throw NativeLoggerFailure("Unable to encode native log entry")
        }
        let size = (try? fileManager.attributesOfItem(atPath: currentURL.path)[.size] as? NSNumber)?.uint64Value ?? 0
        if size > 0 && size + UInt64(data.count) > maxFileSizeBytes {
            try rotate()
        }
        try handle?.seekToEnd()
        try handle?.write(contentsOf: data)
    }

    func writeSessionMarker(_ message: String) throws {
        let line = NativeLogLine.format(
            date: Date(),
            pid: Int(ProcessInfo.processInfo.processIdentifier),
            tid: Int(ProcessInfo.processInfo.processIdentifier),
            level: "I",
            tag: "NativeLogger",
            message: message
        )
        try append(line)
    }

    func lastTimestamp() throws -> String? {
        if let timestamp = try lastTimestamp(in: currentURL) {
            return timestamp
        }
        return try lastTimestamp(in: previousURL)
    }

    func synchronize() throws {
        try handle?.synchronize()
    }

    func close() throws {
        try synchronize()
        try handle?.close()
        handle = nil
    }

    func exportLog() throws -> URL {
        try synchronize()
        let cacheURL = try fileManager.url(
            for: .cachesDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let exportURL = cacheURL.appendingPathComponent("native-log-\(Int(Date().timeIntervalSince1970 * 1000)).log")
        var combined = Data()
        if fileManager.fileExists(atPath: previousURL.path) {
            combined.append(try Data(contentsOf: previousURL))
        }
        if fileManager.fileExists(atPath: currentURL.path) {
            combined.append(try Data(contentsOf: currentURL))
        }
        let metricPayloads = try fileManager.contentsOfDirectory(
            at: directoryURL,
            includingPropertiesForKeys: nil
        )
            .filter { $0.lastPathComponent.hasPrefix("metrickit-") && $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        for payload in metricPayloads {
            combined.append(contentsOf: "\n\n[MetricKit payload: \(payload.lastPathComponent)]\n".utf8)
            combined.append(try Data(contentsOf: payload))
            if combined.last != 0x0A {
                combined.append(0x0A)
            }
        }
        try combined.write(to: exportURL, options: .atomic)
        try setProtection(for: exportURL)
        return exportURL
    }

    func saveSupplementalJSON(_ data: Data, name: String) throws -> URL {
        let url = directoryURL.appendingPathComponent(name)
        try data.write(to: url, options: .atomic)
        try setProtection(for: url)
        return url
    }

    private func pruneOlderThan(_ maxAgeMs: Double) throws {
        guard maxAgeMs > 0 else {
            return
        }
        let cutoff = Date().addingTimeInterval(-maxAgeMs / 1000)
        try prune(file: currentURL, before: cutoff)
        try prune(file: previousURL, before: cutoff)
        let metricPayloads = try fileManager.contentsOfDirectory(
            at: directoryURL,
            includingPropertiesForKeys: nil
        )
            .filter { $0.lastPathComponent.hasPrefix("metrickit-") && $0.pathExtension == "json" }
        for payload in metricPayloads {
            let attributes = try fileManager.attributesOfItem(atPath: payload.path)
            if let modified = attributes[.modificationDate] as? Date, modified < cutoff {
                try fileManager.removeItem(at: payload)
            }
        }
    }

    private func prune(file: URL, before cutoff: Date) throws {
        guard fileManager.fileExists(atPath: file.path) else {
            return
        }
        let contents = try String(contentsOf: file, encoding: .utf8)
        let retained = contents.split(separator: "\n", omittingEmptySubsequences: false).filter { line in
            guard
                let parsed = NativeLogLine.parse(String(line)),
                let timestamp = NativeLogLine.date(from: parsed.timestamp)
            else {
                return true
            }
            return timestamp >= cutoff
        }
        try (retained.joined(separator: "\n")).write(to: file, atomically: true, encoding: .utf8)
        try setProtection(for: file)
    }

    private func rotate() throws {
        try close()
        if fileManager.fileExists(atPath: previousURL.path) {
            try fileManager.removeItem(at: previousURL)
        }
        if fileManager.fileExists(atPath: currentURL.path) {
            try fileManager.moveItem(at: currentURL, to: previousURL)
        }
        guard fileManager.createFile(atPath: currentURL.path, contents: nil) else {
            throw NativeLoggerFailure("Unable to create rotated native log")
        }
        try setProtection(for: currentURL)
        try openWriter()
    }

    private func openWriter() throws {
        handle = try FileHandle(forWritingTo: currentURL)
        try handle?.seekToEnd()
    }

    private func lastTimestamp(in file: URL) throws -> String? {
        guard fileManager.fileExists(atPath: file.path) else {
            return nil
        }
        let contents = try String(contentsOf: file, encoding: .utf8)
        return contents
            .split(separator: "\n")
            .compactMap { NativeLogLine.parse(String($0))?.timestamp }
            .last
    }

    private func setProtection(for file: URL) throws {
        try fileManager.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: file.path
        )
    }
}

struct NativeLoggerFailure: LocalizedError {
    let message: String

    init(_ message: String) {
        self.message = message
    }

    var errorDescription: String? {
        message
    }
}
