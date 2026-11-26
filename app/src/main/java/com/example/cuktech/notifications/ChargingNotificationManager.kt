package com.example.cuktech.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import java.util.Locale

data class LiveNotificationPayload(
    val title: String,
    val content: String,
    val bigText: String,
    val totalWatts: Double,
    val nearestCountdownEndMs: Long?,
    val activeLimitProgressPct: Int?,
    val liveUpdatesEnabled: Boolean,
    val serviceStartTimeMs: Long
)

/**
 * Unified Notification & Android 16 Live Updates / Promoted Ongoing controller.
 * Keeps standard NotificationCompat for API 26–35 and upgrades cleanly to Android 16 (API 36+)
 * Notification.ProgressStyle + shortCriticalText + requestPromotedOngoing when enabled.
 */
object ChargingNotificationManager {

    const val CHANNEL_LIVE_TELEMETRY = "CuktechServiceChannel"
    const val CHANNEL_COMPLETION_ALERT = "CuktechCompletionChannel"
    const val CHANNEL_COMPLETION_SILENT = "CuktechCompletionSilentChannel"
    const val CHANNEL_ANOMALY_ALERT = "CuktechAnomalyChannel"

    const val COMPLETION_NOTIF_BASE_ID = 20200
    const val ANOMALY_NOTIF_BASE_ID = 20300
    const val BLE_PROXIMITY_NOTIF_ID = 20400

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val liveChannel = NotificationChannel(
            CHANNEL_LIVE_TELEMETRY,
            context.getString(R.string.notif_channel_live_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_live_desc)
            setShowBadge(false)
        }

        val completionChannel = NotificationChannel(
            CHANNEL_COMPLETION_ALERT,
            context.getString(R.string.notif_channel_completion_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_completion_desc)
            enableVibration(true)
        }

        val completionSilentChannel = NotificationChannel(
            CHANNEL_COMPLETION_SILENT,
            context.getString(R.string.notif_channel_completion_silent_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_completion_silent_desc)
            setSound(null, null)
            enableVibration(false)
        }

        val anomalyChannel = NotificationChannel(
            CHANNEL_ANOMALY_ALERT,
            context.getString(R.string.notif_channel_anomaly_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_channel_anomaly_desc)
        }

        nm.createNotificationChannels(
            listOf(liveChannel, completionChannel, completionSilentChannel, anomalyChannel)
        )
    }

    fun canPostPromotedNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return runCatching { nm.canPostPromotedNotifications() }.getOrDefault(false)
    }

    fun buildPromotedNotificationsSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= 36) {
            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun formatShortCriticalText(totalWatts: Double, nearestCountdownEndMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
        if (nearestCountdownEndMs != null && nearestCountdownEndMs > nowMs) {
            val remSec = ((nearestCountdownEndMs - nowMs) / 1000L).coerceAtLeast(0L)
            val m = remSec / 60L
            val s = remSec % 60L
            return if (m >= 60L) {
                val h = m / 60L
                val rm = m % 60L
                String.format(Locale.US, "%dh%02dm", h, rm).take(7)
            } else {
                String.format(Locale.US, "%d:%02d", m, s).take(7)
            }
        }
        if (totalWatts < 0.0) return "BLE"
        return if (totalWatts >= 100.0) {
            String.format(Locale.US, "%.0fW", totalWatts).take(7)
        } else {
            String.format(Locale.US, "%.1fW", totalWatts).take(7)
        }
    }

    fun buildOngoingNotification(
        context: Context,
        payload: LiveNotificationPayload,
        openPendingIntent: PendingIntent,
        disconnectPendingIntent: PendingIntent,
        nowMs: Long = System.currentTimeMillis()
    ): Notification {
        ensureChannels(context)

        val hasCountdown = payload.nearestCountdownEndMs != null && payload.nearestCountdownEndMs > nowMs
        val hasActiveLoadOrProgress = payload.totalWatts >= 0.85 || hasCountdown || payload.activeLimitProgressPct != null
        val whenMs = if (hasCountdown) payload.nearestCountdownEndMs!! else payload.serviceStartTimeMs

        val compat = NotificationCompat.Builder(context, CHANNEL_LIVE_TELEMETRY)
            .setSmallIcon(R.drawable.ic_stat_cuktech)
            .setContentTitle(payload.title)
            .setContentText(payload.content)
            .setSubText(context.getString(R.string.app_name))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .setWhen(whenMs)
            .setShowWhen(true)
            .setPriority(
                if (payload.liveUpdatesEnabled && hasActiveLoadOrProgress && Build.VERSION.SDK_INT >= 36) {
                    NotificationCompat.PRIORITY_DEFAULT
                } else {
                    NotificationCompat.PRIORITY_LOW
                }
            )
            .setLocalOnly(true)
            .setSortKey("cuktech_gateway")
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.action_disconnect), disconnectPendingIntent)

        if (payload.liveUpdatesEnabled && hasActiveLoadOrProgress) {
            compat.setCategory(NotificationCompat.CATEGORY_PROGRESS)
            compat.setRequestPromotedOngoing(true)
            compat.setShortCriticalText(formatShortCriticalText(payload.totalWatts, payload.nearestCountdownEndMs, nowMs))
            val pct = payload.activeLimitProgressPct ?: computePowerLoadProgressPct(payload.totalWatts)
            if (Build.VERSION.SDK_INT >= 36 && pct != null) {
                val segmentColor = when {
                    payload.totalWatts >= 95.0 -> Color.parseColor("#EF6C00")
                    payload.totalWatts >= 45.0 -> Color.parseColor("#00ACC1")
                    else -> Color.parseColor("#00B4D8")
                }
                val style = NotificationCompat.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgress(pct.coerceIn(0, 100))
                    .setProgressSegments(
                        listOf(
                            NotificationCompat.ProgressStyle.Segment(100)
                                .setColor(segmentColor)
                        )
                    )
                compat.setStyle(style)
            } else {
                compat.setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle(payload.title)
                        .bigText(payload.bigText)
                )
            }
        } else {
            compat.setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(payload.title)
                    .bigText(payload.bigText)
            )
        }

        if (hasCountdown) {
            compat.setUsesChronometer(true)
            compat.setChronometerCountDown(true)
        } else {
            compat.setUsesChronometer(false)
        }
        return compat.build()
    }

    fun computePowerLoadProgressPct(totalWatts: Double): Int? {
        if (totalWatts < 0.2) return null
        return ((totalWatts / 120.0) * 100.0).toInt().coerceIn(1, 100)
    }

    fun postCompletionNotification(
        context: Context,
        event: CompletionAlertEvent,
        portDisplayName: String,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean
    ) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val useSilent = event.suppressedByQuietHours || (!soundEnabled && !vibrationEnabled)
        val channelId = if (useSilent) CHANNEL_COMPLETION_SILENT else CHANNEL_COMPLETION_ALERT

        val title = if (event.repeatIndex > 0) {
            context.getString(R.string.notif_completion_repeat_title, portDisplayName)
        } else {
            context.getString(R.string.notif_completion_title, portDisplayName)
        }
        val body = context.getString(
            R.string.notif_completion_body,
            event.sessionWh,
            event.peakWatts
        )

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_NAV_TAB", "ANALYTICS")
        }
        val pi = PendingIntent.getActivity(
            context,
            COMPLETION_NOTIF_BASE_ID + event.portId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_cuktech)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(context.getString(R.string.app_name))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(body)
            )
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setLocalOnly(true)
            .setPriority(if (useSilent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)

        if (useSilent) {
            builder.setSilent(true)
        } else {
            var defaults = 0
            if (soundEnabled) defaults = defaults or NotificationCompat.DEFAULT_SOUND
            if (vibrationEnabled) defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
            builder.setDefaults(defaults)
        }

        nm.notify(COMPLETION_NOTIF_BASE_ID + event.portId, builder.build())
    }

    fun postAnomalyNotification(
        context: Context,
        alert: AnomalyAlert,
        inQuietHours: Boolean
    ) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_NAV_TAB", "OVERVIEW")
        }
        val pi = PendingIntent.getActivity(
            context,
            ANOMALY_NOTIF_BASE_ID + (alert.portId ?: 0),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = context.getString(R.string.notif_anomaly_title)
        val notif = NotificationCompat.Builder(context, CHANNEL_ANOMALY_ALERT)
            .setSmallIcon(R.drawable.ic_stat_cuktech)
            .setContentTitle(title)
            .setContentText(alert.summary)
            .setSubText(alert.portLabel ?: context.getString(R.string.app_name))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(alert.summary)
            )
            .setAutoCancel(true)
            .setSilent(inQuietHours)
            .setContentIntent(pi)
            .setLocalOnly(true)
            .build()

        nm.notify(ANOMALY_NOTIF_BASE_ID + (alert.portId ?: 0), notif)
    }

    fun postBleProximityExitNotification(
        context: Context,
        rssiDbm: Int,
        totalWatts: Double,
        hasActiveLimit: Boolean,
        inQuietHours: Boolean
    ) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_NAV_TAB", "OVERVIEW")
        }
        val pi = PendingIntent.getActivity(
            context,
            BLE_PROXIMITY_NOTIF_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = context.getString(R.string.event_ble_exit_title)
        val body = if (hasActiveLimit) {
            context.getString(R.string.notif_ble_exit_limit_body_fmt, rssiDbm, totalWatts)
        } else {
            context.getString(R.string.event_ble_exit_detail_fmt, rssiDbm, totalWatts)
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_ANOMALY_ALERT)
            .setSmallIcon(R.drawable.ic_stat_cuktech)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(context.getString(R.string.app_name))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(body)
            )
            .setAutoCancel(true)
            .setSilent(inQuietHours)
            .setContentIntent(pi)
            .setLocalOnly(true)
            .build()

        nm.notify(BLE_PROXIMITY_NOTIF_ID, notif)
    }
}
