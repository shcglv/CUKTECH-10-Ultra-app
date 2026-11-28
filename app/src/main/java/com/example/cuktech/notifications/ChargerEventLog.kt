package com.example.cuktech.notifications

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ChargerEventType {
    WH_LIMIT_CUTOFF,
    TIMER_EXPIRED,
    CHARGING_COMPLETE,
    ANOMALY,
    AUTOMATION_TRIGGERED,
    HIGH_LOAD_ALERT,
    LOW_POWER_DROP_ALERT,
    BLE_PROXIMITY_EXIT,
    BRIDGE_SYNC_MERGED,
    TELEMETRY_STALE,
    WIFI_LEAVE_LOAD
}

data class ChargerEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val type: ChargerEventType,
    val portId: Int = 0, // 0 = charger-wide
    val title: String,
    val detail: String = "",
    val isRead: Boolean = false
)

/**
 * Persistent reactive feed of real charger events (limit cutoffs, timer expirations,
 * trickle completions, anomalies, automation rule triggers, and threshold alerts)
 * surfaced in the TopAppBar Event Center bell sheet.
 */
class ChargerEventLog(
    private val maxEntries: Int = 60,
    private val highLoadCooldownMs: Long = 300_000L,
    private val lowDropConfirmMs: Long = 10_000L,
    private val storagePrefs: SharedPreferences? = null
) {
    private val stateLock = Any()
    private val _events = MutableStateFlow(loadPersistedEvents())
    val events: StateFlow<List<ChargerEvent>> = _events.asStateFlow()

    private val _unreadCount = MutableStateFlow(_events.value.count { !it.isRead })
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private var highLoadArmed = true
    private var lastHighLoadFiredMs = 0L

    private val portWasAbovePeak = ConcurrentHashMap<Int, Boolean>()
    private val portLowSinceMs = ConcurrentHashMap<Int, Long>()
    private val portLowDropFired = ConcurrentHashMap<Int, Boolean>()

    fun addEvent(
        type: ChargerEventType,
        title: String,
        detail: String = "",
        portId: Int = 0,
        timestampMs: Long = System.currentTimeMillis()
    ): ChargerEvent {
        val ev = ChargerEvent(
            id = "${type.name}_${portId}_${timestampMs}_${UUID.randomUUID().toString().take(6)}",
            timestampMs = timestampMs,
            type = type,
            portId = portId,
            title = title,
            detail = detail,
            isRead = false
        )
        synchronized(stateLock) {
            val next = (listOf(ev) + _events.value).take(maxEntries)
            _events.value = next
            _unreadCount.value = next.count { !it.isRead }
            persistEventsLocked(next)
        }
        return ev
    }

    fun markAllRead() {
        synchronized(stateLock) {
            val cur = _events.value
            if (cur.none { !it.isRead }) return
            val next = cur.map { if (it.isRead) it else it.copy(isRead = true) }
            _events.value = next
            _unreadCount.value = 0
            persistEventsLocked(next)
        }
    }

    fun clearAll() {
        synchronized(stateLock) {
            _events.value = emptyList()
            _unreadCount.value = 0
            persistEventsLocked(emptyList())
        }
    }

    private fun loadPersistedEvents(): List<ChargerEvent> {
        val raw = storagePrefs?.getString(KEY_EVENTS_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val typeName = obj.optString("type", "")
                    val evType = runCatching { ChargerEventType.valueOf(typeName) }.getOrNull() ?: continue
                    val id = obj.optString("id", "").ifBlank { UUID.randomUUID().toString() }
                    val ts = obj.optLong("ts", 0L)
                    val title = obj.optString("title", "")
                    if (title.isBlank()) continue
                    add(
                        ChargerEvent(
                            id = id,
                            timestampMs = ts,
                            type = evType,
                            portId = obj.optInt("portId", 0),
                            title = title,
                            detail = obj.optString("detail", ""),
                            isRead = obj.optBoolean("isRead", false)
                        )
                    )
                }
            }.sortedByDescending { it.timestampMs }.take(maxEntries)
        }.getOrDefault(emptyList())
    }

    private fun persistEventsLocked(list: List<ChargerEvent>) {
        val prefs = storagePrefs ?: return
        runCatching {
            val arr = JSONArray()
            list.take(maxEntries).forEach { ev ->
                arr.put(
                    JSONObject().apply {
                        put("id", ev.id)
                        put("ts", ev.timestampMs)
                        put("type", ev.type.name)
                        put("portId", ev.portId)
                        put("title", ev.title)
                        put("detail", ev.detail)
                        put("isRead", ev.isRead)
                    }
                )
            }
            prefs.edit().putString(KEY_EVENTS_JSON, arr.toString()).apply()
        }
    }

    companion object {
        private const val KEY_EVENTS_JSON = "events_json"
    }

    /**
     * Evaluates threshold alerts with hysteresis & cooldown against real telemetry samples.
     * Returns newly fired events (empty list if disabled or within cooldown/hysteresis).
     */
    fun evaluateThresholds(
        enabled: Boolean,
        totalWatts: Double,
        highLoadThresholdW: Double,
        portPowers: Map<Int, Double>,
        lowDropThresholdW: Double,
        nowMs: Long = System.currentTimeMillis(),
        highLoadTitleBuilder: (Double, Double) -> Pair<String, String> = { curW, thrW ->
            "High Load Alert" to String.format(java.util.Locale.US, "%.1f W >= %.0f W threshold", curW, thrW)
        },
        lowDropTitleBuilder: (Int, Double, Double) -> Pair<String, String> = { pid, curW, thrW ->
            "Port P$pid Low Power Drop" to String.format(java.util.Locale.US, "Dropped to %.1f W (<= %.1f W)", curW, thrW)
        }
    ): List<ChargerEvent> {
        if (!enabled) {
            highLoadArmed = true
            portWasAbovePeak.clear()
            portLowSinceMs.clear()
            portLowDropFired.clear()
            return emptyList()
        }
        val fired = mutableListOf<ChargerEvent>()

        // 1. High-load alert with 5W hysteresis reset + cooldown
        val safeHigh = highLoadThresholdW.coerceIn(40.0, 120.0)
        if (totalWatts >= safeHigh) {
            if (highLoadArmed && (nowMs - lastHighLoadFiredMs >= highLoadCooldownMs)) {
                highLoadArmed = false
                lastHighLoadFiredMs = nowMs
                val (t, d) = highLoadTitleBuilder(totalWatts, safeHigh)
                fired += addEvent(ChargerEventType.HIGH_LOAD_ALERT, t, d, portId = 0, timestampMs = nowMs)
            }
        } else if (totalWatts < safeHigh - 5.0) {
            highLoadArmed = true
        }

        // 2. Per-port low-power drop alert (only after sustained prior load >= max(thr*2, 5W))
        val safeLow = lowDropThresholdW.coerceIn(0.5, 15.0)
        val armThresholdW = maxOf(safeLow * 2.0, 5.0)
        for (pid in 1..4) {
            val w = portPowers[pid] ?: 0.0
            if (w >= armThresholdW) {
                portWasAbovePeak[pid] = true
                portLowSinceMs.remove(pid)
                portLowDropFired[pid] = false
            } else if (portWasAbovePeak[pid] == true) {
                if (w in 0.2..safeLow) {
                    val since = portLowSinceMs.getOrPut(pid) { nowMs }
                    if ((nowMs - since >= lowDropConfirmMs) && portLowDropFired[pid] != true) {
                        portLowDropFired[pid] = true
                        val (t, d) = lowDropTitleBuilder(pid, w, safeLow)
                        fired += addEvent(ChargerEventType.LOW_POWER_DROP_ALERT, t, d, portId = pid, timestampMs = nowMs)
                    }
                } else if (w < 0.2) {
                    // Disconnected/idle reset
                    portWasAbovePeak[pid] = false
                    portLowSinceMs.remove(pid)
                    portLowDropFired[pid] = false
                } else {
                    portLowSinceMs.remove(pid)
                }
            }
        }
        return fired
    }

    fun resetThresholdState() {
        highLoadArmed = true
        lastHighLoadFiredMs = 0L
        portWasAbovePeak.clear()
        portLowSinceMs.clear()
        portLowDropFired.clear()
    }
}
