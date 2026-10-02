package com.excellanto.mycrmsync.data

import android.content.Context
import android.util.Log
import com.excellanto.mycrmsync.accessibility.WhatsAppCallStateMachine
import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.data.local.CallLogUploadStatus
import com.excellanto.mycrmsync.data.local.CrmDatabase
import com.excellanto.mycrmsync.data.local.WhatsAppCallDao
import com.excellanto.mycrmsync.data.local.WhatsAppCallEntity
import com.excellanto.mycrmsync.data.model.RegisterCallLogRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class WhatsAppCallRegistrationSync(
    private val context: Context,
) {
    private val prefs = PrefsManager(context)
    private val crmApi = CrmApiService.create()
    private val db = CrmDatabase.getInstance(context)
    private val dao: WhatsAppCallDao = db.whatsAppCallDao()

    suspend fun persistCompletedCall(
        completed: WhatsAppCallStateMachine.CompletedCall,
    ): WhatsAppCallEntity? {
        val registerUserId =
            prefs.getRegisterUserId()
                ?: throw IllegalStateException("No register user id")

        val normalizedDirection = CallDirection.normalize(completed.direction)
        val whatsAppNumber = WhatsAppPhoneHint.fromWhatsAppUi(completed.phoneRaw, completed.displayName)
        val phoneE164 = PhoneUtils.normalizeToE164(whatsAppNumber ?: completed.phoneRaw)
        val fingerprint =
            WhatsAppCallFingerprint.build(
                phoneE164 = phoneE164,
                phoneRaw = whatsAppNumber ?: completed.phoneRaw,
                displayName = completed.displayName,
                startedMs = completed.startedAtMs,
                durationSec = completed.durationSec,
                direction = normalizedDirection,
            )

        val phoneHint = whatsAppNumber
        val chosen =
            WhatsAppContactMatchStore.peek(
                displayName = completed.displayName,
                phoneRaw = completed.phoneRaw ?: phoneHint,
                startedAtMs = completed.startedAtMs,
            )
        if (chosen == null) {
            Log.i(TAG, "Skip WhatsApp save — user has not confirmed a CRM contact")
            return null
        }

        val existing = dao.getByFingerprint(fingerprint)
        if (existing != null) {
            WhatsAppContactMatchStore.consume(
                displayName = completed.displayName,
                phoneRaw = completed.phoneRaw ?: phoneHint,
                startedAtMs = completed.startedAtMs,
            )
            return existing
        }

        Log.i(
            TAG,
            "WhatsApp call confirmed — contactId=${chosen.contactId} " +
                "display=${completed.displayName.trim().ifBlank { "—" }} " +
                "direction=${completed.direction} durationSec=${completed.durationSec}",
        )

        val contactId = chosen.contactId
        val contactName = chosen.contactName
        val uploadStatus = CallLogUploadStatus.PENDING
        val resolvedPhoneRaw =
            chosen.contactPhone?.trim()?.ifBlank { null }
                ?: phoneHint
                ?: whatsAppNumber
                ?: completed.phoneRaw
        val resolvedPhoneE164 =
            PhoneUtils.normalizeToE164(chosen.contactPhone)
                ?: PhoneUtils.normalizeToE164(resolvedPhoneRaw)
                ?: phoneE164

        val entity =
            WhatsAppCallEntity(
                syncFingerprint = fingerprint,
                displayName = contactName.ifBlank { completed.displayName },
                phoneRaw = resolvedPhoneRaw,
                phoneE164 = resolvedPhoneE164,
                direction = normalizedDirection,
                durationSec = completed.durationSec,
                startedAtMs = completed.startedAtMs,
                connectedAtMs = completed.connectedAtMs,
                endedAtMs = completed.endedAtMs,
                startedAtIso = iso8601(completed.startedAtMs),
                endedAtIso =
                    if (completed.durationSec > 0) {
                        iso8601(completed.endedAtMs)
                    } else {
                        null
                    },
                userId = registerUserId,
                contactId = contactId,
                contactName = contactName,
                uploadStatus = uploadStatus,
            )
        dao.upsert(entity)
        if (chosen != null) {
            WhatsAppContactMatchStore.consume(
                displayName = completed.displayName,
                phoneRaw = completed.phoneRaw ?: phoneHint,
                startedAtMs = completed.startedAtMs,
            )
        }
        return entity
    }

    suspend fun sync(): Result<Unit> = runCatching {
        val registerUserId =
            prefs.getRegisterUserId()
                ?: run {
                    Log.d(TAG, "Skip WhatsApp sync: no register user_id")
                    return@runCatching
                }

        var registeredThisPass = 0
        var hadRetryableFailures = drainPending(registerUserId) { registeredThisPass++ }

        if (registeredThisPass > 0) {
            CallLogRegistrationEvents.notifyRegistered()
        }

        if (hadRetryableFailures) {
            throw PendingWhatsAppCallSyncException(
                pending = dao.countByStatus(CallLogUploadStatus.RETRYABLE.toList()),
            )
        }
    }

    private suspend fun drainPending(
        registerUserId: Int,
        onRegistered: () -> Unit,
    ): Boolean {
        val now = System.currentTimeMillis()
        val rows =
            dao.listRetryable(
                statuses = CallLogUploadStatus.RETRYABLE.toList(),
                nowMs = now,
                limit = OUTBOX_DRAIN_BATCH,
            )
        if (rows.isEmpty()) return false

        var hadRetryable = false

        for (row in rows) {
            val processed =
                processRow(
                    row = row,
                    registerUserId = registerUserId,
                    onRegistered = onRegistered,
                )
            if (!processed) hadRetryable = true
        }
        return hadRetryable
    }

    private suspend fun processRow(
        row: WhatsAppCallEntity,
        registerUserId: Int,
        onRegistered: () -> Unit,
    ): Boolean {
        val contactId = row.contactId?.trim()?.ifBlank { null }
        val contactName = row.contactName

        if (contactId == null) {
            Log.i(TAG, "Drop unconfirmed WhatsApp call ${row.syncFingerprint}")
            dao.deleteByFingerprint(row.syncFingerprint)
            return true
        }

        return uploadRow(
            row.copy(
                userId = registerUserId,
                contactId = contactId,
                contactName = contactName,
            ),
            onRegistered,
        )
    }

    private suspend fun uploadRow(
        row: WhatsAppCallEntity,
        onRegistered: () -> Unit,
    ): Boolean {
        val contactId = row.contactId?.trim()?.ifBlank { null } ?: return false
        val request =
            RegisterCallLogRequest(
                userId = row.userId,
                direction = row.direction,
                phoneRaw = row.phoneRaw,
                phoneE164 = row.phoneE164,
                contactId = contactId,
                contactName = row.contactName,
                durationSec = row.durationSec.takeIf { it >= 0 },
                startedAt = row.startedAtIso,
                endedAt = row.endedAtIso,
                simAccountId = "whatsapp",
                syncFingerprint = row.syncFingerprint,
                status = "synced_whatsapp",
            )

        return when (val result = postRegister(request)) {
            is RegisterPostResult.Success,
            is RegisterPostResult.Duplicate,
            -> {
                markTerminal(
                    row = row,
                    status = CallLogUploadStatus.SYNCED,
                    crmCallLogId = result.crmCallLogId ?: row.crmCallLogId,
                )
                onRegistered()
                true
            }
            is RegisterPostResult.RetryableFailure -> {
                scheduleRetry(row, result.message)
                false
            }
            is RegisterPostResult.PermanentFailure -> {
                markTerminal(row = row, status = CallLogUploadStatus.FAILED, lastError = result.message)
                true
            }
        }
    }

    private suspend fun maybePostAutoTimelineNote(row: WhatsAppCallEntity) {
        val authorization = prefs.getBearerAuthorization()?.trim()?.ifBlank { null } ?: return
        val userId =
            prefs.getAuthUserId()?.trim()?.ifBlank { null }
                ?: prefs.getUserId()?.trim()?.ifBlank { null }
                ?: return
        maybePostAutoTimelineNoteReturning(row, authorization, userId)
    }

    private suspend fun postAutoTimelineNotesForLongCalls(): Int {
        val authorization = prefs.getBearerAuthorization()?.trim()?.ifBlank { null } ?: return 0
        val userId =
            prefs.getAuthUserId()?.trim()?.ifBlank { null }
                ?: prefs.getUserId()?.trim()?.ifBlank { null }
                ?: return 0
        val pending =
            dao.listSyncedNeedingNote(
                syncedStatus = CallLogUploadStatus.SYNCED,
                minDurationSec = CallStatusNotePoster.AUTO_NOTE_MIN_DURATION_SEC,
                limit = 25,
            )
        var posted = 0
        for (row in pending) {
            if (maybePostAutoTimelineNoteReturning(row, authorization, userId)) {
                posted++
            }
        }
        return posted
    }

    private suspend fun maybePostAutoTimelineNoteReturning(
        row: WhatsAppCallEntity,
        authorization: String,
        userId: String,
    ): Boolean {
        if (row.noteUploadedAtMs != null) return false
        if (!CallStatusNotePoster.shouldAutoPost(row.durationSec, row.contactId)) return false
        val contactId = row.contactId?.trim()?.ifBlank { null } ?: return false
        return CallStatusNotePoster.postCallStatusNote(
            api = crmApi,
            authorization = authorization,
            userId = userId,
            db = db,
            contactId = contactId,
            contactDisplayName =
                row.contactName?.trim()?.ifBlank { null }
                    ?: row.displayName.trim().ifBlank { "Unknown contact" },
            direction = row.direction,
            durationSec = row.durationSec,
            startedAtIso = row.startedAtIso,
            phoneE164 = row.phoneE164,
            phoneRaw = row.phoneRaw,
            syncFingerprint = row.syncFingerprint,
            isWhatsAppCall = true,
            hasRecording = false,
        )
    }

    private suspend fun postRegister(request: RegisterCallLogRequest): RegisterPostResult {
        return try {
            val response = crmApi.registerCallLog(request)
            when {
                response.success -> {
                    val id = response.data?.id?.trim()?.ifBlank { null }
                    if (response.data?.created == false) {
                        RegisterPostResult.Duplicate(id)
                    } else {
                        RegisterPostResult.Success(id)
                    }
                }
                else -> RegisterPostResult.RetryableFailure(response.message)
            }
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}".trim()
            if (isDuplicateError(msg)) {
                RegisterPostResult.Duplicate(id = null)
            } else if (isRetryableError(msg, e)) {
                RegisterPostResult.RetryableFailure(msg)
            } else {
                RegisterPostResult.PermanentFailure(msg)
            }
        }
    }

    private suspend fun scheduleRetry(row: WhatsAppCallEntity, error: String) {
        val attempts = row.attemptCount + 1
        val status =
            if (attempts >= MAX_ATTEMPTS) {
                CallLogUploadStatus.FAILED
            } else {
                row.uploadStatus
            }
        val nextRetry =
            if (status == CallLogUploadStatus.FAILED) {
                row.nextRetryAtMs
            } else {
                System.currentTimeMillis() + backoffMs(attempts)
            }
        dao.updateSyncState(
            fingerprint = row.syncFingerprint,
            status = status,
            contactId = row.contactId,
            contactName = row.contactName,
            crmCallLogId = row.crmCallLogId,
            attemptCount = attempts,
            lastError = error.take(500),
            nextRetryAtMs = nextRetry,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private suspend fun markTerminal(
        row: WhatsAppCallEntity,
        status: String,
        crmCallLogId: String? = row.crmCallLogId,
        lastError: String? = null,
    ) {
        dao.updateSyncState(
            fingerprint = row.syncFingerprint,
            status = status,
            contactId = row.contactId,
            contactName = row.contactName,
            crmCallLogId = crmCallLogId,
            attemptCount = row.attemptCount,
            lastError = lastError,
            nextRetryAtMs = 0L,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun iso8601(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        return sdf.format(Date(epochMs))
    }

    private fun backoffMs(attempt: Int): Long {
        val capped = attempt.coerceIn(1, 10)
        return min(30_000L * (1L shl (capped - 1)), 6 * 60 * 60 * 1000L)
    }

    private fun isDuplicateError(msg: String): Boolean {
        val lower = msg.lowercase(Locale.US)
        return lower.contains("duplicate") ||
            lower.contains("unique") ||
            lower.contains("23505") ||
            lower.contains("409") ||
            lower.contains("conflict") ||
            lower.contains("already exists")
    }

    private fun isRetryableError(msg: String, e: Exception): Boolean {
        val lower = msg.lowercase(Locale.US)
        if (lower.contains("timeout") ||
            lower.contains("timed out") ||
            lower.contains("unable to resolve host") ||
            lower.contains("failed to connect") ||
            lower.contains("connection") ||
            lower.contains("network") ||
            lower.contains("503") ||
            lower.contains("502") ||
            lower.contains("500")
        ) {
            return true
        }
        return e is java.io.IOException
    }

    private sealed class RegisterPostResult {
        val crmCallLogId: String?
            get() =
                when (this) {
                    is Success -> id
                    is Duplicate -> id
                    else -> null
                }

        data class Success(val id: String?) : RegisterPostResult()
        data class Duplicate(val id: String?) : RegisterPostResult()
        data class RetryableFailure(val message: String) : RegisterPostResult()
        data class PermanentFailure(val message: String) : RegisterPostResult()
    }


    companion object {
        private const val TAG = "WhatsAppCallRegSync"
        private const val OUTBOX_DRAIN_BATCH = 20
        private const val MAX_ATTEMPTS = 15
    }
}

class PendingWhatsAppCallSyncException(val pending: Int) : Exception("WhatsApp call sync pending: $pending")
