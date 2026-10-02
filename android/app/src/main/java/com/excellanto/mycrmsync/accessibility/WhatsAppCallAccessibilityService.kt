package com.excellanto.mycrmsync.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.excellanto.mycrmsync.call.PhoneCallMonitor
import com.excellanto.mycrmsync.call.PhoneCallWatcher
import com.excellanto.mycrmsync.call.WhatsAppContactMatchPresenter
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.WhatsAppContactMatchQuery
import com.excellanto.mycrmsync.data.WhatsAppContactMatchStore

class WhatsAppCallAccessibilityService : AccessibilityService() {

    private val stateMachine = WhatsAppCallStateMachine()
    private val handler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null
    private var lastEventClassName: String? = null
    private var lastMissedBannerKey: String? = null
    private val prefs by lazy { PrefsManager(applicationContext) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs.setWhatsAppAccessibilityServiceEnabled(true)
        PhoneCallWatcher.ensureListening(this)
        PhoneCallMonitor.start(this)
        Log.i(TAG, "WhatsApp call accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!WhatsAppCallUiPatterns.isWhatsAppPackage(event.packageName?.toString())) return
        if (!prefs.isLoggedIn() || !prefs.isWhatsAppCallTrackingEnabled()) return

        event.className?.toString()?.trim()?.ifBlank { null }?.let { lastEventClassName = it }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> scheduleProcess()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        debounceRunnable?.let { handler.removeCallbacks(it) }
        WhatsAppContactMatchPresenter.dismiss()
        super.onDestroy()
    }

    private fun scheduleProcess() {
        debounceRunnable?.let { handler.removeCallbacks(it) }
        debounceRunnable =
            Runnable {
                runCatching { processWhatsAppWindows() }
                    .onFailure { e -> Log.w(TAG, "Process window failed: ${e.message}") }
            }
        handler.postDelayed(debounceRunnable!!, DEBOUNCE_MS)
    }

    private fun processWhatsAppWindows() {
        val roots = collectWhatsAppRoots()
        if (roots.isEmpty()) {
            val completed = stateMachine.onSnapshot(WhatsAppCallUiParser.parse(null))
            if (completed != null) {
                Log.i(TAG, "Call completed (no window) dir=${completed.direction} name=${completed.displayName}")
                WhatsAppCallSessionHandler.onCallCompleted(this, completed)
            }
            return
        }

        var bestParsed = WhatsAppCallUiParser.parse(null)
        for (root in roots) {
            try {
                val parsed =
                    WhatsAppCallUiParser.parseFromSnapshot(
                        root = root.toSnapshot(),
                        windowClassName = lastEventClassName,
                    )
                if (parsed.screenType != WhatsAppCallScreenType.None) {
                    bestParsed = parsed
                    if (parsed.screenType == WhatsAppCallScreenType.Connected ||
                        parsed.screenType == WhatsAppCallScreenType.Missed ||
                        parsed.displayName != null
                    ) {
                        break
                    }
                }
            } finally {
                // Snapshot copies text; root is recycled below
            }
        }
        roots.forEach { it.recycle() }

        if (bestParsed.screenType != WhatsAppCallScreenType.None) {
            Log.d(
                TAG,
                "Call UI type=${bestParsed.screenType} name=${bestParsed.displayName} timer=${bestParsed.timerSeconds}",
            )
        }

        if (bestParsed.screenType == WhatsAppCallScreenType.Missed) {
            val missedKey =
                listOf(
                    bestParsed.displayName?.trim()?.lowercase().orEmpty(),
                    bestParsed.phoneSubtitle?.trim().orEmpty(),
                ).joinToString("|")
            if (missedKey.isNotBlank() && missedKey != lastMissedBannerKey) {
                lastMissedBannerKey = missedKey
                stateMachine.onMissedCallDetected(bestParsed)?.let { missed ->
                    Log.i(TAG, "Missed call detected name=${missed.displayName}")
                    offerContactMatch(
                        displayName = missed.displayName,
                        phoneRaw = missed.phoneRaw,
                        direction = "INCOMING",
                        startedAtMs = missed.startedAtMs,
                    )
                    WhatsAppCallSessionHandler.onCallCompleted(this, missed)
                }
            }
            return
        }

        val completed = stateMachine.onSnapshot(bestParsed)
        if (completed == null) {
            stateMachine.activeSession()?.let { session ->
                val direction = session.direction.trim().uppercase()
                if (direction == "INCOMING" || direction == "OUTGOING") {
                    offerContactMatch(
                        displayName = session.displayName,
                        phoneRaw = session.phoneRaw,
                        direction = direction,
                        startedAtMs = session.startedAtMs,
                    )
                }
            }
        }
        if (completed != null) {
            Log.i(
                TAG,
                "Call completed dir=${completed.direction} name=${completed.displayName} dur=${completed.durationSec}s",
            )
            WhatsAppCallSessionHandler.onCallCompleted(this, completed)
        }
    }

    private fun offerContactMatch(
        displayName: String,
        phoneRaw: String?,
        direction: String,
        startedAtMs: Long,
    ) {
        if (startedAtMs <= 0L) return
        if (WhatsAppContactMatchStore.isConfirmed(startedAtMs)) return
        if (WhatsAppContactMatchStore.wasOffered(startedAtMs)) return
        if (WhatsAppContactMatchQuery.build(displayName, phoneRaw) == null) return
        WhatsAppContactMatchStore.markOffered(startedAtMs)
        val event = if (direction == "INCOMING") "received" else "made"
        val message = "WhatsApp call $event — calling contact match API for $displayName"
        Log.i(TAG, message)
        println(message)
        WhatsAppContactMatchPresenter.show(
            context = this,
            displayName = displayName,
            phoneRaw = phoneRaw,
            direction = direction,
            startedAtMs = startedAtMs,
        )
    }

    private fun collectWhatsAppRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val windowList: List<AccessibilityWindowInfo>? = windows
            windowList?.forEach { window ->
                val root = window.root ?: return@forEach
                if (WhatsAppCallUiPatterns.isWhatsAppPackage(root.packageName?.toString())) {
                    roots += AccessibilityNodeInfo.obtain(root)
                } else {
                    root.recycle()
                }
            }
        }
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { active ->
                if (WhatsAppCallUiPatterns.isWhatsAppPackage(active.packageName?.toString())) {
                    roots += active
                } else {
                    active.recycle()
                }
            }
        }
        return roots
    }

    private fun AccessibilityNodeInfo.toSnapshot(): WhatsAppCallUiParser.NodeSnapshot {
        val children = ArrayList<WhatsAppCallUiParser.NodeSnapshot>(childCount)
        for (i in 0 until childCount) {
            getChild(i)?.let { child ->
                children += child.toSnapshot()
                child.recycle()
            }
        }
        return WhatsAppCallUiParser.NodeSnapshot(
            text = text?.toString(),
            contentDescription = contentDescription?.toString(),
            viewIdResourceName = viewIdResourceName,
            isClickable = isClickable,
            children = children,
        )
    }

    companion object {
        private const val TAG = "WhatsAppA11yService"
        private const val DEBOUNCE_MS = 300L
    }
}
