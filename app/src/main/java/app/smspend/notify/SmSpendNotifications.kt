package app.smspend.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.smspend.MainActivity
import app.smspend.R

object SmSpendNotifications {
    const val CHANNEL_SCAN = "scan"

    private const val ID_SCAN = 1001
    /** Foreground notification for expedited scans on Android 11 and below. */
    const val ID_SCAN_FOREGROUND = 1003
    /** Cleans up notifications/channels from versions that had large-debit alerts. */
    private const val LEGACY_CHANNEL_ALERTS = "alerts"
    private const val LEGACY_ID_LARGE_DEBIT = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCAN,
                "SMS scan",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Quiet updates when new spends are found"
            },
        )
        manager.cancel(LEGACY_ID_LARGE_DEBIT)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ALERTS)
    }

    /** POST_NOTIFICATIONS is a runtime permission on Android 13+; the user may deny it. */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun notifyNewSpends(context: Context, count: Int) {
        if (count <= 0) return
        if (!canPost(context)) return
        ensureChannels(context)
        val notification = baseBuilder(context, CHANNEL_SCAN)
            .setContentTitle("SMSpend")
            .setContentText(if (count == 1) "Added 1 spend" else "Added $count spends")
            .setSilent(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_SCAN, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    /** Shown only while an expedited SMS scan runs as a foreground service (API < 31). */
    fun scanInProgress(context: Context): android.app.Notification {
        ensureChannels(context)
        return baseBuilder(context, CHANNEL_SCAN)
            .setContentTitle("SMSpend")
            .setContentText("Reading new bank SMS…")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun baseBuilder(context: Context, channel: String): NotificationCompat.Builder {
        val launch = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(launch)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }
}
