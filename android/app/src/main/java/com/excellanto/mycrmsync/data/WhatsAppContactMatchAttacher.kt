package com.excellanto.mycrmsync.data

import android.content.Context
import com.excellanto.mycrmsync.data.local.CallLogUploadStatus
import com.excellanto.mycrmsync.data.local.CrmDatabase
import com.excellanto.mycrmsync.data.local.WhatsAppCallEntity
import com.excellanto.mycrmsync.work.CallLogSyncScheduler
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Writes a confirmed CRM contact onto the WhatsApp call row, if it already exists. */
object WhatsAppContactMatchAttacher {
    suspend fun attachIfPresent(
        context: Context,
        selection: WhatsAppContactMatchStore.Selection,
    ) {
        val appContext = context.applicationContext
        withContext(Dispatchers.IO) {
            val dao = CrmDatabase.getInstance(appContext).whatsAppCallDao()
            val row = findRow(dao.listStartedBetween(
                selection.startedAtMs - WhatsAppContactMatchStoreWindow.MS,
                selection.startedAtMs + WhatsAppContactMatchStoreWindow.MS,
            ), selection) ?: return@withContext

            val status =
                if (row.uploadStatus == CallLogUploadStatus.SYNCED) {
                    row.uploadStatus
                } else {
                    CallLogUploadStatus.PENDING
                }
            val phoneRaw = selection.contactPhone?.trim()?.ifBlank { null } ?: row.phoneRaw
            val phoneE164 = PhoneUtils.normalizeToE164(phoneRaw) ?: row.phoneE164
            val unchanged =
                row.contactId == selection.contactId &&
                    row.uploadStatus == status &&
                    row.phoneRaw == phoneRaw
            if (unchanged) {
                WhatsAppContactMatchStore.consume(
                    selection.displayName,
                    selection.phoneRaw,
                    selection.startedAtMs,
                )
                return@withContext
            }

            dao.upsert(
                row.copy(
                    contactId = selection.contactId,
                    contactName = selection.contactName,
                    phoneRaw = phoneRaw,
                    phoneE164 = phoneE164,
                    uploadStatus = status,
                    lastError = null,
                    nextRetryAtMs = 0L,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
            WhatsAppContactMatchStore.consume(
                selection.displayName,
                selection.phoneRaw,
                selection.startedAtMs,
            )
            if (status == CallLogUploadStatus.PENDING) {
                CallLogSyncScheduler.enqueueAfterWhatsAppCall(appContext)
            }
        }
    }

    private fun findRow(
        rows: List<WhatsAppCallEntity>,
        selection: WhatsAppContactMatchStore.Selection,
    ): WhatsAppCallEntity? {
        return rows
            .filter { row ->
                row.displayName.equals(selection.displayName, ignoreCase = true) ||
                    phonesMatch(row, selection.phoneRaw)
            }
            .minByOrNull { abs(it.startedAtMs - selection.startedAtMs) }
    }

    private fun phonesMatch(row: WhatsAppCallEntity, phoneRaw: String?): Boolean {
        val phone = phoneRaw?.trim()?.ifBlank { null } ?: return false
        if (row.phoneRaw?.trim() == phone) return true
        val e164 = PhoneUtils.normalizeToE164(phone) ?: return false
        return row.phoneE164 == e164
    }
}

internal object WhatsAppContactMatchStoreWindow {
    const val MS = 3 * 60 * 1000L
}
