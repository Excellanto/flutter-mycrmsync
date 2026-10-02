package com.excellanto.mycrmsync.call

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.excellanto.mycrmsync.R
import com.excellanto.mycrmsync.ui.screens.WhatsAppContactMatchCard
import com.excellanto.mycrmsync.ui.theme.CRM_CALL_SYNCTheme

/**
 * Shows CRM contact suggestions over WhatsApp while a call is on screen.
 * Uses the display-over-apps overlay when allowed, otherwise a notification
 * that opens [WhatsAppContactMatchActivity].
 */
object WhatsAppContactMatchPresenter {
    const val EXTRA_DISPLAY_NAME = "display_name"
    const val EXTRA_PHONE = "phone"
    const val EXTRA_DIRECTION = "direction"
    const val EXTRA_STARTED_AT = "started_at"

    private const val TAG = "WhatsAppMatchPopup"
    private const val NOTIFICATION_ID = 9102

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionState = mutableStateOf<WhatsAppContactMatchSession?>(null)

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var appContext: Context? = null

    fun show(
        context: Context,
        displayName: String,
        phoneRaw: String?,
        direction: String,
        startedAtMs: Long,
    ) {
        val application = context.applicationContext
        mainHandler.post {
            val current = sessionState.value
            if (current != null && current.startedAtMs == startedAtMs) return@post
            appContext = application
            removeOverlay()
            cancelNotification(application)
            val session =
                WhatsAppContactMatchSession(
                    context = application,
                    displayName = displayName,
                    phoneRaw = phoneRaw,
                    direction = direction,
                    startedAtMs = startedAtMs,
                )
            sessionState.value?.close()
            sessionState.value = session
            session.start {
                mainHandler.post {
                    if (sessionState.value !== session || session.matches.isEmpty()) return@post
                    val message = "Opening WhatsApp contact popup for $displayName ($direction)"
                    Log.i(TAG, message)
                    println(message)
                    if (OverlayPermissionHelper.canDrawOverlays(application) && ensureOverlay(application)) {
                        return@post
                    }
                    Log.i(TAG, "Showing WhatsApp contact popup as a screen")
                    openMatchScreen(context, displayName, phoneRaw, direction, startedAtMs)
                }
            }
        }
    }

    fun dismiss() {
        mainHandler.post {
            sessionState.value?.close()
            sessionState.value = null
            removeOverlay()
            appContext?.let { cancelNotification(it) }
        }
    }

    private fun ensureOverlay(context: Context): Boolean {
        if (composeView != null) return true
        val themed = ContextThemeWrapper(context, R.style.Theme_CRM_CALL_SYNC)
        val wm = themed.getSystemService(WindowManager::class.java) ?: return false
        windowManager = wm
        val owner = OverlayLifecycleOwner().also { it.onCreate() }
        lifecycleOwner = owner

        val metrics = themed.resources.displayMetrics
        val margin = (12 * metrics.density).toInt()
        val width = metrics.widthPixels - margin * 2
        val height = (metrics.heightPixels * 0.42f).toInt().coerceAtLeast((280 * metrics.density).toInt())
        val params =
            WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = margin
            }

        val view =
            ComposeView(themed).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    val session = sessionState.value
                    if (session != null) {
                        CRM_CALL_SYNCTheme {
                            WhatsAppContactMatchCard(
                                direction = session.direction,
                                displayName = session.displayName,
                                isLoading = session.isLoading,
                                matches = session.matches,
                                errorMessage = session.errorMessage,
                                linkingId = session.linkingId,
                                selectedId = session.selectedId,
                                onSelect = session::choose,
                                onSave = { session.save { dismiss() } },
                                onDismiss = { dismiss() },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        return try {
            wm.addView(view, params)
            composeView = view
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not add match overlay", e)
            owner.onDestroy()
            lifecycleOwner = null
            false
        }
    }

    private fun removeOverlay() {
        val view = composeView
        if (view != null) {
            runCatching { windowManager?.removeView(view) }
        }
        composeView = null
        windowManager = null
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
    }

    private fun openMatchScreen(
        context: Context,
        displayName: String,
        phoneRaw: String?,
        direction: String,
        startedAtMs: Long,
    ) {
        if (displayName.isBlank() && phoneRaw.isNullOrBlank()) return
        val message = "WhatsApp contact popup opened for $displayName"
        Log.i(TAG, message)
        println(message)
        val titleName = displayName.ifBlank { phoneRaw.orEmpty() }
        BackgroundPopup.launch(
            context = context,
            intent = matchIntent(context, displayName, phoneRaw, direction, startedAtMs),
            title = context.getString(R.string.whatsapp_match_notification_title, titleName),
            body = context.getString(R.string.whatsapp_match_notification_body),
            notificationId = NOTIFICATION_ID,
        )
    }

    private fun matchIntent(
        context: Context,
        displayName: String,
        phoneRaw: String?,
        direction: String,
        startedAtMs: Long,
    ): Intent =
        Intent(context, WhatsAppContactMatchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_DISPLAY_NAME, displayName)
            putExtra(EXTRA_PHONE, phoneRaw)
            putExtra(EXTRA_DIRECTION, direction)
            putExtra(EXTRA_STARTED_AT, startedAtMs)
        }

    private fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
