package com.example.cuktech.notifications

import android.content.Context

/**
 * Persists absolute wall-clock end timestamps for per-port auto-off countdowns
 * and USB-A 4h Low-Current mode across service restarts and process deaths.
 */
object PersistedTimerStore {
    private const val PREFS_NAME = "cuktech_timers_prefs"
    private const val KEY_PORT_PREFIX = "port_countdown_end_"
    private const val KEY_USBA_LOW_CURRENT = "usba_low_current_end_sec"

    fun savePortCountdownEndSec(context: Context, portId: Int, endEpochSec: Long) {
        if (portId !in 1..4) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong("$KEY_PORT_PREFIX$portId", endEpochSec)
            .apply()
    }

    fun clearPortCountdown(context: Context, portId: Int) {
        if (portId !in 1..4) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("$KEY_PORT_PREFIX$portId")
            .apply()
    }

    fun loadActivePortCountdowns(context: Context, nowEpochSec: Long = System.currentTimeMillis() / 1000L): Map<Int, Long> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val result = mutableMapOf<Int, Long>()
        val editor = prefs.edit()
        var mutated = false
        for (portId in 1..4) {
            val v = prefs.getLong("$KEY_PORT_PREFIX$portId", 0L)
            if (v > nowEpochSec) {
                result[portId] = v
            } else if (v > 0L) {
                editor.remove("$KEY_PORT_PREFIX$portId")
                mutated = true
            }
        }
        if (mutated) editor.apply()
        return result
    }

    fun saveUsbALowCurrentEndSec(context: Context, endEpochSec: Long?) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (endEpochSec != null && endEpochSec > 0L) {
            editor.putLong(KEY_USBA_LOW_CURRENT, endEpochSec)
        } else {
            editor.remove(KEY_USBA_LOW_CURRENT)
        }
        editor.apply()
    }

    fun loadUsbALowCurrentEndSec(context: Context, nowEpochSec: Long = System.currentTimeMillis() / 1000L): Long? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val v = prefs.getLong(KEY_USBA_LOW_CURRENT, 0L)
        if (v > nowEpochSec) return v
        if (v > 0L) prefs.edit().remove(KEY_USBA_LOW_CURRENT).apply()
        return null
    }
}
