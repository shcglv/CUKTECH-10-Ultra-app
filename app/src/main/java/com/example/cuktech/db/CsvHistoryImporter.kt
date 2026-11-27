package com.example.cuktech.db

import com.example.cuktech.db.room.TelemetrySampleEntity
import com.example.cuktech.energy.ChargeSessionRecord
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToLong

data class CsvImportResult(
    val importedSessions: Int = 0,
    val skippedDuplicateSessions: Int = 0,
    val importedSamples: Int = 0,
    val invalidRows: Int = 0
)

data class ParsedCsvBundle(
    val sessions: List<ChargeSessionRecord>,
    val samples: List<TelemetrySampleEntity>,
    val invalidRows: Int
)

/**
 * Parses exported CUKTECH session & telemetry CSV files back into typed records
 * with RFC-4180 quote handling, header recognition, and duplicate-safe merging.
 */
object CsvHistoryImporter {

    private enum class Section {
        NONE,
        SESSIONS,
        TELEMETRY
    }

    fun parseCsv(csvText: String, fallbackDeviceMac: String = ""): ParsedCsvBundle {
        val sessions = ArrayList<ChargeSessionRecord>()
        val samples = ArrayList<TelemetrySampleEntity>()
        var invalidRows = 0
        var section = Section.NONE
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        for (rawLine in csvText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#")) continue

            if (line.startsWith("session_id,port,start_time", ignoreCase = true)) {
                section = Section.SESSIONS
                continue
            }
            if (line.startsWith("timestamp,port_id,voltage_v", ignoreCase = true)) {
                section = Section.TELEMETRY
                continue
            }

            val cols = parseCsvRow(line)
            when (section) {
                Section.SESSIONS -> {
                    if (cols.size < 12) {
                        invalidRows++
                        continue
                    }
                    val portId = parsePortId(cols[1])
                    val startMs = runCatching { fmt.parse(cols[2].trim())?.time }.getOrNull()
                    if (portId !in 1..4 || startMs == null || startMs <= 0L) {
                        invalidRows++
                        continue
                    }
                    val startSec = startMs / 1000.0
                    val rawEnd = cols[3].trim()
                    val endMs = if (rawEnd.equals("active", ignoreCase = true) || rawEnd.isBlank()) {
                        null
                    } else {
                        runCatching { fmt.parse(rawEnd)?.time }.getOrNull()
                    }
                    val activeDurSec = cols[4].trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    val gapDurSec = cols[5].trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    val fastBoostSec = cols[6].trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    val taperSec = cols[7].trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    val wh = cols[8].trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                    val peakW = cols[9].trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                    val avgW = cols[10].trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                    if (wh == null) {
                        invalidRows++
                        continue
                    }
                    val proto = cols[11].trim().ifBlank { "Idle" }
                    val endReason = cols.getOrNull(12)?.trim()?.takeIf { it.isNotBlank() }
                    val cost = cols.getOrNull(13)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                    val currency = cols.getOrNull(14)?.trim()?.ifBlank { "USD" } ?: "USD"
                    val resolvedEndSec = when {
                        endMs != null && endMs >= startMs -> endMs / 1000.0
                        activeDurSec > 0L -> startSec + activeDurSec + gapDurSec
                        else -> startSec
                    }

                    sessions.add(
                        ChargeSessionRecord(
                            id = 0L,
                            portId = portId,
                            portLabel = portLabelFor(portId),
                            deviceMac = fallbackDeviceMac.trim().uppercase(),
                            startTimeSec = startSec,
                            endTimeSec = resolvedEndSec,
                            lastSampleTimeSec = resolvedEndSec,
                            activeDurationSec = activeDurSec,
                            gapDurationSec = gapDurSec,
                            fastBoostDurationSec = fastBoostSec,
                            taperDurationSec = taperSec,
                            whAccumulated = wh,
                            peakWatts = peakW,
                            avgWatts = avgW,
                            protocolUsed = proto,
                            isActive = false,
                            isPaused = false,
                            endReason = endReason,
                            currencyCode = currency,
                            computedCost = cost
                        )
                    )
                }

                Section.TELEMETRY -> {
                    if (cols.size < 7) {
                        invalidRows++
                        continue
                    }
                    val tsMs = runCatching { fmt.parse(cols[0].trim())?.time }.getOrNull()
                    val portId = cols[1].trim().toIntOrNull()
                    val v = cols[2].trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                    val a = cols[3].trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                    val w = cols[4].trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                    val proto = cols[5].trim().ifBlank { "Idle" }
                    val wh = cols[6].trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                    if (tsMs == null || tsMs <= 0L || portId == null || portId !in 1..4 || v == null || a == null || w == null) {
                        invalidRows++
                        continue
                    }
                    samples.add(
                        TelemetrySampleEntity(
                            timestampMs = tsMs,
                            portId = portId,
                            voltageV = v,
                            currentA = a,
                            powerW = w,
                            protocolName = proto,
                            cumulativeWh = wh,
                            deviceMac = fallbackDeviceMac.trim().uppercase(),
                            sourceMode = "CSV_IMPORT"
                        )
                    )
                }

                Section.NONE -> {
                    invalidRows++
                }
            }
        }

        return ParsedCsvBundle(
            sessions = sessions,
            samples = samples,
            invalidRows = invalidRows
        )
    }

    fun sessionDedupeKey(portId: Int, startTimeSec: Double): String =
        "$portId:${startTimeSec.roundToLong()}"

    fun parsePortId(raw: String): Int {
        val norm = raw.trim().uppercase()
        return when {
            norm == "1" || norm.contains("C1") -> 1
            norm == "2" || norm.contains("C2") -> 2
            norm == "3" || norm.contains("C3") -> 3
            norm == "4" || norm == "USB-A" || norm == "A" -> 4
            else -> norm.toIntOrNull() ?: -1
        }
    }

    private fun portLabelFor(portId: Int): String = when (portId) {
        1 -> "USB-C1"
        2 -> "USB-C2"
        3 -> "USB-C3"
        4 -> "USB-A"
        else -> "USB-$portId"
    }

    internal fun parseCsvRow(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    cur.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == ',' && !inQuotes) {
                out.add(unprotectFormulaCell(cur.toString()))
                cur.clear()
            } else {
                cur.append(c)
            }
            i++
        }
        out.add(unprotectFormulaCell(cur.toString()))
        return out
    }

    private fun unprotectFormulaCell(cell: String): String {
        val trimmed = cell.trim()
        return if (trimmed.length >= 2 && trimmed[0] == '\'' && trimmed[1] in charArrayOf('=', '+', '-', '@')) {
            trimmed.substring(1)
        } else {
            trimmed
        }
    }
}
