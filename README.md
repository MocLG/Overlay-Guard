# Overlay Guard

Overlay Guard is an Android 13+ privacy utility that monitors the front camera
for shoulder-surfing risk and blanks the physical display through privileged
Root or Shizuku execution paths. The legacy accessibility service, tilt trigger,
and visual overlay implementation have been removed.

## Project Info

| Key | Value |
|---|---|
| Package | `com.moclg.overlayguard` |
| Compile / Target SDK | 34 |
| Min SDK | 33 |
| Language | Kotlin |
| UI | Jetpack Compose / Material 3 |
| Vision | CameraX `ImageAnalysis` + ML Kit Face Detection |
| Privileged modes | Root `su` or Shizuku |

## Architecture

### Execution Modes

The app exposes exactly two execution backends:

- **Root Mode**: opens a persistent `su` shell and launches a privileged
  `app_process` command for hidden `SurfaceControl` display operations.
- **Shizuku Mode**: uses the Shizuku SDK, `SystemServiceHelper`, and
  `ShizukuBinderWrapper` for privileged shell and binder access.

Both modes implement `core/IExecutionHandler.kt`, so display power and system
setting operations are routed through the selected backend.

### Display Control

`engine/DisplayController.kt` supports two blackout strategies:

- **Surface Brightness Off (-1)**: saves the current brightness state, switches
  to manual brightness, and calls hidden `SurfaceControl.setDisplayBrightness()`
  with `-1f` to turn the backlight off without putting Android to sleep.
- **Surface Power Off**: calls hidden `SurfaceControl.setDisplayPowerMode()` with
  `POWER_MODE_OFF` and restores with `POWER_MODE_NORMAL`. This mirrors the
  no-keyguard approach used by Extinguish-style display control instead of
  `IPowerManager.goToSleep()`, which can trigger normal lock-screen policy.

The privileged command entry point is `core/SurfaceControlCommand.java`. It is
executed with the app APK on `CLASSPATH` through Root or Shizuku, then reflects
into hidden framework display APIs from that privileged process.

### Vision Engine

`engine/CameraVisionEngine.kt` binds a front-camera CameraX `ImageAnalysis`
pipeline with `STRATEGY_KEEP_ONLY_LATEST` on a dedicated analyzer executor. ML
Kit Face Detection counts detected faces and treats the largest face as the
primary user. Extra faces are evaluated with Euler yaw/pitch plus bounding-box
projection checks, so side glances beyond the configured yaw limit are ignored.

ML Kit face detection is on-device, but the public ML Kit API does not expose an
NNAPI/GPU delegate switch for this detector. Overlay Guard keeps inference off
the main thread and drops stale frames before they reach the model.

### Adaptive Sampling

`engine/SensorPollingManager.kt` listens to `Sensor.TYPE_ACCELEROMETER`, applies
low-pass filtering, and derives a G-force flux variance window. Dynamic motion
uses responsive sampling; static periods switch to quiet sampling and only pause
camera analysis after a long idle period.

Default sampling presets:

| Preset | Dynamic | Static | Static pause |
|---|---:|---:|---:|
| Aggressive | 250 ms | 500 ms | 5 min |
| Balanced | 500 ms | 1000 ms | 10 min |
| Eco | 1000 ms | 2000 ms | 15 min |

### Foreground Service

`service/OverlayGuardService.kt` is a lifecycle-aware foreground service using
camera and special-use service types, `START_STICKY`, memory-pressure trimming,
and a low-priority persistent notification. `service/OverlayGuardReceiver.kt`
listens for `BOOT_COMPLETED` and `USER_PRESENT` and restarts monitoring when the
stored service preference is enabled.

## Building

```bash
./gradlew assembleDebug
```

This repository expects a valid Android SDK and JDK 17. In ARM64 Linux
containers, AGP's Maven AAPT2 binary may be x86_64-only; use a native AAPT2
override if needed:

```bash
ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
./gradlew --no-daemon \
  -Dorg.gradle.java.home=/opt/jdk17 \
  -Pandroid.aapt2FromMavenOverride=/usr/lib/android-sdk/build-tools/debian/aapt2 \
  assembleDebug
```

Debug APK:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

## Testing

Recommended first pass:

1. Install the debug APK.
2. Open Overlay Guard and grant Camera permission.
3. Select **Root** execution mode.
4. Select **Surface Power Off**.
5. Start monitoring and grant the root prompt.
6. Test with one face, then introduce a second face looking at the display.

If Surface Power Off still locks the device on a specific ROM, test **Surface
Brightness Off (-1)** and capture logcat around the trigger.

## Logcat

Clear old logs before reproducing:

```bash
adb logcat -c
```

Start capture:

```bash
adb logcat -v time \
  OverlayGuardService:D DisplayController:D CameraVisionEngine:D \
  SensorPollingManager:D RootHandler:D ShizukuHandler:D AndroidRuntime:E '*:S' \
  > overlay-guard.log
```

Then reproduce the issue:

1. Open Overlay Guard.
2. Select Root and the blackout type being tested.
3. Start monitoring.
4. Trigger two-face detection until the screen blanks or locks.
5. Wake/unlock if needed.
6. Stop the logcat command with `Ctrl+C`.

For framework-level power/display clues, run a wider capture:

```bash
adb logcat -v time | grep -Ei \
  'OverlayGuard|SurfaceControl|DisplayPower|PowerManager|Keyguard|WindowManager|AndroidRuntime' \
  > overlay-guard-wide.log
```

## License

Licensed under the GNU General Public License v3.0 or later.
