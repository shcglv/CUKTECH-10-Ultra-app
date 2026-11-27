package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.cuktech.power.LoadSimulationResult
import com.example.cuktech.power.ScenarioAndPdoSnapshot
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PdoExplorerCard(
    snapshot: ScenarioAndPdoSnapshot,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("pdo_explorer_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.pdo_explorer_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.pdo_explorer_c3a_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            snapshot.pdoPorts.forEach { portInfo ->
                val accent = semantic.colorForPort(portInfo.portId)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pdo_port_row_${portInfo.portId}")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = explicitPortName(portInfo.portId),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = accent
                                )
                                Surface(
                                    color = if (portInfo.hasHardwarePdo) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (portInfo.hasHardwarePdo) {
                                                R.string.pdo_explorer_hw_badge
                                            } else {
                                                R.string.pdo_explorer_est_badge
                                            }
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (portInfo.hasHardwarePdo) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.pdo_explorer_samples_fmt, portInfo.observedSampleCount),
                                style = MaterialTheme.typography.labelSmall.tabularNums(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (portInfo.hasHardwarePdo && portInfo.declaredFixedVoltagesV.isNotEmpty()) {
                            val rails = buildString {
                                append(portInfo.declaredFixedVoltagesV.joinToString("/") { "${it.toInt()}V" })
                                portInfo.ppsMaxVoltageV?.let { pps ->
                                    append(" · ")
                                    append(stringResource(R.string.pdo_explorer_pps_fmt, pps))
                                }
                            }
                            Text(
                                text = stringResource(R.string.pdo_explorer_declared_fmt, rails),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = TelemetryMonoFontFamily
                                ).tabularNums(),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (portInfo.protocolPermissions.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                portInfo.protocolPermissions.forEach { (proto, enabled) ->
                                    Surface(
                                        color = if (enabled) {
                                            accent.copy(alpha = 0.16f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        },
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = stringResource(
                                                if (enabled) R.string.pdo_explorer_perm_enabled else R.string.pdo_explorer_perm_disabled,
                                                proto
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        val activeBands = portInfo.observedBands.filter { it.observedMinutes > 0.0 || it.matchesDeclaredPdo }
                        if (activeBands.isEmpty()) {
                            Text(
                                text = stringResource(R.string.pdo_explorer_no_bands),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                activeBands.forEach { band ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = band.label,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = TelemetryMonoFontFamily,
                                                fontWeight = FontWeight.SemiBold
                                            ).tabularNums(),
                                            modifier = Modifier.width(68.dp)
                                        )
                                        LinearProgressIndicator(
                                            progress = { (band.sharePct / 100.0).toFloat().coerceIn(0f, 1f) },
                                            color = if (band.matchesDeclaredPdo) accent else MaterialTheme.colorScheme.secondary,
                                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        )
                                        Text(
                                            text = String.format(Locale.US, "%.0f%% · %.1fm", band.sharePct, band.observedMinutes),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = TelemetryMonoFontFamily
                                            ).tabularNums(),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SceneAndCombinationMatrixCard(
    snapshot: ScenarioAndPdoSnapshot,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("scene_matrix_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.scene_matrix_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.scene_matrix_obs_fmt, snapshot.totalEmpiricalObservations),
                        style = MaterialTheme.typography.labelSmall.tabularNums(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            if (snapshot.totalEmpiricalObservations == 0) {
                Text(
                    text = stringResource(R.string.scene_matrix_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                snapshot.sceneStats.forEach { st ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = localizedScenarioName(st.sceneMode),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                if (st.c3aSharedCount > 0) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.scene_matrix_c3a_shared_badge, st.c3aSharedCount),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = stringResource(
                                    R.string.scene_stat_row_fmt,
                                    st.sampleCount,
                                    st.avgTotalW,
                                    st.peakTotalW,
                                    st.typicalCombinationLabel
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = TelemetryMonoFontFamily
                                ).tabularNums(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (snapshot.combinationMatrix.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.scene_matrix_combos_header),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    snapshot.combinationMatrix.take(8).forEach { combo ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = combo.label,
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontFamily = TelemetryMonoFontFamily
                                        ),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.scene_matrix_combo_row_fmt,
                                            combo.sampleCount,
                                            combo.avgTotalW,
                                            combo.peakTotalW
                                        ),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = TelemetryMonoFontFamily
                                        ).tabularNums(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                val portBreakdown = (1..4).mapNotNull { pid ->
                                    val avg = combo.avgByPortW[pid] ?: 0.0
                                    if (avg >= 0.3) {
                                        val tag = when (pid) {
                                            1 -> "C1"
                                            2 -> "C2"
                                            3 -> "C3"
                                            else -> "A"
                                        }
                                        "$tag ${String.format(Locale.US, "%.1f", avg)}W"
                                    } else null
                                }.joinToString(" · ")
                                if (portBreakdown.isNotEmpty()) {
                                    Text(
                                        text = portBreakdown,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = TelemetryMonoFontFamily
                                        ).tabularNums(),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoadDistributionSimulatorCard(
    simSceneMode: Int,
    simPortsMask: Int,
    simulation: LoadSimulationResult,
    onSelectScene: (Int) -> Unit,
    onTogglePort: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("load_simulator_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.load_sim_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = stringResource(R.string.load_sim_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Port toggles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    1 to R.string.port_short_c1,
                    2 to R.string.port_short_c2,
                    3 to R.string.port_short_c3,
                    4 to R.string.port_short_a
                ).forEach { (pid, labelRes) ->
                    val selected = (simPortsMask and (1 shl (pid - 1))) != 0
                    FilterChip(
                        selected = selected,
                        onClick = { onTogglePort(pid) },
                        label = { Text(stringResource(labelRes)) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("sim_port_$pid")
                    )
                }
            }

            // Scene toggles
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                (1..4).forEach { mode ->
                    FilterChip(
                        selected = simSceneMode == mode,
                        onClick = { onSelectScene(mode) },
                        label = { Text(localizedScenarioName(mode)) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("sim_scene_$mode")
                    )
                }
            }

            if (simPortsMask == 0) {
                Text(
                    text = stringResource(R.string.load_sim_idle_prompt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${simulation.combinationLabel} · ${localizedScenarioName(simulation.sceneMode)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            val confRes = when (simulation.confidence) {
                                "HIGH" -> R.string.load_sim_conf_high
                                "MEDIUM" -> R.string.load_sim_conf_medium
                                else -> R.string.load_sim_conf_estimate
                            }
                            Surface(
                                color = if (simulation.basedOnHistory) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                },
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = stringResource(confRes, simulation.empiricalSampleCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (simulation.basedOnHistory) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    },
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.load_sim_ceiling_fmt,
                                    simulation.estimatedTotalCeilingW,
                                    simulation.ratedMainsMaxW
                                ),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = TelemetryMonoFontFamily
                                ).tabularNums(),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.load_sim_headroom_fmt, simulation.estimatedHeadroomW),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = TelemetryMonoFontFamily
                                ).tabularNums(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        (1..4).forEach { pid ->
                            val w = simulation.perPortCeilingW[pid] ?: 0.0
                            if ((simPortsMask and (1 shl (pid - 1))) != 0) {
                                val accent = semantic.colorForPort(pid)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = explicitPortName(pid),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accent,
                                        modifier = Modifier.width(64.dp)
                                    )
                                    LinearProgressIndicator(
                                        progress = { (w / simulation.ratedMainsMaxW.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) },
                                        color = accent,
                                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(7.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                    )
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", w)} ${stringResource(R.string.unit_w)}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = TelemetryMonoFontFamily,
                                            fontWeight = FontWeight.SemiBold
                                        ).tabularNums()
                                    )
                                }
                            }
                        }

                        if (simulation.c3aSharedActive) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.load_sim_c3a_alert),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }

                        Text(
                            text = stringResource(
                                if (simulation.basedOnHistory) {
                                    R.string.load_sim_disclaimer_history
                                } else {
                                    R.string.load_sim_disclaimer_estimate
                                }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProtocolTransitionsCard(
    snapshot: ScenarioAndPdoSnapshot,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("protocol_transitions_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.proto_transitions_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (snapshot.protocolTransitions.isEmpty()) {
                Text(
                    text = stringResource(R.string.proto_transitions_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                snapshot.protocolTransitions.forEach { edge ->
                    val accent = semantic.colorForPort(edge.portId)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.proto_transition_row_fmt,
                                        explicitPortName(edge.portId),
                                        edge.fromProtocol,
                                        edge.toProtocol,
                                        edge.count
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = accent,
                                    modifier = Modifier.weight(1f)
                                )
                                if (edge.isOscillationAlert) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.proto_transition_osc_badge),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = stringResource(
                                    R.string.proto_transition_delta_fmt,
                                    edge.avgBeforeW,
                                    edge.avgAfterW,
                                    edge.avgDeltaW
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = TelemetryMonoFontFamily
                                ).tabularNums(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
