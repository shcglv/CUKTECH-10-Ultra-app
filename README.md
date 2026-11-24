# ⚡ Cuktech — CUKTECH 10 Ultra (`AD1204`) v2.0.0

<p align="center">
  <b>Native Android Material 3 Expressive Controller, Live Telemetry Dashboard, Glance Widgets &amp; Hardened Automation Hub for CUKTECH 10 Ultra (120W GaN, MiOT PID <code>0x660E</code>, <code>njcuk.fitting.ad1204</code>)</b><br/>
  Package: <code>com.joxit.cuktech</code> · Author: <b>Joxit</b>
</p>

<p align="center">
  <a href="../../releases"><img src="https://img.shields.io/github/v/release/joxit/cuktech-10-ultra?label=Release&amp;color=00E5FF" alt="Release" /></a>
  <a href="https://github.com/ImranR98/Obtainium"><img src="https://img.shields.io/badge/Obtainium-Compatible-8A2BE2?logo=android&amp;logoColor=white" alt="Obtainium" /></a>
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B%20(Target%20SDK%2036)-3DDC84?logo=android&amp;logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/UI-Material%203%20Expressive%20%2B%20Glance-7F52FF?logo=kotlin&amp;logoColor=white" alt="Material 3 Expressive" />
  <img src="https://img.shields.io/badge/Device-CUKTECH%2010%20Ultra%20(AD1204)-00E5FF" alt="Device" />
</p>

---

## 📖 Quick Links
- **[Complete v2.0.0 Features & Hardware Guide (FEATURES.md)](FEATURES.md)** — Detailed reference for telemetry, protocols, safety cutoffs, Glance widgets, Live Updates, backups, and automation.
- **[Hardened TCP JSON Gateway Guide (docs/TCP_SOCKET_BRIDGE.md)](docs/TCP_SOCKET_BRIDGE.md)** — Loopback-first (`127.0.0.1:8080`) token-authenticated TCP socket protocol and ADB forwarding instructions.
- **[Releases & Signed APKs + `.sha256`](../../releases)** — Signed releases (`Cuktech-10-Ultra-v*.apk`) built via GitHub Actions with SHA-256 verification sidecars.
- **[Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.joxit.cuktech%22%2C%22url%22%3A%22https%3A//github.com/joxit/cuktech-10-ultra%22%2C%22author%22%3A%22Joxit%22%2C%22name%22%3A%22Cuktech%20-%20CUKTECH%2010%20Ultra%22%7D)** — Background update tracking directly from GitHub Releases.
- **[OEM Background Guide (dontkillmyapp.com)](https://dontkillmyapp.com/)** — Exempt background BLE services on aggressive OEM ROMs.

---

## 🙏 Acknowledgments & Heritage

**Cuktech (`com.joxit.cuktech`)** for **CUKTECH 10 Ultra (`njcuk.fitting.ad1204`, Product ID `0x660E`)** is an independent app built by **Joxit**, utilizing MiOT BLE protocol findings from:

1. **[`kairui1108/cuktech-ble-server`](https://github.com/kairui1108/cuktech-ble-server)** *(and [`kairui1108/cuktech-ble-ha`](https://github.com/kairui1108/cuktech-ble-ha))* — Xiaomi MiOT BLE v5 + CUKTECH Extended v2 protocol specification, 2-phase `SECP256R1` ECDH + `HKDF-SHA256` + `AES-CCM` handshake, 8-byte port telemetry decoding, and hardware SIID/PIID definitions.
2. **[`woldphone/BLEBridge`](https://github.com/woldphone/BLEBridge)** — Early Foreground Service & sequential GATT queue pattern inspiration.

---

## ✨ What's New in v2.0.0

- **Material 3 Expressive (MD3E) & Adaptive UX (`Overview`, `Ports`, `Analytics`, `Settings` + `Diagnostics`):**
  - True **Material You Dynamic Color** toggle (`useDynamicColor`, Android 12+), Light/Dark/System themes, optional **AMOLED Black** dark mode, spring motion & haptic feedback, predictive back & `WindowInsets.safeDrawing` (`enableEdgeToEdge()`), and **NavigationRail** on tablets/foldables.
  - **Custom Monochrome Vector Icon & Android 12+ SplashScreen:** Built from the clean Cuktech monochrome vector glyph.
  - **3-Step First-Install Onboarding (`OnboardingScreen`):** Explains 120W GaN telemetry, Mainland China (`CN`) single-client BLE behavior, and requests Bluetooth & Notification permissions cleanly; replayable anytime in Settings.
  - **Compact Top-Bar Connection Pill:** Quick-access pairing (`Search & Pair`) and Connect/Disconnect/Cancel pill lives right in the `TopAppBar`, keeping the `Overview` dashboard uncluttered; `Diagnostics` sits neatly inside `Settings`.
  - **100% RU / EN / System Localization:** Live switching across every screen, dialog, Toast, Tasker plugin, notification, and Glance widget without restarting the app.
  - **Interactive Hardware Faceplate:** Visual front-panel representation of `USB-C1`, `USB-C2`, `USB-C3`, and `USB-A` with negotiated protocol chips, power bars, and 1-tap navigation to each port's card.
- **Real Safety Controls & Hardware Fidelity:**
  - **Master Killswitch (Sequential Per-Port Cutoff):** Disables all 4 ports sequentially over `PIID 16` with per-port outcome reporting and snapshot restore.
  - **Localized Undo Snackbar with Safety Lockout:** Supports Undo for user commands (`SetPortEnabled`, `SceneMode`, `ScreenTimeout`, timers, profiles) while automatically invalidating Undo/Restore whenever a `Wh` limit or countdown safety cutoff fires.
  - **Strict Per-Port Hardware Protocols:** Matches actual AD1204 silicon — `PD`/`PPS`/`UFCS` on `USB-C1` & `USB-C2`, and `UFCS`/`SCP` + `USB-A 4h Low-Current Mode` on `USB-C3` & `USB-A`.
  - **Cable / Connection Anomaly Detector:** Alerts on rapid PD renegotiation collapses, sudden load-drop loops, and BLE flapping loops.
- **Validated Energy Integration & Utility Economics (`Room` + `DataStore`):**
  - Integrates `Wh` strictly across confirmed consecutive telemetry samples without fabricating energy across BLE disconnects or dead gaps.
  - **Day/Night Time-of-Use Electricity Tariffs** with immutable session cost snapshots and **30-day automatic archiving** into daily aggregates.
  - **Interactive Multi-Interval Analytics & Session Replay:** Switch between `60s`, `5m`, `30m`, `Session`, `Today`, `Custom` and separated charts (`Power W`, `Voltage V`, `Current A`, `Energy Wh`, `Protocols`), plus chronological scrubbing & playback and custom **Charging Profiles**.
- **Android 16+ Live Updates & MD3E Glance Widgets:**
  - **Promoted Ongoing Notification (Android 16 / API 36+):** Uses native `NotificationCompat` Live Updates (`requestPromotedOngoing` + compact `shortCriticalText`). *Status-bar chip / AOD prominence depends on user grant of Promoted Notifications in system settings and OEM support.*
  - **Completion Alerts with Hysteresis & Quiet Hours:** Distinguishes sustained trickle completion (`<= threshold W` for `30–300s`) from abrupt cable unplugs.
  - **Material 3 Expressive Glance Widgets (`4x2` & `2x2`) with Per-Widget Configuration:** Dynamic Color / M3 rounded surface tiles (`updatePeriodMillis="0"`), transparent dynamic-tinted gradient `ARGB_8888` sparkline, localized RU/EN labels, and 1-tap port/scenario controls.
- **Fail-Closed Security & Encrypted SAF Backup:**
  - **Zero-Trust Secret Storage (`AndroidKeyStore` AES-256-GCM):** Zero raw plaintext key fallback; automatic backup exclusion (`android:allowBackup="false"`).
  - **Opt-In External Automation:** Tasker plugin, `com.joxit.cuktech.*` ADB broadcast intents, and loopback-first TCP JSON Gateway (`127.0.0.1:8080`) are **disabled by default** and protected by a per-install 256-bit CSPRNG token (`MessageDigest.isEqual`), exponential lockout, and rate limits.
  - **Encrypted `.cukbackup` SAF Export/Import:** `PBKDF2WithHmacSHA256` (210k iterations) + `AES-256-GCM`, excluding Xiaomi/automation secrets unless explicitly opted in.
  - **Direct In-App GitHub Updater:** HTTPS download + **fail-closed SHA-256 verification** against release digest / `.sha256` sidecar before package install.

---

## ⚠️ Prerequisites & Hardware Constraints

1. **Initial Mi Home Binding Required Once:** The CUKTECH 10 Ultra (`njcuk.fitting.ad1204`) must be bound once in the official **Xiaomi Mi Home (中国大陆 / Mainland China server)** app to generate the 12-byte MiOT BLE Token.
2. **One Active BLE Connection at a Time:** The charger's Telink BLE MCU accepts only **1 active GATT link**. Close Mi Home while using **Cuktech**; **Settings** provides a 1-tap handoff button for official firmware OTA updates inside Mi Home.
3. **Hidden in System Bluetooth Settings (Normal):** Because the charger advertises nameless encrypted `0xFE95` MiOT beacons (`PID 0x660E`), pair/connect from inside **Cuktech**'s Scanner (`Search & Pair`) or Xiaomi Cloud sync.
4. **Polling & Wake Policy Transparency:** Background telemetry runs inside an Android `connectedDevice` Foreground Service with configurable **Pause polling when screen is locked** or **Log only when phone charging** policies.

---

## 📦 Installation & Building

### Signed Release APKs
Download the latest signed `.apk` (and `.sha256` verification file) from [**GitHub Releases**](../../releases) for Android 8.0+ (`API 26+`), or track updates inside **Settings -> Check for App Updates** or [**Obtainium**](https://github.com/ImranR98/Obtainium).

### Build & Test from Source
```bash
git clone https://github.com/joxit/cuktech-10-ultra.git
cd cuktech-10-ultra
./gradlew :app:testDebugUnitTest
./gradlew assembleDebug
```

---

## 📄 License & Disclaimer

Independent open-source community application by **Joxit**; **not** affiliated with, endorsed by, or sponsored by CUKTECH or Xiaomi Corporation. Hardware control and automation features are provided as-is; always verify electrical safety limits for your connected devices.
