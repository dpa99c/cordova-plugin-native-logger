package uk.co.workingedge.nativelogger;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.PowerManager;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class DeviceDiagnostics {
    private DeviceDiagnostics() {}

    static JSONObject snapshot(Context context, String appState) throws JSONException {
        JSONArray unavailable = new JSONArray();
        JSONObject notifications = new JSONObject();
        JSONObject interruptions = new JSONObject();
        JSONObject audio = new JSONObject();
        JSONObject power = new JSONObject();

        NotificationManager notificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notifications.put("enabled", notificationManager.areNotificationsEnabled());
            JSONArray channels = new JSONArray();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                for (NotificationChannel channel : notificationManager.getNotificationChannels()) {
                    channels.put(new JSONObject()
                        .put("id", channel.getId())
                        .put("name", channel.getName())
                        .put("importance", channel.getImportance())
                        .put("sound", channel.getSound() == null ? JSONObject.NULL : channel.getSound().toString())
                        .put("vibration", channel.shouldVibrate())
                        .put("bypassDnd", channel.canBypassDnd()));
                }
            }
            notifications.put("channels", channels);
            interruptions.put("doNotDisturb", interruptionFilter(notificationManager.getCurrentInterruptionFilter()));
        } else {
            notifications.put("enabled", JSONObject.NULL);
            notifications.put("channels", JSONObject.NULL);
            interruptions.put("doNotDisturb", JSONObject.NULL);
            unavailable.put("notifications");
        }
        unavailable.put("notifications.cooldown");

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audio.put("ringerMode", ringerMode(audioManager.getRingerMode()));
            audio.put("notificationVolume", volume(audioManager, AudioManager.STREAM_NOTIFICATION));
            audio.put("mediaVolume", volume(audioManager, AudioManager.STREAM_MUSIC));
            audio.put("otherAudioPlaying", audioManager.isMusicActive());
            audio.put("outputRoute", outputRoute(audioManager));
        } else {
            audio.put("ringerMode", JSONObject.NULL);
            audio.put("notificationVolume", JSONObject.NULL);
            audio.put("mediaVolume", JSONObject.NULL);
            audio.put("otherAudioPlaying", JSONObject.NULL);
            audio.put("outputRoute", JSONObject.NULL);
            unavailable.put("audio");
        }

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (powerManager != null) {
            power.put("lowPowerMode", powerManager.isPowerSaveMode());
            power.put("deviceIdle", powerManager.isDeviceIdleMode());
            power.put("ignoringBatteryOptimisations",
                powerManager.isIgnoringBatteryOptimizations(context.getPackageName()));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                power.put("thermalState", thermalState(powerManager.getCurrentThermalStatus()));
            } else {
                power.put("thermalState", JSONObject.NULL);
                unavailable.put("power.thermalState");
            }
        } else {
            power.put("lowPowerMode", JSONObject.NULL);
            power.put("deviceIdle", JSONObject.NULL);
            power.put("ignoringBatteryOptimisations", JSONObject.NULL);
            power.put("thermalState", JSONObject.NULL);
            unavailable.put("power");
        }
        if (activityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            power.put("backgroundRestricted", activityManager.isBackgroundRestricted());
        } else {
            power.put("backgroundRestricted", JSONObject.NULL);
            unavailable.put("power.backgroundRestricted");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
            power.put("standbyBucket", usage == null ? JSONObject.NULL : standbyBucket(usage.getAppStandbyBucket()));
        } else {
            power.put("standbyBucket", JSONObject.NULL);
            unavailable.put("power.standbyBucket");
        }
        power.put("backgroundRefresh", JSONObject.NULL);
        unavailable.put("power.backgroundRefresh");

        return new JSONObject()
            .put("platform", "android")
            .put("timestamp", System.currentTimeMillis())
            .put("appState", appState)
            .put("notifications", notifications)
            .put("interruptions", interruptions)
            .put("audio", audio)
            .put("power", power)
            .put("unavailable", unavailable);
    }

    private static String interruptionFilter(int filter) {
        if (filter == NotificationManager.INTERRUPTION_FILTER_ALL) return "off";
        if (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) return "priority";
        if (filter == NotificationManager.INTERRUPTION_FILTER_ALARMS) return "alarms";
        if (filter == NotificationManager.INTERRUPTION_FILTER_NONE) return "none";
        return "unknown";
    }

    private static String ringerMode(int mode) {
        if (mode == AudioManager.RINGER_MODE_NORMAL) return "normal";
        if (mode == AudioManager.RINGER_MODE_VIBRATE) return "vibrate";
        if (mode == AudioManager.RINGER_MODE_SILENT) return "silent";
        return "unknown";
    }

    private static double volume(AudioManager audioManager, int stream) {
        int maximum = audioManager.getStreamMaxVolume(stream);
        return maximum == 0 ? 0 : (double) audioManager.getStreamVolume(stream) / maximum;
    }

    private static String outputRoute(AudioManager audioManager) {
        List<String> routes = new ArrayList<>();
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            String route = routeName(device.getType());
            if (!routes.contains(route)) {
                routes.add(route);
            }
        }
        return routes.isEmpty() ? "unknown" : String.join(",", routes);
    }

    private static String routeName(int type) {
        if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return "speaker";
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
            return "headphones";
        }
        if (
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        ) {
            return "bluetooth";
        }
        return "other";
    }

    private static String thermalState(int state) {
        if (state == PowerManager.THERMAL_STATUS_NONE) return "none";
        if (state == PowerManager.THERMAL_STATUS_LIGHT) return "light";
        if (state == PowerManager.THERMAL_STATUS_MODERATE) return "moderate";
        if (state == PowerManager.THERMAL_STATUS_SEVERE) return "severe";
        if (state == PowerManager.THERMAL_STATUS_CRITICAL) return "critical";
        if (state == PowerManager.THERMAL_STATUS_EMERGENCY) return "emergency";
        if (state == PowerManager.THERMAL_STATUS_SHUTDOWN) return "shutdown";
        return "unknown";
    }

    private static String standbyBucket(int bucket) {
        if (bucket == UsageStatsManager.STANDBY_BUCKET_ACTIVE) return "active";
        if (bucket == UsageStatsManager.STANDBY_BUCKET_WORKING_SET) return "working_set";
        if (bucket == UsageStatsManager.STANDBY_BUCKET_FREQUENT) return "frequent";
        if (bucket == UsageStatsManager.STANDBY_BUCKET_RARE) return "rare";
        if (bucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED) return "restricted";
        return "unknown";
    }
}
