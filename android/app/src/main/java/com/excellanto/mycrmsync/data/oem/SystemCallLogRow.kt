package com.excellanto.mycrmsync.data.oem

import com.excellanto.mycrmsync.data.local.LocalCallLogEntity
import org.json.JSONObject

data class SystemCallLogRow(
    val androidId: String,
    val number: String?,
    val cachedName: String?,
    val dateMs: Long,
    val durationSec: Int,
    val direction: String,
) {
    companion object {
        fun tryParse(map: JSONObject): SystemCallLogRow? {
            val id = map.optString("androidId").trim()
            if (id.isEmpty()) return null
            val dir = map.optString("direction").trim().lowercase()
            if (dir !in setOf("in", "out", "missed")) return null
            return SystemCallLogRow(
                androidId = id,
                number = map.optString("number").takeIf { it.isNotEmpty() },
                cachedName = map.optString("cachedName").takeIf { it.isNotEmpty() },
                dateMs = map.optLong("dateMs"),
                durationSec = map.optInt("durationSec"),
                direction = dir,
            )
        }
    }
}

fun LocalCallLogEntity.toSystemCallLogRow(): SystemCallLogRow =
    SystemCallLogRow(
        androidId = androidCallLogId.toString(),
        number = phoneNumber,
        cachedName = cachedName,
        dateMs = dateMs,
        durationSec = durationSec,
        direction = directionLow,
    )

data class OemMatch(
    val androidCallId: String,
    val recordingUri: String,
    val confidence: Double,
)
