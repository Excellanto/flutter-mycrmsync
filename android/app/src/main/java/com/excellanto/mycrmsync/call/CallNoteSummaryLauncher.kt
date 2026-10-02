package com.excellanto.mycrmsync.call

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.excellanto.mycrmsync.R
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.model.CrmContactNoteSummariesData

object CallNoteSummaryLauncher {
    private const val TAG = "CallNoteSummaryLauncher"

    const val ACTION_DISMISS = "com.excellanto.mycrmsync.DISMISS_CALL_NOTE_SUMMARY"
    const val EXTRA_PHONE = "phone"
    const val EXTRA_DIRECTION = "direction"
    const val EXTRA_CONTACT_ID = "contact_id"
    const val EXTRA_CONTACT_NAME = "contact_name"

    const val DIRECTION_INCOMING = "incoming"
    const val DIRECTION_OUTGOING = "outgoing"

    fun show(
        context: Context,
        phone: String?,
        direction: String,
        contactId: String? = null,
        contactName: String? = null,
        preloaded: CrmContactNoteSummariesData? = null,
    ) {
        val normalizedPhone = phone?.trim()?.ifBlank { null }
        val normalizedContactId = contactId?.trim()?.ifBlank { null }
        if (normalizedPhone == null && normalizedContactId == null) return
        if (!PrefsManager(context.applicationContext).isLoggedIn()) return

        val appContext = context.applicationContext
        val contactNameValue = contactName?.trim()?.ifBlank { null }
        if (
            CallNoteSummaryPresenter.show(
                context = appContext,
                phone = normalizedPhone,
                direction = direction,
                contactId = normalizedContactId,
                contactName = contactNameValue,
                preloaded = preloaded,
            )
        ) {
            return
        }

        val intent =
            Intent(appContext, CallNoteSummaryOverlayService::class.java).apply {
                putExtra(EXTRA_PHONE, normalizedPhone.orEmpty())
                putExtra(EXTRA_DIRECTION, direction)
                putExtra(EXTRA_CONTACT_ID, normalizedContactId)
                putExtra(EXTRA_CONTACT_NAME, contactNameValue)
            }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not show call note summary overlay", e)
            openSummaryScreen(
                appContext,
                normalizedPhone.orEmpty(),
                direction,
                normalizedContactId,
                contactNameValue,
            )
        }
    }

    fun openSummaryScreen(
        context: Context,
        phone: String,
        direction: String,
        contactId: String?,
        contactName: String?,
    ) {
        val appContext = context.applicationContext
        val intent =
            Intent(appContext, CallNoteSummaryActivity::class.java).apply {
                putExtra(EXTRA_PHONE, phone)
                putExtra(EXTRA_DIRECTION, direction)
                putExtra(EXTRA_CONTACT_ID, contactId)
                putExtra(EXTRA_CONTACT_NAME, contactName)
            }
        BackgroundPopup.launch(
            context = appContext,
            intent = intent,
            title = appContext.getString(R.string.call_note_summary_notification_title),
            body = appContext.getString(R.string.call_note_summary_notification_body),
            notificationId = 9203,
        )
    }

    fun dismiss(context: Context) {
        CallNoteSummaryPresenter.dismiss()
        val appContext = context.applicationContext
        val dismissIntent =
            Intent(appContext, CallNoteSummaryOverlayService::class.java).apply {
                action = CallNoteSummaryOverlayService.ACTION_DISMISS
            }
        try {
            appContext.startService(dismissIntent)
        } catch (_: Exception) {
            // Service may already be stopped.
        }
        val broadcast =
            Intent(ACTION_DISMISS).apply {
                setPackage(appContext.packageName)
            }
        appContext.sendBroadcast(broadcast)
    }
}
