package com.excellanto.mycrmsync.data.oem

import android.net.Uri
import org.json.JSONObject

data class DiscoveredRecording(
    val contentUri: String,
    val displayName: String,
    val dataPath: String?,
    val relativePath: String?,
    val dateModifiedSec: Long,
    val dateAddedSec: Long,
    val sizeBytes: Long,
    val mimeType: String?,
    val durationMs: Long?,
) {
    val sortKey: String get() = "$contentUri|$sizeBytes|$dateModifiedSec"

    val haystack: String
        get() {
            val dn = displayName.lowercase()
            val dp = dataPath?.lowercase().orEmpty()
            val rp = relativePath?.lowercase().orEmpty()
            return "$dn $dp $rp"
        }

    companion object {
        fun tryParse(map: JSONObject): DiscoveredRecording? {
            val uriStr = map.optString("contentUri").trim()
            if (uriStr.isEmpty()) return null
            runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
            return DiscoveredRecording(
                contentUri = uriStr,
                displayName = map.optString("displayName"),
                dataPath = map.optString("dataPath").takeIf { it.isNotEmpty() },
                relativePath = map.optString("relativePath").takeIf { it.isNotEmpty() },
                dateModifiedSec = map.optLong("dateModifiedSec"),
                dateAddedSec = map.optLong("dateAddedSec"),
                sizeBytes = map.optLong("sizeBytes"),
                mimeType = map.optString("mimeType").takeIf { it.isNotEmpty() },
                durationMs = when {
                    !map.has("durationMs") || map.isNull("durationMs") -> null
                    else -> map.optLong("durationMs").takeIf { it > 0L }
                },
            )
        }
    }
}
