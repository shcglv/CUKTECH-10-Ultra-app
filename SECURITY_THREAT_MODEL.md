# SECURITY THREAT MODEL — BLEBridge (CUKTECH 10 Ultra)

> **Принцип проектирования:** Zero-Trust к внешним входам (IPC, TCP, локальные файлы, WebView, OTA-файлы), защита пользовательских секретов аппаратным Keystore и нулевые утечки секретов в логи, бэкапы и буфер обмена.

---

## 1. Инвентаризация защищаемых активов (Assets)

| Актив | Формат и чувствительность | Текущее хранение | Целевое хранение / защита |
|---|---|---|---|
| **MiOT Device Token** | 12 байт (24 hex) — критичный ключ рукопожатия с зарядкой | `SharedPreferences("cuktech_secure_vault")` (может упасть в plaintext key fallback) | Только AES-256-GCM с ключом в `AndroidKeyStore` (fail-closed, без plaintext fallback) |
| **BLE Beacon Key** | 16 байт (32 hex) — ключ дешифровки BLE | То же | То же |
| **Xiaomi Cloud Session** | `userId`, `ssecurity`, `serviceToken`, cookies | То же + WebView `CookieManager` | Зашифрованное хранилище в Keystore + немедленная очистка WebView `CookieManager` и кеша |
| **Automation Secret Token** | 256-битный CSPRNG per-install токен для Tasker/ADB/TCP | **Отсутствует** | Генерируется при первом включении автоматизации в `AndroidKeyStore`-шифрованном vault |
| **HA Webhook URL & Bearer Token** | Токен доступа к умному дому пользователя | Нешифрованные `SharedPreferences` (`CloudIntegrations`) | Перенос секретных заголовков/URL в encrypted store + маскирование в логах |
| **ECDH / Session AES-CCM Key** | Эфемерный сеансовый ключ в RAM | Поля в `CuktechBleManager` | Очистка массивов в `disconnect` / `onDestroy`, отсутствие дампов ключей в логи |
| **История телеметрии и алиасы** | Паттерны зарядки устройств, MAC-адреса | Незащищённый SQLite (`allowBackup=true`) | `allowBackup="false"`, строгий `backup_rules.xml`, экспорт только через зашифрованный SAF |

---

## 2. Границы доверия и матрица угроз (STRIDE)

### 2.1. Внешнее управление: BroadcastReceivers & Tasker (`BleCommandReceiver`, `TaskerFireReceiver`)
- **Угроза (Spoofing / Tampering / EoP):** Стороннее вредоносное или фишинговое приложение на том же телефоне отправляет `ACTION_SET_PORT_STATE(enabled=false)`, `ACTION_START_SERVER` или `FORCE_SOFT_RESET`, обесточивая ноутбук/роутер или открывая TCP-порт.
- **Смягчение (подтверждено пользователем):**
  1. **Opt-in по умолчанию OFF:** Отдельные переключатели в настройках: *«Разрешить Tasker / Внешние Intent-команды»* и *«Разрешить ADB / CLI Broadcast»*. Пока выключены — приёмники отклоняют все внешние команды и не выполняют никаких действий.
  2. **Per-Install Secret Token:** Обязательный extra `auth_token` (для `BleCommandReceiver`) и проверка настроенного токена (для Tasker / плагина) с **constant-time сравнением (`MessageDigest.isEqual`)**.
  3. **Tasker Plugin Binding:** В `TaskerEditActivity` при настройке действия автоматически вшивается либо проверяется per-install token (с защитой при экспорте конфига), а `TaskerFireReceiver` валидирует opt-in, токен и допустимые диапазоны аргументов (whitelist портов `1..4`, режимов `1..4`, таймаутов `{1,2,3,4,5}`).
  4. **Защита от Replay и DoS:** Опциональный `nonce` / монотонный window для чувствительных команд + rate limit (не более N команд/сек; защита от зацикленного `SOFT_RESET`).
  5. **Выходящие трансляции `TELEMETRY_UPDATE`:** Ограничиваются явным opt-in (и таргетированием пакета Tasker при необходимости, чтобы не транслировать данные о зарядке всем приложениям на устройстве в фоне).

### 2.2. TCP Socket Gateway (`BleBridgeService.kt`)
- **Угроза (Unauthorized Access / Arbitrary GATT Manipulation / DoS):** В текущей версии открытый `ServerSocket(8080)` на `0.0.0.0` позволяет любому устройству в локальной Wi-Fi сети или Tailscale читать, модифицировать GATT-характеристики и сбрасывать зарядку, а также устроить OOM/DoS длинной строкой без `\n`.
- **Смягчение (подтверждено пользователем):**
  1. **По умолчанию выключен.**
  2. **Привязка к `InetAddress.getByName("127.0.0.1")` по умолчанию** (безопасный локальный мост, например через `adb forward` или локальный скрипт).
  3. **LAN / Tailscale — отдельный явный Opt-in** с предупреждением в UI (*Tailscale / VPN не заменяет аутентификацию клиента*).
  4. **Аутентификация сессии:** Первая команда клиента обязана быть `{"cmd":"auth","token":"<secret>"}` в течение таймаута 5 сек (`socket.soTimeout`), иначе разрыв. Constant-time сравнение токена.
  5. **Жёсткий Allowlist команд:** Полное удаление произвольных `read`/`write`/`notify`/`reset_bluetooth` по сырым UUID. Разрешены только безопасные высокоуровневые команды домена (`status`, `get_ports`, `set_port`, `set_scene`, `set_timeout`, `soft_reset` при разрешении, `ping`) с валидацией параметров.
  6. **Лимиты и устойчивость:** Максимум N одновременных клиентов (напр. 4), лимит длины строки (`8192` байт), read/idle таймаут (`30s`), rate limit на команды клиента (напр. 10 команд/сек) и `ExponentialBackoff`/ban при многократных неверных токенах.

### 2.3. Локальное хранилище ключей, импорт и Auto Backup (`SecureCredentialStore.kt`)
- **Угроза (Key Compromise / Rogue File Injection / Backup Extraction):**
  - Plaintext `KEY_FALLBACK_RAW` обнуляет шифрование при сбое Keystore.
  - Автосканирование `/storage/emulated/0/Download/cuktech_credentials.json` позволяет любому приложению/загрузке из браузера подменить ключи без ведома пользователя.
  - `allowBackup="true"` выгружает ключи в облачный бэкап / через `adb backup`.
- **Смягчение:**
  1. Удалить `KEY_FALLBACK_RAW` и любой plaintext fallback полностью. Использовать `AndroidKeyStore` (AES-256-GCM) с деградацией только до предупреждения пользователя об ошибке Keystore (fail-closed). Продумать безопасную миграцию старых записей при первом запуске (однократное перешифрование и удаление `test_fallback_k`).
  2. Удалить `tryAutoImportLocalCredentialsFile()` на инициализации (`CuktechBleManager.init`). Импорт JSON-ключей — только по явному действию пользователя через системный `ActivityResultContracts.OpenDocument()` (SAF).
  3. Установить `android:allowBackup="false"`, заполнить `backup_rules.xml` и `data_extraction_rules.xml` строгими `<exclude>` для всех `sharedpref` и `database` (дополнительная эшелонированная защита для Android 12+).

### 2.4. Зашифрованный локальный бэкап (SAF Export / Import)
- **Угроза (Weak Encryption / Tampering):** Сохранение токенов и истории в открытом виде на SD/Downloads.
- **Смягчение (подтверждено пользователем):**
  1. Только ручной экспорт и импорт через Storage Access Framework (`CreateDocument` / `OpenDocument`), никакой облачной синхронизации.
  2. Формат `.cukbackup`: заголовок (`magic`, `version`, `salt[16]`, `iv[12]`, `kdfIterations`), KDF **`PBKDF2WithHmacSHA256` (минимум 210 000 итераций)** из пользовательской парольной фразы (с требованием минимальной сложности/длины), шифрование **`AES-256-GCM`** с аутентифицированными метаданными (AAD).
  3. Явный чекбокс при экспорте: *«Включить секретные токены устройств (рекомендуется только с надёжным паролем)»* vs *«Только настройки, алиасы, профили и история»*.

### 2.5. Логи, буфер обмена и диагностика (`LogSanitizer`)
- **Угроза (Information Disclosure):** Утечка MiOT-токенов, `beaconkey`, `serviceToken`, `ssecurity`, cookies, bearer-токенов HA Webhook и MAC-адресов через Logcat, консоль в UI, буфер обмена и баг-репорты.
- **Смягчение:**
  1. Единый `LogSanitizer.sanitize(msg)` на входе в `BleBridgeService.addLogMessage()`, `Log.*` и при формировании отчёта `BleDiagnostics`.
  2. Регулярные маски: 24/32/64-hex секреты (`tokenHex`, `bleKey`, `ssecurity`, `serviceToken`, `passToken`, `auth_token`), URL query-параметры (`d=`, `ticket=`, `serviceToken=`, `token=`), `Authorization: Bearer ...`, URL вебхуков (скрывается путь токена после `/webhook/`).
  3. Для анонимизированного экспорта диагностики (Этап 4) additionally маскируются MAC-адреса (`AA:BB:**:**:EE:FF`), локальные IP (`192.168.*.*`) и имена пользовательских алиасов.

### 2.6. Xiaomi Cloud & WebView (`XiaomiAccountDialogs.kt`, `XiaomiCloudClient.kt`)
- **Угроза (Cookie Theft / Open Redirect / JS Injection):** Глобальное сохранение сессионных cookie Xiaomi в WebView, перехват токенов с недоверенных URL при редиректе, загрузка QR-кода без таймаутов.
- **Смягчение:**
  1. Strict host allowlist для `WebViewClient` и перехвата `serviceToken` (`account.xiaomi.com`, `sts.api.io.mi.com`, `*.account.xiaomi.com` — только HTTPS).
  2. В WebView: `settings.allowFileAccess = false`, `settings.allowContentAccess = false`, `settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW`, очистка `CookieManager.removeAllCookies` и `clearCache(true)` при закрытии диалога и после извлечения токена.
  3. Таймауты на загрузку QR-изображения (`connectTimeout = 10s`, `readTimeout = 10s`, проверка схемы `https://`).
  4. Вынесение `RC4-drop[1024]` / `MD5` / `SHA-1` в помеченный `@MiOTLegacyCloudCrypto` адаптер внутри `XiaomiCloudClient` (строго только для протокола Xiaomi Cloud API, чтобы статические аудиты и разработчики не путали их с локальной криптографией приложения).

### 2.7. Home Assistant Webhook & GitHub Direct APK Updater
- **Home Assistant (`CloudIntegrations.kt`):**
  - Валидация URL (`https://` по умолчанию; для `http://` в локальной LAN — явный баннер предупреждения в UI о передаче данных открытым текстом, подтверждаемый пользователем).
  - Редакция URL вебхука и bearer-токена в логах и диагностике.
- **GitHub Direct APK Updater (Этап 4):**
  - Запрос метаданных релиза строго по HTTPS (`https://api.github.com/repos/.../releases/latest`).
  - Обязательная загрузка/сверка **SHA-256 контрольной суммы** из файла релиза (`.sha256` или `digest` из GitHub API) перед установкой.
  - Проверка **подписи (signing certificate SHA-256)** скачанного APK через `PackageManager` (`GET_SIGNING_CERTIFICATES`) на совпадение с подписью установленного приложения перед запуском `Intent.ACTION_INSTALL_PACKAGE` через `FileProvider`. Если контрольная сумма отсутствует или не совпадает — установка **запрещена** (предупреждение + отмена).
