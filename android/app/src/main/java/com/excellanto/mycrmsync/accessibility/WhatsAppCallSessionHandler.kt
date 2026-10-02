package com.excellanto.mycrmsync.accessibility

import android.content.Context
import android.util.Log
import com.excellanto.mycrmsync.call.WhatsAppContactMatchPresenter
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.CallLogRegistrationEvents
import com.excellanto.mycrmsync.data.WhatsAppCallEvents
import com.excellanto.mycrmsync.data.WhatsAppCallRegistrationSync
import com.excellanto.mycrmsync.data.WhatsAppContactMatchStore
import com.excellanto.mycrmsync.data.WhatsAppPostCallPayload
import com.excellanto.mycrmsync.work.CallLogSyncScheduler
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Tracks whether MainActivity is in the foreground for post-call UX routing. */
object WhatsAppAppForegroundTracker {
    private val foreground = AtomicBoolean(false)

    fun setForeground(value: Boolean) {
        foreground.set(value)
    }

    fun isForeground(): Boolean = foreground.get()
}

object WhatsAppCallSessionHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val TAG = "WhatsAppCallSession"

    fun onCallCompleted(context: Context, completed: WhatsAppCallStateMachine.CompletedCall) {
        val appContext = context.applicationContext
        val prefs = PrefsManager(appContext)
        if (!prefs.isLoggedIn() || !prefs.isWhatsAppCallTrackingEnabled()) {
            Log.d(TAG, "Skip completed call: not logged in or tracking disabled")
            return
        }

        val confirmed = WhatsAppContactMatchStore.onCallEnded(completed)
        if (confirmed == null) {
            if (WhatsAppContactMatchStore.wasOffered(completed.startedAtMs)) {
                Log.i(TAG, "WhatsApp call not confirmed in the popup — not saved")
                return
            }
            val message = "WhatsApp call ended — matching contact once for ${completed.displayName}"
            Log.i(TAG, message)
            println(message)
            WhatsAppContactMatchStore.markOffered(completed.startedAtMs)
            WhatsAppContactMatchPresenter.show(
                context = context,
                displayName = completed.displayName,
                phoneRaw = completed.phoneRaw,
                direction = completed.direction,
                startedAtMs = completed.startedAtMs,
            )
            return
        }

        scope.launch {
            runCatching {
                val sync = WhatsAppCallRegistrationSync(appContext)
                val entity = sync.persistCompletedCall(completed) ?: return@runCatching
                CallLogRegistrationEvents.notifyRegistered()
                CallLogSyncScheduler.enqueueAfterWhatsAppCall(appContext)

                val payload =
                    WhatsAppPostCallPayload(
                        syncFingerprint = entity.syncFingerprint,
                        displayName = entity.displayName,
                        direction = entity.direction,
                        durationSec = entity.durationSec,
                        startedAtIso = entity.startedAtIso,
                        contactId = entity.contactId,
                        crmCallLogId = entity.crmCallLogId,
                    )
                WhatsAppCallEvents.notifyPostCall(payload)

                if (WhatsAppAppForegroundTracker.isForeground()) {
                    Log.d(TAG, "App foreground — in-app post-call modal will show")
                } else if (prefs.isWhatsAppPostCallNotificationsEnabled()) {
                    WhatsAppPostCallNotifier.show(appContext, payload)
                } else {
                    Log.d(TAG, "Post-call notification disabled — call saved")
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to persist WhatsApp call: ${e.message}", e)
            }
        }
    }
}
