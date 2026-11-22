package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.cuktech.power.BudgetStatusLevel
import com.example.cuktech.power.PowerBudgetSnapshot
import com.example.ui.theme.ExpressiveMotion
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Material 3 Expressive wavy progress indicator for active charging load & Wh limits.
 * Animates a subtle travelling sine wave while active, settling cleanly when idle/complete.
 */
@Composable
fun WavyLoadProgressBar(
    progress: Float,
    color: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest
) {
    val clampedTarget = progress.coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = clampedTarget,
        animationSpec = ExpressiveMotion.expressiveSpring(),
        label = "wavy_progress"
    )

    val phaseTransition = rememberInfiniteTransition(label = "wavy_phase")
    val phaseRad by phaseTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavy_phase_rad"
    )
    val phase = if (active && animatedProgress > 0.02f && animatedProgress < 0.995f) phaseRad else 0f
    val ampFraction = if (active && animatedProgress > 0.02f && animatedProgress < 0.995f) 0.28f else 0f

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val midY = size.height / 2f
        val strokePx = 3.5.dp.toPx().coerceAtMost(size.height * 0.45f)
        val activeX = size.width * animatedProgress

        // Inactive remainder track
        val trackStartX = (activeX + strokePx).coerceAtMost(size.width)
        if (trackStartX < size.width) {
            drawLine(
                color = trackColor,
                start = Offset(trackStartX, midY),
                end = Offset(size.width, midY),
                strokeWidth = strokePx * 0.8f,
                cap = StrokeCap.Round
            )
        }

        if (activeX > 2f) {
            val amplitude = (size.height * ampFraction).coerceAtLeast(0f)
            val wavelength = 20.dp.toPx().coerceAtLeast(10f)
            val stepPx = 3f
            val path = Path()
            var x = 0f
            var first = true
            while (x <= activeX) {
                val theta = (x / wavelength) * (2.0 * PI).toFloat() - phase
                val y = midY + sin(theta.toDouble()).toFloat() * amplitude
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
                x += stepPx
            }
            val endTheta = (activeX / wavelength) * (2.0 * PI).toFloat() - phase
            path.lineTo(activeX, midY + sin(endTheta.toDouble()).toFloat() * amplitude)

            drawPath(
                path = path,
                color = color,
                style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

/**
 * Live 120W GaN Power Balancer & Per-Port Budget card for OverviewScreen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PowerBudgetCard(
    budget: PowerBudgetSnapshot,
    isConnected: Boolean,
    onPortClick: (Int) -> Unit,
    onScenarioClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val unitW = stringResource(R.string.unit_w)
    val utilizationPct = (budget.utilizationFraction * 100f).toInt().coerceIn(0, 100)

    val statusText = when (budget.statusLevel) {
        BudgetStatusLevel.IDLE -> stringResource(R.string.budget_status_idle)
        BudgetStatusLevel.BALANCED -> stringResource(R.string.budget_status_balanced)
        BudgetStatusLevel.HIGH_LOAD -> stringResource(R.string.budget_status_high)
        BudgetStatusLevel.NEAR_CEILING -> stringResource(R.string.budget_status_ceiling)
    }
    val statusBg by animateColorAsState(
        targetValue = when (budget.statusLevel) {
            BudgetStatusLevel.IDLE -> MaterialTheme.colorScheme.surfaceContainerHighest
            BudgetStatusLevel.BALANCED -> semantic.successContainer
            BudgetStatusLevel.HIGH_LOAD -> MaterialTheme.colorScheme.primaryContainer
            BudgetStatusLevel.NEAR_CEILING -> MaterialTheme.colorScheme.errorContainer
        },
        animationSpec = ExpressiveMotion.snappySpring(),
        label = "budget_badge_bg"
    )
    val statusFg = when (budget.statusLevel) {
        BudgetStatusLevel.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        BudgetStatusLevel.BALANCED -> semantic.success
        BudgetStatusLevel.HIGH_LOAD -> MaterialTheme.colorScheme.onPrimaryContainer
        BudgetStatusLevel.NEAR_CEILING -> MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("power_budget_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.budget_card_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    color = statusBg,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusFg,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.budget_utilization_fmt,
                        budget.totalWatts,
                        budget.ratedMaxWatts,
                        utilizationPct
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = TelemetryMonoFontFamily,
                        fontWeight = FontWeight.SemiBold
                    ).tabularNums()
                )
                Text(
                    text = stringResource(R.string.budget_headroom_fmt, budget.headroomWatts),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = TelemetryMonoFontFamily
                    ).tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Segmented 120W utilization bar
            val activeAllocs = budget.allocations.filter { it.totalFraction > 0.01f }
            val usedFractionSum = activeAllocs.sumOf { it.totalFraction.toDouble() }.toFloat().coerceIn(0f, 1f)
            val freeFraction = (1f - usedFractionSum).coerceAtLeast(0f)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                activeAllocs.forEach { alloc ->
                    val segWeight = alloc.totalFraction.coerceAtLeast(0.02f)
                    Box(
                        modifier = Modifier
                            .weight(segWeight)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(semantic.colorForPort(alloc.portId))
                            .clickable { onPortClick(alloc.portId) }
                    )
                }
                if (freeFraction > 0.01f) {
                    Spacer(modifier = Modifier.weight(freeFraction))
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                budget.allocations.forEach { alloc ->
                    val color = semantic.colorForPort(alloc.portId)
                    Surface(
                        onClick = { onPortClick(alloc.portId) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (alloc.isActive) {
                            color.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("budget_port_segment_${alloc.portId}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (alloc.isActive) color else MaterialTheme.colorScheme.outlineVariant)
                            )
                            val labelText = if (alloc.isSharedC3ABus) {
                                stringResource(R.string.port_c3a_shared_title)
                            } else {
                                explicitPortName(alloc.portId)
                            }
                            Text(
                                text = "$labelText · ${String.format(Locale.US, "%.0f", alloc.liveWatts)}/${alloc.hardwareMaxWatts}$unitW",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = TelemetryMonoFontFamily
                                ).tabularNums(),
                                color = if (alloc.isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            AssistChip(
                onClick = { if (isConnected) onScenarioClick() },
                enabled = isConnected,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.ElectricBolt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = {
                    Text(
                        text = stringResource(R.string.budget_switch_scenario, localizedScenarioName(budget.sceneMode)),
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("budget_scenario_chip")
            )
        }
    }
}
