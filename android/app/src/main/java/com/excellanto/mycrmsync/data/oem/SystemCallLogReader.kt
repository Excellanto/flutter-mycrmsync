package com.excellanto.mycrmsync.data.oem

import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.CallLog
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

object SystemCallLogReader {

    fun mapDirection(callType: Int): String = when (callType) {
        CallLog.Calls.INCOMING_TYPE -> "in"
        CallLog.Calls.OUTGOING_TYPE -> "out"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "in"
        CallLog.Calls.REJECTED_TYPE -> "missed"
        CallLog.Calls.BLOCKED_TYPE -> "missed"
        else ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                callType == CallLog.Calls.ANSWERED_EXTERNALLY_TYPE
            ) {
                "in"
            } else {
                "missed"
            }
    }

    /**
     * Recent rows from [CallLog.Calls.CONTENT_URI], newest first, capped.
     *
     * *(Flutter/RN analogue: platform channel handler `getSystemCallLog`.)*
     */
    fun readCallLogJson(context: Context, limit: Int): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return "[]"
        }

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
        )
        val sortOrder = "${CallLog.Calls.DATE} DESC"

        val arr = JSONArray()
        try {
            context.applicationContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                sortOrder,
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                val iNum = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val iName = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iDate = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val iDur = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val iType = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)

                var n = 0
                while (c.moveToNext() && n < limit) {
                    n++
                    val type = c.getInt(iType)
                    val jo = JSONObject().apply {
                        put("androidId", c.getString(iId))
                        put("number", c.getString(iNum) ?: JSONObject.NULL)
                        put(
                            "cachedName",
                            if (iName >= 0) c.getString(iName) ?: JSONObject.NULL else JSONObject.NULL,
                        )
                        put("dateMs", c.getLong(iDate))
                        put("durationSec", c.getLong(iDur).toInt())
                        put("direction", mapDirection(type))
                    }
                    arr.put(jo)
                }
            }
        } catch (_: SecurityException) {
            return "[]"
        }

        return arr.toString()
    }

    /**
     * Calls strictly after [minExclusiveDateMs], oldest first (incremental local mirror).
     */
    fun readCallLogSinceJson(context: Context, minExclusiveDateMs: Long, limit: Int): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return "[]"
        }

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
        )
        val selection = "${CallLog.Calls.DATE} > ?"
        val args = arrayOf(minExclusiveDateMs.toString())
        val sortOrder = "${CallLog.Calls.DATE} ASC"

        val arr = JSONArray()
        try {
            context.applicationContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                args,
                sortOrder,
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                val iNum = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val iName = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iDate = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val iDur = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val iType = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)

                var n = 0
                while (c.moveToNext() && n < limit) {
                    n++
                    val type = c.getInt(iType)
                    val jo = JSONObject().apply {
                        put("androidId", c.getString(iId))
                        put("number", c.getString(iNum) ?: JSONObject.NULL)
                        put(
                            "cachedName",
                            if (iName >= 0) c.getString(iName) ?: JSONObject.NULL else JSONObject.NULL,
                        )
                        put("dateMs", c.getLong(iDate))
                        put("durationSec", c.getLong(iDur).toInt())
                        put("direction", mapDirection(type))
                    }
                    arr.put(jo)
                }
            }
        } catch (_: SecurityException) {
            return "[]"
        }

        return arr.toString()
    }
}
