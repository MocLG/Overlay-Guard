# Overlay Guard

Overlay Guard is a high-performance system utility designed to bring the advanced privacy features of the 2026 Galaxy S26 Ultra to Android 13+ devices. By leveraging real-time sensor fusion and the WindowManager API, this app simulates a "software-defined" hardware privacy screen.

## Project Info

| Key | Value |
|---|---|
| Package | `com.moclg.overlayguard` |
| Target SDK | 35 (Android 15) |
| Min SDK | 33 (Android 13) |
| Language | Kotlin |
| Build System | Gradle 9.3.1 / AGP 8.7.3, Kotlin DSL |
| UI | Jetpack Compose (Material 3) |
| Target Device | Rooted Samsung Note 20 Ultra (custom kernel) |

## Technical Architecture

### PrivacyOverlayService (AccessibilityService)
- Uses `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY` to draw a black `View` over the status bar.
- Flags: `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN` — the overlay covers the notch/status bar without intercepting touch events.
- `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` to cover display cutouts/notches.
- Sets `PRIVATE_FLAG_IS_SCREEN_DECOR` via reflection for elevated z-order on supported devices.
- Overlay height (default 150 px) and roll threshold are stored in `SharedPreferences` and updated at runtime.
- Supports **pause / resume** — toggling off stops the sensor and overlay without unregistering the accessibility service, so no re-enablement is needed.

### Notification Suppression
- **DND toggle** — enables Do Not Disturb (ALARMS-only) via `NotificationManager` API when the overlay is active; restores on deactivation.
- **Root DND fallback** — also sets `heads_up_notifications_enabled`, `zen_mode`, and `cmd notification set_dnd` via root.
- **Heads-up swipe dismissal** — Samsung One UI renders heads-up notification (HUN) windows above `TYPE_ACCESSIBILITY_OVERLAY`. On `TYPE_NOTIFICATION_STATE_CHANGED` events while the overlay is active:
  - **With root**: `PersistentRootShell` executes `input swipe` for zero-latency physical dismissal.
  - **Without root**: `AccessibilityService.dispatchGesture()` simulates the same upward swipe.
- DND access is auto-granted via root on launch (`cmd notification allow_dnd`).

### Sensor Fusion (RollSensorListener)
- Registers `Sensor.TYPE_ROTATION_VECTOR` at `SENSOR_DELAY_UI`.
- Derives a 3×3 rotation matrix via `SensorManager.getRotationMatrixFromVector`.
- Extracts orientation angles via `SensorManager.getOrientation`; index **2** = **roll** (radians → degrees).
- Trigger logic: `|roll| > threshold ⟹ α = 1.0`; otherwise `α = 0.0`.

### Compose Dashboard (MainActivity)
- **Start / Stop Toggle** — on rooted devices, automatically runs `settings put` commands via `su`; on non-rooted devices:
  - If service is already enabled, pauses/resumes without opening settings.
  - Only opens `ACTION_ACCESSIBILITY_SETTINGS` when the service needs initial enablement.
- **Height Slider** — adjusts overlay height from 50 px to 500 px.
- **Sensitivity Slider** — adjusts roll threshold from 10° to 45°.
- **Status card** — shows green "Running without root" when active, or yellow "Root not detected" with setup instructions when inactive.

### Root Automation (RootHelper / PersistentRootShell)
- Detects root by checking for `su` binary paths (Magisk, KernelSU, legacy).
- `RootHelper` executes commands via interactive `su` stdin (ProcessBuilder) — more compatible with Magisk/KernelSU than `Runtime.exec(arrayOf("su", "-c", ...))`.
- `PersistentRootShell` keeps a single `su` process alive for the service lifetime — commands are written to stdin with zero process-spawn overhead.

## Restricted Settings Bypass (Android 13 / 14)

Starting with Android 13, sideloaded apps are blocked from enabling accessibility services. To bypass:

1. **Go to** Settings → Apps → Overlay Guard → ⋮ (three-dot menu).
2. **Tap** "Allow Restricted Settings".
3. **Authenticate** with your PIN / biometric.
4. **Now navigate** to Settings → Accessibility → Overlay Guard and enable the service.

> On rooted devices this step is unnecessary — the root toggle writes the settings directly.

## Building

```bash
# Requires JDK 17 — gradle.properties pins org.gradle.java.home
./gradlew clean assembleDebug
# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
> Or download and install an apk file from release section.

## Git Conventions

All commits must be signed off:

```bash
git commit -s -m "type: description"
```

Types: `feat`, `fix`, `docs`, `refactor`, `chore`.

## License

Proprietary — all rights reserved.
