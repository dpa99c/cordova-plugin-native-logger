package uk.co.workingedge.nativelogger;

import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class NativeLogcatReader implements Runnable {
    private static final String LOG_TAG = "NativeLogger";
    private static final String BACKLOG_MARKER_PREFIX = "native logger backlog flushed; id=";

    interface Listener {
        String resumeTimestamp();
        void onLine(String line);
        void onBacklogFlushed(String timestamp);
        void onStatus(String status, String message);
    }

    private final Listener listener;
    private final boolean markBacklogFlush;
    private final String backlogMarker;
    private volatile boolean stopped;
    private volatile Process process;
    private Thread thread;
    private boolean backlogFlushed;

    NativeLogcatReader(Listener listener, boolean markBacklogFlush) {
        this.listener = listener;
        this.markBacklogFlush = markBacklogFlush;
        backlogMarker = BACKLOG_MARKER_PREFIX + UUID.randomUUID();
    }

    synchronized void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this, "NativeLoggerReader");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stopAndJoin() {
        stopped = true;
        Process active = process;
        if (active != null) {
            active.destroy();
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    @Override
    public void run() {
        int backoffSeconds = 1;
        while (!stopped) {
            try {
                String since = listener.resumeTimestamp();
                List<String> command = new ArrayList<>(Arrays.asList(
                    "logcat",
                    "-v",
                    "threadtime,year",
                    "-b",
                    "main",
                    "-b",
                    "system",
                    "-b",
                    "crash",
                    "-T",
                    since == null ? "1" : since
                ));
                Process active = new ProcessBuilder(command).redirectErrorStream(true).start();
                process = active;
                if (stopped) {
                    active.destroy();
                    return;
                }
                if (markBacklogFlush && !backlogFlushed) {
                    Log.i(LOG_TAG, backlogMarker);
                }
                try (
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(active.getInputStream(), StandardCharsets.UTF_8)
                    )
                ) {
                    String line;
                    while (!stopped && (line = reader.readLine()) != null) {
                        LogLine entry = LogLine.parse(line);
                        if (
                            entry != null &&
                            LOG_TAG.equals(entry.tag) &&
                            entry.message.startsWith(BACKLOG_MARKER_PREFIX)
                        ) {
                            if (markBacklogFlush && !backlogFlushed &&
                                backlogMarker.equals(entry.message)) {
                                backlogFlushed = true;
                                listener.onBacklogFlushed(entry.timestamp);
                            }
                            continue;
                        }
                        listener.onLine(line);
                    }
                }
                if (stopped) {
                    return;
                }
                int exitCode = active.waitFor();
                listener.onStatus("reader-restarted", "logcat exited with code " + exitCode);
            } catch (IOException exception) {
                if (!stopped) {
                    listener.onStatus("reader-restarted", exception.getMessage());
                }
            } catch (InterruptedException exception) {
                if (stopped) {
                    return;
                }
                Thread.currentThread().interrupt();
            } finally {
                process = null;
            }

            if (!stopped) {
                try {
                    Thread.sleep(backoffSeconds * 1000L);
                } catch (InterruptedException exception) {
                    if (stopped) {
                        return;
                    }
                    Thread.currentThread().interrupt();
                }
                backoffSeconds = Math.min(backoffSeconds * 2, 30);
            }
        }
    }
}
