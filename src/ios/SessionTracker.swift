import Foundation

final class NativeSessionTracker {
    private static let maxExitRecords = 100

    private let sessionURL: URL
    private let exitsURL: URL
    private var session: [String: Any]
    private var lastHeartbeat = Date.distantPast

    init(optionsDirectory: URL) throws {
        sessionURL = optionsDirectory.appendingPathComponent("session.json")
        exitsURL = optionsDirectory.appendingPathComponent("exit-reasons.json")
        let previous = try Self.readDictionary(from: sessionURL)
        let now = Date()
        let bootTime = now.addingTimeInterval(-ProcessInfo.processInfo.systemUptime)
        if let previous = previous, !(previous["cleanExit"] as? Bool ?? true) {
            let previousBootTime = previous["bootTime"] as? TimeInterval ?? 0
            let lastHeartbeat = previous["lastHeartbeat"] as? TimeInterval ?? now.timeIntervalSince1970
            let appState = previous["appState"] as? String ?? "background"
            let didReboot = abs(previousBootTime - bootTime.timeIntervalSince1970) > 60
            let reason = didReboot
                ? "reboot"
                : (appState == "active" ? "unexpected-foreground" : "unexpected-background")
            try Self.appendExit(
                [
                    "timestamp": lastHeartbeat * 1000,
                    "pid": previous["pid"] as? Int ?? 0,
                    "reason": reason,
                    "description": "Previous session did not record a clean exit",
                    "source": "inferred",
                    "details": [
                        "lastHeartbeatMs": lastHeartbeat * 1000,
                        "lastAppState": appState,
                        "previousBootTimeMs": previousBootTime * 1000,
                        "currentBootTimeMs": bootTime.timeIntervalSince1970 * 1000,
                    ],
                ],
                to: exitsURL
            )
        }

        session = [
            "pid": Int(ProcessInfo.processInfo.processIdentifier),
            "startedAt": now.timeIntervalSince1970,
            "bootTime": bootTime.timeIntervalSince1970,
            "lastHeartbeat": now.timeIntervalSince1970,
            "appState": "active",
            "cleanExit": false,
            "previousPid": previous?["pid"] ?? NSNull(),
        ]
        lastHeartbeat = now
        try writeSession()
    }

    func heartbeat(foreground: Bool) throws {
        let state = foreground ? "active" : "background"
        let now = Date()
        guard now.timeIntervalSince(lastHeartbeat) >= 30 || session["appState"] as? String != state else {
            return
        }
        session["lastHeartbeat"] = now.timeIntervalSince1970
        session["appState"] = state
        lastHeartbeat = now
        try writeSession()
    }

    func markCleanExit() throws {
        session["lastHeartbeat"] = Date().timeIntervalSince1970
        session["cleanExit"] = true
        try writeSession()
    }

    func describeStart() -> String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "unknown"
        let previousPid = session["previousPid"] ?? NSNull()
        return "session start; app \(version) (\(build)); os iOS \(ProcessInfo.processInfo.operatingSystemVersionString); " +
            "pid \(ProcessInfo.processInfo.processIdentifier); booted \(session["bootTime"] ?? 0); previous session pid \(previousPid)"
    }

    func recordExit(_ record: [String: Any]) throws {
        try Self.appendExit(record, to: exitsURL)
    }

    func exitReasons(sinceMs: Double) throws -> [[String: Any]] {
        let records = try Self.readArray(from: exitsURL)
        return records
            .filter { ($0["timestamp"] as? Double ?? 0) > sinceMs }
            .sorted { ($0["timestamp"] as? Double ?? 0) < ($1["timestamp"] as? Double ?? 0) }
    }

    private func writeSession() throws {
        let data = try JSONSerialization.data(withJSONObject: session, options: [.sortedKeys])
        try data.write(to: sessionURL, options: .atomic)
    }

    private static func readDictionary(from url: URL) throws -> [String: Any]? {
        guard FileManager.default.fileExists(atPath: url.path) else {
            return nil
        }
        let data = try Data(contentsOf: url)
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private static func readArray(from url: URL) throws -> [[String: Any]] {
        guard FileManager.default.fileExists(atPath: url.path) else {
            return []
        }
        let data = try Data(contentsOf: url)
        return try JSONSerialization.jsonObject(with: data) as? [[String: Any]] ?? []
    }

    private static func appendExit(_ record: [String: Any], to url: URL) throws {
        var records = try readArray(from: url)
        let timestamp = record["timestamp"] as? Double ?? 0
        let pid = record["pid"] as? Int ?? 0
        let reason = record["reason"] as? String ?? ""
        if records.contains(where: {
            ($0["timestamp"] as? Double ?? -1) == timestamp &&
                ($0["pid"] as? Int ?? -1) == pid &&
                ($0["reason"] as? String ?? "") == reason
        }) {
            return
        }
        records.append(record)
        if records.count > maxExitRecords {
            records.removeFirst(records.count - maxExitRecords)
        }
        let data = try JSONSerialization.data(withJSONObject: records, options: [.sortedKeys])
        try data.write(to: url, options: .atomic)
    }
}
