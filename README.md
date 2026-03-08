# Overlay Guard

Overlay Guard is a high-performance system utility designed to bring the advanced privacy features of the 2026 Galaxy S26 Ultra to Android 13+ devices. By leveraging real-time sensor fusion and the WindowManager API, this app simulates a "software-defined" hardware privacy screen.

## Project Info

| Key | Value |
|---|---|
| Package | `com.moclg.overlayguard` |
| Target SDK | 34 (Android 14) |
| Min SDK | 33 (Android 13) |
| Language | Kotlin |
| Build System | Gradle 8.11 / AGP 8.7, Kotlin DSL |
| UI | Jetpack Compose (Material 3) |
| Target Device | Rooted Samsung Note 20 Ultra (custom kernel) |

## Technical Architecture

### PrivacyOverlayService (AccessibilityService)
- Uses `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY` to draw a black `View` over the status bar.
- Flags: `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN` — the overlay covers the notch/status bar without intercepting touch events.
- Overlay height (default 150 px) and roll threshold are stored in `SharedPreferences` and updated at runtime.

### Sensor Fusion (RollSensorListener)
- Registers `Sensor.TYPE_ROTATION_VECTOR` at `SENSOR_DELAY_UI`.
- Derives a 3×3 rotation matrix via `SensorManager.getRotationMatrixFromVector`.
- Extracts orientation angles via `SensorManager.getOrientation`; index **2** = **roll** (radians → degrees).
- Trigger logic: `|roll| > threshold ⟹ α = 1.0`; otherwise `α = 0.0`.

### Compose Dashboard (MainActivity)
- **Start / Stop Toggle** — on rooted devices, automatically runs `settings put` commands via `su`; on non-rooted devices, opens `ACTION_ACCESSIBILITY_SETTINGS`.
- **Height Slider** — adjusts overlay height from 50 px to 500 px.
- **Sensitivity Slider** — adjusts roll threshold from 10° to 45°.

### Root Automation (RootHelper)
- Detects root by checking for `su` binary paths (Magisk, KernelSU, legacy).
- Executes via `Runtime.exec("su", "-c", …)`:
  ```
  settings put secure enabled_accessibility_services com.moclg.overlayguard/com.moclg.overlayguard.service.PrivacyOverlayService
  settings put secure accessibility_enabled 1
  ```

## Restricted Settings Bypass (Android 13 / 14)

Starting with Android 13, sideloaded apps are blocked from enabling accessibility services. To bypass:

1. **Go to** Settings → Apps → Overlay Guard → ⋮ (three-dot menu).
2. **Tap** "Allow Restricted Settings".
3. **Authenticate** with your PIN / biometric.
4. **Now navigate** to Settings → Accessibility → Overlay Guard and enable the service.

> On rooted devices this step is unnecessary — the root toggle writes the settings directly.

## Building

```bash
# Make sure ANDROID_HOME / JAVA_HOME are set
./gradlew assembleDebug
# Install on device
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Git Conventions

All commits must be signed off:

```bash
git commit -s -m "type: description"
```

Types: `feat`, `fix`, `docs`, `refactor`, `chore`.

## Build Status

- [x] Milestone 1 — Project Scaffolding & Manifest
- [x] Milestone 2 — Privacy Overlay Service
- [x] Milestone 3 — Sensor Fusion
- [x] Milestone 4 — UI & Automation
- [x] Milestone 5 — Documentation

## License

Proprietary — all rights reserved.
