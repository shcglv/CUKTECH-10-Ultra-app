package com.example.ui.cuktech

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.cuktech.protocol.PortTelemetry
import com.example.cuktech.protocol.PortTelemetryDecoder
import com.example.ui.components.explicitPortName
import com.example.ui.theme.ExpressiveMotion
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Interactive front-panel illustration of the physical CUKTECH 10 Ultra GaN Charger (AD1204).
 * Uses constant-period infinite transitions + smoothly interpolated intensity so animations
 * never stutter or reset when wattage telemetry updates.
 */
@Composable
fun CuktechChargerVisual(
    portsMap: Map<Int, PortTelemetry>,
    @Suppress("UNUSED_PARAMETER") modeLabel: String = stringResource(R.string.scenario_ai),
    @Suppress("UNUSED_PARAMETER") onModeClick: (() -> Unit)? = null,
    onPortClick: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val unitW = stringResource(R.string.unit_w)
    val unitV = stringResource(R.string.unit_v)
    val semantic = LocalSemanticColors.current
    val accent = semantic.chargerAccent

    val c3aBus = PortTelemetryDecoder.resolveC3ASharedBus(portsMap)
    val totalEffectiveW = PortTelemetryDecoder.computeEffectiveTotalWatts(portsMap)
    val hasActiveCharging = totalEffectiveW > 0.2 || portsMap.values.any { it.powerW > 0.2 }

    val loadRatio by animateFloatAsState(
        targetValue = (totalEffectiveW / 120.0).toFloat().coerceIn(0f, 1f),
        animationSpec = tween(320, easing = ExpressiveMotion.StandardEasing),
        label = "charger_load_ratio"
    )
    val activeBlend by animateFloatAsState(
        targetValue = if (hasActiveCharging) 1f else 0f,
        animationSpec = tween(320, easing = ExpressiveMotion.StandardEasing),
        label = "charger_active_blend"
    )

    // Constant-period transitions avoid recreating animations when wattage fluctuates
    val infiniteTransition = rememberInfiniteTransition(label = "charger_energy_flow")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.62f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val flowPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1550, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flowPhase"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val rowHeight = 36.dp
        val rowSpacing = 11.dp
        val topPadding = 16.dp

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Top
        ) {
            // Left: Material 3 Expressive AC Squircle Bolt & Flowing S-Curve Wave Conduit
            Box(
                modifier = Modifier
                    .width(84.dp)
                    .height(238.dp)
            ) {
                val trackIdleColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                val badgeCenterX = 30.dp
                val badgeCenterY = 148.dp

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val startX = badgeCenterX.toPx()
                    val startY = (badgeCenterY - 22.dp).toPx()
                    val endX = size.width
                    val endY = (topPadding + rowHeight / 2).toPx()

                    // Smooth S-curve cubic Bezier feeding into upper-left side of CUKTECH chassis
                    val c1x = startX
                    val c1y = startY - (startY - endY) * 0.58f
                    val c2x = startX + (endX - startX) * 0.34f
                    val c2y = endY

                    fun bezierAt(t: Float): Offset {
                        val u = 1f - t
                        val tt = t * t
                        val uu = u * u
                        val uuu = uu * u
                        val ttt = tt * t
                        val x = uuu * startX + 3f * uu * t * c1x + 3f * u * tt * c2x + ttt * endX
                        val y = uuu * startY + 3f * uu * t * c1y + 3f * u * tt * c2y + ttt * endY
                        return Offset(x, y)
                    }

                    val conduitPath = Path().apply {
                        moveTo(startX, startY)
                        cubicTo(c1x, c1y, c2x, c2y, endX, endY)
                    }

                    drawPath(
                        path = conduitPath,
                        color = if (hasActiveCharging) accent.copy(alpha = 0.22f) else trackIdleColor,
                        style = Stroke(
                            width = 2.6.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )

                    if (activeBlend > 0.02f) {
                        // Subtle M3E sinusoidal ripple overlay traveling along the S-curve
                        val steps = 32
                        val waveAmp = (1.6.dp.toPx() + 1.4.dp.toPx() * loadRatio) * activeBlend
                        val wavePath = Path()
                        for (i in 0..steps) {
                            val t = i.toFloat() / steps
                            val pt = bezierAt(t)
                            val taper = sin(t * PI).toFloat()
                            val ripple = sin((t * 3.5f - flowPhase * 2f) * 2f * PI).toFloat() * waveAmp * taper
                            val wx = pt.x + ripple * 0.6f
                            val wy = pt.y + ripple * 0.8f
                            if (i == 0) wavePath.moveTo(wx, wy) else wavePath.lineTo(wx, wy)
                        }
                        drawPath(
                            path = wavePath,
                            color = accent.copy(alpha = (0.36f + 0.24f * loadRatio) * activeBlend),
                            style = Stroke(
                                width = 2.2.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )

                        // Dual traveling M3E energy beads for continuous fluid motion
                        for (beadIdx in 0..1) {
                            val phase = (flowPhase + beadIdx * 0.5f) % 1f
                            val head = bezierAt(phase)
                            val tailT = (phase - 0.16f).coerceAtLeast(0f)
                            if (phase > 0.04f) {
                                val mid = bezierAt((phase + tailT) * 0.5f)
                                val tail = bezierAt(tailT)
                                val trail = Path().apply {
                                    moveTo(tail.x, tail.y)
                                    quadraticTo(mid.x, mid.y, head.x, head.y)
                                }
                                drawPath(
                                    path = trail,
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            accent.copy(alpha = 0.04f * activeBlend),
                                            accent.copy(alpha = (0.75f + 0.25f * loadRatio) * activeBlend)
                                        ),
                                        start = tail,
                                        end = head
                                    ),
                                    style = Stroke(width = 3.4.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                            val edgeFade = sin(phase * PI).toFloat().coerceIn(0.15f, 1f) * activeBlend
                            drawCircle(
                                color = accent.copy(alpha = 0.34f * pulseAlpha * edgeFade),
                                radius = (5.5.dp.toPx() + 1.5.dp.toPx() * loadRatio),
                                center = head
                            )
                            drawCircle(
                                color = accent.copy(alpha = edgeFade),
                                radius = 2.8.dp.toPx(),
                                center = head
                            )
                        }
                    }
                }

                // Tonal MD3 Expressive Squircle AC Bolt Container
                val squircleSize = 40.dp
                val badgeScale = if (hasActiveCharging) 0.98f + 0.04f * pulseAlpha else 1f
                Box(
                    modifier = Modifier
                        .offset(
                            x = badgeCenterX - squircleSize / 2,
                            y = badgeCenterY - squircleSize / 2
                        )
                        .size(squircleSize)
                        .scale(badgeScale),
                    contentAlignment = Alignment.Center
                ) {
                    if (hasActiveCharging) {
                        Box(
                            modifier = Modifier
                                .size(squircleSize)
                                .clip(RoundedCornerShape(14.dp))
                                .background(accent.copy(alpha = 0.16f * pulseAlpha))
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = if (hasActiveCharging) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        tonalElevation = if (hasActiveCharging) 2.dp else 0.dp,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = stringResource(R.string.overview_ac_connected),
                                tint = if (hasActiveCharging) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                },
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }

            // Center: Physical CUKTECH 10 Ultra Chassis
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(238.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF14171E))
                    .border(
                        width = 1.8.dp,
                        brush = Brush.verticalGradient(listOf(Color(0xFF525A6B), Color(0xFF282D38))),
                        shape = RoundedCornerShape(20.dp)
                    )
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val grooveColor = Color(0xFF1B1F28)
                    val grooveStep = 7.dp.toPx()
                    var y = 16.dp.toPx()
                    while (y < size.height - 36.dp.toPx()) {
                        drawLine(
                            color = grooveColor,
                            start = Offset(10.dp.toPx(), y),
                            end = Offset(size.width - 10.dp.toPx(), y),
                            strokeWidth = 2.dp.toPx()
                        )
                        y += grooveStep
                    }
                }

                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF71717A), Color(0xFFD4D4D8), Color(0xFF3F3F46))
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .fillMaxHeight()
                        .align(Alignment.CenterEnd)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF3F3F46), Color(0xFFD4D4D8), Color(0xFF71717A))
                            )
                        )
                )

                // Shared 5V C3+A bridge indicator inside chassis when both C3 & A share the 5V bus
                if (c3aBus.isSharedActive) {
                    val c3Top = topPadding + (rowHeight + rowSpacing) * 2
                    Box(
                        modifier = Modifier
                            .padding(top = c3Top + 6.dp, end = 6.dp)
                            .width(4.dp)
                            .height(rowHeight * 2 + rowSpacing - 12.dp)
                            .align(Alignment.TopEnd)
                            .clip(RoundedCornerShape(2.dp))
                            .background(semantic.portC3.copy(alpha = 0.85f * pulseAlpha))
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = topPadding),
                    verticalArrangement = Arrangement.spacedBy(rowSpacing)
                ) {
                    for (portId in 1..4) {
                        val port = portsMap[portId] ?: PortTelemetry(portId, explicitPortName(portId))
                        val isTypeC = portId in 1..3
                        val isCharging = port.powerW > 0.2
                        val portColor = if (c3aBus.isSharedActive && portId in 3..4) {
                            semantic.portC3
                        } else {
                            semantic.colorForPort(portId)
                        }
                        val physicalSilkscreen = if (isTypeC) "C$portId" else "A"
                        val fullPortName = explicitPortName(portId)
                        val rowCd = "$fullPortName: ${String.format(Locale.US, "%.1f", port.powerW)} $unitW"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeight)
                                .testTag("faceplate_port_$portId")
                                .semantics { contentDescription = rowCd }
                                .clickable(role = Role.Button) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onPortClick?.invoke(portId)
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Text(
                                    text = physicalSilkscreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = TelemetryMonoFontFamily,
                                    color = if (isCharging) portColor else Color(0xFF8E99A8)
                                )
                            }

                            if (isTypeC) {
                                Box(
                                    modifier = Modifier
                                        .width(44.dp)
                                        .height(12.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            when {
                                                !port.enabled -> Color(0xFF281214)
                                                isCharging -> portColor.copy(alpha = 0.35f * pulseAlpha)
                                                else -> Color(0xFF0B0D11)
                                            }
                                        )
                                        .border(
                                            width = if (isCharging) 1.5.dp else 1.dp,
                                            color = when {
                                                !port.enabled -> Color(0xFF7F1D1D)
                                                isCharging -> portColor.copy(alpha = pulseAlpha)
                                                else -> Color(0xFF2C323D)
                                            },
                                            shape = RoundedCornerShape(6.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(26.dp)
                                            .height(3.5.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(if (isCharging) portColor else Color(0xFF1E222A))
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .width(48.dp)
                                        .height(16.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            when {
                                                !port.enabled -> Color(0xFF281214)
                                                isCharging -> portColor.copy(alpha = 0.35f * pulseAlpha)
                                                else -> Color(0xFF0B0D11)
                                            }
                                        )
                                        .border(
                                            width = if (isCharging) 1.5.dp else 1.dp,
                                            color = when {
                                                !port.enabled -> Color(0xFF7F1D1D)
                                                isCharging -> portColor.copy(alpha = pulseAlpha)
                                                else -> Color(0xFF2C323D)
                                            },
                                            shape = RoundedCornerShape(3.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(28.dp)
                                            .height(5.5.dp)
                                            .clip(RoundedCornerShape(1.dp))
                                            .background(if (isCharging) portColor else Color(0xFF1E5BB5))
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(if (isTypeC) 12.dp else 16.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (isCharging) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        drawRect(
                                            brush = Brush.horizontalGradient(
                                                listOf(
                                                    portColor.copy(alpha = 0.25f * pulseAlpha),
                                                    portColor.copy(alpha = 0.50f * pulseAlpha)
                                                )
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.5.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        accent.copy(alpha = 0.2f),
                                        accent.copy(alpha = if (hasActiveCharging) 0.95f * pulseAlpha else 0.4f),
                                        accent.copy(alpha = 0.2f)
                                    )
                                )
                            )
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF2E333D), Color(0xFF1E2229))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "CUKTECH",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            color = Color(0xFFA6B0C0)
                        )
                    }
                }
            }

            // Right: Animated Flowing Port Beams & Live Wattage Readouts (with unified C3+A bus)
            Column(
                modifier = Modifier
                    .width(104.dp)
                    .height(238.dp)
                    .padding(top = topPadding),
                verticalArrangement = Arrangement.spacedBy(rowSpacing)
            ) {
                for (portId in 1..2) {
                    PortRightBeamRow(
                        portId = portId,
                        powerW = portsMap[portId]?.powerW ?: 0.0,
                        portColor = semantic.colorForPort(portId),
                        flowPhase = flowPhase,
                        pulseAlpha = pulseAlpha,
                        unitW = unitW,
                        rowHeight = rowHeight,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPortClick?.invoke(portId)
                        }
                    )
                }

                if (c3aBus.isSharedActive) {
                    val combinedHeight = rowHeight * 2 + rowSpacing
                    val sharedColor = semantic.portC3
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(combinedHeight)
                            .clickable(role = Role.Button) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPortClick?.invoke(3)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Canvas(
                            modifier = Modifier
                                .width(28.dp)
                                .height(combinedHeight * 0.72f)
                        ) {
                            val topY = size.height * 0.22f
                            val botY = size.height * 0.78f
                            val midY = size.height * 0.5f
                            val mergeX = size.width * 0.45f
                            val strokeW = 2.dp.toPx()

                            // Y-junction uniting C3 and A lines into a single bus beam
                            drawLine(sharedColor.copy(alpha = 0.45f), Offset(0f, topY), Offset(mergeX, midY), strokeW, StrokeCap.Round)
                            drawLine(sharedColor.copy(alpha = 0.45f), Offset(0f, botY), Offset(mergeX, midY), strokeW, StrokeCap.Round)
                            drawLine(sharedColor.copy(alpha = 0.45f), Offset(mergeX, midY), Offset(size.width, midY), strokeW, StrokeCap.Round)

                            val pulseX = mergeX + (size.width - mergeX) * flowPhase
                            drawCircle(
                                color = sharedColor.copy(alpha = 0.85f * pulseAlpha),
                                radius = 3.dp.toPx(),
                                center = Offset(pulseX, midY)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "${stringResource(R.string.port_c3a_short)} · 5$unitV",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                            Row {
                                Text(
                                    text = String.format(Locale.US, "%.1f", c3aBus.sharedPowerW),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = TelemetryMonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ).tabularNums(),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    modifier = Modifier.alignByBaseline()
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = unitW,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.alignByBaseline()
                                )
                            }
                        }
                    }
                } else {
                    for (portId in 3..4) {
                        PortRightBeamRow(
                            portId = portId,
                            powerW = portsMap[portId]?.powerW ?: 0.0,
                            portColor = semantic.colorForPort(portId),
                            flowPhase = flowPhase,
                            pulseAlpha = pulseAlpha,
                            unitW = unitW,
                            rowHeight = rowHeight,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPortClick?.invoke(portId)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PortRightBeamRow(
    portId: Int,
    powerW: Double,
    portColor: Color,
    flowPhase: Float,
    pulseAlpha: Float,
    unitW: String,
    rowHeight: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val isCharging = powerW > 0.2
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isCharging) {
            Canvas(
                modifier = Modifier
                    .width(28.dp)
                    .height(14.dp)
            ) {
                val midY = size.height / 2f
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(
                            portColor.copy(alpha = 0.26f * pulseAlpha),
                            portColor.copy(alpha = 0.06f),
                            Color.Transparent
                        )
                    ),
                    topLeft = Offset(0f, size.height * 0.2f),
                    size = Size(size.width, size.height * 0.6f),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
                val particleX = size.width * ((flowPhase + (portId - 1) * 0.22f) % 1f)
                drawCircle(
                    color = portColor.copy(alpha = 0.45f * pulseAlpha),
                    radius = 4.dp.toPx(),
                    center = Offset(particleX, midY)
                )
                drawCircle(
                    color = portColor,
                    radius = 2.dp.toPx(),
                    center = Offset(particleX, midY)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Row {
                Text(
                    text = String.format(Locale.US, "%.1f", powerW),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = TelemetryMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ).tabularNums(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline()
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = unitW,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline()
                )
            }
        }
    }
}
