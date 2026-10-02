package com.excellanto.mycrmsync.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_call_log_registration")
data class PendingCallLogRegistrationEntity(
    @PrimaryKey
    @ColumnInfo(name = "sync_fingerprint")
    val syncFingerprint: String,
    @ColumnInfo(name = "call_date_ms")
    val callDateMs: Long,
    @ColumnInfo(name = "user_id")
    val userId: Int,
    @ColumnInfo(name = "phone_raw")
    val phoneRaw: String?,
    @ColumnInfo(name = "phone_e164")
    val phoneE164: String?,
    val direction: String,
    @ColumnInfo(name = "duration_sec")
    val durationSec: Int,
    @ColumnInfo(name = "contact_id")
    val contactId: String?,
    @ColumnInfo(name = "contact_name")
    val contactName: String?,
    @ColumnInfo(name = "started_at_iso")
    val startedAtIso: String,
    @ColumnInfo(name = "ended_at_iso")
    val endedAtIso: String?,
    @ColumnInfo(name = "sim_account_id")
    val simAccountId: String?,
    @ColumnInfo(name = "upload_status")
    val uploadStatus: String,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "next_retry_at_ms")
    val nextRetryAtMs: Long = 0L,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = System.currentTimeMillis(),
    /** Millis when the auto/manual call-status CRM note was posted for this fingerprint. */
    @ColumnInfo(name = "crm_note_uploaded_at_ms")
    val crmNoteUploadedAtMs: Long? = null,
)
