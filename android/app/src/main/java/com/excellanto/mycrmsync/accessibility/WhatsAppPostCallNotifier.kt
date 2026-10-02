package com.excellanto.mycrmsync.accessibility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.excellanto.mycrmsync.MainActivity
import com.excellanto.mycrmsync.R
import com.excellanto.mycrmsync.data.WhatsAppPostCallPayload

object WhatsAppPostCallNotifier {
    private const val CHANNEL_ID = "whatsapp_post_call"
    private const val NOTIFICATION_ID = 9101

    fun show(context: Context, payload: WhatsAppPostCallPayload) {
        val appContext = context.applicationContext
        ensureChannel(appContext)

        val intent =
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_SYNC_FINGERPRINT, payload.syncFingerprint)
                putExtra(EXTRA_OPEN_POST_CALL, true)
            }

        val pendingIntent =
            PendingIntent.getActivity(
                appContext,
                payload.syncFingerprint.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val directionLabel =
            when (payload.direction.uppercase()) {
                "INCOMING" -> "Incoming"
                "OUTGOING" -> "Outgoing"
                "MISSED" -> "Missed"
                else -> payload.direction
            }

        val notification =
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_whatsapp)
                .setContentTitle("WhatsApp call with ${payload.displayName}")
                .setContentText("$directionLabel · ${formatDuration(payload.durationSec)}")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("Tap to add notes for your WhatsApp call with ${payload.displayName}."),
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID + payload.syncFingerprint.hashCode().and(0xFFFF), notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.whatsapp_post_call_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.whatsapp_post_call_channel_description)
            }
        manager.createNotificationChannel(channel)
    }

    private fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return "—"
        val m = seconds / 60
        val s = seconds % 60
        return if (m <= 0) "${s}s" else "${m}:${s.toString().padStart(2, '0')}"
    }

    const val EXTRA_SYNC_FINGERPRINT = "whatsapp_sync_fingerprint"
    const val EXTRA_OPEN_POST_CALL = "whatsapp_open_post_call"
}
