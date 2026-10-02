package com.excellanto.mycrmsync.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.excellanto.mycrmsync.data.ContactNoteSummariesLoader
import com.excellanto.mycrmsync.data.ContactNoteSummariesResult
import com.excellanto.mycrmsync.work.CallLogSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hears phone-state changes while the process is alive, including after the
 * Flutter screen has been closed. Manifest [android.telephony.TelephonyManager]
 * broadcasts are not delivered to a stopped app on current Android versions.
 */
object PhoneCallWatcher {
    private const val TAG = "PhoneCallWatcher"

    private var appContext: Context? = null
    private var listening = false
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var isIncoming = false
    private var savedNumber: String? = null
    private var lastEnqueueAtMs: Long = 0
    private var noteSummaryShownForSession = false
    private var matchJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Suppress("DEPRECATION")
    private val listener =
        object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                val context = appContext ?: return
                onCallStateChanged(context, state, phoneNumber)
            }
        }

    fun ensureListening(context: Context) {
        val application = context.applicationContext
        appContext = application
        if (listening) return
        if (
            ContextCompat.checkSelfPermission(application, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val telephony = application.getSystemService(TelephonyManager::class.java) ?: return
        try {
            @Suppress("DEPRECATION")
            telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            listening = true
            Log.i(TAG, "Listening for calls while the app is closed")
        } catch (e: SecurityException) {
            Log.w(TAG, "Phone state listener not allowed", e)
        }
    }

    fun onCallStateChanged(context: Context, state: Int, number: String?) {
        appContext = context.applicationContext
        if (lastState == state) {
            if (state == TelephonyManager.CALL_STATE_IDLE) return
            val lateNumber = number?.trim()?.ifBlank { null } ?: return
            if (!savedNumber.isNullOrBlank() || noteSummaryShownForSession) return
            savedNumber = lateNumber
            val direction =
                if (state == TelephonyManager.CALL_STATE_RINGING || isIncoming) {
                    CallNoteSummaryLauncher.DIRECTION_INCOMING
                } else {
                    CallNoteSummaryLauncher.DIRECTION_OUTGOING
                }
            showNoteSummaryIfPossible(context, savedNumber, direction)
            return
        }

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                isIncoming = true
                savedNumber = number ?: savedNumber
                noteSummaryShownForSession = false
                Log.d(TAG, "Incoming call from: $savedNumber")
                showNoteSummaryIfPossible(
                    context = context,
                    phone = savedNumber,
                    direction = CallNoteSummaryLauncher.DIRECTION_INCOMING,
                )
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (lastState != TelephonyManager.CALL_STATE_RINGING) {
                    isIncoming = false
                    val outgoingPhone =
                        number?.trim()?.ifBlank { null }
                            ?: OutgoingCallTracker.consumePendingOutgoing()
                    savedNumber = outgoingPhone ?: savedNumber
                    Log.d(TAG, "Outgoing call started to: $savedNumber")
                    showNoteSummaryIfPossible(
                        context = context,
                        phone = savedNumber,
                        direction = CallNoteSummaryLauncher.DIRECTION_OUTGOING,
                    )
                } else {
                    Log.d(TAG, "Call answered")
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    Log.d(TAG, "Missed call from: $savedNumber")
                } else if (isIncoming) {
                    Log.d(TAG, "Incoming call ended: $savedNumber")
                } else {
                    Log.d(TAG, "Outgoing call ended")
                }
                noteSummaryShownForSession = false
                matchJob?.cancel()
                matchJob = null
                OutgoingCallTracker.consumePendingOutgoing()
                CallNoteSummaryLauncher.dismiss(context)
                enqueueCallSync(context)
            }
        }
        lastState = state
    }

    private fun showNoteSummaryIfPossible(context: Context, phone: String?, direction: String) {
        if (noteSummaryShownForSession) return
        val targetPhone = phone?.trim()?.ifBlank { null } ?: return
        noteSummaryShownForSession = true
        val appContext = context.applicationContext
        matchJob?.cancel()
        matchJob =
            scope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        ContactNoteSummariesLoader.load(appContext, targetPhone)
                    }
                val matched = (result as? ContactNoteSummariesResult.Success)?.data
                val contact = matched?.contact
                if (contact == null) {
                    Log.i(TAG, "Skipping notes popup — $targetPhone is not a CRM contact")
                    return@launch
                }
                CallNoteSummaryLauncher.show(
                    context = appContext,
                    phone = contact.phone ?: targetPhone,
                    direction = direction,
                    contactId = contact.id,
                    contactName = contact.name,
                    preloaded = matched,
                )
            }
    }

    private fun enqueueCallSync(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastEnqueueAtMs < 1500) return
        lastEnqueueAtMs = now
        CallLogSyncScheduler.enqueueAfterCall(context.applicationContext)
    }
}
