package com.excellanto.mycrmsync.call

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.excellanto.mycrmsync.R
import com.excellanto.mycrmsync.data.ContactNoteSummariesLoader
import com.excellanto.mycrmsync.data.ContactNoteSummariesResult
import com.excellanto.mycrmsync.data.model.CrmContactNoteSummariesData
import com.excellanto.mycrmsync.ui.screens.CallNoteSummaryOverlay
import com.excellanto.mycrmsync.ui.theme.CRM_CALL_SYNCTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Draws the previous-notes card in a system overlay. This does not need a
 * foreground service, so it still appears after the app screen is closed.
 */
object CallNoteSummaryPresenter {
    private const val TAG = "CallNoteSummaryPopup"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var loadJob: Job? = null

    private var phone by mutableStateOf("")
    private var direction by mutableStateOf(CallNoteSummaryLauncher.DIRECTION_INCOMING)
    private var isLoading by mutableStateOf(true)
    private var summariesData by mutableStateOf<CrmContactNoteSummariesData?>(null)
    private var errorMessage by mutableStateOf<String?>(null)

    fun show(
        context: Context,
        phone: String?,
        direction: String,
        contactId: String? = null,
        contactName: String? = null,
        preloaded: CrmContactNoteSummariesData? = null,
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val appContext = context.applicationContext
            mainHandler.post {
                val shown = show(appContext, phone, direction, contactId, contactName, preloaded)
                if (!shown) {
                    CallNoteSummaryLauncher.openSummaryScreen(
                        appContext,
                        phone?.trim().orEmpty(),
                        direction,
                        contactId,
                        contactName,
                    )
                }
            }
            return true
        }
        if (!OverlayPermissionHelper.canDrawOverlays(context)) return false
        this.phone = phone?.trim().orEmpty()
        this.direction = direction
        if (preloaded != null) {
            summariesData = preloaded
            isLoading = false
            errorMessage = null
        }
        val attached = ensureOverlay(context.applicationContext)
        if (!attached) return false
        if (preloaded != null) {
            bindContent()
        } else {
            load(context.applicationContext, this.phone, contactId, contactName)
        }
        return true
    }

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismiss() }
            return
        }
        loadJob?.cancel()
        loadJob = null
        val view = composeView
        if (view != null) {
            runCatching { windowManager?.removeView(view) }
        }
        composeView = null
        windowManager = null
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        summariesData = null
        errorMessage = null
    }

    private fun ensureOverlay(context: Context): Boolean {
        if (composeView != null) return true
        val themed = ContextThemeWrapper(context, R.style.Theme_CRM_CALL_SYNC)
        val wm = themed.getSystemService(WindowManager::class.java) ?: return false
        windowManager = wm
        val owner = OverlayLifecycleOwner().also { it.onCreate() }
        lifecycleOwner = owner

        val metrics = themed.resources.displayMetrics
        val margin = (16 * metrics.density).toInt()
        val width = metrics.widthPixels - margin * 2
        val height = (metrics.heightPixels * 0.6f).toInt().coerceAtLeast((280 * metrics.density).toInt())
        val params =
            WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (metrics.heightPixels * 0.08f).toInt()
            }

        val view =
            ComposeView(themed).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
            }
        return try {
            wm.addView(view, params)
            composeView = view
            bindContent()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not add notes overlay", e)
            owner.onDestroy()
            lifecycleOwner = null
            windowManager = null
            false
        }
    }

    private fun load(
        context: Context,
        targetPhone: String,
        contactId: String?,
        contactName: String?,
    ) {
        loadJob?.cancel()
        isLoading = true
        errorMessage = null
        summariesData = null
        bindContent()
        loadJob =
            scope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        ContactNoteSummariesLoader.load(
                            context,
                            targetPhone,
                            contactId,
                            contactName,
                        )
                    }
                when (result) {
                    is ContactNoteSummariesResult.Success -> {
                        summariesData = result.data
                        isLoading = false
                        if (result.data.contact == null && result.data.summaries.isEmpty()) {
                            dismiss()
                        } else {
                            bindContent()
                        }
                    }
                    is ContactNoteSummariesResult.Error -> {
                        errorMessage = result.message
                        isLoading = false
                        bindContent()
                    }
                    ContactNoteSummariesResult.Skipped -> dismiss()
                }
            }
    }

    /**
     * Draws the card again from the latest data. A system overlay has no
     * activity, so Compose does not always repaint when the notes arrive.
     */
    private fun bindContent() {
        val view = composeView ?: return
        view.setContent {
            CRM_CALL_SYNCTheme {
                CallNoteSummaryOverlay(
                    phone = phone,
                    direction = direction,
                    isLoading = isLoading,
                    data = summariesData,
                    errorMessage = errorMessage,
                    onDismiss = { dismiss() },
                )
            }
        }
        view.invalidate()
        view.requestLayout()
    }
}
