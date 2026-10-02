package com.excellanto.mycrmsync.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.excellanto.mycrmsync.R

/**
 * Opens a call popup when the app is not on screen. A full-screen intent is the
 * path Android still allows for call UI started from a background service.
 */
object BackgroundPopup {
    private const val TAG = "BackgroundPopup"
    private const val CHANNEL_ID = "call_popup"

    fun launch(
        context: Context,
        intent: Intent,
        title: String,
        body: String,
        notificationId: Int,
    ) {
        val appContext = context.applicationContext
        val launch =
            Intent(intent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        if (OverlayPermissionHelper.canDrawOverlays(appContext)) {
            try {
                appContext.startActivity(launch)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Could not open call popup", e)
            }
        }
        try {
            appContext.startActivity(launch)
        } catch (e: Exception) {
            Log.w(TAG, "Call popup activity was blocked", e)
        }
        showFullScreen(appContext, launch, title, body, notificationId)
    }

    private fun showFullScreen(
        context: Context,
        intent: Intent,
        title: String,
        body: String,
        notificationId: Int,
    ) {
        ensureChannel(context)
        val pending =
            PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_whatsapp)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setFullScreenIntent(pending, true)
                .build()
        context.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.call_popup_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.call_popup_channel_description)
            }
        manager.createNotificationChannel(channel)
    }
}
