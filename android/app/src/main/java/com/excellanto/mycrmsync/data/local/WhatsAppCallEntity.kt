package com.excellanto.mycrmsync.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "whatsapp_call")
data class WhatsAppCallEntity(
    @PrimaryKey
    @ColumnInfo(name = "sync_fingerprint")
    val syncFingerprint: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "phone_raw")
    val phoneRaw: String?,
    @ColumnInfo(name = "phone_e164")
    val phoneE164: String?,
    val direction: String,
    @ColumnInfo(name = "duration_sec")
    val durationSec: Int,
    @ColumnInfo(name = "started_at_ms")
    val startedAtMs: Long,
    @ColumnInfo(name = "connected_at_ms")
    val connectedAtMs: Long?,
    @ColumnInfo(name = "ended_at_ms")
    val endedAtMs: Long,
    @ColumnInfo(name = "started_at_iso")
    val startedAtIso: String,
    @ColumnInfo(name = "ended_at_iso")
    val endedAtIso: String?,
    @ColumnInfo(name = "user_id")
    val userId: Int,
    @ColumnInfo(name = "contact_id")
    val contactId: String?,
    @ColumnInfo(name = "contact_name")
    val contactName: String?,
    @ColumnInfo(name = "crm_call_log_id")
    val crmCallLogId: String? = null,
    @ColumnInfo(name = "note_uploaded_at_ms")
    val noteUploadedAtMs: Long? = null,
    @ColumnInfo(name = "post_call_prompt_dismissed_at_ms")
    val postCallPromptDismissedAtMs: Long? = null,
    @ColumnInfo(name = "upload_status")
    val uploadStatus: String,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "next_retry_at_ms")
    val nextRetryAtMs: Long = 0L,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = System.currentTimeMillis(),
)
