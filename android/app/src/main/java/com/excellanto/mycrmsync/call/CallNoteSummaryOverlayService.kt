package com.excellanto.mycrmsync.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.excellanto.mycrmsync.R
import com.excellanto.mycrmsync.data.ContactNoteSummariesLoader
import com.excellanto.mycrmsync.data.ContactNoteSummariesResult
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.model.CrmContactNoteSummariesData
import com.excellanto.mycrmsync.ui.screens.CallNoteSummaryOverlay
import com.excellanto.mycrmsync.ui.theme.CRM_CALL_SYNCTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CallNoteSummaryOverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleOwner = OverlayLifecycleOwner()
    private val prefs by lazy { PrefsManager(this) }

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var loadJob: Job? = null
    private var dismissReceiverRegistered = false

    private var phone by mutableStateOf("")
    private var contactId: String? = null
    private var contactName: String? = null
    private var direction by mutableStateOf(CallNoteSummaryLauncher.DIRECTION_INCOMING)
    private var isLoading by mutableStateOf(true)
    private var summariesData by mutableStateOf<CrmContactNoteSummariesData?>(null)
    private var errorMessage by mutableStateOf<String?>(null)

    private val dismissReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                stopOverlay()
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        lifecycleOwner.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                stopOverlay()
                return START_NOT_STICKY
            }
            else -> {
                val targetPhone =
                    intent?.getStringExtra(CallNoteSummaryLauncher.EXTRA_PHONE)?.trim().orEmpty()
                val targetContactId =
                    intent?.getStringExtra(CallNoteSummaryLauncher.EXTRA_CONTACT_ID)?.trim()?.ifBlank { null }
                if (targetPhone.isBlank() && targetContactId == null) {
                    stopOverlay()
                    return START_NOT_STICKY
                }
                phone = targetPhone
                contactId = targetContactId
                contactName =
                    intent?.getStringExtra(CallNoteSummaryLauncher.EXTRA_CONTACT_NAME)?.trim()?.ifBlank { null }
                direction =
                    intent?.getStringExtra(CallNoteSummaryLauncher.EXTRA_DIRECTION)
                        ?: CallNoteSummaryLauncher.DIRECTION_INCOMING
                try {
                    startForeground(NOTIFICATION_ID, buildNotification())
                } catch (e: Exception) {
                    android.util.Log.w("CallNoteSummary", "Foreground notes popup was blocked", e)
                    CallNoteSummaryLauncher.openSummaryScreen(
                        this,
                        phone,
                        direction,
                        contactId,
                        contactName,
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
                registerDismissReceiver()
                attachOverlayIfNeeded()
                loadSummaries(targetPhone, targetContactId, contactName)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlayView()
        unregisterDismissReceiver()
        lifecycleOwner.onDestroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun attachOverlayIfNeeded() {
        if (composeView != null) return
        if (!OverlayPermissionHelper.canDrawOverlays(this)) {
            stopOverlay()
            CallNoteSummaryActivityFallback.show(this, phone, direction, contactId, contactName)
            return
        }

        val metrics = displayMetrics()
        val horizontalMarginPx = dpToPx(OVERLAY_HORIZONTAL_MARGIN_DP)
        val overlayWidth = metrics.widthPixels - horizontalMarginPx * 2
        val overlayHeight = (metrics.heightPixels * OVERLAY_HEIGHT_FRACTION).toInt()

        val savedX = prefs.getCallNoteOverlayX()
        val savedY = prefs.getCallNoteOverlayY()
        val initialX = if (savedX >= 0) savedX else horizontalMarginPx
        val initialY =
            if (savedY >= 0) {
                savedY
            } else {
                (metrics.heightPixels * DEFAULT_Y_FRACTION).toInt()
            }

        val params =
            WindowManager.LayoutParams(
                overlayWidth,
                overlayHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialX
                y = initialY
            }
        layoutParams = params

        val view =
            ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    CRM_CALL_SYNCTheme {
                        CallNoteSummaryOverlay(
                            phone = phone,
                            direction = direction,
                            isLoading = isLoading,
                            data = summariesData,
                            errorMessage = errorMessage,
                            onDismiss = { stopOverlay() },
                            onDrag = { dx, dy -> moveOverlayBy(dx, dy) },
                        )
                    }
                }
            }
        composeView = view
        windowManager?.addView(view, params)
    }

    private fun moveOverlayBy(dx: Float, dy: Float) {
        val view = composeView ?: return
        val params = layoutParams ?: return
        val metrics = displayMetrics()

        val maxX = (metrics.widthPixels - params.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - params.height).coerceAtLeast(0)

        params.x = (params.x + dx.toInt()).coerceIn(0, maxX)
        params.y = (params.y + dy.toInt()).coerceIn(0, maxY)
        windowManager?.updateViewLayout(view, params)
        prefs.setCallNoteOverlayPosition(params.x, params.y)
    }

    private fun loadSummaries(targetPhone: String, targetContactId: String?, targetContactName: String?) {
        loadJob?.cancel()
        isLoading = true
        errorMessage = null
        summariesData = null

        loadJob =
            serviceScope.launch {
                when (
                    val result =
                        ContactNoteSummariesLoader.load(
                            this@CallNoteSummaryOverlayService,
                            targetPhone,
                            targetContactId,
                            targetContactName,
                        )
                ) {
                    is ContactNoteSummariesResult.Success -> {
                        summariesData = result.data
                        isLoading = false
                        if (result.data.contact == null && result.data.summaries.isEmpty()) {
                            stopOverlay()
                        }
                    }
                    is ContactNoteSummariesResult.Error -> {
                        errorMessage = result.message
                        isLoading = false
                        stopOverlay()
                    }
                    ContactNoteSummariesResult.Skipped -> stopOverlay()
                }
            }
    }

    private fun stopOverlay() {
        removeOverlayView()
        unregisterDismissReceiver()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeOverlayView() {
        val view = composeView ?: return
        runCatching { windowManager?.removeView(view) }
        composeView = null
        layoutParams = null
    }

    private fun registerDismissReceiver() {
        if (dismissReceiverRegistered) return
        val filter = IntentFilter(CallNoteSummaryLauncher.ACTION_DISMISS)
        ContextCompat.registerReceiver(
            this,
            dismissReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        dismissReceiverRegistered = true
    }

    private fun unregisterDismissReceiver() {
        if (!dismissReceiverRegistered) return
        unregisterReceiver(dismissReceiver)
        dismissReceiverRegistered = false
    }

    private fun displayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        return metrics
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.call_note_summary_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.call_note_summary_channel_description)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_contact_notes)
            .setContentTitle(getString(R.string.call_note_summary_notification_title))
            .setContentText(getString(R.string.call_note_summary_notification_body))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        const val ACTION_DISMISS = "com.excellanto.mycrmsync.action.DISMISS_CALL_NOTE_OVERLAY"
        private const val CHANNEL_ID = "call_note_summary_overlay"
        private const val NOTIFICATION_ID = 9201
        private const val OVERLAY_HEIGHT_FRACTION = 0.6f
        private const val DEFAULT_Y_FRACTION = 0.08f
        private const val OVERLAY_HORIZONTAL_MARGIN_DP = 16
    }
}

/** Activity fallback when overlay permission is not granted. */
private object CallNoteSummaryActivityFallback {
    fun show(
        context: Context,
        phone: String,
        direction: String,
        contactId: String?,
        contactName: String?,
    ) {
        val intent =
            Intent(context, CallNoteSummaryActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(CallNoteSummaryLauncher.EXTRA_PHONE, phone)
                putExtra(CallNoteSummaryLauncher.EXTRA_DIRECTION, direction)
                putExtra(CallNoteSummaryLauncher.EXTRA_CONTACT_ID, contactId)
                putExtra(CallNoteSummaryLauncher.EXTRA_CONTACT_NAME, contactName)
            }
        context.startActivity(intent)
    }
}
