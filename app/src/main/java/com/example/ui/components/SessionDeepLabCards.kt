package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.cuktech.db.room.IncidentBlackBoxEntity
import com.example.cuktech.energy.ChargeSessionRecord
import com.example.cuktech.power.ComparisonAlignmentMode
import com.example.cuktech.power.SessionClassification
import com.example.cuktech.power.SessionComparisonResult
import com.example.cuktech.power.SessionPersonalRecords
import com.example.ui.theme.TelemetryMonoFontFamily
import com.example.ui.theme.tabularNums
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun localizedClassificationLabel(c: SessionClassification): String = when (c) {
    SessionClassification.FAST_BOOST -> stringResource(R.string.session_class_fast_boost)
    SessionClassification.LONG_STEADY -> stringResource(R.string.session_class_long_steady)
    SessionClassification.SHORT_BURST -> stringResource(R.string.session_class_short_burst)
    SessionClassification.INTERRUPTED -> stringResource(R.string.session_class_interrupted)
    SessionClassification.LOW_POWER_TRICKLE -> stringResource(R.string.session_class_trickle)
    SessionClassification.MULTIPORT -> stringResource(R.string.session_class_multiport)
    SessionClassification.INCOMPLETE_DROP -> stringResource(R.string.session_class_drop)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionRecordsAndNormsCard(
    records: SessionPersonalRecords,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("session_records_card")
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
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.session_records_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (records.totalCompletedCount == 0) {
                Text(
                    text = stringResource(R.string.session_records_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val unitW = stringResource(R.string.unit_w)
                val unitWh = stringResource(R.string.unit_wh)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RecordTile(
                        label = stringResource(R.string.session_rec_peak),
                        value = "${String.format(Locale.US, "%.1f", records.maxPeakWatts)} $unitW",
                        modifier = Modifier.weight(1f)
                    )
                    RecordTile(
                        label = stringResource(R.string.session_rec_wh),
                        value = "${String.format(Locale.US, "%.2f", records.maxWh)} $unitWh",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RecordTile(
                        label = stringResource(R.string.session_rec_dur),
                        value = formatDurationHms(records.maxDurationSec),
                        modifier = Modifier.weight(1f)
                    )
                    RecordTile(
                        label = stringResource(R.string.session_rec_rate),
                        value = stringResource(R.string.session_rec_rate_fmt, records.maxWhPerHour),
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = stringResource(
                        R.string.session_rec_failure_fmt,
                        records.totalCompletedCount,
                        records.failureRatePct
                    ),
                    style = MaterialTheme.typography.labelMedium.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (records.classificationCounts.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        records.classificationCounts.entries.sortedByDescending { it.value }.forEach { (cls, cnt) ->
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "${localizedClassificationLabel(cls)}: $cnt",
                                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = TelemetryMonoFontFamily,
                    fontWeight = FontWeight.Bold
                ).tabularNums(),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun TwoSessionComparisonCard(
    sessions: List<ChargeSessionRecord>,
    selectedAId: Long?,
    selectedBId: Long?,
    alignment: ComparisonAlignmentMode,
    comparison: SessionComparisonResult,
    onSelectSessions: (Long?, Long?) -> Unit,
    onSelectAlignment: (ComparisonAlignmentMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("session_compare_card")
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
                    imageVector = Icons.Default.CompareArrows,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.session_compare_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (sessions.size < 2) {
                Text(
                    text = stringResource(R.string.session_compare_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val recent = sessions.take(8)
                Text(
                    text = stringResource(R.string.session_compare_pick_a),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    recent.forEach { s ->
                        FilterChip(
                            selected = selectedAId == s.id,
                            onClick = { onSelectSessions(s.id, selectedBId) },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.session_compare_item_fmt,
                                        s.id.toInt(),
                                        explicitPortName(s.portId),
                                        s.whAccumulated,
                                        s.peakWatts
                                    )
                                )
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("compare_pick_a_${s.id}")
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.session_compare_pick_b),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    recent.forEach { s ->
                        FilterChip(
                            selected = selectedBId == s.id,
                            onClick = { onSelectSessions(selectedAId, s.id) },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.session_compare_item_fmt,
                                        s.id.toInt(),
                                        explicitPortName(s.portId),
                                        s.whAccumulated,
                                        s.peakWatts
                                    )
                                )
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("compare_pick_b_${s.id}")
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = alignment == ComparisonAlignmentMode.BY_TIME,
                        onClick = { onSelectAlignment(ComparisonAlignmentMode.BY_TIME) },
                        label = { Text(stringResource(R.string.session_compare_align_time)) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("compare_align_time")
                    )
                    FilterChip(
                        selected = alignment == ComparisonAlignmentMode.BY_WH,
                        onClick = { onSelectAlignment(ComparisonAlignmentMode.BY_WH) },
                        label = { Text(stringResource(R.string.session_compare_align_wh)) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("compare_align_wh")
                    )
                }

                if (comparison.sessionA != null && comparison.sessionB != null && comparison.points.size >= 2) {
                    val colorA = MaterialTheme.colorScheme.primary
                    val colorB = MaterialTheme.colorScheme.tertiary
                    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    val maxP = comparison.points.flatMap { listOfNotNull(it.powerA, it.powerB) }
                        .maxOrNull()?.coerceAtLeast(10.0) ?: 10.0

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                            ) {
                                val padY = 8.dp.toPx()
                                val usableH = (size.height - padY * 2).coerceAtLeast(4f)
                                drawLine(
                                    gridColor,
                                    Offset(0f, size.height - padY),
                                    Offset(size.width, size.height - padY),
                                    strokeWidth = 1.dp.toPx()
                                )
                                drawLine(
                                    gridColor,
                                    Offset(0f, size.height * 0.5f),
                                    Offset(size.width, size.height * 0.5f),
                                    strokeWidth = 1.dp.toPx()
                                )

                                val ptsA = comparison.points.mapIndexedNotNull { idx, pt ->
                                    pt.powerA?.let { w ->
                                        val x = (idx.toFloat() / (comparison.points.size - 1)) * size.width
                                        val y = size.height - padY - ((w / maxP).toFloat().coerceIn(0f, 1f) * usableH)
                                        Offset(x, y)
                                    }
                                }
                                val ptsB = comparison.points.mapIndexedNotNull { idx, pt ->
                                    pt.powerB?.let { w ->
                                        val x = (idx.toFloat() / (comparison.points.size - 1)) * size.width
                                        val y = size.height - padY - ((w / maxP).toFloat().coerceIn(0f, 1f) * usableH)
                                        Offset(x, y)
                                    }
                                }

                                if (ptsA.size >= 2) {
                                    val pathA = buildMonotoneCubicPath(ptsA, padY, size.height - padY)
                                    drawPath(
                                        path = pathA,
                                        color = colorA,
                                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                }
                                if (ptsB.size >= 2) {
                                    val pathB = buildMonotoneCubicPath(ptsB, padY, size.height - padY)
                                    drawPath(
                                        path = pathB,
                                        color = colorB,
                                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                }
                            }

                            val durSign = if (comparison.deltaDurationSec >= 0L) "+" else "-"
                            val durFormatted = "$durSign${formatDurationHms(abs(comparison.deltaDurationSec))}"
                            Text(
                                text = stringResource(
                                    R.string.session_compare_delta_fmt,
                                    comparison.deltaWh,
                                    comparison.deltaPeakW,
                                    comparison.deltaAvgW,
                                    durFormatted
                                ),
                                style = MaterialTheme.typography.labelMedium.copy(
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

@Composable
fun IncidentBlackBoxCard(
    blackBoxes: List<IncidentBlackBoxEntity>,
    onCaptureManual: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tsFmt = remember { SimpleDateFormat("dd MMM · HH:mm:ss", Locale.US) }
    var expandedId by remember { mutableStateOf<Long?>(null) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("incident_blackbox_card")
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = stringResource(R.string.blackbox_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Text(
                text = stringResource(R.string.blackbox_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FilledTonalButton(
                onClick = {
                    onCaptureManual()
                    Toast.makeText(context, context.getString(R.string.toast_blackbox_captured), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("blackbox_capture_btn")
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.blackbox_capture_btn))
            }

            if (blackBoxes.isEmpty()) {
                Text(
                    text = stringResource(R.string.blackbox_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                blackBoxes.take(8).forEach { box ->
                    val isExpanded = expandedId == box.id
                    Surface(
                        onClick = { expandedId = if (isExpanded) null else box.id },
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("blackbox_item_${box.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val typeLabel = when (box.incidentType) {
                                    "UNEXPECTED_DROP" -> stringResource(R.string.blackbox_type_drop)
                                    "PROTOCOL_COLLAPSE" -> stringResource(R.string.blackbox_type_proto)
                                    else -> stringResource(R.string.blackbox_type_manual)
                                }
                                Surface(
                                    color = if (box.incidentType == "MANUAL") {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.errorContainer
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = typeLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (box.incidentType == "MANUAL") {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        },
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = tsFmt.format(Date(box.timestampMs)),
                                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = box.summary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = if (isExpanded) 10 else 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (isExpanded) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                Text(
                                    text = box.windowJson.take(600),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = TelemetryMonoFontFamily
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("BlackBox", box.windowJson))
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.toast_json_copied),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("blackbox_copy_${box.id}")
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.blackbox_copy_json))
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
