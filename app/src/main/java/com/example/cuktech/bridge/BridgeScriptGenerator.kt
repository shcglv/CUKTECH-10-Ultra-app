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
from aiohttp import web
from zeroconf import ServiceInfo, Zeroconf

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
        self.pending_commands = []

    def count_samples(self) -> int:
        row = self.db.execute("SELECT COUNT(*) FROM samples").fetchone()
        return int(row[0]) if row else 0

    def record_sample(self, port_id: int, v: float, a: float, w: float, proto: str = "Idle", wh: float = 0.0, rssi: int = 0):
        ts_ms = int(time.time() * 1000)
        self.db.execute(
            "INSERT INTO samples (ts_ms, mac, port_id, voltage_v, current_a, power_w, protocol, cumulative_wh, rssi) VALUES (?,?,?,?,?,?,?,?,?)",
            (ts_ms, self.mac, port_id, v, a, w, proto, wh, rssi),
        )
        self.db.execute("DELETE FROM samples WHERE ts_ms < ?", (ts_ms - 7 * 86400 * 1000,))
        self.db.commit()
        self.last_poll_ms = ts_ms


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
            "version": "1.0-py",
            "activeMac": state.mac,
            "bufferedSamples": state.count_samples(),
            "lastPollMs": state.last_poll_ms,
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

    async def handle_command(request: web.Request):
        if not is_authorized(request):
            return web.json_response({"error": "unauthorized"}, status=401)
        body = await request.json()
        state.pending_commands.append({"ts_ms": int(time.time() * 1000), "payload": body})
        return web.json_response({"accepted": True, "queued": len(state.pending_commands)})

    app.router.add_get("/api/v1/status", handle_status)
    app.router.add_get("/api/v1/telemetry/export", handle_export)
    app.router.add_post("/api/v1/command", handle_command)
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


async def main():
    parser = argparse.ArgumentParser(description="CUKTECH 10 Ultra Stationary LAN Bridge")
    parser.add_argument("--mac", default=os.environ.get("CUKTECH_MAC", DEFAULT_MAC))
    parser.add_argument("--port", type=int, default=int(os.environ.get("CUKTECH_BRIDGE_PORT", DEFAULT_PORT)))
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
    try:
        while True:
            await asyncio.sleep(5)
    finally:
        zc.unregister_service(info)
        zc.close()
        await runner.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
""".trimIndent()
    }
}
