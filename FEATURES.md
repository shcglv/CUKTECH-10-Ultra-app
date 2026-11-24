# ⚡ Complete Features & Hardware Specification (v2.0.0)

Technical reference for **Cuktech (`com.joxit.cuktech`) — CUKTECH 10 Ultra (`AD1204`, MiOT PID `0x660E`, `njcuk.fitting.ad1204`)** by **Joxit**.

---

## Table of Contents
1. [Navigation, Design System, Onboarding & Localization](#1-navigation-design-system-onboarding--localization)
2. [Interactive Charger Faceplate & Port Controls](#2-interactive-charger-faceplate--port-controls)
3. [Master Killswitch, Undo Snackbar & Safety Cutoffs](#3-master-killswitch-undo-snackbar--safety-cutoffs)
4. [Multi-Interval Analytics, Energy Integrator & Session Replay](#4-multi-interval-analytics-energy-integrator--session-replay)
5. [Day/Night Electricity Tariffs & 30-Day Retention](#5-daynight-electricity-tariffs--30-day-retention)
6. [Charger Hardware Settings & Charging Profiles](#6-charger-hardware-settings--charging-profiles)
7. [Android 16+ Live Updates, Completion Alerts & Cable Anomaly Detector](#7-android-16-live-updates-completion-alerts--cable-anomaly-detector)
8. [Material 3 Expressive / Glance Home Screen Widgets](#8-material-3-expressive--glance-home-screen-widgets)
9. [Security, Keystore Encryption & Encrypted SAF Backup](#9-security-keystore-encryption--encrypted-saf-backup)
10. [External Automation (Tasker, ADB, Home Assistant, TCP Gateway)](#10-external-automation-tasker-adb-home-assistant-tcp-gateway)
11. [Direct In-App GitHub Updater (SHA-256 Enforced)](#11-direct-in-app-github-updater-sha-256-enforced)
12. [Diagnostics & Redacted Console Log](#12-diagnostics--redacted-console-log)

---

## 1. Navigation, Design System, Onboarding & Localization
- **Adaptive Material 3 Expressive Chrome (`CuktechAppRoot`):**
  - Uses `NavigationBar` on compact phones and `NavigationRail` on `Medium`/`Expanded` width classes (tablets/foldables), full `enableEdgeToEdge()`, spring motion physics, haptic touch feedback, and `WindowInsets.safeDrawing` padding.
  - **TopAppBar Connection Pill:** Quick pairing (`Search & Pair`) and Connect/Disconnect/Cancel pill sits directly in the top bar; no bulky connection card clutters the `Overview` body.
  - **4 Primary Destinations:** `Overview` (default launch screen), `Ports`, `Analytics`, `Settings`.
  - **Secondary Screen:** `Diagnostics` (moved cleanly into `Settings`, absent from the TopAppBar).
  - **Configurable Start Screen:** Launch directly to `Overview` or `Settings` (Setup).
- **Theme & Dynamic Color:** Explicit Material You Dynamic Color toggle (`useDynamicColor` on Android 12+ `API 31+`) with Light/Dark/System modes and optional **AMOLED Pure Black** dark surface override; applies consistently across main UI, Tasker config, and Glance Widget Config activity.
- **First-Install Onboarding (`OnboardingScreen`):** 3-page interactive guide explaining 120W telemetry, Mainland China (`CN`) single-BLE-client constraints, and runtime Bluetooth/Notification permissions; replayable anytime from `Settings`.
- **Dynamic Runtime Localization (`RU` / `EN` / `System`):** Live switching across Compose screens, Xiaomi 2FA/pairing dialogs, notifications, Tasker plugin, and Glance widgets without restarting the process.

---

## 2. Interactive Charger Faceplate & Port Controls
- **Physical Port Naming:** Uses explicit hardware labels across Overview, Ports, Faceplate, Tasker, and Widgets: **`USB-C1`**, **`USB-C2`**, **`USB-C3`**, and **`USB-A`**, paired with user-editable port aliases.
- **Faceplate Navigation:** Tapping any port slot on the live CUKTECH 10 Ultra faceplate opens the `Ports` screen scrolled directly to that port card with a focus highlight.
- **Hardware-Accurate Fast-Charge Protocol Switches:**
  - `USB-C1` & `USB-C2`: `PD`, `PPS`, `UFCS`.
  - `USB-C3` & `USB-A`: `UFCS`, `SCP` (no non-existent PD/PPS switches).
  - `USB-A 4h Low-Current Mode` (`PIID 18`) with persisted wall-clock countdown deadline.
- **Power Allocation Scenarios (`SIID 2 / PIID 15`):** `AI Mode (1)`, `Hybrid (2)`, `Single Priority (3)`, `Dual High-Power (4)`.
- **Soft Port Reset (`PIID 25`):** Non-destructive DC-DC switch cycle (`0x00 -> 0x0F`) for PD/PPS contract re-negotiation with confirmation dialog.
- **Timers & Wh Cutoffs:**
  - Delay auto-off countdown (`Always On`, presets, or custom duration up to `1440 min`) persisted across app restarts.
  - Per-port energy cutoff (`Wh` target in `Once` or `Always` mode) enforced in `EnergyTracker` + `CommandDispatcher`.

---

## 3. Master Killswitch, Undo Snackbar & Safety Cutoffs
- **Master Killswitch (`Overview`):** Executes an explicit confirmation dialog, then dispatches a **sequential** per-port shutdown across ports `1..4` via `PIID 16` and reports per-port outcomes.
- **Localized Undo & Killswitch Restore (`CommandDispatcher`):**
  - Successful user actions (`SetPortEnabled`, `SetSceneMode`, `SetScreenTimeout`, `SetPortCountdown`, `SetUsbALowCurrent`, `ApplyChargingProfile`, etc.) emit structured `UndoActionKind` metadata rendered cleanly in RU or EN inside a timed M3 **Undo Snackbar**.
  - **Safety Invariant:** Whenever an energy limit (`WH_LIMIT`) or port timer safety cutoff fires (`notifySafetyCutoffTriggered`), any pending Undo entry and Killswitch restore snapshot are **immediately invalidated** (`SAFETY_LOCKOUT` / `WH_LIMIT_TRIGGERED`) so automated protections cannot be accidentally bypassed.

---

## 4. Multi-Interval Analytics, Energy Integrator & Session Replay
- **Honest Non-Interpolated Energy Integration (`EnergyTracker`):**
  - Integrates `Wh` strictly across consecutive confirmed telemetry samples within valid sampling intervals; **never** bridges long BLE disconnects or idle gaps with fabricated energy.
  - Marks active and completed sessions with exact active duration (`activeDurationSec`), `Wh`, peak `W`, average `W`, protocol badge, and end reason (`UNPLUGGED`, `WH_LIMIT`, `USER_STOP`, `MASTER_KILLSWITCH`, `DISCONNECTED`).
- **Separated Chart Metrics (`AnalyticsScreen`):**
  - Intervals: `60s` (live high-rate buffer), `5m`, `30m`, `Session`, `Today`, `Custom` (Room snapshot queries).
  - Metric selectors: `Power (W)`, `Voltage (V)`, `Current (A)`, `Energy (Wh)`, and `Protocol Timeline`.
- **Interactive Session Replay (`SessionReplayDialog`):** Replay any recorded session's `W`/`V`/`A`/`Wh` samples using Play/Pause + timeline scrubber slider.
- **CSV Export (`Analytics`):** Exports filtered history (`Session` / `1h` / `24h` / `7d` / `All Time`; `All` / `Sessions` / `Telemetry`) via Storage/MediaStore.

---

## 5. Day/Night Electricity Tariffs & 30-Day Retention
- **Configurable ToU Tariff (`AppPreferences`):** Configure currency (`USD`, `EUR`, `RUB`, `GBP`, `CNY`, etc.), day/flat rate per `kWh`, optional Time-of-Use night rate, and day/night boundary minutes (midnight-wrap safe).
- **Immutable Session Cost Snapshots:** When a charging session ends, its calculated energy cost and tariff metadata (`estimatedCost`, `currencyCodeSnapshot`, `tariffRateSnapshot`) are persisted inside Room so changing future electricity rates never retroactively rewrites historical bills.
- **30-Day Automatic Retention Archiving (`CuktechPersistence`):** Sessions older than **30 days** are rolled up into `DailyEnergyAggregateEntity` rows before pruning raw old snapshots/sessions.

---

## 6. Charger Hardware Settings & Charging Profiles
- **Hardware Display & Behavior (`Settings`):**
  - Screen Sleep Timeout (`PIID 6`: Always On / 1m / 5m / 10m / 30m)
  - Idle Screen-Off (`PIID 19`)
  - Physical LCD Language (`PIID 13`: Chinese / English)
  - Orientation Lock & 180° Screen Rotate (`PIID 20` / `PIID 14`)
  - Charger LCD Page Switching (`PIID 14`: pages 1..5)
- **Saved Charging Profiles (`ChargingProfileEntity`):** Save and 1-tap apply combinations of Scene Mode, active port bitmap, screen timeout, USB-A low-current mode, idle screen-off, orientation lock, and optional per-port `Wh` limits from the `Overview` dashboard.

---

## 7. Android 16+ Live Updates, Completion Alerts & Cable Anomaly Detector
- **Android Foreground Service (`connectedDevice`) & Promoted Ongoing Notifications (`ChargingNotificationManager`):**
  - Displays localized live total watts, active per-port wattage breakdown, and concise `shortCriticalText` (`mm:ss` / `XhYm` nearest countdown or live wattage badge) with `NotificationCompat` Promoted Ongoing hints (`API 36+` Android 16 Live Updates) and a quick settings shortcut for `ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`.
- **Smart Charging Completion Alerts (`ChargingCompletionDetector`):**
  - Fires only when power drops from meaningful active load (`>= 2.5W`) into trickle band (`<= completionPowerThresholdW`, default `1.0W`) and stays connected & above `0.05W` for the configurable hysteresis window (`30–300s`, default `60s`). Abrupt cable unplugs (`0.0W`) or safety cutoffs do **not** spuriously trigger completion alerts.
  - Supports **Quiet Hours** and optional capped repeat reminders.
- **Cable & BLE Anomaly Detector (`CableAnomalyDetector`):**
  - Raises localized dismissible alert banners on **3+** rapid `PD -> 5V Normal` renegotiation collapses, **3+** sharp `>= 15W -> <= 0.5W` load-drop loops, or **3+** unplanned BLE disconnect flaps within 3 minutes.

---

## 8. Material 3 Expressive / Glance Home Screen Widgets
- **2 Adaptive Jetpack Glance Widgets (`4x2` Wide & `2x2` Compact):**
  - Built with `GlanceTheme` Material 3 Dynamic Color tokens (`cornerRadius(26.dp)` outer, `18.dp`/`14.dp` interactive port pills), `updatePeriodMillis="0"`, and RU/EN localization via `context.withAppLocale(...)`.
  - Features a transparent `ARGB_8888` gradient power sparkline tinted with `GlanceTheme.colors.primary`.
  - Zero timer alarms: `CuktechWidgetUpdater` deduplicates identical telemetry snapshots and throttles sub-second jitter while dispatching auth-state transitions immediately.
  - **Per-Widget Customization (`CuktechWidgetConfigActivity`):** Honors `useDynamicColor` & AMOLED black theme, lets you pick emphasized ports, sparkline visibility, cumulative `Wh` summary, and tap-to-open target destination (`Overview`, `Ports`, `Analytics`, `Settings`).
  - Interactive Glance `ActionCallback` buttons toggle individual ports (`USB-C1`..`USB-A`) or cycle power allocation scenes via `CommandDispatcher`.

---

## 9. Security, Keystore Encryption & Encrypted SAF Backup
- **Android Keystore Credential Store (`SecureCredentialStore`):**
  - Encrypts Xiaomi MiOT device keys, tokens, session secrets, automation Bearer tokens, and HA Webhook Bearer token via `AndroidKeyStore` (`AES-256-GCM`).
  - Strictly **fail-closed**: zero hardcoded fallback encryption keys; purges any legacy fallback entries on startup.
- **Backup & Export Hygiene:**
  - `android:allowBackup="false"`, `backup_rules.xml`, and `data_extraction_rules.xml` exclude sensitive SharedPreferences (`cuktech_app_prefs.xml`), DataStore files, and Room databases from ADB/cloud device backups.
  - Zero automatic reads from shared `Downloads/` directories.
- **Password-Protected SAF `.cukbackup` (`SafEncryptedBackupManager`):**
  - Export and restore preferences, port aliases, profiles, `Wh` limits, session history, and daily aggregates via Android Storage Access Framework using `PBKDF2WithHmacSHA256` (210,000 iterations) + `AES-256-GCM` with versioned AAD.
  - Device keys/secrets are **excluded by default** (explicit opt-in checkbox with warning). Wrong passphrases or tampered ciphertext fail cleanly (`AEADBadTagException`).
- **Automatic Log Redaction (`LogSanitizer`):** Masks MAC addresses, Xiaomi `ssecurity`/`serviceToken`/`miToken`/BLE keys, Bearer tokens, URLs with query credentials, and auth payloads across logs and clipboard copies.

---

## 10. External Automation (Tasker, ADB, Home Assistant, TCP Gateway)
- All external triggers (`TaskerFireReceiver`, `BleCommandReceiver`, `TcpGatewayServer`) are **disabled by default** in `AutomationAuthManager` and validate requests using constant-time `MessageDigest.isEqual`, per-source exponential failure lockout, and sliding-window rate limits:
  - **Tasker / MacroDroid Plugin:** Auto-injects the per-install token when editing an action while Tasker opt-in is active; enforces verification inside `TaskerFireReceiver`.
  - **ADB / Termux Broadcasts (`BleCommandReceiver`):** Listen for `com.joxit.cuktech.*` actions and require `--es auth_token "<token>"` for every action (`CONNECT_CHARGER`, `DISCONNECT_CHARGER`, `SCAN_CHARGER`, `SET_PORT_STATE`, `SET_SCENE_MODE`, `SET_SCREEN_TIMEOUT`, `SET_CHARGE_LIMIT`, `FORCE_SOFT_RESET`, `MASTER_KILLSWITCH`, `UNDO_LAST_COMMAND`, `START_SERVER`, `STOP_SERVER`, `QUERY_STATUS`, and sleep policy toggles).
  - **Home Assistant HTTP Webhook (`CloudIntegrations`):** Rate-limited (`10s`) JSON POST with Keystore-encrypted Bearer token and plaintext `http://` warning.
  - **TCP Socket Gateway:** Binds `127.0.0.1:8080` by default; see [docs/TCP_SOCKET_BRIDGE.md](docs/TCP_SOCKET_BRIDGE.md).

---

## 11. Direct In-App GitHub Updater (SHA-256 Enforced)
- Queries GitHub Releases (`joxit/cuktech-10-ultra`, `releases/latest`), compares semver (`isNewerVersion`), ignoring drafts/prereleases.
- Downloads the APK over HTTPS into private `cacheDir/updates/` and verifies the computed **SHA-256** digest against the GitHub asset digest, `.sha256` sidecar asset, or release checksum notes.
- **Fail-Closed:** Refuses updates lacking a valid 64-hex-char SHA-256 and **immediately deletes** any downloaded APK whose digest mismatches.

---

## 12. Diagnostics & Redacted Console Log
- **Dedicated Diagnostics Screen (`DiagnosticsScreen`):** Accessed via `Settings`, kept off the main dashboard and top app bar.
- **Rolling Console Buffer:** 1,000 entries across `LEVEL 1` (Errors), `LEVEL 2` (Connections & Status), `LEVEL 3` (Sanitized Protocol Frames), with **1-tap sanitized Copy to Clipboard**.
- **On-Demand BLE Stack Audit (`BleDiagnostics`):** Manually runs checks for BT adapter capabilities (LE 2M PHY, Coded PHY, Extended Advertising), Xiaomi `0xFE95` visibility, GATT MTU, negotiated telemetry stats, Keystore/auth state, and battery optimization exemptions.
