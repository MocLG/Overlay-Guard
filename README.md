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
| Target Device | Rooted Samsung Note 20 Ultra (custom kernel) |

## Architecture

- **AccessibilityService** (`PrivacyOverlayService`) — draws a `TYPE_ACCESSIBILITY_OVERLAY` over the status bar.
- **Sensor Fusion** — `TYPE_ROTATION_VECTOR` sensor to derive roll angle.
- **Compose UI** — dashboard with toggle, height slider, and sensitivity slider.
- **Root Automation** — auto-enable accessibility service via `settings put` on rooted devices.

## Build Status

- [x] Milestone 1 — Project Scaffolding & Manifest
- [ ] Milestone 2 — Privacy Overlay Service
- [ ] Milestone 3 — Sensor Fusion
- [ ] Milestone 4 — UI & Automation
- [ ] Milestone 5 — Documentation
