package com.excellanto.mycrmsync.call

/**
 * Holds the dialed number when the app starts an outgoing call, because
 * [android.telephony.TelephonyManager] often omits the number on OFFHOOK.
 */
object OutgoingCallTracker {
    @Volatile
    private var pendingPhone: String? = null

    fun setPendingOutgoing(phone: String) {
        pendingPhone = phone.trim().ifBlank { null }
    }

    fun peekPendingOutgoing(): String? = pendingPhone

    fun consumePendingOutgoing(): String? {
        val phone = pendingPhone
        pendingPhone = null
        return phone
    }
}
