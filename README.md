# Cordova Native Logger

Captures native logs emitted by the current app process on Android and iOS. The plugin is intended for debug builds and tester/staff diagnostics, not general-user telemetry.

## API

The API is exposed as `cordova.plugins.nativeLogger`. Wait for `deviceready` before calling it:

```js
document.addEventListener('deviceready', function () {
  var nativeLogger = cordova.plugins.nativeLogger;
  nativeLogger.start({
    minLevel: 'D',
    maxAgeMs: 2 * 24 * 60 * 60 * 1000,
    maxFileSizeBytes: 5 * 1024 * 1024,
    forward: {
      minLevel: 'W',
      tags: ['LocalNotification', 'SpeechSynthesis', 'AndroidRuntime'],
      excludeTags: ['NativeLogger', 'chromium', 'Console'],
    },
    ios: {
      pollIntervalMs: 5000,
      captureStdio: false,
    },
  }, function (event) {
    console.log('Native logger event:', event);
  }).then(function (capabilities) {
    console.log('Native capture running:', capabilities);
  });
});
```

Methods:

| Method | Result |
| --- | --- |
| `start(options, onEvent)` | Capabilities for the running capture |
| `stop()` | Stops capture and closes the log |
| `flush()` | Persists pending entries |
| `resetLogFile()` | Clears the current and rotated native text logs |
| `pruneLogFile(maxAgeMs)` | Removes native log entries older than the supplied age in milliseconds |
| `getExitReasons(sinceMs)` | Exit records, oldest first |
| `getDiagnostics()` | Device-state snapshot with explicit unavailable fields |
| `exportLog()` | File URL of a private combined export |
| `getCapabilities()` | Platform capture capabilities |

`start()` rejects with code `ALREADY_STARTED` if capture is already running. Operations that require active capture reject with `NOT_STARTED`. Events have `type: 'entry'` or `type: 'status'`; log entries use levels `V`, `D`, `I`, `W`, `E` or `F`.

## Platform behaviour and limits

- Android streams the app's accessible logcat entries to private app files. It can recover previous-process lines still retained by the shared log buffer and uses system exit records on Android 11+. Recovery is best-effort because the buffer may wrap. The plugin never clears it.
- iOS polls `OSLogStore` for the current process and writes each batch to private Application Support storage. It cannot read other processes or recover entries not persisted before process termination. Session inference and MetricKit add context but cannot remove that limitation.
- Neither platform can read OS services or the isolated WebView renderer. Forwarded events exclude the plugin's own tag and WebView console output to prevent a feedback loop.
- iOS `captureStdio` is disabled by default. It should only be enabled when a required component logs through `print`/`printf`, and not under Xcode's `OS_ACTIVITY_DT_MODE`.
- Native logs may contain identifiers, URLs or tokens. Keep capture restricted to debug/tester use and only share exported logs when a tester opts to send a support report.

## Development

Run the JavaScript bridge tests with:

```sh
npm test
```

Platform builds and device checks are required to validate logcat access, OSLogStore polling, background persistence, process exits, MetricKit delivery and diagnostics availability. iOS exports include retained MetricKit JSON payloads alongside the combined text logs.
