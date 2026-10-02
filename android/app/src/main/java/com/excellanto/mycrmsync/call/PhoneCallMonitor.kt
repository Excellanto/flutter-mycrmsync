package com.excellanto.mycrmsync.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.excellanto.mycrmsync.R
import com.excellanto.mycrmsync.data.PrefsManager

/**
 * Keeps the process alive after the Flutter screen is closed so incoming and
 * outgoing calls can still open the previous-notes popup.
 */
object PhoneCallMonitor {
    private const val TAG = "PhoneCallMonitor"

    fun start(context: Context) {
        val appContext = context.applicationContext
        if (!PrefsManager(appContext).isLoggedIn()) return
        val intent = Intent(appContext, PhoneCallMonitorService::class.java)
        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start call monitor", e)
            PhoneCallWatcher.ensureListening(appContext)
        }
    }
}

class PhoneCallMonitorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
        PhoneCallWatcher.ensureListening(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        PhoneCallWatcher.ensureListening(this)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        PhoneCallWatcher.ensureListening(this)
        super.onTaskRemoved(rootIntent)
    }

    private fun promoteToForeground() {
        ensureChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Call monitor could not enter the foreground", e)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.phone_call_monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.phone_call_monitor_channel_description)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_whatsapp)
            .setContentTitle(getString(R.string.phone_call_monitor_notification_title))
            .setContentText(getString(R.string.phone_call_monitor_notification_body))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val TAG = "PhoneCallMonitor"
        private const val CHANNEL_ID = "phone_call_monitor"
        private const val NOTIFICATION_ID = 9202
    }
}
