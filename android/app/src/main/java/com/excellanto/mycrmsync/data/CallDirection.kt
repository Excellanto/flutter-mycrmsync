package com.excellanto.mycrmsync.data

/** Normalizes CRM / device call direction strings for KPI bucketing. */
object CallDirection {
    fun normalize(raw: String?): String {
        return when (raw?.trim()?.uppercase()) {
            "INCOMING", "IN" -> "INCOMING"
            "OUTGOING", "OUT" -> "OUTGOING"
            "MISSED" -> "MISSED"
            null, "" -> "UNKNOWN"
            else -> raw.trim().uppercase()
        }
    }

    fun isIncoming(direction: String): Boolean = normalize(direction) == "INCOMING"

    fun isOutgoing(direction: String): Boolean = normalize(direction) == "OUTGOING"

    fun isMissed(direction: String): Boolean = normalize(direction) == "MISSED"

    fun isConnected(direction: String): Boolean = !isMissed(direction)

    /** Missed, received (incoming), and dialled (outgoing) WhatsApp calls. */
    fun isWhatsAppCallLogDirection(direction: String): Boolean =
        normalize(direction) in setOf("INCOMING", "OUTGOING", "MISSED")

    fun shouldShowWhatsAppInCallLog(direction: String, crmContactId: String?): Boolean =
        !crmContactId.isNullOrBlank() && isWhatsAppCallLogDirection(direction)
}
