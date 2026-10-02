package com.excellanto.mycrmsync.data.oem

import com.excellanto.mycrmsync.data.PhoneUtils
import com.excellanto.mycrmsync.data.local.LocalCallLogEntity
import com.excellanto.mycrmsync.data.model.CrmCallLogItem
import com.excellanto.mycrmsync.data.recordingmatch.parseStartedAtMs

/**
 * Aligns with [com.excellanto.mycrmsync.data.CallLogSyncer] fingerprint shape for CRM dedupe.
 */
object DeviceCallFingerprint {

    fun fromSystemRow(call: SystemCallLogRow, defaultCountryCode: String = "+91"): String {
        val phoneE164 = PhoneUtils.normalizeToE164(call.number, defaultCountryCode)
        val direction = when (call.direction) {
            "in" -> "INCOMING"
            "out" -> "OUTGOING"
            "missed" -> "MISSED"
            else -> "UNKNOWN"
        }
        val key = phoneE164?.trim()?.ifBlank { null }
            ?: call.number?.trim()?.ifBlank { null }
            ?: "na"
        return "$key|${call.dateMs}|${call.durationSec}|$direction"
    }

    fun fromLocalEntity(entity: LocalCallLogEntity, defaultCountryCode: String = "+91"): String? {
        val direction =
            when (entity.directionLow) {
                "in" -> "INCOMING"
                "out" -> "OUTGOING"
                "missed" -> "MISSED"
                else -> "UNKNOWN"
            }
        val phoneE164 = PhoneUtils.normalizeToE164(entity.phoneNumber, defaultCountryCode)
        val key =
            phoneE164?.trim()?.ifBlank { null }
                ?: entity.phoneNumber?.trim()?.ifBlank { null }
                ?: "na"
        return "$key|${entity.dateMs}|${entity.durationSec}|$direction"
    }

    fun fromCrmItem(item: CrmCallLogItem, defaultCountryCode: String = "+91"): String? {
        val fp = item.syncFingerprint?.trim()?.takeIf { it.isNotEmpty() }
        if (fp != null) return fp

        val started = parseStartedAtMs(item.startedAt) ?: return null
        val phoneE164 =
            item.phoneE164?.trim()?.ifBlank { null }
                ?: PhoneUtils.normalizeToE164(item.phoneRaw, defaultCountryCode)
        val key =
            phoneE164?.trim()?.ifBlank { null }
                ?: item.phoneRaw?.trim()?.ifBlank { null }
                ?: "na"
        val dir = item.direction?.trim()?.uppercase() ?: "UNKNOWN"
        val dur = item.durationSec ?: 0
        return "$key|$started|$dur|$dir"
    }
}
