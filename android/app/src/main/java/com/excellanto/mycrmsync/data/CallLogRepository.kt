package com.excellanto.mycrmsync.data

import android.content.Context
import android.provider.CallLog

/**
 * Read-only access to the **system** call log for diagnostics / future dual-SIM hooks.
 * The in-app Call Logs list is Room-backed; CRM matching uses the same phone/time signals.
 */
class CallLogRepository(
    private val context: Context,
) {
    fun loadRecentCalls(limit: Int = 750): List<DeviceCallLogEntry> {
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.PHONE_ACCOUNT_ID,
        )
        val sortOrder = "${CallLog.Calls.DATE} DESC"
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        ) ?: return emptyList()

        cursor.use { c ->
            val out = ArrayList<DeviceCallLogEntry>(limit.coerceAtMost(512))
            val iNum = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val iType = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val iDate = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val iDur = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val iName = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val iAcc = c.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

            while (c.moveToNext() && out.size < limit) {
                out.add(
                    DeviceCallLogEntry(
                        number = c.getString(iNum),
                        type = c.getInt(iType),
                        dateMillis = c.getLong(iDate),
                        durationSec = c.getString(iDur)?.toIntOrNull() ?: 0,
                        cachedName = if (iName >= 0) c.getString(iName) else null,
                        phoneAccountId = if (iAcc >= 0) c.getString(iAcc) else null,
                    )
                )
            }
            return out
        }
    }

    /**
     * Calls strictly after [minExclusiveDateMs], oldest first (incremental CRM sync).
     */
    fun loadCallsWithDateAfter(minExclusiveDateMs: Long, limit: Int = 320): List<DeviceCallLogEntry> {
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.PHONE_ACCOUNT_ID,
        )
        val selection = "${CallLog.Calls.DATE} > ?"
        val args = arrayOf(minExclusiveDateMs.toString())
        val sortOrder = "${CallLog.Calls.DATE} ASC"
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            selection,
            args,
            sortOrder,
        ) ?: return emptyList()

        cursor.use { c ->
            val out = ArrayList<DeviceCallLogEntry>(limit.coerceAtMost(512))
            val iNum = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val iType = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val iDate = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val iDur = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val iName = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val iAcc = c.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

            while (c.moveToNext() && out.size < limit) {
                out.add(
                    DeviceCallLogEntry(
                        number = c.getString(iNum),
                        type = c.getInt(iType),
                        dateMillis = c.getLong(iDate),
                        durationSec = c.getString(iDur)?.toIntOrNull() ?: 0,
                        cachedName = if (iName >= 0) c.getString(iName) else null,
                        phoneAccountId = if (iAcc >= 0) c.getString(iAcc) else null,
                    )
                )
            }
            return out
        }
    }
}

data class DeviceCallLogEntry(
    val number: String?,
    val type: Int,
    val dateMillis: Long,
    val durationSec: Int,
    val cachedName: String?,
    val phoneAccountId: String?,
)

fun DeviceCallLogEntry.directionLabel(): String = when (type) {
    CallLog.Calls.INCOMING_TYPE -> "INCOMING"
    CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
    CallLog.Calls.MISSED_TYPE -> "MISSED"
    CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
    CallLog.Calls.REJECTED_TYPE -> "REJECTED"
    CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
    else -> "UNKNOWN"
}
