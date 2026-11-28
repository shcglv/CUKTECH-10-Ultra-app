package com.example.cuktech.bridge

/**
 * Generates a self-contained Python 3 companion bridge script (`cuktech_bridge.py`)
 * matching the HTTP JSON & mDNS contract expected by [BridgeSyncClient] and [BridgeDiscoveryHelper].
 * Designed to run on a Raspberry Pi, Linux mini-PC, or Termux host left near the charger.
 */
object BridgeScriptGenerator {

    fun generateCompanionScript(
        chargerMac: String,
        port: Int = 8787,
        serviceName: String = "CUKTECH-Pi-Bridge"
    ): String {
        val cleanMac = chargerMac.trim().uppercase().ifBlank { "AA:BB:CC:DD:EE:FF" }
        val safePort = port.coerceIn(1024, 65535)
        val safeName = serviceName.trim().ifBlank { "CUKTECH-Pi-Bridge" }

        return """
#!/usr/bin/env python3
\"\"\"CUKTECH 10 Ultra (AD1204) Stationary LAN Bridge Companion
Matches Android app contract in BridgeSyncClient / BridgeDiscoveryHelper.
Dependencies: pip install aiohttp bleak zeroconf
\"\"\"
import argparse
import asyncio
import json
import os
import socket
import sqlite3
import time
from typing import Optional
from aiohttp import web
from zeroconf import ServiceInfo, Zeroconf

try:
    from bleak import BleakScanner
except ImportError:
    BleakScanner = None

DEFAULT_MAC = "$cleanMac"
DEFAULT_PORT = $safePort
SERVICE_NAME = "$safeName"
DB_PATH = os.environ.get("CUKTECH_BRIDGE_DB", "cuktech_bridge_cache.db")
BEARER_TOKEN = os.environ.get("CUKTECH_BRIDGE_TOKEN", "")


def init_db(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path)
    conn.execute(
        \"\"\"CREATE TABLE IF NOT EXISTS samples (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts_ms INTEGER NOT NULL,
            mac TEXT NOT NULL,
            port_id INTEGER NOT NULL,
            voltage_v REAL NOT NULL,
            current_a REAL NOT NULL,
            power_w REAL NOT NULL,
            protocol TEXT NOT NULL,
            cumulative_wh REAL NOT NULL,
            rssi INTEGER NOT NULL DEFAULT 0
        )\"\"\"
    )
    conn.execute("CREATE INDEX IF NOT EXISTS idx_samples_ts ON samples(mac, ts_ms)")
    conn.commit()
    return conn


class BridgeState:
    def __init__(self, mac: str, db: sqlite3.Connection):
        self.mac = mac.upper()
        self.db = db
        self.last_poll_ms = 0
        self.last_ble_seen_ms = 0
        self.last_rssi = 0
        self.ble_device_name = ""
        self.pending_commands = []
        self._next_cmd_id = 1

    def count_samples(self) -> int:
        row = self.db.execute("SELECT COUNT(*) FROM samples").fetchone()
        return int(row[0]) if row else 0

    def is_ble_online(self, max_age_ms: int = 45000) -> bool:
        return self.last_ble_seen_ms > 0 and (int(time.time() * 1000) - self.last_ble_seen_ms) <= max_age_ms

    def record_sample(
        self,
        port_id: int,
        v: float,
        a: float,
        w: float,
        proto: str = "Idle",
        wh: float = 0.0,
        rssi: int = 0,
        ts_ms: Optional[int] = None,
        mac: Optional[str] = None,
    ) -> bool:
        if port_id not in (1, 2, 3, 4):
            return False
        now_ms = int(ts_ms if ts_ms and ts_ms > 0 else time.time() * 1000)
        target_mac = (mac or self.mac).strip().upper() or self.mac
        self.db.execute(
            "INSERT INTO samples (ts_ms, mac, port_id, voltage_v, current_a, power_w, protocol, cumulative_wh, rssi) VALUES (?,?,?,?,?,?,?,?,?)",
            (now_ms, target_mac, port_id, max(0.0, float(v)), max(0.0, float(a)), max(0.0, float(w)), proto or "Idle", max(0.0, float(wh)), int(rssi)),
        )
        self.db.execute("DELETE FROM samples WHERE ts_ms < ?", (now_ms - 7 * 86400 * 1000,))
        self.db.commit()
        self.last_poll_ms = now_ms
        return True

    def record_ble_sighting(self, name: str, rssi: int):
        now_ms = int(time.time() * 1000)
        self.last_ble_seen_ms = now_ms
        self.last_rssi = int(rssi)
        if name:
            self.ble_device_name = name


def is_authorized(request: web.Request) -> bool:
    if not BEARER_TOKEN:
        return True
    auth = request.headers.get("Authorization", "")
    return auth == f"Bearer {BEARER_TOKEN}"


def build_app(state: BridgeState) -> web.Application:
    app = web.Application()

    async def handle_status(request: web.Request):
        if not is_authorized(request):
            return web.json_response({"error": "unauthorized"}, status=401)
        return web.json_response({
            "online": True,
            "version": "1.1-py",
            "activeMac": state.mac,
            "bufferedSamples": state.count_samples(),
            "lastPollMs": state.last_poll_ms,
            "bleOnline": state.is_ble_online(),
            "lastBleSeenMs": state.last_ble_seen_ms,
            "rssi": state.last_rssi,
            "bleScannerAvailable": BleakScanner is not None,
        })

    async def handle_ble(request: web.Request):
        if not is_authorized(request):
            return web.json_response({"error": "unauthorized"}, status=401)
        return web.json_response({
            "mac": state.mac,
            "bleOnline": state.is_ble_online(),
            "lastBleSeenMs": state.last_ble_seen_ms,
            "rssi": state.last_rssi,
            "deviceName": state.ble_device_name,
            "scannerAvailable": BleakScanner is not None,
        })

    async def handle_export(request: web.Request):
        if not is_authorized(request):
            return web.json_response({"error": "unauthorized"}, status=401)
        mac = request.query.get("mac", state.mac).upper() or state.mac
        since_ms = int(request.query.get("since_ms", "0"))
        until_ms = int(request.query.get("until_ms", str(int(time.time() * 1000))))
        rows = state.db.execute(
            "SELECT ts_ms, mac, port_id, voltage_v, current_a, power_w, protocol, cumulative_wh, rssi "
            "FROM samples WHERE mac = ? AND ts_ms BETWEEN ? AND ? ORDER BY ts_ms ASC LIMIT 5000",
            (mac, since_ms, until_ms),
        ).fetchall()
        samples = [
            {
                "ts_ms": r[0],
                "mac": r[1],
                "port_id": r[2],
                "voltage_v": r[3],
                "current_a": r[4],
                "power_w": r[5],
                "protocol": r[6],
                "cumulative_wh": r[7],
                "rssi": r[8],
            }
            for r in rows
        ]
        return web.json_response({"samples": samples})

    async def handle_ingest(request: web.Request):
        if not is_authorized(request):
            return web.json_response({"error": "unauthorized"}, status=401)
        body = await request.json()
        raw_items = body.get("samples") if isinstance(body, dict) and "samples" in body else (body if isinstance(body, list) else [body])
        inserted = 0
        for item in raw_items:
            if not isinstance(item, dict):
                continue
            ok = state.record_sample(
                port_id=int(item.get("port_id", item.get("portId", 0))),
                v=float(item.get("voltage_v", item.get("voltageV", 0.0))),
                a=float(item.get("current_a", item.get("currentA", 0.0))),
                w=float(item.get("power_w", item.get("powerW", 0.0))),
                proto=str(item.get("protocol", item.get("protocolName", "Idle"))),
                wh=float(item.get("cumulative_wh", item.get("cumulativeWh", 0.0))),
                rssi=int(item.get("rssi", item.get("rssiDbm", state.last_rssi))),
                ts_ms=int(item.get("ts_ms", item.get("timestampMs", 0))) or None,
                mac=str(item.get("mac", item.get("deviceMac", state.mac))),
            )
            if ok:
                inserted += 1
        return web.json_response({"accepted": True, "inserted": inserted, "bufferedSamples": state.count_samples()})

    async def handle_command_post(request: web.Request):
        if not is_authorized(request):
            return web.json_response({"error": "unauthorized"}, status=401)
        body = await request.json()
        cmd_id = state._next_cmd_id
        state._next_cmd_id += 1
        state.pending_commands.append({"id": cmd_id, "ts_ms": int(time.time() * 1000), "payload": body})
        return web.json_response({"accepted": True, "id": cmd_id, "queued": len(state.pending_commands)})

    async def handle_command_get(request: web.Request):
        if not is_authorized(request):
            return web.json_response({"error": "unauthorized"}, status=401)
        return web.json_response({"commands": list(state.pending_commands)})

    async def handle_command_ack(request: web.Request):
        if not is_authorized(request):
            return web.json_response({"error": "unauthorized"}, status=401)
        body = await request.json()
        ack_id = body.get("id") if isinstance(body, dict) else None
        before = len(state.pending_commands)
        if ack_id is not None:
            state.pending_commands = [c for c in state.pending_commands if c.get("id") != ack_id]
        return web.json_response({"removed": before - len(state.pending_commands), "queued": len(state.pending_commands)})

    app.router.add_get("/api/v1/status", handle_status)
    app.router.add_get("/api/v1/ble", handle_ble)
    app.router.add_get("/api/v1/telemetry/export", handle_export)
    app.router.add_post("/api/v1/telemetry/ingest", handle_ingest)
    app.router.add_post("/api/v1/ingest", handle_ingest)
    app.router.add_post("/api/v1/command", handle_command_post)
    app.router.add_get("/api/v1/command", handle_command_get)
    app.router.add_post("/api/v1/command/ack", handle_command_ack)
    return app


def get_lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.168.1.1", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


async def ble_scanner_loop(state: BridgeState, interval_sec: float):
    if BleakScanner is None:
        print("[CUKTECH Bridge] bleak not installed; running in HTTP ingest/relay mode only.")
        while True:
            await asyncio.sleep(interval_sec)

    target_mac = state.mac.upper()
    last_heartbeat_ms = 0

    def on_detect(device, adv_data):
        addr = (getattr(device, "address", "") or "").upper()
        name = getattr(device, "name", "") or getattr(adv_data, "local_name", "") or ""
        if addr == target_mac or ("AD1204" in name.upper()):
            rssi = getattr(adv_data, "rssi", None)
            if rssi is None:
                rssi = getattr(device, "rssi", 0) or 0
            state.record_ble_sighting(name, int(rssi))

    scanner = BleakScanner(detection_callback=on_detect)
    while True:
        try:
            await scanner.start()
            await asyncio.sleep(min(5.0, interval_sec))
            await scanner.stop()
            now_ms = int(time.time() * 1000)
            if state.is_ble_online() and (now_ms - last_heartbeat_ms) >= int(interval_sec * 1000):
                # Record BLE link/RSSI presence marker on port 1 only if no live sample arrived recently
                if (now_ms - state.last_poll_ms) >= int(interval_sec * 1000):
                    state.record_sample(1, 0.0, 0.0, 0.0, proto="BLE Beacon", wh=0.0, rssi=state.last_rssi, ts_ms=now_ms)
                last_heartbeat_ms = now_ms
        except Exception as exc:
            print(f"[CUKTECH Bridge] BLE scan notice: {exc}")
        await asyncio.sleep(max(2.0, interval_sec - 5.0))


async def main():
    parser = argparse.ArgumentParser(description="CUKTECH 10 Ultra Stationary LAN Bridge")
    parser.add_argument("--mac", default=os.environ.get("CUKTECH_MAC", DEFAULT_MAC))
    parser.add_argument("--port", type=int, default=int(os.environ.get("CUKTECH_BRIDGE_PORT", DEFAULT_PORT)))
    parser.add_argument("--poll-interval", type=float, default=float(os.environ.get("CUKTECH_BRIDGE_POLL_SEC", "15")))
    args = parser.parse_args()

    db = init_db(DB_PATH)
    state = BridgeState(args.mac, db)
    app = build_app(state)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", args.port)
    await site.start()

    ip = get_lan_ip()
    zc = Zeroconf()
    info = ServiceInfo(
        "_http._tcp.local.",
        f"{SERVICE_NAME}._http._tcp.local.",
        addresses=[socket.inet_aton(ip)],
        port=args.port,
        properties={"mac": state.mac, "role": "cuktech-bridge"},
    )
    zc.register_service(info)
    print(f"[CUKTECH Bridge] Listening on http://{ip}:{args.port} for MAC {state.mac}")
    scanner_task = asyncio.create_task(ble_scanner_loop(state, max(5.0, args.poll_interval)))
    try:
        await scanner_task
    finally:
        scanner_task.cancel()
        zc.unregister_service(info)
        zc.close()
        await runner.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
""".trimIndent()
    }
}
