# ARCHITECTURE — BLEBridge (CUKTECH 10 Ultra AD1204)

> **Целевая архитектура:** Чистый MVVM + Repository + Domain Engines + Foreground BLE/Automation Service, совместимый с Jetpack Compose Navigation, адаптивной раскладкой (телефон / планшет / складные устройства) и Google Play Policy.

---

## 1. Целевая модульно-пакетная структура (`com.example`)

Монолиты (`CuktechDashboardScreen.kt` 3667L, `CuktechBleManager.kt` 2773L, `MainActivity.kt` 2127L, `BleBridgeService.kt` 2075L) декомпозируются по слоям:

```text
com.example
├── core/
│   ├── security/
│   │   ├── SecureCredentialStore.kt        # AES-256-GCM (AndroidKeyStore only, fail-closed)
│   │   ├── AutomationAuthManager.kt        # CSPRNG per-install token, opt-in flags, constant-time verify
│   │   ├── LogSanitizer.kt                 # Централизованное маскирование секретов/MAC/URL
│   │   └── SafEncryptedBackupManager.kt    # PBKDF2-HMAC-SHA256 + AES-256-GCM экспорт/импорт (.cukbackup)
│   ├── prefs/
│   │   └── AppPreferencesRepository.kt     # DataStore / SharedPrefs: тема, язык, стартовый экран, тарифы, тихие часы, ретеншн
│   └── localization/
│       └── AppLocaleHelper.kt              # RU/EN переключение через AppCompatDelegate / LocaleManager
├── data/
│   ├── db/
│   │   ├── CuktechDatabase.kt              # Room DB + миграция с legacy cuktech10_history.db
│   │   ├── entities/                       # TelemetrySampleEntity, ChargeSessionEntity, ArchivedSessionEntity,
│   │   │                                   # DailyEnergyAggregateEntity, PortChargeLimitEntity, DeviceAliasEntity, ChargingProfileEntity
│   │   └── dao/                            # HistoryDao, ArchiveDao, StatsDao, ConfigDao
│   ├── repository/
│   │   ├── EnergyHistoryRepository.kt      # Запись сэмплов, сшивание сессий, 30д автоархив, расчёт стоимости, CSV/SAF экспорт
│   │   └── DeviceConfigRepository.kt       # Алиасы устройств/портов, профили зарядки, лимиты Wh
│   └── cloud/
│       └── XiaomiCloudClient.kt            # Изолированный клиент (выделенный @MiOTLegacyCloudCrypto для RC4-drop[1024])
├── domain/
│   ├── protocol/
│   │   ├── CuktechProtocol.kt              # Константы AD1204 (SIID/PIID, UUID, коды режимов/таймаутов/протоколов)
│   │   ├── MiOTCrypto.kt                   # ECDH SECP256R1, HKDF-SHA256, AES-CCM
│   │   └── PortTelemetryDecoder.kt         # Декодер 8B V2 & 4B кадров с дедупликацией
│   └── energy/
│       ├── GapAwareEnergyIntegrator.kt     # Трапеция без фантомного начисления при разрывах > maxValidDt
│       ├── SessionStitchingEngine.kt       # Сшивание разрывов <= graceWindow, защита от нулевых провалов и сна
│       ├── TariffCalculator.kt             # Снимки стоимости кВт·ч (дневной / ночной / плоский тариф)
│       └── CableAnomalyDetector.kt         # Детектирование просадки VBUS, protocol flapping, падения мощности
├── ble/
│   ├── CuktechBleManager.kt                # Фасад и публичный StateFlow API для UI/Service
│   ├── CuktechConnectionCoordinator.kt     # Scan, Connect, Reconnect backoff, Disconnect cooldown, Bluetooth state
│   ├── CuktechAuthHandshaker.kt            # Фаза A (0xA4 init) + Фаза B (ECDH/MiOT token)
│   ├── CuktechGattQueue.kt                 # Mutex + Channel очередь, таймауты, реальный MTU, refreshGattCache
│   ├── CuktechTelemetryProcessor.kt        # Фильтрация silent-idle (без двойного вызова!), обновление портов/энергии
│   └── CuktechCommandExecutor.kt           # Атомарные команды PIID 15/16/17/21/6/13/19/14/20/таймеры
├── automation/
│   ├── CommandDispatcher.kt                # Единый валидирующий шлюз + Master Killswitch + rate limiter + allowlist
│   ├── BleCommandReceiver.kt               # BroadcastReceiver (ADB/Tasker intents -> проверка opt-in + token)
│   ├── TcpGatewayServer.kt                 # 127.0.0.1 по умолчанию; LAN/Tailscale opt-in + auth + JSON allowlist
│   ├── CloudIntegrations.kt                # Home Assistant Webhook (санитизация, backoff, https warning)
│   └── tasker/
│       ├── TaskerEditActivity.kt           # Все 15+ действий (вкл. SET_CHARGE_LIMIT, FORCE_SOFT_RESET, KILLSWITCH)
│       └── TaskerFireReceiver.kt           # Проверка opt-in + token + allowlist через CommandDispatcher
├── service/
│   ├── BleBridgeService.kt                 # Foreground service, условный polling/таймер-тик (0 Гц в disconnect), адаптивный WakeLock
│   ├── NotificationOrchestrator.kt         # Завершение зарядки (гистерезис + тихие часы), канал alert + Android 16 ProgressStyle Live Updates
│   └── QuickSettingsTileService.kt         # (Опционально) быстрый Master Killswitch / Статус
├── updater/
│   ├── GitHubReleaseChecker.kt             # HTTPS GitHub Releases API check
│   └── ApkUpdateVerifier.kt                # Сверка SHA-256 и подписи сертификата APK перед установкой через FileProvider
├── ui/
│   ├── navigation/                         # @Serializable маршруты, настраиваемый стартовый экран, NavigationBar / NavigationRail
│   ├── theme/                              # M3 Expressive ColorScheme (Dynamic Color + CUKTECH Cyan/Oled/Carbon/Cobalt/Emerald/Light), Type (`tnum`)
│   ├── components/                         # InteractiveFaceplate, LivePowerCharts (1m..Month), SessionReplayScrubber, UndoSnackbarHost
│   ├── overview/                           # OverviewScreen (живые ватты, лицевая панель, быстрые действия, активный профиль)
│   ├── ports/                              # PortsControlScreen (4 порта, протоколы PIID 21, таймеры, Wh-лимиты, USB-A Low Current, профили Night/Laptop/Low-Power)
│   ├── analytics/                          # AnalyticsHistoryScreen (графики 1m/15m/1h/сессия/день/месяц, стоимость, 30д история + архив, CSV)
│   ├── automation/                         # AutomationGatewayScreen (Tasker/ADB шпаргалка с точными кодами, генератор/копия токена, TCP, HA Webhook, консоль + диагностика)
│   └── settings/                           # SettingsScreen (тема, язык RU/EN, стартовый экран, тарифы, уведомления/тихие часы, SAF бэкап, обновления, Killswitch)
└── widget/
    └── CuktechWidgetProvider.kt            # 2x2 & 4x2 M3 виджеты (исправленные метки C1/C2/C3/A, Dynamic/Dark стили)
```

---

## 2. Ключевые доменные механизмы

### 2.1. Gap-Aware Wh Integrator & Session Stitching (Этап 2)
- **Проблема в текущем коде:** `maxDtSec = 30.0` интегрирует `0.5 * (P_last + P_now) * dt` даже после 20-секундного разрыва BLE, начисляя фантомные Wh; при разрыве/блокировке экрана сессия обрывается на куски.
- **Решение:**
  1. `GAP_THRESHOLD_SEC` (напр. `5.0s`): если `dt <= 5.0s` — честное трапецеидальное интегрирование; если `dt > 5.0s` — интервал считается пропуском телеметрии (gap) и энергия за него **не дорисовывается** (или помечается в статистике как `gapSeconds` без фантомных Wh).
  2. `SessionStitchingEngine`: при кратковременном провале мощности ниже порога, переключении сценария, soft-reset или кратковременном разрыве BLE (до конфигурируемого `stitchGraceSec`, напр. `120s`, при возврате на тот же `mac` + `portId`), активная сессия **продолжается** с сохранением `accumulatedWh` в Room (`ChargeSessionEntity`), переживая перезапуск процесса.
  3. Восстановление `portCumulativeWh` для авто-отключения (Wh Limit) из активной незавершённой записи в Room при старте.

### 2.2. Схема Room Database & Автоархив (Этап 2)
- `TelemetrySampleEntity`: `(id, sessionId, deviceMac, portId, timestampMs, voltageV, currentA, powerW, protocolCode, isGapMarker)` с индексом по `(sessionId, timestampMs)` и `(portId, timestampMs)`.
- `ChargeSessionEntity` (горячая история, по умолчанию ≤ 30 дней): `(sessionId, deviceMac, portId, startMs, endMs, activeDurationSec, gapDurationSec, whTotal, peakWatts, avgWatts, dominantProtocol, tariffSnapshotPerKwh, currencyCode, computedCost, status [ACTIVE|COMPLETED], isArchived=false)`.
- `ArchivedSessionEntity` (или флаг/таблица долгосрочного архива > N дней): сохраняет сводку старых сессий, удаляя только высокочастотные 1-секундные сэмплы старше порога (или сжимая их в минутные бакеты/агрегаты) по настраиваемому сроку (`7 / 14 / 30 [default] / 60 / 90 / 180` дней или вручную).
- `DailyEnergyAggregateEntity`: `(dateEpochDay, deviceMac, portId, totalWh, totalCost, currencyCode, sessionCount, peakWatts)` — мгновенная отрисовка графиков «День / Месяц» за год без сканирования тысяч сэмплов.
- `PortChargeLimitEntity`, `DeviceAliasEntity`, `ChargingProfileEntity`.
- **Миграция:** Однократный безопасный импорт существующих таблиц из `SQLiteOpenHelper` (`cuktech10_history.db`) в Room при первом запуске новой версии без потери истории пользователя.

### 2.3. Условное пробуждение, BLE Cache & Polling (Этап 2)
- Отмена постоянного 1 Гц цикла в `CuktechBleManager.init`: тикер обратного отсчёта запускается **только** пока устройство `AUTHENTICATED` И есть хотя бы один активный аппаратный таймер (`countdownCode > 0` или `usbALowCurrent`) либо активный живой счётчик UI/уведомления.
- `acquireWakeLock()` вызывается только на время активной критичной транзакции или при явной настройке *High-Reliability Keepalive*, с обязательным release в `finally` и при отключении, а `BleDiagnostics` читает реальное состояние `wakeLock?.isHeld` и фактический `negotiatedMtu`.
- При GATT-ошибках (`status = 133`, `discoverServices() == false`, ненайденные характеристики) выполняется `refreshGattCache(gatt)` перед переподключением с экспоненциальной задержкой.

### 2.4. Уведомления, Android 16 Live Updates & Статус-чип (Этап 4)
- **Уведомление о завершении зарядки:** Логика гистерезиса per-port — фиксируется пик зарядки (например `>= 10W`), затем при падении мощности ниже порога (например `< 2.0W` на протяжении `60–120 сек` непрерывно, настраивается) отправляется уведомление с проверкой **Тихих часов (Quiet Hours)**, настраиваемым звуком/вибро и лимитом повтора.
- **Android 16 Promoted Live Updates (`API 36+`):**
  - В манифесте `<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />`.
  - Использование `Notification.ProgressStyle` с сегментами активных портов / прогрессом к Wh-лимиту, `setShortCriticalText("84W")`, `setWhen` + `setUsesChronometer` (или обратный отсчёт для таймера порта) и проверка `NotificationManager.canPostPromotedNotifications()`.
  - Строго **без кастомных `RemoteViews`** для Live Update (системное требование Android 16 для promotion в Status Chip / Lock Screen).
  - На Android 8–15 / устройствах без промоушена — элегантное `Ongoing` уведомление `BigTextStyle` / компактный прогресс.

### 2.5. Стартовый экран и адаптивный M3 Expressive UI (Этап 3)
- Стартовый экран конфигурируется в Настройках; по умолчанию **Обзорный Dashboard** (`OverviewRoute`), второй вариант — `GatewayRoute`.
- Адаптивная навигация на `WindowWidthSizeClass`: `NavigationBar` снизу на телефонах (`Compact`) и `NavigationRail` + two-pane Master-Detail на планшетах/складных экранах (`Medium` / `Expanded`).
