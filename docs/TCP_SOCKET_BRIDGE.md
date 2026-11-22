# Hardened TCP JSON Gateway Guide (v2.0.0)

The **TCP Socket Gateway** in **Cuktech (`com.joxit.cuktech`) — CUKTECH 10 Ultra** is a hardened, loopback-first, token-authenticated JSON gateway routed through the single `CommandDispatcher` chokepoint.

---

## Security Model & Defaults

- **Disabled by Default (Opt-In):** Turning on the TCP Gateway requires explicitly enabling **TCP Socket Gateway** in **Settings -> Security & Automation**.
- **Loopback Bind by Default (`127.0.0.1:8080`):** By default the server binds strictly to localhost (`127.0.0.1`) so arbitrary devices on public or home Wi-Fi cannot connect. Binding to `0.0.0.0` (LAN) requires toggling an explicit opt-in switch and displays a security warning.
- **Per-Install Token Handshake (`auth` within 5 seconds):** Every newly accepted client has **5,000 ms** to send `{"cmd":"auth","token":"<token>"}` using the 256-bit CSPRNG token from **Settings -> Security & Automation**. Repeated bad attempts trigger exponential backoff lockout.
- **No Arbitrary Raw GATT Commands:** Legacy raw BLE operations (`scan`, `connect`, `disconnect`, `read`, `write`, `notify`, `reset_bluetooth`, `request_mtu`, `discover`) are strictly blocked (`COMMAND_FORBIDDEN`). Only validated high-level charger commands are accepted.
- **Resource Caps:** Maximum **4 concurrent clients** and **8,192 bytes per JSON line**.
- **Independent of BLE Telemetry:** Disabling the TCP Gateway stops only the local socket listener and never terminates your active BLE session with the charger.

---

## Connecting via ADB (Recommended)

With the TCP Gateway enabled on its default `127.0.0.1:8080` bind:

```bash
# Forward local port 8080 over USB or Wireless ADB:
adb forward tcp:8080 tcp:8080

# Connect locally on your workstation:
nc 127.0.0.1 8080
```

> If you explicitly enable **Allow LAN bind (`0.0.0.0`)** or tunnel over Tailscale / WireGuard, ensure only trusted peers can reach the port and treat your automation token as sensitive credentials.

---

## JSON Line Protocol (v2.0.0)

Send one UTF-8 JSON object per newline (`\n`). Field `cmd` (or alias `command`) selects the operation.

### 1. Ping (Allowed Before or After Auth)
```json
{"cmd": "ping"}
```
Response (before auth):
```json
{"status": "pong", "auth_required": true, "timestamp": 1763635200000}
```

### 2. Authenticate (Mandatory First Operational Step)
```json
{"cmd": "auth", "token": "YOUR_64_HEX_TOKEN"}
```
Response:
```json
{"status": "authenticated", "timestamp": 1763635201000}
```

### 3. Charger Status & Port Telemetry
```json
{"cmd": "status"}
{"cmd": "get_ports"}
```

### 4. Validated Charger Controls
```json
{"cmd": "set_port", "port": 1, "enabled": true}
{"cmd": "set_scene", "mode": 1}
{"cmd": "set_timeout", "timeout": 3}
{"cmd": "set_countdown", "port": 2, "minutes": 60}
{"cmd": "set_charge_limit", "port": 1, "wh_target": 50.0, "mode": "once", "enabled": true}
{"cmd": "soft_reset"}
{"cmd": "killswitch"}
{"cmd": "restore_killswitch"}
{"cmd": "undo"}
```

- **Killswitch Response:** Returns `"status": "ok"` or `"status": "partial"` with per-port `outcomes` (`USB-C1`..`USB-A`) because sequential `PIID 16` writes are not claimed as hardware-atomic.
- **Undo & Safety Lockout:** `undo` and `restore_killswitch` are automatically rejected (`SAFETY_LOCKOUT` / `WH_LIMIT_TRIGGERED`) if a safety cutoff fired since the command was executed.

---

## Alternatives for Home Assistant & Scripted Automation

1. **Home Assistant HTTP Webhook (`Settings -> Home Assistant Webhook`):** Periodically pushes decoded JSON telemetry (`total_power_w`, per-port `v`/`a`/`w`/`protocol`/`enabled`) with optional Keystore-encrypted Bearer token over `HTTPS` (or warned `HTTP`).
2. **Tasker / MacroDroid Plugin (`TaskerEditActivity` / `TaskerFireReceiver`):** Opt-in plugin actions signed with the per-install token.
3. **ADB / Termux Broadcast Intent API (`BleCommandReceiver`):** Opt-in intents requiring `--es auth_token "<token>"` (see [FEATURES.md](../FEATURES.md)).
