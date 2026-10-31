package uk.co.workingedge.nativelogger;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class NativeLoggerPlugin extends CordovaPlugin implements NativeLogcatReader.Listener {
    private static final String TAG = "NativeLogger";
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final int MAX_RECENT_LINES = 4096;
    private static final String[] NEVER_FORWARD_TAGS = {"NativeLogger", "chromium", "Console"};

    private final Object stateLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            SessionTracker activeSession = session;
            if (activeSession != null) {
                try {
                    activeSession.heartbeat("active".equals(appState));
                } catch (IOException | JSONException exception) {
                    Log.e(TAG, "Failed to persist session heartbeat", exception);
                }
            }
            if (running) {
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        }
    };

    private volatile boolean running;
    private boolean starting;
    private volatile String appState = "active";
    private JSONObject options;
    private LogFile logFile;
    private SessionTracker session;
    private NativeLogcatReader reader;
    private CallbackContext eventCallback;
    private String persistedResumeTimestamp;
    private volatile String latestResumeTimestamp;
    private final LinkedHashMap<String, Boolean> recentLines = new LinkedHashMap<>();

    @Override
    protected void pluginInitialize() {
        installUncaughtExceptionHandler();
    }

    @Override
    public boolean execute(String action, org.json.JSONArray args, CallbackContext callback) {
        if ("getCapabilities".equals(action)) {
            try {
                callback.success(capabilities());
            } catch (JSONException exception) {
                sendError(callback, "CAPABILITIES_FAILED", exception.getMessage());
            }
            return true;
        }

        if ("start".equals(action)) {
            JSONObject startOptions = args.optJSONObject(0);
            cordova.getThreadPool().execute(() -> start(startOptions, callback));
            return true;
        }

        cordova.getThreadPool().execute(() -> executeActiveAction(action, args, callback));
        return true;
    }

    private void start(JSONObject requestedOptions, CallbackContext callback) {
        synchronized (stateLock) {
            if (running || starting) {
                sendError(callback, "ALREADY_STARTED", "Native logger is already started");
                return;
            }
            starting = true;
        }

        JSONObject startOptions = requestedOptions == null ? new JSONObject() : requestedOptions;
        String minimumLevel = startOptions.optString("minLevel", "D");
        JSONObject forward = startOptions.optJSONObject("forward");
        String forwardMinimumLevel = forward == null ? "W" : forward.optString("minLevel", "W");
        if (LogLine.rank(minimumLevel) < 0 || LogLine.rank(forwardMinimumLevel) < 0) {
            synchronized (stateLock) {
                starting = false;
            }
            sendError(callback, "INVALID_OPTIONS", "minLevel and forward.minLevel must be V, D, I, W, E or F");
            return;
        }

        LogFile openedLog = null;
        SessionTracker openedSession = null;
        try {
            Context context = cordova.getContext().getApplicationContext();
            openedLog = LogFile.open(context, startOptions);
            String resumeTimestamp = openedLog.lastTimestamp();
            openedSession = SessionTracker.begin(context);
            openedLog.writeSessionMarker(openedSession.describePreviousSession());
            NativeLogcatReader openedReader = new NativeLogcatReader(this);

            synchronized (stateLock) {
                options = startOptions;
                logFile = openedLog;
                session = openedSession;
                eventCallback = callback;
                reader = openedReader;
                persistedResumeTimestamp = resumeTimestamp;
                latestResumeTimestamp = resumeTimestamp;
                recentLines.clear();
                starting = false;
                running = true;
            }

            try {
                recordSystemExitReasons(context, openedSession);
            } catch (IOException | JSONException exception) {
                Log.e(TAG, "Failed to record system process exit reasons", exception);
                sendStatus("exit-reasons-failed", exception.getMessage(), true);
            }
            mainHandler.removeCallbacks(heartbeat);
            mainHandler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS);
            sendStatus("started", null, true);
            openedReader.start();
        } catch (IOException | JSONException exception) {
            synchronized (stateLock) {
                starting = false;
                running = false;
                eventCallback = null;
                logFile = null;
                session = null;
                options = null;
            }
            if (openedLog != null) {
                try {
                    openedLog.close();
                } catch (IOException closeException) {
                    Log.e(TAG, "Failed to close native log after startup failure", closeException);
                }
            }
            if (openedSession != null) {
                try {
                    openedSession.markCleanExit();
                } catch (IOException | JSONException closeException) {
                    Log.e(TAG, "Failed to close session after startup failure", closeException);
                }
            }
            sendError(callback, "START_FAILED", exception.getMessage());
        }
    }

    private void executeActiveAction(String action, org.json.JSONArray args, CallbackContext callback) {
        if ("stop".equals(action)) {
            stop(callback, true);
            return;
        }
        if (!running) {
            sendError(callback, "NOT_STARTED", "Native logger is not started");
            return;
        }

        try {
            if ("flush".equals(action)) {
                logFile.flush();
                callback.success();
            } else if ("getExitReasons".equals(action)) {
                recordSystemExitReasons(cordova.getContext(), session);
                callback.success(session.exitReasons(args.optLong(0, 0L)));
            } else if ("getDiagnostics".equals(action)) {
                callback.success(DeviceDiagnostics.snapshot(cordova.getContext(), appState));
            } else if ("exportLog".equals(action)) {
                callback.success(logFile.exportTo(cordova.getContext().getCacheDir()));
            } else {
                sendError(callback, "INVALID_ACTION", "Unknown NativeLogger action: " + action);
            }
        } catch (IOException | JSONException exception) {
            sendError(callback, "OPERATION_FAILED", exception.getMessage());
        }
    }

    private void stop(CallbackContext callback, boolean sendStoppedEvent) {
        synchronized (stateLock) {
            if (!running) {
                sendError(callback, "NOT_STARTED", "Native logger is not started");
                return;
            }
            running = false;
        }

        mainHandler.removeCallbacks(heartbeat);
        NativeLogcatReader activeReader = reader;
        reader = null;
        if (activeReader != null) {
            activeReader.stopAndJoin();
        }

        IOException stopFailure = null;
        LogFile activeLog = logFile;
        if (activeLog != null) {
            try {
                activeLog.flush();
            } catch (IOException exception) {
                stopFailure = exception;
            }
            try {
                activeLog.close();
            } catch (IOException exception) {
                if (stopFailure == null) {
                    stopFailure = exception;
                }
            }
        }

        SessionTracker activeSession = session;
        if (activeSession != null) {
            try {
                activeSession.markCleanExit();
            } catch (IOException | JSONException exception) {
                if (stopFailure == null) {
                    stopFailure = new IOException(exception);
                }
            }
        }

        logFile = null;
        session = null;
        options = null;
        persistedResumeTimestamp = null;
        latestResumeTimestamp = null;
        recentLines.clear();

        if (stopFailure != null) {
            Log.e(TAG, "Failed to stop native logger cleanly", stopFailure);
            sendError(callback, "STOP_FAILED", stopFailure.getMessage());
            closeEventCallback();
            return;
        }

        if (sendStoppedEvent) {
            sendStatus("stopped", null, false);
        } else {
            closeEventCallback();
        }
        logFile = null;
        callback.success();
    }

    private void closeEventCallback() {
        synchronized (stateLock) {
            eventCallback = null;
        }
    }

    @Override
    public String resumeTimestamp() {
        return latestResumeTimestamp;
    }

    @Override
    public void onLine(String line) {
        if (!running) {
            return;
        }
        LogLine entry = LogLine.parse(line);
        if (entry == null) {
            return;
        }
        String resumeTimestamp = persistedResumeTimestamp;
        if (resumeTimestamp != null && entry.timestamp.compareTo(resumeTimestamp) <= 0) {
            return;
        }
        if (LogLine.rank(entry.level) < LogLine.rank(options.optString("minLevel", "D"))) {
            updateResumeTimestamp(entry.timestamp);
            return;
        }
        if (recentLines.containsKey(line)) {
            updateResumeTimestamp(entry.timestamp);
            return;
        }
        try {
            logFile.append(line);
            recentLines.put(line, Boolean.TRUE);
            if (recentLines.size() > MAX_RECENT_LINES) {
                recentLines.remove(recentLines.keySet().iterator().next());
            }
            updateResumeTimestamp(entry.timestamp);
        } catch (IOException exception) {
            Log.e(TAG, "Failed to append native log entry", exception);
            sendStatus("write-failed", exception.getMessage(), true);
            return;
        }
        if (shouldForward(entry)) {
            try {
                sendEvent(entry.toJson(), true);
            } catch (JSONException exception) {
                Log.e(TAG, "Failed to encode native log entry", exception);
            }
        }
    }

    private void updateResumeTimestamp(String timestamp) {
        String latestTimestamp = latestResumeTimestamp;
        if (latestTimestamp == null || timestamp.compareTo(latestTimestamp) > 0) {
            latestResumeTimestamp = timestamp;
        }
    }

    @Override
    public void onStatus(String status, String message) {
        sendStatus(status, message, true);
    }

    private boolean shouldForward(LogLine entry) {
        for (String tag : NEVER_FORWARD_TAGS) {
            if (tag.equals(entry.tag)) {
                return false;
            }
        }
        JSONObject forward = options.optJSONObject("forward");
        String minimum = forward == null ? "W" : forward.optString("minLevel", "W");
        if (LogLine.rank(entry.level) < LogLine.rank(minimum)) {
            return false;
        }
        JSONArray excluded = forward == null ? null : forward.optJSONArray("excludeTags");
        if (matchesTag(entry.tag, excluded)) {
            return false;
        }
        JSONArray included = forward == null ? null : forward.optJSONArray("tags");
        return included == null || included.length() == 0 || matchesTag(entry.tag, included);
    }

    private static boolean matchesTag(String tag, JSONArray tags) {
        if (tags == null) {
            return false;
        }
        for (int index = 0; index < tags.length(); index++) {
            String filter = tags.optString(index);
            if (tag.equals(filter) || tag.startsWith(filter + "/") || tag.startsWith(filter + ":")) {
                return true;
            }
        }
        return false;
    }

    private void sendStatus(String status, String message, boolean keepCallback) {
        try {
            JSONObject event = new JSONObject()
                .put("type", "status")
                .put("status", status);
            if ("started".equals(status)) {
                event.put("capabilities", capabilities());
                event.put("ignoredOptions", ignoredOptions());
            }
            if (message != null) {
                event.put("message", message);
            }
            sendEvent(event, keepCallback);
        } catch (JSONException exception) {
            Log.e(TAG, "Failed to encode native logger status", exception);
        }
    }

    private void sendEvent(JSONObject event, boolean keepCallback) {
        CallbackContext callback = eventCallback;
        if (callback == null) {
            return;
        }
        PluginResult result = new PluginResult(PluginResult.Status.OK, event);
        result.setKeepCallback(keepCallback);
        callback.sendPluginResult(result);
        if (!keepCallback) {
            closeEventCallback();
        }
    }

    private static JSONObject capabilities() throws JSONException {
        return new JSONObject()
            .put("platform", "android")
            .put("liveCapture", "stream")
            .put("previousProcessLogs", true)
            .put("exitReasons", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? "system" : "inferred")
            .put("crashReports", "logcat")
            .put("stdio", false);
    }

    private JSONArray ignoredOptions() {
        JSONArray ignored = new JSONArray();
        JSONObject ios = options.optJSONObject("ios");
        if (ios != null && ios.length() > 0) {
            ignored.put("ios.*");
        }
        return ignored;
    }

    private void recordSystemExitReasons(Context context, SessionTracker activeSession)
        throws IOException, JSONException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || activeSession == null) {
            return;
        }
        ActivityManager activityManager =
            (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return;
        }
        List<ApplicationExitInfo> exits = activityManager.getHistoricalProcessExitReasons(
            context.getPackageName(),
            0,
            0
        );
        for (ApplicationExitInfo exit : exits) {
            JSONObject details = new JSONObject()
                .put("pss", exit.getPss())
                .put("rss", exit.getRss())
                .put("importance", exit.getImportance());
            activeSession.recordExit(new JSONObject()
                .put("timestamp", exit.getTimestamp())
                .put("pid", exit.getPid())
                .put("reason", exitReason(exit.getReason()))
                .put("description", exit.getDescription() == null ? "" : exit.getDescription())
                .put("source", "system")
                .put("details", details));
        }
    }

    private static String exitReason(int reason) {
        if (reason == ApplicationExitInfo.REASON_EXIT_SELF) return "exit-self";
        if (reason == ApplicationExitInfo.REASON_SIGNALED) return "signalled";
        if (reason == ApplicationExitInfo.REASON_LOW_MEMORY) return "low-memory";
        if (reason == ApplicationExitInfo.REASON_CRASH) return "crash";
        if (reason == ApplicationExitInfo.REASON_CRASH_NATIVE) return "native-crash";
        if (reason == ApplicationExitInfo.REASON_ANR) return "anr";
        if (reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE) return "initialization-failure";
        if (reason == ApplicationExitInfo.REASON_PERMISSION_CHANGE) return "permission-change";
        if (reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE) return "resource-limit";
        if (reason == ApplicationExitInfo.REASON_USER_REQUESTED) return "user-requested";
        if (reason == ApplicationExitInfo.REASON_USER_STOPPED) return "user-stopped";
        if (reason == ApplicationExitInfo.REASON_DEPENDENCY_DIED) return "dependency-died";
        if (reason == ApplicationExitInfo.REASON_FREEZER) return "freezer";
        return "other";
    }

    private void setAppState(String state) {
        appState = state;
        SessionTracker activeSession = session;
        if (activeSession != null) {
            try {
                activeSession.heartbeat("active".equals(state));
            } catch (IOException | JSONException exception) {
                Log.e(TAG, "Failed to persist app lifecycle state", exception);
            }
        }
        if ("background".equals(state) && logFile != null) {
            try {
                logFile.flush();
            } catch (IOException exception) {
                Log.e(TAG, "Failed to flush native log on pause", exception);
            }
        }
    }

    @Override
    public void onPause(boolean multitasking) {
        setAppState("background");
        super.onPause(multitasking);
    }

    @Override
    public void onResume(boolean multitasking) {
        setAppState("active");
        super.onResume(multitasking);
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(heartbeat);
        stopAndClose(false);
        if (session != null) {
            try {
                session.markCleanExit();
            } catch (IOException | JSONException exception) {
                Log.e(TAG, "Failed to mark native logger session clean", exception);
            }
        }
        super.onDestroy();
    }

    private void stopAndClose(boolean closeSession) {
        running = false;
        NativeLogcatReader activeReader = reader;
        reader = null;
        if (activeReader != null) {
            activeReader.stopAndJoin();
        }
        if (logFile != null) {
            try {
                logFile.flush();
                logFile.close();
            } catch (IOException exception) {
                Log.e(TAG, "Failed to close native log", exception);
            }
            logFile = null;
        }
        if (closeSession && session != null) {
            try {
                session.markCleanExit();
            } catch (IOException | JSONException exception) {
                Log.e(TAG, "Failed to mark native logger session clean", exception);
            }
        }
        closeEventCallback();
    }

    private void installUncaughtExceptionHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (logFile != null) {
                try {
                    logFile.flush();
                } catch (IOException exception) {
                    Log.e(TAG, "Failed to flush native log before crash", exception);
                }
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                thread.getThreadGroup().uncaughtException(thread, error);
            }
        });
    }

    private static void sendError(CallbackContext callback, String code, String message) {
        JSONObject error = new JSONObject();
        try {
            error.put("code", code);
            error.put("message", message == null ? code : message);
        } catch (JSONException exception) {
            callback.error(code);
            return;
        }
        callback.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, error));
    }
}
