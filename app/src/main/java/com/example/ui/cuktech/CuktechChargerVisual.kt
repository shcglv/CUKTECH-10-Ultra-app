package com.example.ui.cuktech

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.ui.components.explicitPortName
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import java.util.Locale

/**
 * Interactive front-panel illustration of the physical CUKTECH 10 Ultra GaN Charger (AD1204).
 * Tap any port slot or beam readout to jump directly to detailed control for that physical port.
 */
@Composable
fun CuktechChargerVisual(
    portsMap: Map<Int, PortTelemetry>,
    modeLabel: String = "AI Mode",
    onModeClick: (() -> Unit)? = null,
    onPortClick: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val accent = semantic.chargerAccent
    val infiniteTransition = rememberInfiniteTransition(label = "charger_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val hasActiveCharging = portsMap.values.any { it.powerW > 0.2 }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
            modifier = Modifier
                .padding(bottom = 12.dp)
                .then(
                    if (onModeClick != null) {
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button, onClick = onModeClick)
                    } else Modifier
                )
                .testTag("faceplate_scenario_pill")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = modeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        val rowHeight = 36.dp
        val rowSpacing = 11.dp
        val topPadding = 16.dp

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Top
        ) {
            // Left: AC Drop-line & Lightning Bolt when active
            Box(
                modifier = Modifier
                    .width(92.dp)
                    .height(238.dp)
            ) {
                if (hasActiveCharging) {
                    val wireColor = MaterialTheme.colorScheme.outlineVariant
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 1.4.dp.toPx()
                        val startY = (topPadding + rowHeight / 2).toPx()
                        val cornerX = size.width - 24.dp.toPx()
                        val endY = 138.dp.toPx()
                        drawLine(
                            color = wireColor,
                            start = Offset(size.width, startY),
                            end = Offset(cornerX, startY),
                            strokeWidth = stroke
                        )
                        drawLine(
                            color = wireColor,
                            start = Offset(cornerX, startY),
                            end = Offset(cornerX, endY),
                            strokeWidth = stroke
                        )
                    }

                    Box(
                        modifier = Modifier
                            .offset(x = 66.dp, y = 78.dp)
                            .width(3.dp)
                            .height(24.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        accent.copy(alpha = 0.95f * pulseAlpha),
                                        accent.copy(alpha = 0.25f)
                                    )
                                )
                            )
                    )

                    Box(
                        modifier = Modifier
                            .offset(x = 50.dp, y = 120.dp)
                            .size(36.dp)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        accent.copy(alpha = 0.42f * pulseAlpha),
                                        accent.copy(alpha = 0.12f * pulseAlpha),
                                        Color.Transparent
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = stringResource(R.string.overview_ac_connected),
                            tint = accent,
                            modifier = Modifier.size(20.dp)
                        )
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
                        val portColor = semantic.colorForPort(portId)
                        val physicalSilkscreen = if (isTypeC) "C$portId" else "A"
                        val fullPortName = explicitPortName(portId)
                        val rowCd = "$fullPortName: ${String.format(Locale.US, "%.1f", port.powerW)} W"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeight)
                                .testTag("faceplate_port_$portId")
                                .semantics { contentDescription = rowCd }
                                .clickable(role = Role.Button) { onPortClick?.invoke(portId) },
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
                                    color = if (isCharging) accent else Color(0xFF8E99A8)
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
                                                isCharging -> accent.copy(alpha = 0.35f * pulseAlpha)
                                                else -> Color(0xFF0B0D11)
                                            }
                                        )
                                        .border(
                                            width = if (isCharging) 1.5.dp else 1.dp,
                                            color = when {
                                                !port.enabled -> Color(0xFF7F1D1D)
                                                isCharging -> accent.copy(alpha = pulseAlpha)
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
                                            .background(if (isCharging) accent else Color(0xFF1E222A))
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
                                                isCharging -> accent.copy(alpha = 0.35f * pulseAlpha)
                                                else -> Color(0xFF0B0D11)
                                            }
                                        )
                                        .border(
                                            width = if (isCharging) 1.5.dp else 1.dp,
                                            color = when {
                                                !port.enabled -> Color(0xFF7F1D1D)
                                                isCharging -> accent.copy(alpha = pulseAlpha)
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
                                            .background(if (isCharging) accent else Color(0xFF1E5BB5))
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
                                                    portColor.copy(alpha = 0.22f * pulseAlpha),
                                                    portColor.copy(alpha = 0.42f * pulseAlpha)
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

            // Right: Aligned Wattage Beams
            Column(
                modifier = Modifier
                    .width(96.dp)
                    .height(238.dp)
                    .padding(top = topPadding),
                verticalArrangement = Arrangement.spacedBy(rowSpacing)
            ) {
                for (portId in 1..4) {
                    val port = portsMap[portId] ?: PortTelemetry(portId, explicitPortName(portId))
                    val isCharging = port.powerW > 0.2
                    val portColor = semantic.colorForPort(portId)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .clickable(role = Role.Button) { onPortClick?.invoke(portId) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCharging) {
                            Canvas(
                                modifier = Modifier
                                    .width(26.dp)
                                    .height(14.dp)
                            ) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        listOf(
                                            portColor.copy(alpha = 0.30f * pulseAlpha),
                                            portColor.copy(alpha = 0.08f * pulseAlpha),
                                            Color.Transparent
                                        )
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = String.format(Locale.US, "%.1f", port.powerW),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = TelemetryMonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ).tabularNums(),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "W",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
