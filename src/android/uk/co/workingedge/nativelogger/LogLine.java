package uk.co.workingedge.nativelogger;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

final class LogLine {
    private static final Pattern LINE_PATTERN = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+([^:]+):\\s?(.*)$"
    );
    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";

    final String timestamp;
    final int pid;
    final int tid;
    final String level;
    final String tag;
    final String message;

    private LogLine(String timestamp, int pid, int tid, String level, String tag, String message) {
        this.timestamp = timestamp;
        this.pid = pid;
        this.tid = tid;
        this.level = level;
        this.tag = tag;
        this.message = message;
    }

    static LogLine parse(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = LINE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new LogLine(
                matcher.group(1),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                matcher.group(4),
                matcher.group(5).trim(),
                matcher.group(6)
            );
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static String format(Date date, int pid, int tid, String level, String tag, String message) {
        SimpleDateFormat formatter = new SimpleDateFormat(DATE_PATTERN, Locale.US);
        formatter.setTimeZone(TimeZone.getDefault());
        return formatter.format(date) + String.format(
            Locale.US,
            "  %5d  %5d %s %s: %s",
            pid,
            tid,
            level,
            tag,
            message == null ? "" : message.replace('\n', ' ')
        );
    }

    static String truncateLineMessage(String line, int maxMessageLength) {
        if (maxMessageLength <= 0) {
            return line;
        }
        Matcher matcher = LINE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return line;
        }
        String message = matcher.group(6);
        int messageCodePoints = message.codePointCount(0, message.length());
        if (messageCodePoints <= maxMessageLength) {
            return line;
        }
        int messageEnd = message.offsetByCodePoints(0, maxMessageLength);
        return line.substring(0, matcher.start(6)) +
            message.substring(0, messageEnd) +
            "… [+" + (messageCodePoints - maxMessageLength) + " chars]";
    }

    static Integer maxMessageLength(Object value) {
        if (value == null) {
            return 0;
        }
        if (!(value instanceof Number)) {
            return null;
        }
        double numericValue = ((Number) value).doubleValue();
        if (Double.isNaN(numericValue) ||
            Double.isInfinite(numericValue) ||
            numericValue < 0 ||
            numericValue > Integer.MAX_VALUE ||
            numericValue != Math.floor(numericValue)) {
            return null;
        }
        return (int) numericValue;
    }

    static Date parseTimestamp(String timestamp) {
        SimpleDateFormat formatter = new SimpleDateFormat(DATE_PATTERN, Locale.US);
        formatter.setLenient(false);
        formatter.setTimeZone(TimeZone.getDefault());
        try {
            return formatter.parse(timestamp);
        } catch (ParseException exception) {
            return null;
        }
    }

    static int rank(String level) {
        if ("V".equals(level)) return 0;
        if ("D".equals(level)) return 1;
        if ("I".equals(level)) return 2;
        if ("W".equals(level)) return 3;
        if ("E".equals(level)) return 4;
        if ("F".equals(level)) return 5;
        return -1;
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
            .put("type", "entry")
            .put("timestamp", timestamp)
            .put("pid", pid)
            .put("tid", tid)
            .put("level", level)
            .put("tag", tag)
            .put("message", message);
    }
}
