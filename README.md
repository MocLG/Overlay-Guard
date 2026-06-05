# Overlay Guard

Overlay Guard is an Android 13+ privacy utility that monitors the front camera
for shoulder-surfing risk and blanks the display through privileged Root or
Shizuku execution paths. The legacy accessibility service, tilt trigger, and
visual overlay implementation have been removed.

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

- **Root Mode**: opens a persistent `su` shell and uses Android 13-compatible
  `service call power` binder transactions for `IPowerManager.goToSleep()` and
  `IPowerManager.wakeUp()`.
- **Shizuku Mode**: uses the Shizuku SDK, `SystemServiceHelper`, and
  `ShizukuBinderWrapper` to transact directly with the system power binder.

Both modes implement `core/IExecutionHandler.kt`, so display power and system
setting operations are routed through the selected backend.

### Display Control

`engine/DisplayController.kt` supports two blackout strategies:

- **Absolute Brightness Dimming**: saves the current brightness state, switches
  to manual brightness, and forces `screen_brightness` / `screen_brightness_float`
  to panel minimum values.
- **Simulated Screen Extinguish**: calls the hidden `IPowerManager.goToSleep()`
  and restores with `IPowerManager.wakeUp()`.

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
uses responsive sampling; static periods switch to quiet sampling and eventually
pause camera analysis until motion resumes.

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

## License

Licensed under the GNU General Public License v3.0 or later.
