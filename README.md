# GPS Setter Next 🛰️

[![release](https://img.shields.io/github/v/release/BabaModz-Official/gps-setter-next)](https://github.com/BabaModz-Official/gps-setter-next/releases)
[![license](https://img.shields.io/github/license/BabaModz-Official/gps-setter-next)](https://github.com/BabaModz-Official/gps-setter-next/blob/main/LICENSE)

A powerful, stealthy, and bug-free next-generation Xposed module that sets your device's location, built on the modern [libxposed](https://github.com/libxposed/api) API for LSPosed 2.x, Vector, and modern Zygisk frameworks.

This project is a major evolutionary fork of [varma322/android-gps-setter](https://github.com/varma322/android-gps-setter), which builds on [jqssun/android-gps-setter](https://github.com/jqssun/android-gps-setter) and [Android1500/GpsSetter](https://github.com/Android1500/GpsSetter).

---

## ⚡ What's New in GPS Setter Next

- 🛡️ **Anti-Detection Stealth Cloaking Suite:**
  - **Wi-Fi Geolocation Masking:** Hooks `WifiManager.getScanResults` to block local BSSID triangulation that leaks your true location to Google Geolocation API.
  - **Cell Tower Masking:** Hooks `TelephonyManager.getAllCellInfo` and `getCellLocation` to prevent cellular tower ID and LAC triangulation leaks.
  - **Mock Flag Stripping:** Completely scrubs `Location.isMock()` (Android 12+), `Location.isFromMockProvider()`, and `Settings.Secure.ALLOW_MOCK_LOCATION` detection flags.
- 🌊 **Realistic Brownian Motion Drift (Ornstein-Uhlenbeck):**
  - Replaced erratic 12 Hz white noise teleport jumping with a physically modeled, smooth random walk that simulates genuine atmospheric and multipath GPS drift.
- 🕹️ **Floating Draggable Joystick 2.0:**
  - Fully draggable anywhere on the screen with a modern semi-transparent floating widget.
  - Quick-switch speed presets: **Walk (4 km/h)**, **Run (12 km/h)**, and **Drive (50 km/h)**.
  - Quick close button right on the overlay without needing to reopen settings.
  - Rate-limited position updates to prevent UI stutters and IPC thrashing.
- 🧭 **Full Motion & Route Playback Support:**
  - Scoped apps now receive full motion telemetry: `speed`, `bearing`, `altitude`, `speedAccuracy`, and `bearingAccuracy` alongside coordinates.
- 📐 **Fixed App-Internal Distance Calculations:**
  - Fixed a critical upstream bug where all `Location` instances were hijacked, causing `distanceTo()` to return 0.0 meters.
- ⚡ **Latest Modern Xposed API (libxposed 102+):**
  - Native LSPosed 2.x and Vector compatibility without deprecated methods or file-sharing warnings.

---

## 📱 Downloads

Grab the latest APK from [Releases](https://github.com/BabaModz-Official/gps-setter-next/releases).

|                | `app-full-*.apk`                     | `app-foss-*.apk`                        |
|----------------|--------------------------------------|-----------------------------------------|
| Maps           | Google Maps (`play-services-maps`)   | MapLibre + OpenFreeMap                  |
| Fused location | Google Play services                 | microG client (`org.microg.gms`)        |

This fork installs under application ID `io.github.babamodz.gpssetternext`.

---

## ⚙️ Requirements

- A rooted device with **LSPosed 2.x or Vector** (libxposed API 101+).
- **Android 8.1+ to Android 15/16+**. System-wide spoofing with continuous fix generation runs on Android 12+.

---

## 🚀 Setup

1. Install the APK and enable **GPS Setter Next** in LSPosed.
2. In its scope, select **System Framework** for system-wide spoofing. Optionally tick individual target apps for dedicated in-app cloaking.
3. Reboot your device (System Framework hooks load at boot; per-app hooks load on app restart).
4. Open GPS Setter Next, pick a location on the map, and press ▶.

---

## 🛠️ Credits & Attribution

- [@BabaModz-Official](https://github.com/BabaModz-Official) - GPS Setter Next maintainer & enhancements
- [@varma322](https://github.com/varma322) - Upstream continuous fix generation & libxposed 101 port
- [@jqssun](https://github.com/jqssun) - Routes, targets UI & modern foundations
- [@Android1500](https://github.com/Android1500) - Original GpsSetter creator
- [libxposed](https://github.com/libxposed) & [LSPosed](https://github.com/LSPosed) / [Vector](https://github.com/JingMatrix/Vector)

---

## 📜 License

[GPL-3.0](LICENSE)
