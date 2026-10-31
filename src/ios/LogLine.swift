import Foundation

struct NativeLogLine {
    private static let pattern = #"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s?(.*)$"#
    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone.current
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        return formatter
    }()

    let timestamp: String
    let pid: Int
    let tid: Int
    let level: String
    let tag: String
    let message: String

    static func parse(_ line: String) -> NativeLogLine? {
        guard let expression = try? NSRegularExpression(pattern: pattern) else {
            return nil
        }
        let range = NSRange(line.startIndex..<line.endIndex, in: line)
        guard let match = expression.firstMatch(in: line, range: range), match.numberOfRanges == 7 else {
            return nil
        }

        func capture(_ index: Int) -> String? {
            guard let captureRange = Range(match.range(at: index), in: line) else {
                return nil
            }
            return String(line[captureRange])
        }

        guard
            let timestamp = capture(1),
            let pidText = capture(2),
            let tidText = capture(3),
            let level = capture(4),
            let tag = capture(5),
            let message = capture(6),
            let pid = Int(pidText),
            let tid = Int(tidText)
        else {
            return nil
        }
        return NativeLogLine(
            timestamp: timestamp,
            pid: pid,
            tid: tid,
            level: level,
            tag: tag.trimmingCharacters(in: .whitespaces),
            message: message
        )
    }

    static func format(
        date: Date,
        pid: Int,
        tid: Int,
        level: String,
        tag: String,
        message: String
    ) -> String {
        let timestamp = dateFormatter.string(from: date)
        let cleanMessage = message.replacingOccurrences(of: "\n", with: " ")
        return String(
            format: "%@  %5d  %5d %@ %@: %@",
            timestamp,
            pid,
            tid,
            level,
            tag,
            cleanMessage
        )
    }

    static func date(from timestamp: String) -> Date? {
        dateFormatter.date(from: timestamp)
    }

    static func rank(_ level: String) -> Int {
        switch level {
        case "V": return 0
        case "D": return 1
        case "I": return 2
        case "W": return 3
        case "E": return 4
        case "F": return 5
        default: return -1
        }
    }

    var dictionary: [String: Any] {
        [
            "type": "entry",
            "timestamp": timestamp,
            "pid": pid,
            "tid": tid,
            "level": level,
            "tag": tag,
            "message": message,
        ]
    }
}
