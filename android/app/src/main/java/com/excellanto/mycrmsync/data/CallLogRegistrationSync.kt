package com.excellanto.mycrmsync.data

import android.content.Context
import android.util.Log
import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.data.local.CallLogUploadStatus
import com.excellanto.mycrmsync.data.local.CrmContactPhoneEntity
import com.excellanto.mycrmsync.data.local.CrmDatabase
import com.excellanto.mycrmsync.data.local.PendingCallLogRegistrationDao
import com.excellanto.mycrmsync.data.local.PendingCallLogRegistrationEntity
import com.excellanto.mycrmsync.data.model.RegisterCallLogRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Reliable device → CRM call log registration using a local outbox.
 *
 * - Queues matched calls before upload; retries on network/API failure.
 * - Advances the device watermark only for terminal rows (synced / no_contact).
 * - Drains pending rows on every sync pass (call end, periodic, app refresh).
 */
class CallLogRegistrationSync(
    private val context: Context,
) {
    private val prefs = PrefsManager(context)
    private val crmApi = CrmApiService.create()
    private val repo = CallLogRepository(context)
    private val db = CrmDatabase.getInstance(context)
    private val outbox: PendingCallLogRegistrationDao = db.pendingCallLogRegistrationDao()
    private val contactDao = db.crmContactPhoneDao()

    /**
     * @return failure when retryable work remains (WorkManager should retry).
     */
    suspend fun sync(): Result<Unit> = runCatching {
        val registerUserId =
            prefs.getRegisterUserId()
                ?: run {
                    Log.d(TAG, "Skip sync: no register user_id")
                    return@runCatching
                }

        var registeredThisPass = 0

        bootstrapContactIndexIfNeeded()

        var hadRetryableFailures = drainOutbox(registerUserId) { registeredThisPass++ }
        hadRetryableFailures =
            scanNewDeviceCalls(registerUserId) { registeredThisPass++ } || hadRetryableFailures

        updateWatermarkFromTerminalRows()

        if (registeredThisPass > 0) {
            CallLogRegistrationEvents.notifyRegistered()
        }

        if (hadRetryableFailures) {
            throw PendingCallLogSyncException(
                pending = outbox.countByStatus(CallLogUploadStatus.RETRYABLE.toList()),
            )
        }
    }

    private suspend fun bootstrapContactIndexIfNeeded() {
        val bearerAuthorization = prefs.getBearerAuthorization()
        val crmSearchUserId = prefs.getAuthUserId() ?: prefs.getUserId()
        if (bearerAuthorization.isNullOrBlank() || crmSearchUserId.isNullOrBlank()) return
        if (contactDao.listAll().isNotEmpty()) return
        runCatching {
            CrmContactDirectorySync.refreshPhoneLookup(
                api = crmApi,
                authorization = bearerAuthorization,
                userId = crmSearchUserId,
                contactDao = contactDao,
            )
            prefs.setCrmContactDirectorySyncedAt(System.currentTimeMillis())
        }.onFailure { e ->
            Log.w(TAG, "Contact index bootstrap failed: ${e.message}")
        }
    }

    private suspend fun drainOutbox(
        registerUserId: Int,
        onRegistered: () -> Unit,
    ): Boolean {
        val now = System.currentTimeMillis()
        val rows =
            outbox.listRetryable(
                statuses = CallLogUploadStatus.RETRYABLE.toList(),
                nowMs = now,
                limit = OUTBOX_DRAIN_BATCH,
            )
        if (rows.isEmpty()) return false

        val bearerAuthorization = prefs.getBearerAuthorization()
        val crmSearchUserId = prefs.getAuthUserId() ?: prefs.getUserId()
        val contactIndex = contactDao.listAll().associateBy { it.lookupKey }
        val contactCache = mutableMapOf<String, ResolvedContact?>()

        var anyFailure = false
        for (row in rows) {
            val ok =
                when (row.uploadStatus) {
                    CallLogUploadStatus.PENDING_CONTACT ->
                        processPendingContactRow(
                            row = row,
                            registerUserId = registerUserId,
                            bearerAuthorization = bearerAuthorization,
                            crmSearchUserId = crmSearchUserId,
                            contactIndex = contactIndex,
                            contactCache = contactCache,
                            onRegistered = onRegistered,
                        )
                    CallLogUploadStatus.PENDING ->
                        processPendingUploadRow(row, registerUserId, onRegistered)
                    else -> true
                }
            if (!ok) anyFailure = true
        }
        return anyFailure
    }

    private suspend fun scanNewDeviceCalls(
        registerUserId: Int,
        onRegistered: () -> Unit,
    ): Boolean {
        val watermark = prefs.getDeviceCallLogSyncWatermarkMs()
        val entries: List<DeviceCallLogEntry> =
            if (watermark == 0L) {
                repo.loadRecentCalls(limit = DeviceCallLogLimits.INITIAL_BOOTSTRAP)
                    .sortedBy { it.dateMillis }
            } else {
                repo.loadCallsWithDateAfter(watermark, limit = DeviceCallLogLimits.INCREMENTAL_SYNC_BATCH)
            }

        if (entries.isEmpty()) return false

        val bearerAuthorization = prefs.getBearerAuthorization()
        val crmSearchUserId = prefs.getAuthUserId() ?: prefs.getUserId()
        val contactIndex = contactDao.listAll().associateBy { it.lookupKey }
        val contactCache = mutableMapOf<String, ResolvedContact?>()

        var newWatermark = watermark
        var hadRetryableFailures = false

        for (entry in entries.sortedBy { it.dateMillis }) {
            val fp =
                buildFingerprint(
                    phoneE164 = PhoneUtils.normalizeToE164(entry.number, defaultCountryCode = "+91"),
                    phoneRaw = entry.number,
                    startedMs = entry.dateMillis,
                    durationSec = entry.durationSec,
                    direction = entry.directionLabel().toApiDirection(),
                )

            val existing = outbox.getByFingerprint(fp)
            if (existing != null && existing.uploadStatus in CallLogUploadStatus.TERMINAL) {
                newWatermark = maxOf(newWatermark, entry.dateMillis)
                continue
            }
            if (existing != null && existing.uploadStatus in CallLogUploadStatus.RETRYABLE) {
                hadRetryableFailures = true
                break
            }

            val phoneE164 = PhoneUtils.normalizeToE164(entry.number, defaultCountryCode = "+91")
            val direction = entry.directionLabel().toApiDirection()
            val resolve =
                resolveContact(
                    contactIndex = contactIndex,
                    bearerAuthorization = bearerAuthorization,
                    crmSearchUserId = crmSearchUserId,
                    phoneE164 = phoneE164,
                    phoneRaw = entry.number,
                    cache = contactCache,
                    allowApiSearch = true,
                )

            when (resolve) {
                is ContactResolveResult.Found -> {
                    val endedAtMs = entry.dateMillis + entry.durationSec * 1000L
                    val row =
                        PendingCallLogRegistrationEntity(
                            syncFingerprint = fp,
                            callDateMs = entry.dateMillis,
                            userId = registerUserId,
                            phoneRaw = entry.number,
                            phoneE164 = phoneE164,
                            direction = direction,
                            durationSec = entry.durationSec.coerceAtLeast(0),
                            contactId = resolve.contact.id,
                            contactName = resolve.contact.name,
                            startedAtIso = iso8601(entry.dateMillis),
                            endedAtIso = if (entry.durationSec > 0) iso8601(endedAtMs) else null,
                            simAccountId = entry.phoneAccountId,
                            uploadStatus = CallLogUploadStatus.PENDING,
                            attemptCount = 0,
                            nextRetryAtMs = 0L,
                        )
                    outbox.upsert(row)
                    val uploaded = uploadRow(row, onRegistered)
                    if (uploaded) {
                        newWatermark = maxOf(newWatermark, entry.dateMillis)
                    } else {
                        hadRetryableFailures = true
                        break
                    }
                }
                ContactResolveResult.NotInCrm -> {
                    outbox.upsert(
                        PendingCallLogRegistrationEntity(
                            syncFingerprint = fp,
                            callDateMs = entry.dateMillis,
                            userId = registerUserId,
                            phoneRaw = entry.number,
                            phoneE164 = phoneE164,
                            direction = direction,
                            durationSec = entry.durationSec.coerceAtLeast(0),
                            contactId = null,
                            contactName = null,
                            startedAtIso = iso8601(entry.dateMillis),
                            endedAtIso = null,
                            simAccountId = entry.phoneAccountId,
                            uploadStatus = CallLogUploadStatus.NO_CONTACT,
                        ),
                    )
                    newWatermark = maxOf(newWatermark, entry.dateMillis)
                }
                ContactResolveResult.NeedsNetwork -> {
                    outbox.upsert(
                        PendingCallLogRegistrationEntity(
                            syncFingerprint = fp,
                            callDateMs = entry.dateMillis,
                            userId = registerUserId,
                            phoneRaw = entry.number,
                            phoneE164 = phoneE164,
                            direction = direction,
                            durationSec = entry.durationSec.coerceAtLeast(0),
                            contactId = null,
                            contactName = null,
                            startedAtIso = iso8601(entry.dateMillis),
                            endedAtIso = null,
                            simAccountId = entry.phoneAccountId,
                            uploadStatus = CallLogUploadStatus.PENDING_CONTACT,
                            attemptCount = 0,
                            nextRetryAtMs = 0L,
                        ),
                    )
                    hadRetryableFailures = true
                    break
                }
            }
        }

        if (newWatermark > watermark) {
            prefs.setDeviceCallLogSyncWatermarkMs(newWatermark)
        }
        return hadRetryableFailures
    }

    private suspend fun processPendingContactRow(
        row: PendingCallLogRegistrationEntity,
        registerUserId: Int,
        bearerAuthorization: String?,
        crmSearchUserId: String?,
        contactIndex: Map<String, CrmContactPhoneEntity>,
        contactCache: MutableMap<String, ResolvedContact?>,
        onRegistered: () -> Unit,
    ): Boolean {
        val resolve =
            resolveContact(
                contactIndex = contactIndex,
                bearerAuthorization = bearerAuthorization,
                crmSearchUserId = crmSearchUserId,
                phoneE164 = row.phoneE164,
                phoneRaw = row.phoneRaw,
                cache = contactCache,
                allowApiSearch = !bearerAuthorization.isNullOrBlank(),
            )
        return when (resolve) {
            is ContactResolveResult.Found -> {
                val updated =
                    row.copy(
                        contactId = resolve.contact.id,
                        contactName = resolve.contact.name,
                        uploadStatus = CallLogUploadStatus.PENDING,
                        userId = registerUserId,
                    )
                outbox.upsert(updated)
                processPendingUploadRow(updated, registerUserId, onRegistered)
            }
            ContactResolveResult.NotInCrm -> {
                markTerminal(row, CallLogUploadStatus.NO_CONTACT)
                true
            }
            ContactResolveResult.NeedsNetwork -> {
                scheduleRetry(row, "Contact lookup needs network")
                false
            }
        }
    }

    private suspend fun processPendingUploadRow(
        row: PendingCallLogRegistrationEntity,
        registerUserId: Int,
        onRegistered: () -> Unit,
    ): Boolean {
        val contactId = row.contactId?.trim()?.ifBlank { null }
        if (contactId == null) {
            outbox.updateStatus(
                fp = row.syncFingerprint,
                status = CallLogUploadStatus.PENDING_CONTACT,
                contactId = null,
                contactName = null,
                attemptCount = row.attemptCount,
                lastError = "Missing contact_id",
                nextRetryAtMs = 0L,
                updatedAtMs = System.currentTimeMillis(),
            )
            return false
        }
        val uploadRow =
            row.copy(
                userId = registerUserId,
                contactId = contactId,
            )
        return uploadRow(uploadRow, onRegistered)
    }

    private suspend fun uploadRow(
        row: PendingCallLogRegistrationEntity,
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
                simAccountId = row.simAccountId,
                syncFingerprint = row.syncFingerprint,
                status = "synced_device",
            )

        return when (val result = postRegister(request)) {
            RegisterPostResult.Success,
            RegisterPostResult.Duplicate,
            -> {
                markTerminal(row, CallLogUploadStatus.SYNCED)
                onRegistered()
                true
            }
            is RegisterPostResult.RetryableFailure -> {
                scheduleRetry(row, result.message)
                false
            }
            is RegisterPostResult.PermanentFailure -> {
                markTerminal(row, CallLogUploadStatus.FAILED, result.message)
                true
            }
        }
    }

    private suspend fun maybePostAutoTimelineNote(row: PendingCallLogRegistrationEntity) {
        val authorization = prefs.getBearerAuthorization()?.trim()?.ifBlank { null } ?: return
        val userId =
            prefs.getAuthUserId()?.trim()?.ifBlank { null }
                ?: prefs.getUserId()?.trim()?.ifBlank { null }
                ?: return
        maybePostAutoTimelineNoteReturning(row, authorization, userId)
    }

    /** Backfill: synced long calls that never got a timeline note (e.g. crash mid-sync). */
    private suspend fun postAutoTimelineNotesForLongCalls(): Int {
        val authorization = prefs.getBearerAuthorization()?.trim()?.ifBlank { null } ?: return 0
        val userId =
            prefs.getAuthUserId()?.trim()?.ifBlank { null }
                ?: prefs.getUserId()?.trim()?.ifBlank { null }
                ?: return 0
        val pending =
            outbox.listSyncedNeedingNote(
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
        row: PendingCallLogRegistrationEntity,
        authorization: String,
        userId: String,
    ): Boolean {
        if (row.crmNoteUploadedAtMs != null) return false
        if (!CallStatusNotePoster.shouldAutoPost(row.durationSec, row.contactId)) return false
        val contactId = row.contactId?.trim()?.ifBlank { null } ?: return false
        return CallStatusNotePoster.postCallStatusNote(
            api = crmApi,
            authorization = authorization,
            userId = userId,
            db = db,
            contactId = contactId,
            contactDisplayName = row.contactName?.trim()?.ifBlank { null } ?: "Unknown contact",
            direction = row.direction,
            durationSec = row.durationSec,
            startedAtIso = row.startedAtIso,
            phoneE164 = row.phoneE164,
            phoneRaw = row.phoneRaw,
            syncFingerprint = row.syncFingerprint,
            isWhatsAppCall = false,
            hasRecording =
                CallStatusNotePoster.findLocalByFingerprint(db, row.syncFingerprint)
                    ?.recordingUriStr
                    ?.isNotBlank() == true,
        )
    }

    private suspend fun postRegister(request: RegisterCallLogRequest): RegisterPostResult {
        return try {
            val response = crmApi.registerCallLog(request)
            when {
                response.success -> {
                    if (response.data?.created == false) {
                        RegisterPostResult.Duplicate
                    } else {
                        RegisterPostResult.Success
                    }
                }
                else -> RegisterPostResult.RetryableFailure(response.message)
            }
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}".trim()
            if (isDuplicateError(msg)) {
                RegisterPostResult.Duplicate
            } else if (isRetryableError(msg, e)) {
                RegisterPostResult.RetryableFailure(msg)
            } else {
                RegisterPostResult.PermanentFailure(msg)
            }
        }
    }

    private suspend fun scheduleRetry(row: PendingCallLogRegistrationEntity, error: String) {
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
        outbox.updateStatus(
            fp = row.syncFingerprint,
            status = status,
            contactId = row.contactId,
            contactName = row.contactName,
            attemptCount = attempts,
            lastError = error.take(500),
            nextRetryAtMs = nextRetry,
            updatedAtMs = System.currentTimeMillis(),
        )
        Log.w(TAG, "Retry scheduled fp=${row.syncFingerprint} attempt=$attempts status=$status")
    }

    private suspend fun markTerminal(
        row: PendingCallLogRegistrationEntity,
        status: String,
        error: String? = null,
    ) {
        outbox.updateStatus(
            fp = row.syncFingerprint,
            status = status,
            contactId = row.contactId,
            contactName = row.contactName,
            attemptCount = row.attemptCount,
            lastError = error,
            nextRetryAtMs = 0L,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private suspend fun updateWatermarkFromTerminalRows() {
        val terminalMax =
            outbox.maxTerminalCallDateMs(CallLogUploadStatus.TERMINAL.toList())
                ?: return
        val current = prefs.getDeviceCallLogSyncWatermarkMs()
        if (terminalMax > current) {
            prefs.setDeviceCallLogSyncWatermarkMs(terminalMax)
        }
    }

    private sealed class ContactResolveResult {
        data class Found(val contact: ResolvedContact) : ContactResolveResult()
        data object NotInCrm : ContactResolveResult()
        data object NeedsNetwork : ContactResolveResult()
    }

    private data class ResolvedContact(
        val id: String,
        val name: String,
    )

    private suspend fun resolveContact(
        contactIndex: Map<String, CrmContactPhoneEntity>,
        bearerAuthorization: String?,
        crmSearchUserId: String?,
        phoneE164: String?,
        phoneRaw: String?,
        cache: MutableMap<String, ResolvedContact?>,
        allowApiSearch: Boolean,
    ): ContactResolveResult {
        val cacheKey = phoneE164?.trim()?.ifBlank { null } ?: phoneRaw?.trim()?.ifBlank { null }
        if (cacheKey.isNullOrBlank()) return ContactResolveResult.NotInCrm

        if (cache.containsKey(cacheKey)) {
            return cache[cacheKey]?.let { ContactResolveResult.Found(it) }
                ?: ContactResolveResult.NotInCrm
        }

        val local = CrmContactDirectorySync.lookupContactLocal(contactIndex, phoneRaw, phoneE164)
        if (local != null) {
            val resolved =
                ResolvedContact(
                    id = local.contactId,
                    name = local.displayName?.trim()?.ifBlank { null } ?: "Unknown contact",
                )
            cache[cacheKey] = resolved
            return ContactResolveResult.Found(resolved)
        }

        if (!allowApiSearch || bearerAuthorization.isNullOrBlank() || crmSearchUserId.isNullOrBlank()) {
            return if (contactIndex.isEmpty()) {
                ContactResolveResult.NeedsNetwork
            } else {
                cache[cacheKey] = null
                ContactResolveResult.NotInCrm
            }
        }

        return try {
            val contact =
                CrmContactDirectorySync.searchContactByPhone(
                    api = crmApi,
                    authorization = bearerAuthorization,
                    userId = crmSearchUserId,
                    phoneRaw = phoneRaw,
                    phoneE164 = phoneE164,
                )
            if (contact != null) {
                val resolved =
                    ResolvedContact(
                        id = contact.id,
                        name = contact.fullName,
                    )
                cache[cacheKey] = resolved
                ContactResolveResult.Found(resolved)
            } else {
                cache[cacheKey] = null
                ContactResolveResult.NotInCrm
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact search failed: ${e.message}")
            ContactResolveResult.NeedsNetwork
        }
    }

    private sealed class RegisterPostResult {
        data object Success : RegisterPostResult()
        data object Duplicate : RegisterPostResult()
        data class RetryableFailure(val message: String) : RegisterPostResult()
        data class PermanentFailure(val message: String) : RegisterPostResult()
    }

    private fun String.toApiDirection(): String = when (this) {
        "INCOMING", "OUTGOING", "MISSED", "UNKNOWN" -> this
        else -> "UNKNOWN"
    }

    private fun buildFingerprint(
        phoneE164: String?,
        phoneRaw: String?,
        startedMs: Long,
        durationSec: Int,
        direction: String,
    ): String {
        val key = phoneE164?.trim()?.ifBlank { null } ?: phoneRaw?.trim()?.ifBlank { null } ?: "na"
        return "$key|$startedMs|$durationSec|$direction"
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
            lower.contains("504")
        ) {
            return true
        }
        return e is java.io.IOException
    }

    companion object {
        private const val TAG = "CallLogRegistrationSync"
        private const val MAX_ATTEMPTS = 15
        private const val OUTBOX_DRAIN_BATCH = 40

        suspend fun pendingCount(context: Context): Int {
            val dao = CrmDatabase.getInstance(context).pendingCallLogRegistrationDao()
            return dao.countByStatus(CallLogUploadStatus.RETRYABLE.toList())
        }
    }
}

class PendingCallLogSyncException(
    val pending: Int,
) : Exception("Call log sync incomplete ($pending pending)")
