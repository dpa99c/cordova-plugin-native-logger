import Cordova
import Foundation
import MetricKit
import OSLog
import UIKit

@objc(NativeLoggerPlugin)
class NativeLoggerPlugin: CDVPlugin, MXMetricManagerSubscriber {
    private static let neverForwardTags: Set<String> = ["NativeLogger", "chromium", "Console"]
    private static var activeInstance: NativeLoggerPlugin?
    private static var previousExceptionHandler: (@convention(c) (NSException) -> Void)?
    private static var exceptionHandlerInstalled = false
    private static let exceptionHandler: (@convention(c) (NSException) -> Void) = { exception in
        activeInstance?.flushBeforeUncaughtException()
        previousExceptionHandler?(exception)
    }

    private let queue = DispatchQueue(label: "uk.co.workingedge.nativelogger")
    private let queueKey = DispatchSpecificKey<Bool>()
    private var logFile: NativeLogFile?
    private var session: NativeSessionTracker?
    private var store: OSLogStore?
    private var timer: DispatchSourceTimer?
    private var lifecycleObservers: [NSObjectProtocol] = []
    private var callbackId: String?
    private var options: [String: Any] = [:]
    private var appState = "active"
    private var lastDate: Date?
    private var writtenAtLastDate = Set<String>()
    private var pollFailureReported = false
    private var stdioCapture: NativeStdioCapture?

    override func pluginInitialize() {
        super.pluginInitialize()
        queue.setSpecific(key: queueKey, value: true)
        NativeLoggerPlugin.activeInstance = self
        installUncaughtExceptionHandler()
        MXMetricManager.shared.add(self)
    }

    @objc(start:)
    func start(_ command: CDVInvokedUrlCommand) {
        let requestedOptions = command.argument(at: 0) as? [String: Any] ?? [:]
        queue.async {
            self.startCapture(requestedOptions, callbackId: command.callbackId)
        }
    }

    @objc(stop:)
    func stop(_ command: CDVInvokedUrlCommand) {
        queue.async {
            guard self.logFile != nil else {
                self.sendError(command.callbackId, code: "NOT_STARTED", message: "Native logger is not started")
                return
            }
            do {
                self.poll()
                try self.stopCapture()
                self.sendSuccess(command.callbackId)
            } catch {
                self.sendError(command.callbackId, code: "STOP_FAILED", message: error.localizedDescription)
            }
        }
    }

    @objc(flush:)
    func flush(_ command: CDVInvokedUrlCommand) {
        queue.async {
            guard let logFile = self.logFile else {
                self.sendError(command.callbackId, code: "NOT_STARTED", message: "Native logger is not started")
                return
            }
            self.poll()
            do {
                try logFile.synchronize()
                self.sendSuccess(command.callbackId)
            } catch {
                self.sendError(command.callbackId, code: "FLUSH_FAILED", message: error.localizedDescription)
            }
        }
    }

    @objc(resetLogFile:)
    func resetLogFile(_ command: CDVInvokedUrlCommand) {
        queue.async {
            guard let logFile = self.logFile else {
                self.sendError(command.callbackId, code: "NOT_STARTED", message: "Native logger is not started")
                return
            }
            do {
                try logFile.reset()
                self.sendSuccess(command.callbackId)
            } catch {
                self.sendError(command.callbackId, code: "RESET_FAILED", message: error.localizedDescription)
            }
        }
    }

    @objc(pruneLogFile:)
    func pruneLogFile(_ command: CDVInvokedUrlCommand) {
        queue.async {
            guard let logFile = self.logFile else {
                self.sendError(command.callbackId, code: "NOT_STARTED", message: "Native logger is not started")
                return
            }
            let maxAgeMs = command.argument(at: 0) as? Double ??
                (self.options["maxAgeMs"] as? Double ?? NativeLogFile.defaultMaxAgeMs)
            do {
                try logFile.prune(maxAgeMs: maxAgeMs)
                self.sendSuccess(command.callbackId)
            } catch {
                self.sendError(command.callbackId, code: "PRUNE_FAILED", message: error.localizedDescription)
            }
        }
    }

    @objc(getExitReasons:)
    func getExitReasons(_ command: CDVInvokedUrlCommand) {
        queue.async {
            guard let session = self.session else {
                self.sendError(command.callbackId, code: "NOT_STARTED", message: "Native logger is not started")
                return
            }
            let sinceMs = command.argument(at: 0) as? Double ?? 0
            do {
                self.send(
                    CDVPluginResult(status: .ok, messageAs: try session.exitReasons(sinceMs: sinceMs)),
                    callbackId: command.callbackId
                )
            } catch {
                self.sendError(command.callbackId, code: "EXIT_REASONS_FAILED", message: error.localizedDescription)
            }
        }
    }

    @objc(getDiagnostics:)
    func getDiagnostics(_ command: CDVInvokedUrlCommand) {
        queue.async {
            guard self.logFile != nil else {
                self.sendError(command.callbackId, code: "NOT_STARTED", message: "Native logger is not started")
                return
            }
            NativeDeviceDiagnostics.snapshot(appState: self.appState) { snapshot in
                self.send(
                    CDVPluginResult(status: .ok, messageAs: snapshot),
                    callbackId: command.callbackId
                )
            }
        }
    }

    @objc(exportLog:)
    func exportLog(_ command: CDVInvokedUrlCommand) {
        queue.async {
            guard let logFile = self.logFile else {
                self.sendError(command.callbackId, code: "NOT_STARTED", message: "Native logger is not started")
                return
            }
            do {
                self.poll()
                let url = try logFile.exportLog()
                self.send(CDVPluginResult(status: .ok, messageAs: url.absoluteString), callbackId: command.callbackId)
            } catch {
                self.sendError(command.callbackId, code: "EXPORT_FAILED", message: error.localizedDescription)
            }
        }
    }

    @objc(getCapabilities:)
    func getCapabilities(_ command: CDVInvokedUrlCommand) {
        send(CDVPluginResult(status: .ok, messageAs: Self.capabilities()), callbackId: command.callbackId)
    }

    private func startCapture(_ requestedOptions: [String: Any], callbackId: String) {
        guard logFile == nil else {
            sendError(callbackId, code: "ALREADY_STARTED", message: "Native logger is already started")
            return
        }
        let forward = requestedOptions["forward"] as? [String: Any] ?? [:]
        let minimumLevel = requestedOptions["minLevel"] as? String ?? "D"
        let forwardMinimumLevel = forward["minLevel"] as? String ?? "W"
        guard NativeLogLine.rank(minimumLevel) >= 0, NativeLogLine.rank(forwardMinimumLevel) >= 0 else {
            sendError(
                callbackId,
                code: "INVALID_OPTIONS",
                message: "minLevel and forward.minLevel must be V, D, I, W, E or F"
            )
            return
        }

        var openedLog: NativeLogFile?
        var openedSession: NativeSessionTracker?
        do {
            let openedLogFile = try NativeLogFile(options: requestedOptions)
            openedLog = openedLogFile
            let openedSessionTracker = try NativeSessionTracker(optionsDirectory: openedLogFile.storageDirectory)
            openedSession = openedSessionTracker
            let openedStore = try OSLogStore(scope: .currentProcessIdentifier)
            try openedLogFile.writeSessionMarker(openedSessionTracker.describeStart())
            self.options = requestedOptions
            self.logFile = openedLogFile
            self.session = openedSessionTracker
            self.store = openedStore
            self.callbackId = callbackId
            self.lastDate = nil
            self.writtenAtLastDate.removeAll()
            self.appState = "active"

            var ignored = [String]()
            let iosOptions = requestedOptions["ios"] as? [String: Any] ?? [:]
            if iosOptions["captureStdio"] as? Bool == true {
                let capture = NativeStdioCapture()
                if capture.isAvailable {
                    try capture.start { [weak self] level, message in
                        self?.queue.async {
                            self?.appendStdio(level: level, message: message)
                        }
                    }
                    stdioCapture = capture
                } else {
                    ignored.append("ios.captureStdio (OS_ACTIVITY_DT_MODE)")
                }
            }

            observeLifecycle()
            startPollTimer(intervalMs: iosOptions["pollIntervalMs"] as? Int ?? 5000)
            sendStatus("started", capabilities: Self.capabilities(), ignoredOptions: ignored, keepCallback: true)
            poll()
        } catch {
            timer?.setEventHandler {}
            timer?.cancel()
            timer = nil
            lifecycleObservers.forEach(NotificationCenter.default.removeObserver)
            lifecycleObservers.removeAll()
            stdioCapture?.stop()
            stdioCapture = nil
            if let openedLog = openedLog {
                do {
                    try openedLog.close()
                } catch {
                    NSLog("NativeLogger failed to close log after start failure: %@", error.localizedDescription)
                }
            }
            if let openedSession = openedSession {
                do {
                    try openedSession.markCleanExit()
                } catch {
                    NSLog("NativeLogger failed to close session after start failure: %@", error.localizedDescription)
                }
            }
            logFile = nil
            session = nil
            store = nil
            self.callbackId = nil
            options = [:]
            lastDate = nil
            writtenAtLastDate.removeAll()
            sendError(callbackId, code: "START_FAILED", message: error.localizedDescription)
        }
    }

    private func stopCapture() throws {
        timer?.setEventHandler {}
        timer?.cancel()
        timer = nil
        lifecycleObservers.forEach(NotificationCenter.default.removeObserver)
        lifecycleObservers.removeAll()
        stdioCapture?.stop()
        stdioCapture = nil
        var stopFailure: Error?
        do {
            try logFile?.synchronize()
        } catch {
            stopFailure = error
        }
        do {
            try session?.markCleanExit()
        } catch {
            if stopFailure == nil {
                stopFailure = error
            }
        }
        do {
            try logFile?.close()
        } catch {
            if stopFailure == nil {
                stopFailure = error
            }
        }
        sendStatus("stopped", capabilities: nil, ignoredOptions: [], keepCallback: false)
        logFile = nil
        session = nil
        store = nil
        options = [:]
        callbackId = nil
        lastDate = nil
        writtenAtLastDate.removeAll()
        if let stopFailure = stopFailure {
            throw stopFailure
        }
    }

    private func startPollTimer(intervalMs: Int) {
        let interval = max(1000, intervalMs)
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .milliseconds(interval), repeating: .milliseconds(interval))
        timer.setEventHandler { [weak self] in
            self?.poll()
            do {
                try self?.session?.heartbeat(foreground: self?.appState == "active")
            } catch {
                self?.sendStatus("session-write-failed", message: error.localizedDescription)
            }
        }
        timer.resume()
        self.timer = timer
    }

    private func observeLifecycle() {
        let center = NotificationCenter.default
        let names: [(Notification.Name, String)] = [
            (UIApplication.willResignActiveNotification, "inactive"),
            (UIApplication.didEnterBackgroundNotification, "background"),
            (UIApplication.didBecomeActiveNotification, "active"),
        ]
        for (name, state) in names {
            lifecycleObservers.append(center.addObserver(forName: name, object: nil, queue: nil) { [weak self] _ in
                self?.queue.async {
                    guard let self = self, self.logFile != nil else {
                        return
                    }
                    self.appState = state
                    self.poll()
                    do {
                        try self.logFile?.synchronize()
                        try self.session?.heartbeat(foreground: state == "active")
                    } catch {
                        self.sendStatus("lifecycle-flush-failed", message: error.localizedDescription)
                    }
                }
            })
        }
        lifecycleObservers.append(center.addObserver(
            forName: UIApplication.willTerminateNotification,
            object: nil,
            queue: nil
        ) { [weak self] _ in
            self?.queue.async {
                guard let self = self, self.logFile != nil else {
                    return
                }
                self.poll()
                do {
                    try self.logFile?.synchronize()
                    try self.session?.markCleanExit()
                } catch {
                    self.sendStatus("termination-flush-failed", message: error.localizedDescription)
                }
            }
        })
    }

    private func poll() {
        guard let store = store, let logFile = logFile else {
            return
        }
        do {
            let position = store.position(date: lastDate ?? Date.distantPast)
            let entries = try store.getEntries(at: position)
            for case let entry as OSLogEntryLog in entries {
                guard isNew(entry) else {
                    continue
                }
                let line = formattedLine(for: entry)
                guard NativeLogLine.rank(line.level) >= NativeLogLine.rank(options["minLevel"] as? String ?? "D") else {
                    markProcessed(entry)
                    continue
                }
                try logFile.append(NativeLogLine.format(
                    date: entry.date,
                    pid: line.pid,
                    tid: line.tid,
                    level: line.level,
                    tag: line.tag,
                    message: line.message
                ))
                markProcessed(entry)
                if shouldForward(line) {
                    sendEvent(line.dictionary, keepCallback: true)
                }
            }
            try logFile.synchronize()
            pollFailureReported = false
        } catch {
            if !pollFailureReported {
                sendStatus("poll-failed", message: error.localizedDescription)
                pollFailureReported = true
            }
        }
    }

    private func isNew(_ entry: OSLogEntryLog) -> Bool {
        let fingerprint = fingerprint(for: entry)
        if let lastDate = lastDate, entry.date < lastDate {
            return false
        }
        if let lastDate = lastDate, entry.date == lastDate {
            return !writtenAtLastDate.contains(fingerprint)
        }
        return true
    }

    private func markProcessed(_ entry: OSLogEntryLog) {
        let fingerprint = fingerprint(for: entry)
        if let lastDate = lastDate, entry.date == lastDate {
            writtenAtLastDate.insert(fingerprint)
        } else {
            lastDate = entry.date
            writtenAtLastDate = [fingerprint]
        }
    }

    private func fingerprint(for entry: OSLogEntryLog) -> String {
        "\(entry.processIdentifier)|\(entry.threadIdentifier)|\(entry.level.rawValue)|\(entry.composedMessage)"
    }

    private func formattedLine(for entry: OSLogEntryLog) -> NativeLogLine {
        let level: String
        switch entry.level {
        case .undefined: level = "V"
        case .debug: level = "D"
        case .info, .notice: level = "I"
        case .error: level = "E"
        case .fault: level = "F"
        @unknown default: level = "I"
        }
        let message = entry.composedMessage
        let tag: String
        if !entry.subsystem.isEmpty {
            tag = entry.category.isEmpty ? entry.subsystem : "\(entry.subsystem)/\(entry.category)"
        } else if let prefix = message.range(of: #"^\w+:"#, options: .regularExpression) {
            tag = String(message[prefix].dropLast())
        } else {
            tag = entry.process
        }
        return NativeLogLine(
            timestamp: NativeLogLine.format(
                date: entry.date,
                pid: Int(entry.processIdentifier),
                tid: Int(entry.threadIdentifier),
                level: level,
                tag: tag,
                message: message
            ).components(separatedBy: "  ").first ?? "",
            pid: Int(entry.processIdentifier),
            tid: Int(entry.threadIdentifier),
            level: level,
            tag: tag,
            message: message
        )
    }

    private func shouldForward(_ entry: NativeLogLine) -> Bool {
        if Self.neverForwardTags.contains(entry.tag) ||
            entry.message.lowercased().hasPrefix("console:") ||
            entry.message.lowercased().hasPrefix("cordova console:") {
            return false
        }
        let forward = options["forward"] as? [String: Any] ?? [:]
        let minLevel = forward["minLevel"] as? String ?? "W"
        guard NativeLogLine.rank(entry.level) >= NativeLogLine.rank(minLevel) else {
            return false
        }
        let excluded = forward["excludeTags"] as? [String] ?? []
        if excluded.contains(where: {
            entry.tag == $0 || entry.tag.hasPrefix($0 + "/") || entry.tag.hasPrefix($0 + ":")
        }) {
            return false
        }
        let included = forward["tags"] as? [String] ?? []
        return included.isEmpty || included.contains(where: {
            entry.tag == $0 || entry.tag.hasPrefix($0 + "/") || entry.tag.hasPrefix($0 + ":")
        })
    }

    private func appendStdio(level: String, message: String) {
        guard let logFile = logFile else {
            return
        }
        let line = NativeLogLine.format(
            date: Date(),
            pid: Int(ProcessInfo.processInfo.processIdentifier),
            tid: Int(ProcessInfo.processInfo.processIdentifier),
            level: level,
            tag: "stdio",
            message: message
        )
        do {
            try logFile.append(line)
            if let parsed = NativeLogLine.parse(line), shouldForward(parsed) {
                sendEvent(parsed.dictionary, keepCallback: true)
            }
        } catch {
            sendStatus("write-failed", message: error.localizedDescription)
        }
    }

    private func saveMetricKitPayload(_ data: Data, reason: String) {
        guard let logFile = logFile else {
            return
        }
        do {
            let filename = "metrickit-\(Int(Date().timeIntervalSince1970 * 1000)).json"
            let url = try logFile.saveSupplementalJSON(data, name: filename)
            try logFile.append(NativeLogLine.format(
                date: Date(),
                pid: Int(ProcessInfo.processInfo.processIdentifier),
                tid: Int(ProcessInfo.processInfo.processIdentifier),
                level: "I",
                tag: "NativeLogger",
                message: "MetricKit \(reason) payload saved: \(url.lastPathComponent)"
            ))
            try session?.recordExit([
                "timestamp": Date().timeIntervalSince1970 * 1000,
                "pid": Int(ProcessInfo.processInfo.processIdentifier),
                "reason": "metrickit-\(reason)",
                "description": "MetricKit payload received",
                "source": "metrickit",
                "details": ["file": url.lastPathComponent],
            ])
        } catch {
            sendStatus("metrickit-write-failed", message: error.localizedDescription)
        }
    }

    private func sendStatus(
        _ status: String,
        capabilities: [String: Any]? = nil,
        ignoredOptions: [String] = [],
        message: String? = nil,
        keepCallback: Bool = true
    ) {
        var event: [String: Any] = ["type": "status", "status": status]
        if let capabilities = capabilities {
            event["capabilities"] = capabilities
        }
        if status == "started" {
            event["ignoredOptions"] = ignoredOptions
        }
        if let message = message {
            event["message"] = message
        }
        sendEvent(event, keepCallback: keepCallback)
    }

    private func sendEvent(_ event: [String: Any], keepCallback: Bool) {
        guard let callbackId = callbackId else {
            return
        }
        let result = CDVPluginResult(status: .ok, messageAs: event)
        result.setKeepCallbackAs(keepCallback)
        send(result, callbackId: callbackId)
        if !keepCallback {
            self.callbackId = nil
        }
    }

    private func sendSuccess(_ callbackId: String) {
        send(CDVPluginResult(status: .ok), callbackId: callbackId)
    }

    private func sendError(_ callbackId: String, code: String, message: String) {
        send(
            CDVPluginResult(status: .error, messageAs: ["code": code, "message": message]),
            callbackId: callbackId
        )
    }

    private func send(_ result: CDVPluginResult, callbackId: String) {
        commandDelegate.send(result, callbackId: callbackId)
    }

    private static func capabilities() -> [String: Any] {
        [
            "platform": "ios",
            "liveCapture": "poll",
            "previousProcessLogs": false,
            "exitReasons": "inferred",
            "crashReports": "metrickit",
            "stdio": ProcessInfo.processInfo.environment["OS_ACTIVITY_DT_MODE"] == nil,
        ]
    }

    private func flushBeforeUncaughtException() {
        let flush = {
            do {
                try self.logFile?.synchronize()
            } catch {
                NSLog("NativeLogger failed to flush before uncaught exception: %@", error.localizedDescription)
            }
        }
        if DispatchQueue.getSpecific(key: queueKey) == true {
            flush()
        } else {
            queue.sync(execute: flush)
        }
    }

    private func installUncaughtExceptionHandler() {
        guard !Self.exceptionHandlerInstalled else {
            return
        }
        Self.previousExceptionHandler = NSGetUncaughtExceptionHandler()
        NSSetUncaughtExceptionHandler(Self.exceptionHandler)
        Self.exceptionHandlerInstalled = true
    }

    private func requireActive(_ callbackId: String) -> Bool {
        guard logFile != nil else {
            sendError(callbackId, code: "NOT_STARTED", message: "Native logger is not started")
            return false
        }
        return true
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        queue.async {
            payloads.forEach { payload in
                self.saveMetricKitPayload(payload.jsonRepresentation(), reason: "diagnostic")
            }
        }
    }

    func didReceive(_ payloads: [MXMetricPayload]) {
        queue.async {
            payloads.forEach { payload in
                self.saveMetricKitPayload(payload.jsonRepresentation(), reason: "metrics")
            }
        }
    }
}
