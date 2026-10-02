package com.excellanto.mycrmsync.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_call_log")
data class LocalCallLogEntity(
    @PrimaryKey val androidCallLogId: Long,
    val phoneNumber: String?,
    val cachedName: String?,
    /** "in", "out", or "missed" from [com.excellanto.mycrmsync.data.oem.SystemCallLogReader]. */
    val directionLow: String,
    val dateMs: Long,
    val durationSec: Int,
    val recordingUriStr: String?,
    val matchedConfidence: Float?,
    val recordingDurationSec: Int?,
    val matchedAtUtcMs: Long?,
    /** Millis when device posted the CRM call-status note for this row; null = not posted yet. */
    val crmCallStatusNoteUploadedAtMs: Long?,
    /** Local queue flag for future CRM attachment of this recording (not synced yet). */
    val pendingCrmRecordingUpload: Boolean,
)
