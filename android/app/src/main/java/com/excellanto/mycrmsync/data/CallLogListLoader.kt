package com.excellanto.mycrmsync.data

import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.data.local.CrmDatabase
import com.excellanto.mycrmsync.data.local.LocalCallLogEntity
import com.excellanto.mycrmsync.data.local.WhatsAppCallEntity
import com.excellanto.mycrmsync.data.model.CrmCallLogItem
import com.excellanto.mycrmsync.data.model.CrmCallLogsGetRequest
import com.excellanto.mycrmsync.data.oem.DeviceCallFingerprint
import com.excellanto.mycrmsync.data.recordingmatch.RecordingCallMatcher
import com.excellanto.mycrmsync.data.recordingmatch.parseStartedAtMs
import com.excellanto.mycrmsync.ui.viewmodel.UiCallLogRow
import kotlin.math.abs

/**
 * Loads call logs from `POST api/crm/call-logs/get` and enriches rows with on-device
 * recording matches from Room when available.
 */
object CallLogListLoader {

    data class FetchResult(
        val items: List<CrmCallLogItem>,
        val nextCursor: String?,
    )

    suspend fun fetchFromApi(
        api: CrmApiService,
        authorization: String,
        scope: CallLogListScope,
        meUserId: String,
        limit: Int = DeviceCallLogLimits.UI_LIST_MAX,
        cursor: String? = null,
    ): FetchResult =
        fetchFromGetApi(
            api = api,
            authorization = authorization,
            meUserId = meUserId,
            mine = scope == CallLogListScope.ME,
            limit = limit,
            cursor = cursor,
        )

    suspend fun fetchDashboardFromGetApi(
        api: CrmApiService,
        authorization: String,
        meUserId: String,
        mine: Boolean,
    ): List<CrmCallLogItem> =
        fetchFromGetApi(
            api = api,
            authorization = authorization,
            meUserId = meUserId,
            mine = mine,
        ).items

    private suspend fun fetchFromGetApi(
        api: CrmApiService,
        authorization: String,
        meUserId: String,
        mine: Boolean,
        limit: Int? = null,
        cursor: String? = null,
    ): FetchResult {
        val userId =
            meUserId.toIntOrNull()
                ?: throw IllegalStateException("Invalid CRM user id for call logs.")

        val response =
            api.getCallLogs(
                authorization = authorization,
                request =
                    CrmCallLogsGetRequest(
                        userId = userId,
                        mine = mine,
                        limit = limit,
                        cursor = cursor,
                    ),
            )
        if (!response.success) {
            throw IllegalStateException(response.message.ifBlank { "Failed to load call logs" })
        }
        val data = response.data
        return FetchResult(
            items = data?.items.orEmpty(),
            nextCursor = data?.nextCursor,
        )
    }

    suspend fun toUiRows(
        apiItems: List<CrmCallLogItem>,
        db: CrmDatabase,
    ): List<UiCallLogRow> {
        val locals =
            db.localCallLogDao()
                .listRecent(DeviceCallLogLimits.UI_LIST_MAX.coerceAtLeast(200))
        val localByFingerprint = buildLocalFingerprintIndex(locals)
        val localById = locals.associateBy { it.androidCallLogId }

        val noteUploadedFingerprints = mutableSetOf<String>()
        db.pendingCallLogRegistrationDao()
            .listRecentWithNote(limit = DeviceCallLogLimits.UI_LIST_MAX.coerceAtLeast(200))
            .forEach { noteUploadedFingerprints.add(it) }
        db.whatsAppCallDao()
            .listRecentWithNote(limit = DeviceCallLogLimits.UI_LIST_MAX.coerceAtLeast(100))
            .forEach { noteUploadedFingerprints.add(it) }

        val apiRows =
            apiItems.mapNotNull { item ->
                item.toUiRow(localByFingerprint, localById, locals, noteUploadedFingerprints)
            }

        val apiFingerprints =
            apiItems.mapNotNull { it.syncFingerprint?.trim()?.ifBlank { null } }.toSet()

        val localWhatsApp =
            db.whatsAppCallDao()
                .listRecent(DeviceCallLogLimits.UI_LIST_MAX.coerceAtLeast(100))

        val merged =
            mergeLocalWhatsAppRows(
                apiRows = apiRows,
                apiFingerprints = apiFingerprints,
                localWhatsApp = localWhatsApp,
            )

        return merged.sortedByDescending { parseStartedAtMs(it.startedAtIso) ?: 0L }
    }

    /** Local WhatsApp rows with a matched integrated CRM contact only. */
    fun mergeLocalWhatsAppRows(
        apiRows: List<UiCallLogRow>,
        apiFingerprints: Set<String>,
        localWhatsApp: List<WhatsAppCallEntity>,
    ): List<UiCallLogRow> =
        apiRows +
            localWhatsApp
                .asSequence()
                .filter { entity ->
                    CallDirection.shouldShowWhatsAppInCallLog(entity.direction, entity.contactId)
                }
                .filter { it.syncFingerprint !in apiFingerprints }
                .map { it.toLocalUiRow() }
                .toList()

    private fun WhatsAppCallEntity.toLocalUiRow(): UiCallLogRow {
        val stableId =
            syncFingerprint.hashCode().toLong().let { if (it == Long.MIN_VALUE) 3L else kotlin.math.abs(it) }
        return UiCallLogRow(
            androidCallLogId = stableId,
            crmCallLogId = crmCallLogId,
            phoneRaw = phoneRaw,
            phoneE164 = phoneE164,
            crmContactId = contactId.orEmpty(),
            contactDisplayName = contactName?.trim()?.ifBlank { null } ?: displayName,
            direction = CallDirection.normalize(direction),
            durationSec = durationSec,
            startedAtIso = startedAtIso,
            recordingUri = null,
            matchedConfidence = null,
            recordingDurationSec = null,
            pendingCrmRecordingUpload = false,
            crmCallStatusNoteUploaded = noteUploadedAtMs != null,
            staffUserName = null,
            isWhatsAppCall = true,
            whatsAppSyncPending =
                uploadStatus != com.excellanto.mycrmsync.data.local.CallLogUploadStatus.SYNCED ||
                    contactId.isNullOrBlank(),
        )
    }

    private fun buildLocalFingerprintIndex(
        locals: List<LocalCallLogEntity>,
    ): Map<String, LocalCallLogEntity> {
        val out = LinkedHashMap<String, LocalCallLogEntity>(locals.size)
        for (local in locals) {
            val fp = DeviceCallFingerprint.fromLocalEntity(local) ?: continue
            out.putIfAbsent(fp, local)
        }
        return out
    }

    private fun findLocalByPhoneAndTime(
        item: CrmCallLogItem,
        locals: List<LocalCallLogEntity>,
    ): LocalCallLogEntity? {
        val startedMs = parseStartedAtMs(item.startedAt) ?: return null
        val directionLow =
            when (item.direction?.trim()?.uppercase()) {
                "INCOMING" -> "in"
                "OUTGOING" -> "out"
                "MISSED" -> "missed"
                else -> return null
            }
        val phoneDigits =
            RecordingCallMatcher.normalizeDigits(
                item.phoneE164 ?: item.phoneRaw,
            )
        if (phoneDigits.length < 5) return null

        return locals
            .asSequence()
            .filter { local ->
                local.directionLow == directionLow &&
                    abs(local.dateMs - startedMs) <= LOCAL_MATCH_WINDOW_MS &&
                    RecordingCallMatcher.normalizeDigits(local.phoneNumber) == phoneDigits
            }
            .maxByOrNull { it.matchedAtUtcMs ?: 0L }
    }

    private fun CrmCallLogItem.toUiRow(
        localByFingerprint: Map<String, LocalCallLogEntity>,
        localById: Map<Long, LocalCallLogEntity>,
        allLocals: List<LocalCallLogEntity>,
        noteUploadedFingerprints: Set<String>,
    ): UiCallLogRow? {
        val contactId = contactId?.trim()?.ifBlank { null } ?: return null
        val name = contactName?.trim()?.ifBlank { null } ?: "Unknown contact"
        val dir = direction?.trim()?.uppercase()?.ifBlank { null } ?: "UNKNOWN"
        if (isWhatsAppCall() && !CallDirection.shouldShowWhatsAppInCallLog(dir, contactId)) {
            return null
        }
        val started = startedAt?.trim()?.ifBlank { null } ?: return null

        val fp = DeviceCallFingerprint.fromCrmItem(this)
        val local =
            fp?.let { localByFingerprint[it] }
                ?: findLocalByPhoneAndTime(this, allLocals)
        val androidId =
            local?.androidCallLogId
                ?: fp?.hashCode()?.toLong()?.let { if (it == Long.MIN_VALUE) 1L else kotlin.math.abs(it) }
                ?: id?.hashCode()?.toLong()?.let { if (it == Long.MIN_VALUE) 2L else kotlin.math.abs(it) }
                ?: return null

        val localResolved = local ?: localById[androidId]
        val noteUploaded =
            !ghlNoteId.isNullOrBlank() ||
                localResolved?.crmCallStatusNoteUploadedAtMs != null ||
                (fp != null && fp in noteUploadedFingerprints)

        return UiCallLogRow(
            androidCallLogId = androidId,
            crmCallLogId = id,
            phoneRaw = phoneRaw,
            phoneE164 = phoneE164 ?: PhoneUtils.normalizeToE164(phoneRaw),
            crmContactId = contactId,
            contactDisplayName = name,
            direction = dir,
            durationSec = durationSec,
            startedAtIso = started,
            recordingUri = localResolved?.recordingUriStr,
            matchedConfidence = localResolved?.matchedConfidence,
            recordingDurationSec = localResolved?.recordingDurationSec,
            pendingCrmRecordingUpload = localResolved?.pendingCrmRecordingUpload ?: false,
            crmCallStatusNoteUploaded = noteUploaded,
            staffUserName = userName?.trim()?.ifBlank { null },
            isWhatsAppCall = isWhatsAppCall(),
        )
    }

    private fun CrmCallLogItem.isWhatsAppCall(): Boolean {
        val status = status?.trim()?.lowercase().orEmpty()
        val sim = simAccountId?.trim()?.lowercase().orEmpty()
        val fp = syncFingerprint?.trim()?.lowercase().orEmpty()
        return status == "synced_whatsapp" || sim == "whatsapp" || fp.startsWith("whatsapp|")
    }

    private const val LOCAL_MATCH_WINDOW_MS = 180_000L
}
