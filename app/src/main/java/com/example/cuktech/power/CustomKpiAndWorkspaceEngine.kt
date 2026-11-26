package com.example.cuktech.power

import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.protocol.PortTelemetryDecoder
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.max

enum class WorkspacePreset(val id: String, val matchKeywords: List<String>) {
    ALL("ALL", emptyList()),
    HOME("HOME", listOf("home", "house", "bedroom", "дом", "квартира", "спальня")),
    OFFICE("OFFICE", listOf("office", "work", "desk", "офис", "работа", "стол")),
    TRAVEL("TRAVEL", listOf("travel", "flight", "hotel", "дорога", "поездка", "отель")),
    LAB("LAB", listOf("lab", "bench", "test", "лаб", "стенд", "тест"));

    companion object {
        fun fromId(id: String): WorkspacePreset =
            entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) } ?: ALL
    }
}

enum class KpiFormulaType(val expectedUnitGroup: String) {
    ACTIVE_LOAD_PCT("PERCENT"),
    HOURLY_COST_RATE("COST_PER_HOUR"),
    AVERAGE_SESSION_WH("ENERGY_WH"),
    PEAK_HEADROOM_W("POWER_W"),
    MULTI_PORT_SHARE_PCT("PERCENT"),
    DAILY_TARGET_PROGRESS("PERCENT")
}

data class CustomKpiDefinition(
    val id: String = UUID.randomUUID().toString().take(8),
    val title: String,
    val formulaType: KpiFormulaType,
    val unit: String,
    val targetValue: Double = 100.0
)

data class EvaluatedCustomKpi(
    val definition: CustomKpiDefinition,
    val currentValue: Double,
    val formattedValue: String,
    val progressRatio: Float?
)

/**
 * Manages Workspace contexts and Custom KPI formulas with explicit formula/unit
 * compatibility validation so user-defined dashboards cannot mix incompatible units.
 */
object CustomKpiAndWorkspaceEngine {

    val ALLOWED_UNITS_BY_FORMULA: Map<KpiFormulaType, Set<String>> = mapOf(
        KpiFormulaType.ACTIVE_LOAD_PCT to setOf("%"),
        KpiFormulaType.MULTI_PORT_SHARE_PCT to setOf("%"),
        KpiFormulaType.DAILY_TARGET_PROGRESS to setOf("%"),
        KpiFormulaType.PEAK_HEADROOM_W to setOf("W", "Вт"),
        KpiFormulaType.AVERAGE_SESSION_WH to setOf("Wh", "Вт·ч"),
        KpiFormulaType.HOURLY_COST_RATE to setOf("COST/h", "USD/h", "RUB/h", "EUR/h", "/h")
    )

    fun defaultUnitForFormula(formulaType: KpiFormulaType, currencyCode: String = "USD"): String = when (formulaType) {
        KpiFormulaType.ACTIVE_LOAD_PCT,
        KpiFormulaType.MULTI_PORT_SHARE_PCT,
        KpiFormulaType.DAILY_TARGET_PROGRESS -> "%"
        KpiFormulaType.PEAK_HEADROOM_W -> "W"
        KpiFormulaType.AVERAGE_SESSION_WH -> "Wh"
        KpiFormulaType.HOURLY_COST_RATE -> "${currencyCode.ifBlank { "USD" }}/h"
    }

    /**
     * Returns null when the unit is valid for [formulaType], or an explicit localized error key.
     */
    fun validateUnitCompatibility(formulaType: KpiFormulaType, rawUnit: String): String? {
        val u = rawUnit.trim()
        if (u.isEmpty()) return "kpi_err_unit_empty"
        return when (formulaType) {
            KpiFormulaType.ACTIVE_LOAD_PCT,
            KpiFormulaType.MULTI_PORT_SHARE_PCT,
            KpiFormulaType.DAILY_TARGET_PROGRESS ->
                if (u == "%" || u.equals("pct", ignoreCase = true)) null else "kpi_err_percent_required"

            KpiFormulaType.PEAK_HEADROOM_W ->
                if (u.equals("W", ignoreCase = true) || u.equals("Вт", ignoreCase = true)) null else "kpi_err_watts_required"

            KpiFormulaType.AVERAGE_SESSION_WH ->
                if (u.equals("Wh", ignoreCase = true) || u.equals("Вт·ч", ignoreCase = true)) null else "kpi_err_wh_required"

            KpiFormulaType.HOURLY_COST_RATE ->
                if (u.endsWith("/h", ignoreCase = true) || u.endsWith("/ч", ignoreCase = true)) null else "kpi_err_cost_rate_required"
        }
    }

    fun serializeKpis(list: List<CustomKpiDefinition>): String {
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("formulaType", item.formulaType.name)
                    put("unit", item.unit)
                    put("targetValue", item.targetValue)
                }
            )
        }
        return arr.toString()
    }

    fun deserializeKpis(rawJson: String): List<CustomKpiDefinition> {
        val trimmed = rawJson.trim()
        if (trimmed.isBlank() || !trimmed.startsWith("[")) return emptyList()
        return runCatching {
            val arr = JSONArray(trimmed)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val formula = runCatching {
                        KpiFormulaType.valueOf(obj.optString("formulaType"))
                    }.getOrNull() ?: continue
                    val title = obj.optString("title", "").trim()
                    if (title.isBlank()) continue
                    val unit = obj.optString("unit", defaultUnitForFormula(formula)).trim()
                    if (validateUnitCompatibility(formula, unit) != null) continue
                    add(
                        CustomKpiDefinition(
                            id = obj.optString("id", UUID.randomUUID().toString().take(8)),
                            title = title,
                            formulaType = formula,
                            unit = unit,
                            targetValue = obj.optDouble("targetValue", 100.0).coerceAtLeast(0.0)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun evaluateKpis(
        definitions: List<CustomKpiDefinition>,
        ports: Map<Int, PortTelemetry>,
        ratedMaxW: Double,
        effectiveTariffPerKwh: Double,
        currencyCode: String,
        completedSessions: List<ChargeSessionRecord>,
        todayWh: Double
    ): List<EvaluatedCustomKpi> {
        if (definitions.isEmpty()) return emptyList()
        val effectiveW = PortTelemetryDecoder.computeEffectiveTotalWatts(ports)
        val safeMaxW = ratedMaxW.coerceAtLeast(60.0)
        val topPortW = (1..4).maxOfOrNull { ports[it]?.powerW ?: 0.0 } ?: 0.0

        return definitions.map { def ->
            val value = when (def.formulaType) {
                KpiFormulaType.ACTIVE_LOAD_PCT ->
                    ((effectiveW / safeMaxW) * 100.0).coerceIn(0.0, 100.0)
                KpiFormulaType.HOURLY_COST_RATE ->
                    (effectiveW / 1000.0) * max(0.0, effectiveTariffPerKwh)
                KpiFormulaType.AVERAGE_SESSION_WH ->
                    if (completedSessions.isEmpty()) 0.0 else completedSessions.map { it.whAccumulated }.average()
                KpiFormulaType.PEAK_HEADROOM_W ->
                    max(0.0, safeMaxW - effectiveW)
                KpiFormulaType.MULTI_PORT_SHARE_PCT ->
                    if (effectiveW > 0.3) ((topPortW / effectiveW) * 100.0).coerceIn(0.0, 100.0) else 0.0
                KpiFormulaType.DAILY_TARGET_PROGRESS ->
                    if (def.targetValue > 0.0) ((todayWh / def.targetValue) * 100.0).coerceIn(0.0, 200.0) else 0.0
            }

            val formatted = when (def.formulaType) {
                KpiFormulaType.HOURLY_COST_RATE ->
                    String.format(Locale.US, "%.3f %s/h", value, currencyCode.ifBlank { "USD" })
                KpiFormulaType.ACTIVE_LOAD_PCT,
                KpiFormulaType.MULTI_PORT_SHARE_PCT,
                KpiFormulaType.DAILY_TARGET_PROGRESS ->
                    String.format(Locale.US, "%.1f%%", value)
                else ->
                    String.format(Locale.US, "%.1f %s", value, def.unit)
            }

            val progress = when {
                def.formulaType == KpiFormulaType.DAILY_TARGET_PROGRESS -> (value / 100.0).toFloat().coerceIn(0f, 1f)
                def.targetValue > 0.0 -> (value / def.targetValue).toFloat().coerceIn(0f, 1f)
                else -> null
            }

            EvaluatedCustomKpi(
                definition = def,
                currentValue = value,
                formattedValue = formatted,
                progressRatio = progress
            )
        }
    }
}
