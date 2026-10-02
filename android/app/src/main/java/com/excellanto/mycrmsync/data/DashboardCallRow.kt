package com.excellanto.mycrmsync.data

/**
 * Call row for dashboard KPIs — sourced from `POST api/crm/call-logs/get`.
 */
data class DashboardCallRow(
    val userId: String?,
    val direction: String,
    val durationSec: Int?,
    val startedAt: String?,
    val phoneE164: String?,
    val phoneRaw: String?,
    val contactId: String?,
    val contactName: String?,
    val isWhatsAppCall: Boolean = false,
    val syncFingerprint: String? = null,
)