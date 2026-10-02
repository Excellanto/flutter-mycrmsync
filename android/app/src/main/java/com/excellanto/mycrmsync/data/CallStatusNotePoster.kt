package com.excellanto.mycrmsync.data

import android.util.Log
import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.data.local.CrmDatabase
import com.excellanto.mycrmsync.data.local.LocalCallLogEntity
import com.excellanto.mycrmsync.data.model.CrmCreateNoteRequest
import com.excellanto.mycrmsync.data.oem.DeviceCallFingerprint

/**
 * Posts a CRM contact note for a completed call so it appears on the contact Timeline,
 * and marks the local Call Log row so the cloud icon turns green.
 */
object CallStatusNotePoster {
    const val AUTO_NOTE_MIN_DURATION_SEC = 20

    private const val TAG = "CallStatusNotePoster"

    fun shouldAutoPost(durationSec: Int, contactId: String?): Boolean {
        val id = contactId?.trim().orEmpty()
        return durationSec > AUTO_NOTE_MIN_DURATION_SEC && id.isNotEmpty()
    }

    @Suppress("UNUSED_PARAMETER")
    fun formatBody(
        contactDisplayName: String,
        direction: String,
        durationSec: Int?,
        startedAtIso: String,
        phoneE164: String?,
        phoneRaw: String?,
        isWhatsAppCall: Boolean,
        hasRecording: Boolean = false,
    ): String {
        return CallNoteText.format(
            direction = direction,
            durationSec = durationSec,
            startedAtIso = startedAtIso,
            phone = phoneE164 ?: phoneRaw,
            isWhatsAppCall = isWhatsAppCall,
            hasRecording = hasRecording,
        )
    }

    /**
     * Creates the contact note and marks matching local / WhatsApp rows uploaded.
     * @return true when the CRM note was posted successfully.
     */
    suspend fun postCallStatusNote(
        api: CrmApiService,
        authorization: String,
        userId: String,
        db: CrmDatabase,
        contactId: String,
        contactDisplayName: String,
        direction: String,
        durationSec: Int,
        startedAtIso: String,
        phoneE164: String?,
        phoneRaw: String?,
        syncFingerprint: String,
        isWhatsAppCall: Boolean,
        hasRecording: Boolean = false,
    ): Boolean {
        val body =
            formatBody(
                contactDisplayName = contactDisplayName,
                direction = direction,
                durationSec = durationSec,
                startedAtIso = startedAtIso,
                phoneE164 = phoneE164,
                phoneRaw = phoneRaw,
                isWhatsAppCall = isWhatsAppCall,
                hasRecording = hasRecording,
            )
        return try {
            api.createContactNote(
                authorization = authorization,
                request =
                    CrmCreateNoteRequest(
                        userId = userId,
                        contactId = contactId,
                        body = body,
                    ),
            )
            val now = System.currentTimeMillis()
            markLocalUploaded(db, syncFingerprint, now)
            if (isWhatsAppCall) {
                db.whatsAppCallDao().markNoteUploaded(
                    fingerprint = syncFingerprint,
                    noteUploadedAtMs = now,
                    updatedAtMs = now,
                )
            } else {
                db.pendingCallLogRegistrationDao().markNoteUploaded(
                    fp = syncFingerprint,
                    atMs = now,
                    updatedAtMs = now,
                )
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Auto call-status note failed fp=$syncFingerprint: ${e.message}")
            false
        }
    }

    suspend fun findLocalByFingerprint(
        db: CrmDatabase,
        syncFingerprint: String,
        limit: Int = 300,
    ): LocalCallLogEntity? {
        val locals = db.localCallLogDao().listRecent(limit)
        return locals.firstOrNull { DeviceCallFingerprint.fromLocalEntity(it) == syncFingerprint }
    }

    private suspend fun markLocalUploaded(
        db: CrmDatabase,
        syncFingerprint: String,
        atMs: Long,
    ) {
        val local = findLocalByFingerprint(db, syncFingerprint) ?: return
        db.localCallLogDao().setCallStatusNoteUploadedAt(local.androidCallLogId, atMs)
    }
}
