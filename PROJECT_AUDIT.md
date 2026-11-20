# PROJECT AUDIT — BLEBridge (CUKTECH 10 Ultra AD1204)

> **Статус документа:** Этап 1 (Аудит исходного кода до внесения кодовых правок)  
> **Объект аудита:** Импортированный репозиторий `shcglv/CUKTECH-10-Ultra-app` (`applicationId = "com.cramqin.blecuktech10"`, `namespace = "com.example"`, версия в приложении `v1.1.2`)  
> **Аппаратный таргет:** CUKTECH 10 Ultra GaN Charger (`AD1204`, MiOT PID `0x660E`, модель `njcuk.fitting.ad1204`)

---

## 1. Резюме аудита

Исходное приложение реализует рабочий стек BLE GATT + MiOT (ECDH SECP256R1, HKDF-SHA256, AES-CCM, двухфазная аутентификация, 8-байтовая и 4-байтовая телеметрия, управление 4 портами, сценариями, протоколами, таймерами и дисплеем, интеграция с Xiaomi Cloud, Tasker, Home Assistant, виджетами и TCP-мостом).

Однако аудит кода выявил **критические уязвимости безопасности (P0)**, **утечки приватных ключей/логов и дефекты хранения (P1)**, **серьёзные ошибки в интеграторе энергии Wh / wake-блокировке / BLE-кеше (P1)**, **монолитную архитектуру (файлы 2000–3600+ строк)** и **прямые расхождения между документацией (`README.md`, `FEATURES.md`, встроенная справка Tasker) и фактической реализацией в коде**.

---

## 2. Расхождения между документацией (`README.md` / `FEATURES.md` / UI) и реальным кодом

| Тема | Что утверждает документация / UI | Что реально происходит в коде | Файл и строки |
|---|---|---|---|
| **CPU Partial WakeLock** | `README.md` заявляет: *«CPU partial wakelock is strictly released when not actively transmitting»*; `BleDiagnostics.kt` пишет: `"CPU Partial WakeLock: ACTIVE (Foreground Service)"` | Метод `acquireWakeLock()` в `BleBridgeService.kt` (24ч таймаут) **нигде не вызывается** — вызывается только `releaseWakeLock()`. Строка в диагностике захардкожена и не отражает реальное состояние. При этом `CuktechBleManager.init` запускает вечный цикл `while (isActive) { delay(1000L) }` каждую секунду, даже когда зарядка отключена. | `BleBridgeService.kt:227,259-273`; `BleDiagnostics.kt:136`; `CuktechBleManager.kt:273-278` |
| **GATT MTU в диагностике** | `BleDiagnostics.kt` выводит пользователю активный MTU `512 bytes` | Строка `"GATT Instance: Active (MTU Negotiated: 512 bytes)"` захардкожена литералом и не читает реальный `mtu` из `onMtuChanged`. | `BleDiagnostics.kt:89`; `BleBridgeService.kt:1010-1025` |
| **SIID/PIID для Soft Port Reset** | `FEATURES.md` §3 указывает на `PIID 25` | `forceSoftRestart()` фактически записывает `PIID_PORT_SW_BITMAP` (**PIID 16**: `0x00` -> 500мс -> `0x0F`). | `CuktechBleManager.kt:2224-2240`; `CuktechProtocol.kt:71` |
| **ADB Broadcast `FORCE_SOFT_RESET`** | `FEATURES.md` §3 документирует intent `com.cramqin.blecuktech10.FORCE_SOFT_RESET` | **Полностью отсутствует** в `<intent-filter>` в `AndroidManifest.xml` и в `when (intent.action)` в `BleCommandReceiver.kt`. | `AndroidManifest.xml`; `BleCommandReceiver.kt` |
| **Tasker-документация внутри UI** | `CuktechDashboardScreen.kt` (диалог Tasker Docs) указывает `SET_SCENE_MODE` (`1=Smart, 2=Perf, 3=Quiet, 4=Dual`) и `SET_SCREEN_TIMEOUT` (`0=Always On, 1=1min, 2=2min, 3=5min, 4=10min`) | В реальном протоколе (`CuktechProtocol.kt`): `SCENE_MODES` = `1=AI Mode, 2=Hybrid Mode, 3=Single-Port Max, 4=Dual/Multi-Port`; `SCREEN_TIMEOUTS` = `4=Always On, 5=1 min, 1=5 min, 2=10 min, 3=30 min`. Встроенная шпаргалка в UI вводит пользователей в заблуждение неверными кодами `0..4`. | `CuktechDashboardScreen.kt:1305-1309`; `CuktechProtocol.kt:133-155` |
| **`SET_CHARGE_LIMIT` в Tasker Plugin** | `FEATURES.md` §13 и `BleCommandReceiver.kt` поддерживают `SET_CHARGE_LIMIT` | В `TaskerEditActivity.kt` и `TaskerFireReceiver.kt` действие `SET_CHARGE_LIMIT` отсутствует (только через сырой broadcast). | `TaskerEditActivity.kt:164-179`; `TaskerFireReceiver.kt` |
| **Переключение протоколов (`PIID 20–23` vs `PIID 21`)** | `FEATURES.md` §7 упоминает `PIID 20–23`; `CuktechProtocol.kt` также содержит неиспользуемую 14-байтовую `encodeProtocolExtend()` | Реальная запись в `CuktechBleManager.setPortProtocol` использует **PIID 21** (`PIID_PROTOCOL_EXTEND`, 4-байтный `uint32` битмаск). При этом `setFastChargeProtocols` запускает 10 отдельных корутин/записей вместо атомарной 1-кратной записи маски `PIID 21`. | `CuktechProtocol.kt:79,270-303`; `CuktechBleManager.kt` |
| **Android `minSdk`** | `README.md` заявляет `Android 8.0+ (API 26+)` | В `app/build.gradle.kts` выставлен `minSdk = 24` (Android 7.0). | `app/build.gradle.kts:19` |
| **Хранение истории (Room vs Raw SQLite)** | `app/build.gradle.kts` подключает `room-runtime`, `room-ktx`, `room-compiler (KSP)` | Приложение **не использует Room** — вся история пишется вручную через `SQLiteOpenHelper` (`cuktech10_history.db`) с жёстким лимитом `pruneOldSessions(300)` и удалением сэмплов старше 7 дней. | `CuktechPersistence.kt`; `app/build.gradle.kts` |
| **Сетевой стек (Retrofit/Moshi vs HttpURLConnection/OkHttp)** | `app/build.gradle.kts` тянет `retrofit`, `converter-moshi`, `moshi-kotlin`, `moshi-kotlin-codegen (KSP)` | **Нигде в `app/src/` не используются** (`grep` = 0 совпадений). `XiaomiCloudClient` и `CloudIntegrations` используют `HttpURLConnection`, а `checkForUpdates()` — `OkHttpClient`. | `app/build.gradle.kts`; `XiaomiCloudClient.kt`; `CloudIntegrations.kt` |
| **Мёртвый ассет `uuids.json`** | В `app/src/main/assets/` лежит `uuids.json` с Heart Rate / Battery Service профилями | **Нигде не загружается и не читается** — остаток шаблонного проекта. | `app/src/main/assets/uuids.json` |

---

## 3. Дефекты и уязвимости по приоритетам

### P0 — Критические проблемы безопасности и целостности

1. **Незащищённые экспортируемые компоненты управления (`AndroidManifest.xml`, `BleCommandReceiver.kt`, `TaskerFireReceiver.kt`)**
   - `BleCommandReceiver` имеет `android:exported="true"` без `android:permission`, без opt-in переключателя и без валидации отправителя/токена. Любое стороннее приложение на устройстве может отправить broadcast `SET_PORT_STATE`, `DISCONNECT_CHARGER`, `START_SERVER`, `SET_CHARGE_LIMIT` и др.
   - `TaskerFireReceiver` (`android:exported="true"`) принимает `com.twofortyfouram.locale.intent.action.FIRE_SETTING` от любого источника без проверки включения автоматизации и секретного токена.
2. **Неаутентифицированный TCP Socket Gateway на `0.0.0.0:8080` (`BleBridgeService.kt`)**
   - `ServerSocket(port)` (строки 526–568) биндится на все сетевые интерфейсы (`0.0.0.0`), в том числе в публичных Wi-Fi / LAN / Tailscale-сетях.
   - Отсутствует аутентификация и авторизация сессии.
   - `handleClientCommand` (строки 1331–1415) разрешает **произвольные GATT-операции**: `read`, `write`, `notify`, `discover`, `request_mtu`, `reset_bluetooth` на **любые UUID** (включая аутентификационные характеристики MiOT `0x0010`/`0x0019` и управляющие характеристики), а также не имеет лимита длины входной строки, таймаута чтения, rate limit и защиты от флуда подключений.

---

### P1 — Приватность, криптография, данные, BLE и энергия

1. **Уязвимый fallback Keystore в `SecureCredentialStore.kt` (строки 319–329)**
   - При сбое Android Keystore метод `getOrCreateSecretKey()` генерирует 128-битный ключ и **сохраняет его в открытом виде** (`KEY_FALLBACK_RAW = "test_fallback_k"`) в том же `SharedPreferences` (`cuktech_secure_vault`), что и шифротекст.
2. **Скрытый автоимпорт из публичной папки Downloads (`SecureCredentialStore.kt:226-239`, `CuktechBleManager.kt:261`)**
   - `tryAutoImportLocalCredentialsFile()` на каждом старте пытается прочитать `/storage/emulated/0/Download/cuktech_credentials.json` и `/storage/emulated/0/Development/cuktech10/cuktech_credentials.json`, создавая вектор подмены токена/MAC из публичной директории.
3. **Утечка секретов через Android Auto Backup (`AndroidManifest.xml:37`, `backup_rules.xml`, `data_extraction_rules.xml`)**
   - `android:allowBackup="true"`, а правила исключений в XML содержат только закомментированные шаблоны. При облачном бэкапе или `adb backup` утекают `cuktech_secure_vault.xml` и базы данных.
4. **Утечка чувствительных данных в логи, буфер обмена и WebView (`XiaomiCloudClient.kt`, `XiaomiAccountDialogs.kt`, `BleBridgeService.kt`, `MainActivity.kt`)**
   - `XiaomiCloudClient` логирует префиксы токенов (`Token: ${resolvedToken.take(6)}...`, строка 937), ключи cookie и redirect URL с токенами (`URL: $finishUrl`, строка 626), сырые фрагменты ответа (`Raw: ${trimmed.take(120)}`, строка 1017), UID и список устройств.
   - `MainActivity.kt:1765` копирует несанитизированные логи в системный буфер обмена.
   - `TwoFactorChallengeSection` в `XiaomiAccountDialogs.kt:680-725` использует `WebView` с глобальным `CookieManager` (куки не очищаются после логина), `databaseEnabled = true` и перехватывает `serviceToken=` без строгого allowlist хостов Xiaomi.
   - `CuktechDashboardScreen.kt` и `CloudIntegrations.kt` допускают передачу Home Assistant Webhook и Bearer-токена по открытому `http://` без явного предупреждения безопасности и без санитизации URL при логировании ошибок.
5. **Обход фильтра тихого простоя (`PortTelemetryDecoder.kt:261`, `CuktechBleManager.kt:1613-1646,1776-1785`)**
   - `decodePortV2` присваивает `lastUpdatedMs = System.currentTimeMillis()`.
   - В `processDecryptedFrame` при получении пакета телеметрии (`b4 == 0x03` / `0x04`) сначала проверяется условие `if (hasChanged || decoded.powerW > 0.0 || hasActiveSession)`, но сразу после этого вызывается `applyDecodedMiotFrame(decoded)`, который **безусловно** ещё раз вызывает `updatePortTelemetry(portId, decoded)`. Это полностью обходит фильтр, вызывая двойное срабатывание, лишние рассылки Tasker/HA/виджетов и записи даже на `0.0V / 0.0A` в простое.
6. **Баг маппинга портов в виджетах (`BleBridgeService.kt:480-485`)**
   - В `forceRestoreNotification()` метки портов заданы как `when (p.portId) { 1 -> "C1"; 2 -> "C2"; 3 -> "A"; else -> "P${p.portId}" }` — **порт 3 (USB-C3) ошибочно подписывается как `"A"`, а порт 4 (USB-A) — как `"P4"`**.
7. **Ошибки расчёта Wh и разрыв сессий (`EnergyTracker.kt`, `CuktechBleManager.kt`)**
   - `AdaptiveEnergyIntegrator(maxDtSec = 30.0)` интегрирует трапецией `0.5 * (lastWatts + cleanW) * dt` при разрывах/паузах длительностью до 30 секунд. Если до обрыва BLE нагрузка была 100W, это порождает фантомные Wh без реальной телеметрии.
   - Нет сшивания сессий (session stitching): кратковременный разрыв BLE, перезапуск сервиса или блокировка экрана (`forceFinalizeActiveSession()`) режет одну зарядку на несколько обрывков.
   - `cumulativeWh` в UI/лимитах не восстанавливается после перезапуска процесса.
   - `CuktechPersistence.kt` жёстко удаляет сессии свыше `300` и сэмплы старше 7 дней, отсутствует архивирование, дневные/месячные агрегаты и фиксация тарифа на момент сессии.
8. **Мёртвый `refreshGattCache()` (`BleBridgeService.kt:909`) и проблемы BLE-восстановления**
   - `refreshGattCache(gatt)` написан, но не вызывается ни при ошибке GATT `133`, ни при провале `discoverServices()`.
   - При отключённом телефоне цикл `while (isActive) { delay(1000L); updateCountdownTickAndNotification() }` (`CuktechBleManager.kt:273-278`) работает бесконечно вхолостую.

---

### P2 — Архитектура, сборка, UI/UX и тестовый контур

1. **Экстремальная монолитность файлов**
   - `CuktechDashboardScreen.kt` — **3667 строк**
   - `CuktechBleManager.kt` — **2773 строки**
   - `MainActivity.kt` — **2127 строк**
   - `BleBridgeService.kt` — **2075 строк**
   - `XiaomiCloudClient.kt` — **1308 строк**, `XiaomiAccountDialogs.kt` — **1306 строк**
2. **Конфигурация релиза и ProGuard (`app/build.gradle.kts`, `proguard-rules.pro`)**
   - `release { isMinifyEnabled = false }` — R8 выключен, APK неоптимизирован и не обфусцирован.
   - Подключены неиспользуемые зависимости (`retrofit`, `moshi`, `moshi-kotlin`, `moshi-kotlin-codegen` с KSP), раздувающие время сборки и граф зависимостей.
3. **Неполный CI (`/.github/workflows/android.yml`)**
   - Выполняет только `./gradlew assembleDebug --no-daemon`, **не запускает** `:app:testDebugUnitTest` и `:app:lintDebug`.
4. **Несогласованные тесты (`app/src/test/java/com/example/GreetingScreenshotTest.kt`)**
   - Шаблонный скриншот-тест рендерит `BleBridgeDashboard()` и пишет в `dashboard.png` (при наличии старого `greeting.png`), но не покрывает критические сценарии безопасности, Wh, Room и криптографии.
5. **UX-долг**: экран старта всегда открывает технический Gateway вместо обзорного Dashboard; отсутствует RU-локализация и переключатель языка; виджеты имеют жёсткую кастомную стилистику без Material 3 / Dynamic Color адаптации; отсутствует SHA-256 верификация при обновлении с GitHub Releases; отсутствуют уведомления о завершении зарядки и Live Updates (`ProgressStyle`).

---

## 4. Подтверждённые ограничения платформы Android (без приукрашивания)

1. **Always-On Display (AOD):** Android **не предоставляет** сторонним приложениям API для прямой отрисовки виджетов или телеметрии на системном AOD. Единственные легальные каналы поверхности блокировки — стандартные уведомления на Lock Screen и Android 16 Promoted Live Updates (`Notification.ProgressStyle` + `FLAG_PROMOTED_ONGOING`).
2. **Android 16 Live Updates & Status Chips:** Полное отображение Status Chip и продвинутых Promoted Live Updates зависит от поддержки `canPostPromotedNotifications()` на Pixel (Android 16+) и политики конкретного OEM-лаунчера; на Android < 16 и некоторых прошивках уведомление отображается как стандартный/кастомный `Ongoing` Notification. Наличие флага `FLAG_PROMOTED_ONGOING` требует строгих системных правил Android 16 (без кастомных `RemoteViews`).
3. **OEM-агрессивное энергосбережение:** Никакой `WakeLock` или `ForegroundService` не гарантирует 100% выживание на Xiaomi/HyperOS, Samsung, Vivo без отключения оптимизации батареи пользователем (`dontkillmyapp.com`).
4. **Устаревшая криптография Xiaomi Cloud:** `XiaomiCloudClient` вынужденно использует `RC4-drop[1024]`, `MD5` (хеш пароля для Xiaomi Auth API) и `SHA-1` (подпись `generateEncSignature`) — это требование внешнего API Xiaomi, которое нельзя заменить без поломки авторизации; оно должно быть изолировано исключительно в `XiaomiCloudClient` и не применяться ни для каких локальных секретов/бэкапов.
