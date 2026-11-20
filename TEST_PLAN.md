# TEST PLAN — BLEBridge (CUKTECH 10 Ultra AD1204)

> **Ограничение среды:** В облачной среде отсутствуют Android Emulator и `adb`, поэтому инструментальные тесты (`androidTest/` с эмулятором/Espresso) не запускаются. Вся автоматическая верификация строится на **JUnit + Robolectric (`gradle :app:testDebugUnitTest`)**, **Android Lint (`:app:lintDebug`)** и **сборке (`compile_applet` / release R8)**.

---

## 1. Криптография и протокол AD1204 (Unit)

| Тест | Что проверяет | Ожидаемый результат |
|---|---|---|
| `MiOTCryptoTest` | ECDH SECP256R1 генерация пары ключей, общий секрет, HKDF-SHA256 деривация ключей A4/B-phase, шифрование/дешифровка AES-CCM | Детерминированное совпадение с эталонными векторами; отказ дешифровки при битом MAC-теге |
| `PortTelemetryDecoderTest` | Разбор 8-байтовых (V2) и 4-байтовых пакетов `PIID 1..4`, границы V/A/W, протоколы (`PD3.1 EPR`, `UFCS`, `PPS`, `Mi Turbo`, `QC`, `Apple 2.4A`), `Idle / Off` | Точные значения напряжения/тока/ватт; отсутствие ложных триггеров при идентичном кадре (игнорирование `lastUpdatedMs` в `equals`/дедупликации) |
| `CuktechProtocolEncodingTest` | Кодирование команд: `PIID 15` (сценарии `1..4`), `PIID 16` (порт-маска и Soft Reset `0x00 -> 0x0F`), `PIID 17` (USB-A Low Current), `PIID 21` (атомарный 32-битный битмаск протоколов), `PIID 6` (`4,5,1,2,3`), `PIID 13`, `PIID 19` | Точные байты без искажения протокола AD1204 |

---

## 2. Безопасность (Unit + Robolectric)

| Тест | Что проверяет | Ожидаемый результат |
|---|---|---|
| `SecureCredentialStoreTest` | Сохранение/чтение/удаление `XiaomiAccountSession` и `CuktechDeviceCredentials`; отсутствие plaintext `KEY_FALLBACK_RAW`; отсутствие автоимпорта из `/Download` | При повреждении/отсутствии ключа в Keystore — fail-closed без записи сырого ключа в `SharedPreferences`; публичные файлы игнорируются |
| `CommandDispatcherSecurityTest` | Проверка `BleCommandReceiver`, `TaskerFireReceiver` и `CommandDispatcher` | 1) Команды отклоняются при выключенном opt-in; 2) Команды отклоняются без или с неверным `auth_token`; 3) Невалидные `port` (`0`, `5`), `mode` (`9`), `timeout` (`99`) отклоняются; 4) `Master Killswitch` блокирует не-emergency автоматизацию и разрешает killswitch; 5) Rate limit отсекает флуд |
| `TcpGatewayServerTest` | Бинд на `127.0.0.1` по умолчанию; auth таймаут; allowlist; лимит длины строки `8192B` | 1) Неавторизованные команды отклоняются и соединение закрывается; 2) Сырой GATT `write`/`read`/`reset_bluetooth` отклоняется с ошибкой allowlist; 3) Слишком длинный payload отбрасывается без OOM |
| `LogSanitizerTest` | Санитизация 24/32-hex токенов, `ssecurity`, `serviceToken`, `passToken`, `Authorization: Bearer`, query `token=`, URL HA Webhook, анонимизация MAC/IP для экспорта диагностики | Ни одна тестовая секретная строка не остаётся в выходной строке или экспорте диагностики |
| `SafEncryptedBackupTest` | Экспорт и импорт `.cukbackup` (AES-256-GCM + PBKDF2) | Успешное восстановление при верном пароле; отказ (`AEADBadTagException` -> понятная ошибка) при неверном пароле или изменённом байте; опция исключения секретов реально не сериализует токены |
| `ApkUpdateVerifierTest` | Проверка SHA-256 и совпадения подписи APK | Блокировка установки при несовпадении SHA-256, отсутствии хеша или несовпадении сертификата |
| `BackupManifestAuditTest` | Проверка `allowBackup == false`, `backup_rules.xml`, `data_extraction_rules.xml` | Секретные хранилища и БД исключены из всех правил резервного копирования Android |

---

## 3. Энергия (Wh), Сшивание сессий, Room & Архив (Unit + Robolectric)

| Тест | Что проверяет | Ожидаемый результат |
|---|---|---|
| `GapAwareEnergyIntegratorTest` | Интегрирование мощности на шагах `1s`, `3s` и после разрыва `25s` при `P = 100W` | При `dt <= 5s` — точная трапеция; при `dt = 25s` **0.00 фантомных Wh** за время разрыва |
| `SessionStitchingEngineTest` | Разрыв BLE на 45с (в пределах `graceWindow = 120s`) vs длительный простой 10м; блокировка экрана; перезапуск с восстановлением `cumulativeWh` | Краткий разрыв сшивается в одну сессию с верным `activeDurationSec` и `gapDurationSec`; перезапуск восстанавливает накопленный `Wh` для лимита |
| `RoomMigrationAndArchiveTest` | 1) Миграция данных из legacy `SQLiteOpenHelper` (`cuktech10_history.db`) в `CuktechDatabase`; 2) Автоархив сессий старше 30 дней; 3) Исторический тариф | 1) Все старые сессии перенесены без потерь; 2) Старые сессии переходят в архив, детальные сэмплы чистятся, `DailyEnergyAggregate` сходится по Wh; 3) Смена тарифа в настройках НЕ меняет `computedCost` уже сохранённых прошлых сессий |

---

## 4. Автоматизация, Уведомления, Виджеты & UI (Robolectric)

| Тест | Что проверяет | Ожидаемый результат |
|---|---|---|
| `NotificationHysteresisTest` | Срабатывание завершения зарядки при падении с 45W до `< 2W` на протяжении времени удержания; подавление кратковременного провала 5с; Тихие часы | Уведомление отправляется только после подтверждения; звук/вибро подавляются в Quiet Hours |
| `CableAnomalyDetectorTest` | Серия пересогласований протокола (flapping) и критическая просадка напряжения | Генерация единичного предупреждения без спама |
| `WidgetMappingTest` | Обновление `CuktechWidgetProvider` и `forceRestoreNotification` с 4 портами (`1..4`) | Строго `C1, C2, C3, A` (исключён регресс `portId 3 -> "A"`) |
| `ComposeDashboardAndLocalizationTest` | Рендер стартового экрана `Overview`, переключение RU/EN строк, Undo Snackbar, доступность (contentDescriptions / touch targets), поворот экрана | Стартует `Overview` по умолчанию; локализованные строки не содержат хардкода; back navigation работает предсказуемо |

---

## 5. CI / Verification Pipeline (`.github/workflows/android.yml`)

В рамках Этапа 5 обновляется CI workflow на обязательный прогон:
1. `gradle :app:testDebugUnitTest --no-daemon`
2. `gradle :app:lintDebug --no-daemon`
3. `gradle :app:assembleDebug --no-daemon`
4. Периодическая проверка `gradle :app:assembleRelease` (с включённым R8 `isMinifyEnabled = true`) для раннего выявления ошибок ProGuard.
