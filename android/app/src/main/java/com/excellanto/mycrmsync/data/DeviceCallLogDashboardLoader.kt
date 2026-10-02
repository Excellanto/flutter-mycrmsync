package com.excellanto.mycrmsync.data

import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.data.local.CrmDatabase
import com.excellanto.mycrmsync.data.local.WhatsAppCallEntity
import com.excellanto.mycrmsync.data.model.CrmCallLogItem
import com.excellanto.mycrmsync.data.recordingmatch.parseStartedAtMs
/** Builds dashboard call rows from `POST api/crm/call-logs/get`; KPIs are computed client-side. */
object DeviceCallLogDashboardLoader {

    data class LoadResult(
        val rows: List<DashboardCallRow>,
        val staffOptions: List<Pair<String, String>>,
    )

    suspend fun loadFromApi(
        api: CrmApiService,
        authorization: String,
        meUserId: String,
        currentUserId: String?,
        currentUserName: String?,
        db: CrmDatabase,
    ): LoadResult {
        val items =
            CallLogListLoader.fetchDashboardFromGetApi(
                api = api,
                authorization = authorization,
                meUserId = meUserId,
                mine = false,
            )
        val apiRows = items.mapNotNull { it.toDashboardRow() }
        val apiFingerprints =
            items.mapNotNull { it.syncFingerprint?.trim()?.ifBlank { null } }.toSet()
        val localWhatsApp =
            db.whatsAppCallDao()
                .listRecent(DeviceCallLogLimits.UI_LIST_MAX.coerceAtLeast(100))
        val localRows =
            localWhatsApp
                .filter { it.syncFingerprint !in apiFingerprints }
                .mapNotNull { it.toDashboardRow() }
        val merged =
            (apiRows + localRows).sortedByDescending { parseStartedAtMs(it.startedAt) ?: 0L }
        return LoadResult(
            rows = merged,
            staffOptions = buildStaffOptions(items, currentUserId, currentUserName),
        )
    }

    private fun buildStaffOptions(
        items: List<CrmCallLogItem>,
        fallbackUserId: String?,
        fallbackUserName: String?,
    ): List<Pair<String, String>> {
        val fromApi =
            items.mapNotNull { item ->
                val id = item.userId?.trim()?.ifBlank { null } ?: return@mapNotNull null
                val name = item.userName?.trim()?.ifBlank { null } ?: "Unknown"
                id to name
            }.distinctBy { it.first }

        if (fromApi.isNotEmpty()) {
            return fromApi.sortedBy { it.second.lowercase() }
        }

        return buildList {
            val id = fallbackUserId?.trim()?.ifBlank { null } ?: return@buildList
            val name = fallbackUserName?.trim()?.ifBlank { null } ?: "Me"
            add(id to name)
        }
    }

    private fun CrmCallLogItem.toDashboardRow(): DashboardCallRow? {
        val started = startedAt?.trim()?.ifBlank { null } ?: return null
        val contactId = contactId?.trim()?.ifBlank { null }
        val dir = CallDirection.normalize(direction)
        if (isWhatsAppCall() && !CallDirection.shouldShowWhatsAppInCallLog(dir, contactId)) {
            return null
        }
        return DashboardCallRow(
            userId = userId?.trim()?.ifBlank { null },
            direction = dir,
            durationSec = durationSec,
            startedAt = started,
            phoneE164 = phoneE164 ?: PhoneUtils.normalizeToE164(phoneRaw),
            phoneRaw = phoneRaw,
            contactId = contactId,
            contactName = contactName?.trim()?.ifBlank { null },
            isWhatsAppCall = isWhatsAppCall(),
            syncFingerprint = syncFingerprint?.trim()?.ifBlank { null },
        )
    }

    private fun WhatsAppCallEntity.toDashboardRow(): DashboardCallRow? {
        if (!CallDirection.shouldShowWhatsAppInCallLog(direction, contactId)) return null
        val started = startedAtIso.trim().ifBlank { null } ?: return null
        return DashboardCallRow(
            userId = userId.toString(),
            direction = CallDirection.normalize(direction),
            durationSec = durationSec,
            startedAt = started,
            phoneE164 = phoneE164 ?: PhoneUtils.normalizeToE164(phoneRaw),
            phoneRaw = phoneRaw,
            contactId = contactId?.trim()?.ifBlank { null },
            contactName = contactName?.trim()?.ifBlank { null } ?: displayName.trim().ifBlank { null },
            isWhatsAppCall = true,
            syncFingerprint = syncFingerprint,
        )
    }

    private fun CrmCallLogItem.isWhatsAppCall(): Boolean {
        val status = status?.trim()?.lowercase().orEmpty()
        val sim = simAccountId?.trim()?.lowercase().orEmpty()
        val fp = syncFingerprint?.trim()?.lowercase().orEmpty()
        return status == "synced_whatsapp" || sim == "whatsapp" || fp.startsWith("whatsapp|")
    }
}
