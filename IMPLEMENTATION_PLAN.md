# IMPLEMENTATION PLAN — Поэтапный план переработки

> **Правило исполнения:** Этапы выполняются строго последовательно. Переход к следующему этапу происходит после проверки сборки, тестов и фиксации прогресса.

---

## Этап 1: Аудит и документы (ТЕКУЩИЙ ЭТАП)
- [x] Полный аудит кодовой базы (`BleBridgeService`, `CuktechBleManager`, `PortTelemetryDecoder`, `EnergyTracker`, `CuktechPersistence`, `SecureCredentialStore`, `XiaomiCloudClient`, `XiaomiAccountDialogs`, `BleCommandReceiver`, `Tasker*`, `Widget`, `MainActivity`, `CuktechDashboardScreen`, Gradle/CI).
- [x] Подготовка `PROJECT_AUDIT.md`, `FEATURE_TRACEABILITY.md`, `SECURITY_THREAT_MODEL.md`, `ARCHITECTURE.md`, `IMPLEMENTATION_PLAN.md`, `TEST_PLAN.md`.
- **Exit Gate Этапа 1:** Все 6 документов созданы и верифицированы по реальному коду; получено подтверждение перехода к Этапу 2.

---

## Этап 2: Ядро, Безопасность и Данные (Core, Security & Data)

### 2.1. P0 Безопасность управления и сети
1. **`AutomationAuthManager` + `CommandDispatcher` (`automation/`):**
   - Генерация 256-битного per-install токена в `SecureCredentialStore`.
   - Opt-in флаги по умолчанию `false`: `automation_intents_enabled`, `automation_tasker_enabled`, `tcp_gateway_enabled`, `tcp_lan_enabled`.
   - Постоянное по времени сравнение токенов (`MessageDigest.isEqual`), rate limiter, allowlist команд и параметров.
2. **`BleCommandReceiver` и `TaskerFireReceiver` (`AndroidManifest.xml`, `automation/`):**
   - Обязательная проверка opt-in + `auth_token` (или подписанного Tasker plugin payload с токеном).
   - Добавить отсутствующий экшен `FORCE_SOFT_RESET` (как задокументировано в `FEATURES.md`) и `SET_CHARGE_LIMIT` в `TaskerEditActivity` / `TaskerFireReceiver`.
   - Исправить неверные коды `SET_SCENE_MODE` и `SET_SCREEN_TIMEOUT` в справке API.
3. **`TcpGatewayServer` (извлечь из `BleBridgeService.kt`):**
   - Выключен по умолчанию.
   - По умолчанию биндится строго на `127.0.0.1` (LAN/Tailscale — отдельный opt-in + предупреждение).
   - Обязательная JSON `auth` команда по таймауту 5 сек, жёсткий allowlist высокоуровневых команд (удалить произвольные raw GATT `read`/`write`/`notify`/`reset_bluetooth`), лимит длины строки (8 КБ), лимит клиентов и rate limit.

### 2.2. P1 Приватность, Keystore, Бэкап ОС и Логи
1. **`SecureCredentialStore.kt`:**
   - Удалить `KEY_FALLBACK_RAW` и любой fallback записи ключа в `SharedPreferences`. Реализовать fail-closed статус с безопасной миграцией существующих валидных записей при наличии рабочего Keystore.
   - Удалить вызов `tryAutoImportLocalCredentialsFile()` на старте (перевести ручной импорт строго на SAF `OpenDocument` в UI).
2. **`AndroidManifest.xml` + `backup_rules.xml` + `data_extraction_rules.xml`:**
   - Установить `android:allowBackup="false"` и явно исключить `cuktech_secure_vault`, prefs и базы данных в правилах извлечения.
3. **`LogSanitizer` + `XiaomiAccountDialogs.kt` + `XiaomiCloudClient.kt`:**
   - Интегрировать `LogSanitizer.sanitize()` в `BleBridgeService.addLogMessage`, `cloudLog`, `Log.*` и копирование логов/диагностики.
   - В WebView 2FA добавить host allowlist (`*.xiaomi.com`, `sts.api.io.mi.com`), `allowFileAccess=false`, `allowContentAccess=false`, очистку `CookieManager` после завершения и таймауты на загрузку QR.
   - Изолировать `RC4-drop[1024]` / `MD5` / `SHA-1` строго внутри `XiaomiCloudClient` с явной аннотацией совместимости.

### 2.3. P1 Исправления BLE, WakeLock, Фильтра простоя и Энергии
1. **Декомпозиция `CuktechBleManager.kt` и `BleBridgeService.kt`:**
   - Выделить `CuktechGattQueue`, `CuktechConnectionCoordinator`, `CuktechAuthHandshaker`, `CuktechTelemetryProcessor`, `CuktechCommandExecutor`.
   - Вызывать `refreshGattCache()` при GATT 133 и сбоях `discoverServices()`.
   - Хранить и отображать реальный `negotiatedMtu` и реальный статус `WakeLock` в `BleDiagnostics.kt`.
   - Убрать вечный 1 Гц цикл при `DISCONNECTED` и отсутствии активных таймеров; сделать `acquireWakeLock()` условным с гарантированным `releaseWakeLock()`.
   - Исправить атомарную запись `PIID 21` в `setFastChargeProtocols` (1 транзакция вместо 10 корутин).
2. **Фикс фильтра тихого простоя и меток портов:**
   - Устранить повторный безусловный вызов `updatePortTelemetry` из `applyDecodedMiotFrame` для `PIID 1..4`.
   - Исправить маппинг `portId`: `1 -> C1, 2 -> C2, 3 -> C3, 4 -> A` в `BleBridgeService.forceRestoreNotification()` и во всех вспомогательных функциях.
3. **Room Database, Миграция, Gap-Aware Wh, Сшивание сессий, Архив и Тарифы:**
   - Реализовать `CuktechDatabase` (Room + KSP), сущности `TelemetrySample`, `ChargeSession`, `ArchivedSession`, `DailyEnergyAggregate`, `PortChargeLimit`, `DeviceAlias`, `ChargingProfile`.
   - Автоматическая миграция данных из legacy `cuktech10_history.db` (`CuktechPersistence`).
   - Обновить `EnergyTracker` (`GapAwareEnergyIntegrator`, `SessionStitchingEngine`, восстановление `cumulativeWh`).
   - Реализовать автоархивирование по настраиваемому порогу (по умолчанию **30 дней**) + подсчёт стоимости кВт·ч с фиксацией тарифа в сессии.
   - Перевести экспорт CSV на современный `MediaStore` / `SAF` (удалить устаревший `Environment.getExternalStoragePublicDirectory`).

### 2.4. Сборка и Зависимости
- Согласовать `minSdk = 26` (Android 8.0+, в соответствии с README и без костылей до Oreo) в `app/build.gradle.kts`.
- Удалить неиспользуемые зависимости `retrofit`, `converter-moshi`, `moshi-kotlin`, `moshi-kotlin-codegen` (и прочие неиспользуемые библиотеки в `app/build.gradle.kts`).
- Включить R8 в `release`: `isMinifyEnabled = true`, `isShrinkResources = true` + корректный `proguard-rules.pro`.

---

## Этап 3: UX / UI M3 Expressive / Pixel + Локализация RU/EN

1. **Декомпозиция UI-монолитов:** Разбить `MainActivity.kt` и `CuktechDashboardScreen.kt` на маршруты `Overview`, `Ports`, `Analytics`, `Automation`, `Settings`.
2. **Темизация Pixel / M3 Expressive:**
   - Dynamic Color (Android 12+) + Light / Dark / OLED режимы + сохранение фирменных палитр CUKTECH.
   - Подключение шрифтов с `tabular-nums` (`tnum`) для всех цифр W/V/A/Wh/₽ (при необходимости локальные `.ttf` через `font-util` по скиллу `android-typography`).
   - Адаптивная монохромная иконка приложения (`app-icon-generation`).
   - Адаптивные раскладки: `NavigationBar` (Compact) / `NavigationRail` + двухпанельные виды (Medium/Expanded).
3. **Локализация RU/EN:** Полный перевод всех строк в `values/strings.xml` и `values-ru/strings.xml` + экран выбора языка в Настройках.
4. **Настраиваемый стартовый экран:** По умолчанию **Обзорный Dashboard** (`Overview`), переключатель в Настройках на `Gateway`.
5. **Интерактивные экраны и аналитика:**
   - Интерактивная лицевая панель AD1204 (тап по порту -> фокус и быстрое управление, Undo Snackbar на отключение порта).
   - Мультиинтервальные графики (`1m / 15m / 1h / Сессия / День / Месяц`) + таблица тарифов/стоимости.
   - **Session Replay Scrubber** в деталях сессии и архива.

---

## Этап 4: Уведомления, Live Updates, Виджеты и Продвинутые функции

1. **Уведомления о завершении зарядки:** Пороговый гистерезис + удержание, выбор отслеживаемых портов, тихие часы (Quiet Hours), настройка повтора/звука/вибро.
2. **Android 16 Live Updates (`ProgressStyle`):** Использование `Notification.ProgressStyle`, `FLAG_PROMOTED_ONGOING`, `setShortCriticalText`, встроенного хронометра/таймера (без кастомного `RemoteViews`), с элегантным fallback на Android 8–15 и честной документацией поведения на Pixel/OEM.
3. **Виджеты Material 3 Expressive (`2x2`, `4x2`):** Динамические/тёмные/прозрачные пресеты, корректные порты `C1/C2/C3/A`, быстрое переключение и статус подключения.
4. **Зашифрованный SAF Бэкап/Восстановление:** Экспорт/импорт `.cukbackup` (`PBKDF2WithHmacSHA256` + `AES-256-GCM`), опция исключения секретов.
5. **GitHub Direct APK Updater:** Проверка GitHub Releases + скачивание + **строгая проверка SHA-256 и подписи сертификата APK** + установка через `FileProvider`.
6. **Master Killswitch, Профили зарядки (*Ночь*, *Ноутбук*, *Low-Power*), Детектор аномалий кабеля/PD, Алиасы устройств и портов, Экспорт анонимизированной диагностики, Обновлённый Tasker UI + HA Webhook UI (с HTTPS предупреждением).**

---

## Этап 5: Тестирование, CI и Финальная приёмка

1. Написание полного набора Unit и Robolectric тестов по `TEST_PLAN.md`.
2. Обновление `.github/workflows/android.yml` (`testDebugUnitTest`, `lintDebug`, `assembleDebug`) и проверка `release.yml`.
3. Запуск `./gradlew` / `gradle :app:testDebugUnitTest`, `compile_applet`, проверка R8 release-сборки, аудит манифеста и `grep` на отсутствие утечек секретов.
4. Финальное обновление документации (`README.md`, `FEATURES.md`, `docs/TCP_SOCKET_BRIDGE.md`) строго по факту реализации.
