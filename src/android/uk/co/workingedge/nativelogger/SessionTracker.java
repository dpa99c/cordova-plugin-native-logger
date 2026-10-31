package uk.co.workingedge.nativelogger;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class SessionTracker {
    private static final int MAX_EXIT_RECORDS = 100;

    private final File sessionFile;
    private final File exitsFile;
    private final Context context;
    private final JSONObject currentSession;
    private long lastHeartbeatMs;

    private SessionTracker(Context context, JSONObject currentSession) {
        this.context = context;
        this.currentSession = currentSession;
        File directory = new File(context.getFilesDir(), "native-logger");
        sessionFile = new File(directory, "session.json");
        exitsFile = new File(directory, "exit-reasons.json");
    }

    static SessionTracker begin(Context context) throws IOException, JSONException {
        File directory = new File(context.getFilesDir(), "native-logger");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create native logger session directory");
        }
        File sessionFile = new File(directory, "session.json");
        JSONObject previous = readJson(sessionFile);
        long now = System.currentTimeMillis();
        long bootTime = now - SystemClock.elapsedRealtime();
        SessionTracker tracker = new SessionTracker(context, new JSONObject());
        if (previous != null) {
            tracker.recordPreviousSession(previous, bootTime, now);
        }

        tracker.currentSession
            .put("pid", android.os.Process.myPid())
            .put("startedAtMs", now)
            .put("bootTimeMs", bootTime)
            .put("lastHeartbeatMs", now)
            .put("appState", "active")
            .put("cleanExit", false);
        tracker.lastHeartbeatMs = now;
        tracker.writeSession();
        return tracker;
    }

    synchronized void heartbeat(boolean foreground) throws IOException, JSONException {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatMs < 30_000L && currentSession.optString("appState").equals(
            foreground ? "active" : "background"
        )) {
            return;
        }
        currentSession.put("lastHeartbeatMs", now);
        currentSession.put("appState", foreground ? "active" : "background");
        writeSession();
        lastHeartbeatMs = now;
    }

    synchronized void markCleanExit() throws IOException, JSONException {
        currentSession.put("lastHeartbeatMs", System.currentTimeMillis());
        currentSession.put("cleanExit", true);
        writeSession();
    }

    synchronized String describePreviousSession() throws JSONException {
        return "session start; app " + appVersion() + "; os Android " + Build.VERSION.RELEASE +
            "; pid " + android.os.Process.myPid() + "; booted " +
            currentSession.optLong("bootTimeMs") + "; previous session pid " +
            currentSession.optInt("previousPid", -1);
    }

    synchronized void recordExit(JSONObject record) throws IOException, JSONException {
        JSONArray records = readArray(exitsFile);
        String key = record.optLong("timestamp") + ":" + record.optInt("pid") + ":" +
            record.optString("reason");
        for (int index = 0; index < records.length(); index++) {
            JSONObject existing = records.optJSONObject(index);
            if (existing == null) {
                continue;
            }
            String existingKey = existing.optLong("timestamp") + ":" + existing.optInt("pid") + ":" +
                existing.optString("reason");
            if (key.equals(existingKey)) {
                return;
            }
        }
        records.put(record);
        while (records.length() > MAX_EXIT_RECORDS) {
            records = removeFirst(records);
        }
        writeJson(exitsFile, records);
    }

    synchronized JSONArray exitReasons(long sinceMs) throws IOException, JSONException {
        JSONArray records = readArray(exitsFile);
        List<JSONObject> sorted = new ArrayList<>();
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.optJSONObject(index);
            if (record != null && record.optLong("timestamp") > sinceMs) {
                sorted.add(record);
            }
        }
        Collections.sort(sorted, Comparator.comparingLong(record -> record.optLong("timestamp")));
        return new JSONArray(sorted);
    }

    private void recordPreviousSession(JSONObject previous, long bootTime, long now)
        throws IOException, JSONException {
        long previousBootTime = previous.optLong("bootTimeMs");
        boolean clean = previous.optBoolean("cleanExit", true);
        if (clean) {
            return;
        }
        String previousState = previous.optString("appState", "background");
        String reason = Math.abs(previousBootTime - bootTime) > 60_000L
            ? "reboot"
            : ("active".equals(previousState) ? "unexpected-foreground" : "unexpected-background");
        JSONObject details = new JSONObject()
            .put("lastHeartbeatMs", previous.optLong("lastHeartbeatMs"))
            .put("previousBootTimeMs", previousBootTime)
            .put("currentBootTimeMs", bootTime)
            .put("lastAppState", previousState);
        recordExit(new JSONObject()
            .put("timestamp", previous.optLong("lastHeartbeatMs", now))
            .put("pid", previous.optInt("pid"))
            .put("reason", reason)
            .put("description", "Previous session did not record a clean exit")
            .put("source", "inferred")
            .put("details", details));
        currentSession.put("previousPid", previous.optInt("pid", -1));
    }

    private String appVersion() {
        try {
            return context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0)
                .versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException exception) {
            return "unknown";
        }
    }

    private void writeSession() throws IOException {
        writeJson(sessionFile, currentSession);
    }

    private static JSONObject readJson(File file) throws IOException, JSONException {
        if (!file.exists()) {
            return null;
        }
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(file)) {
            bytes = new byte[(int) file.length()];
            int count = input.read(bytes);
            if (count != bytes.length) {
                throw new IOException("Unable to read complete session file");
            }
        }
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static JSONArray readArray(File file) throws IOException, JSONException {
        if (!file.exists()) {
            return new JSONArray();
        }
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(file)) {
            bytes = new byte[(int) file.length()];
            int count = input.read(bytes);
            if (count != bytes.length) {
                throw new IOException("Unable to read complete exit-reasons file");
            }
        }
        return new JSONArray(new String(bytes, StandardCharsets.UTF_8));
    }

    private static void writeJson(File file, Object value) throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!temporary.renameTo(file)) {
            if (!file.delete() || !temporary.renameTo(file)) {
                temporary.delete();
                throw new IOException("Unable to persist native logger state");
            }
        }
    }

    private static JSONArray removeFirst(JSONArray source) throws JSONException {
        JSONArray result = new JSONArray();
        for (int index = 1; index < source.length(); index++) {
            result.put(source.get(index));
        }
        return result;
    }
}
