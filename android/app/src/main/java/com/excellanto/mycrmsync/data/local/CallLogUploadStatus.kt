package com.excellanto.mycrmsync.data.local

/** Outbox state for device → CRM call log registration. */
object CallLogUploadStatus {
    /** Matched contact; waiting for POST (or retry after network error). */
    const val PENDING = "pending"

    /** Device row captured; contact search still needed (e.g. offline). */
    const val PENDING_CONTACT = "pending_contact"

    /** Successfully registered (or server duplicate). */
    const val SYNCED = "synced"

    /** Resolved: number is not a CRM contact — do not retry upload. */
    const val NO_CONTACT = "no_contact"

    /** Exceeded retry budget — manual intervention / support. */
    const val FAILED = "failed"

    val TERMINAL = setOf(SYNCED, NO_CONTACT)

    val RETRYABLE = setOf(PENDING, PENDING_CONTACT)
}
