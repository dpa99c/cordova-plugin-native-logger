import AVFoundation
import Foundation
import UIKit
import UserNotifications

enum NativeDeviceDiagnostics {
    static func snapshot(appState: String, completion: @escaping ([String: Any]) -> Void) {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            DispatchQueue.main.async {
                let session = AVAudioSession.sharedInstance()
                let notificationStatus: String
                switch settings.authorizationStatus {
                case .authorized: notificationStatus = "enabled"
                case .provisional: notificationStatus = "provisional"
                case .ephemeral: notificationStatus = "ephemeral"
                case .denied: notificationStatus = "disabled"
                case .notDetermined: notificationStatus = "not-determined"
                @unknown default: notificationStatus = "unknown"
                }

                let route = session.currentRoute.outputs.map { output -> String in
                    switch output.portType {
                    case .builtInSpeaker: return "speaker"
                    case .headphones, .headsetMic, .lineOut: return "headphones"
                    case .bluetoothA2DP, .bluetoothHFP, .bluetoothLE: return "bluetooth"
                    default: return "other"
                    }
                }
                let thermal: String
                switch ProcessInfo.processInfo.thermalState {
                case .nominal: thermal = "nominal"
                case .fair: thermal = "fair"
                case .serious: thermal = "serious"
                case .critical: thermal = "critical"
                @unknown default: thermal = "unknown"
                }
                let backgroundRefresh: String
                switch UIApplication.shared.backgroundRefreshStatus {
                case .available: backgroundRefresh = "available"
                case .denied: backgroundRefresh = "denied"
                case .restricted: backgroundRefresh = "restricted"
                @unknown default: backgroundRefresh = "unknown"
                }
                let lockScreen = Self.setting(settings.lockScreenSetting)
                let timeSensitive = Self.setting(settings.timeSensitiveSetting)

                completion([
                    "platform": "ios",
                    "timestamp": Date().timeIntervalSince1970 * 1000,
                    "appState": appState,
                    "notifications": [
                        "enabled": settings.authorizationStatus == .authorized ||
                            settings.authorizationStatus == .provisional ||
                            settings.authorizationStatus == .ephemeral,
                        "alert": Self.setting(settings.alertSetting),
                        "sound": Self.setting(settings.soundSetting),
                        "lockScreen": lockScreen,
                        "timeSensitive": timeSensitive,
                    ],
                    "interruptions": ["doNotDisturb": NSNull()],
                    "audio": [
                        "ringerMode": NSNull(),
                        "notificationVolume": NSNull(),
                        "mediaVolume": session.outputVolume,
                        "otherAudioPlaying": session.isOtherAudioPlaying,
                        "outputRoute": route.isEmpty ? "unknown" : route.joined(separator: ","),
                    ],
                    "power": [
                        "lowPowerMode": ProcessInfo.processInfo.isLowPowerModeEnabled,
                        "thermalState": thermal,
                        "deviceIdle": NSNull(),
                        "ignoringBatteryOptimisations": NSNull(),
                        "backgroundRestricted": NSNull(),
                        "standbyBucket": NSNull(),
                        "backgroundRefresh": backgroundRefresh,
                    ],
                    "unavailable": [
                        "interruptions.doNotDisturb",
                        "audio.ringerMode",
                        "audio.notificationVolume",
                        "power.deviceIdle",
                        "power.ignoringBatteryOptimisations",
                        "power.backgroundRestricted",
                        "power.standbyBucket",
                        "notifications.cooldown",
                    ],
                ])
            }
        }
    }

    private static func setting(_ setting: UNNotificationSetting) -> String {
        switch setting {
        case .enabled: return "enabled"
        case .disabled: return "disabled"
        case .notSupported: return "not-supported"
        @unknown default: return "unknown"
        }
    }
}
