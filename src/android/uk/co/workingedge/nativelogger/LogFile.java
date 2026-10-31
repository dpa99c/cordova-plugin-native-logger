package uk.co.workingedge.nativelogger;

import android.content.Context;
import android.net.Uri;
import android.os.Process;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import org.json.JSONObject;

final class LogFile {
    static final long DEFAULT_MAX_AGE_MS = 2L * 24L * 60L * 60L * 1000L;
    static final long DEFAULT_MAX_FILE_SIZE_BYTES = 5L * 1024L * 1024L;

    private final File directory;
    private final File currentFile;
    private final File previousFile;
    private final long maxFileSizeBytes;
    private FileOutputStream output;
    private BufferedWriter writer;

    private LogFile(File directory, JSONObject options) {
        this.directory = directory;
        currentFile = new File(directory, "native.log");
        previousFile = new File(directory, "native.log.1");
        maxFileSizeBytes = Math.max(
            1024L,
            options == null
                ? DEFAULT_MAX_FILE_SIZE_BYTES
                : options.optLong("maxFileSizeBytes", DEFAULT_MAX_FILE_SIZE_BYTES)
        );
    }

    static LogFile open(Context context, JSONObject options) throws IOException {
        File directory = new File(context.getFilesDir(), "native-logger");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create native logger directory");
        }
        LogFile logFile = new LogFile(directory, options);
        long maxAgeMs = options == null
            ? DEFAULT_MAX_AGE_MS
            : options.optLong("maxAgeMs", DEFAULT_MAX_AGE_MS);
        logFile.pruneOlderThan(maxAgeMs);
        logFile.openWriter();
        return logFile;
    }

    synchronized void append(String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (currentFile.length() > 0 && currentFile.length() + bytes.length > maxFileSizeBytes) {
            rotate();
        }
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    synchronized void writeSessionMarker(String message) throws IOException {
        String line = LogLine.format(
            new Date(),
            Process.myPid(),
            Process.myPid(),
            "I",
            "NativeLogger",
            message
        );
        append(line);
    }

    synchronized String lastTimestamp() throws IOException {
        String timestamp = lastTimestampIn(currentFile);
        return timestamp != null ? timestamp : lastTimestampIn(previousFile);
    }

    synchronized void flush() throws IOException {
        if (writer != null) {
            writer.flush();
            output.getFD().sync();
        }
    }

    synchronized void close() throws IOException {
        if (writer != null) {
            flush();
            writer.close();
            writer = null;
            output = null;
        }
    }

    synchronized String exportTo(File cacheDirectory) throws IOException {
        flush();
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw new IOException("Unable to create native logger export directory");
        }
        File exportedFile = new File(cacheDirectory, "native-log-" + System.currentTimeMillis() + ".log");
        try (FileOutputStream destination = new FileOutputStream(exportedFile)) {
            if (previousFile.exists()) {
                copy(previousFile, destination);
            }
            if (currentFile.exists()) {
                copy(currentFile, destination);
            }
            destination.getFD().sync();
        }
        return Uri.fromFile(exportedFile).toString();
    }

    private void pruneOlderThan(long maxAgeMs) throws IOException {
        if (maxAgeMs <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        pruneFile(currentFile, cutoff);
        pruneFile(previousFile, cutoff);
    }

    private void pruneFile(File file, long cutoff) throws IOException {
        if (!file.exists()) {
            return;
        }
        File temporary = new File(directory, file.getName() + ".tmp");
        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)
            );
            FileOutputStream fileOutput = new FileOutputStream(temporary);
            BufferedWriter temporaryWriter = new BufferedWriter(
                new OutputStreamWriter(fileOutput, StandardCharsets.UTF_8)
            )
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                LogLine parsed = LogLine.parse(line);
                Date date = parsed == null ? null : LogLine.parseTimestamp(parsed.timestamp);
                if (date == null || date.getTime() >= cutoff) {
                    temporaryWriter.write(line);
                    temporaryWriter.newLine();
                }
            }
            temporaryWriter.flush();
            fileOutput.getFD().sync();
        }
        if (!file.delete() || !temporary.renameTo(file)) {
            temporary.delete();
            throw new IOException("Unable to replace pruned native log: " + file.getName());
        }
    }

    private void rotate() throws IOException {
        close();
        if (previousFile.exists() && !previousFile.delete()) {
            throw new IOException("Unable to remove previous native log");
        }
        if (currentFile.exists() && !currentFile.renameTo(previousFile)) {
            throw new IOException("Unable to rotate native log");
        }
        openWriter();
    }

    private void openWriter() throws IOException {
        output = new FileOutputStream(currentFile, true);
        writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
    }

    private static String lastTimestampIn(File file) throws IOException {
        if (!file.exists()) {
            return null;
        }
        String latest = null;
        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)
            )
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                LogLine parsed = LogLine.parse(line);
                if (parsed != null) {
                    latest = parsed.timestamp;
                }
            }
        }
        return latest;
    }

    private static void copy(File source, FileOutputStream destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                destination.write(buffer, 0, count);
            }
        }
    }
}
