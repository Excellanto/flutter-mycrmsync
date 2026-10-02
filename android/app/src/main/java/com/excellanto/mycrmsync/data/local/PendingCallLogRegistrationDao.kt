package com.excellanto.mycrmsync.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingCallLogRegistrationDao {

    @Query("SELECT * FROM pending_call_log_registration WHERE sync_fingerprint = :fp LIMIT 1")
    suspend fun getByFingerprint(fp: String): PendingCallLogRegistrationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PendingCallLogRegistrationEntity)

    @Query(
        """
        SELECT * FROM pending_call_log_registration
        WHERE upload_status IN (:statuses)
          AND next_retry_at_ms <= :nowMs
        ORDER BY call_date_ms ASC
        LIMIT :limit
        """,
    )
    suspend fun listRetryable(
        statuses: List<String>,
        nowMs: Long,
        limit: Int = 40,
    ): List<PendingCallLogRegistrationEntity>

    @Query(
        """
        UPDATE pending_call_log_registration SET
          upload_status = :status,
          contact_id = :contactId,
          contact_name = :contactName,
          attempt_count = :attemptCount,
          last_error = :lastError,
          next_retry_at_ms = :nextRetryAtMs,
          updated_at_ms = :updatedAtMs
        WHERE sync_fingerprint = :fp
        """,
    )
    suspend fun updateStatus(
        fp: String,
        status: String,
        contactId: String?,
        contactName: String?,
        attemptCount: Int,
        lastError: String?,
        nextRetryAtMs: Long,
        updatedAtMs: Long,
    ): Int

    @Query(
        """
        SELECT MAX(call_date_ms) FROM pending_call_log_registration
        WHERE upload_status IN (:terminalStatuses)
        """,
    )
    suspend fun maxTerminalCallDateMs(terminalStatuses: List<String>): Long?

    @Query("SELECT COUNT(*) FROM pending_call_log_registration WHERE upload_status IN (:statuses)")
    suspend fun countByStatus(statuses: List<String>): Int

    @Query(
        """
        UPDATE pending_call_log_registration SET
          crm_note_uploaded_at_ms = :atMs,
          updated_at_ms = :updatedAtMs
        WHERE sync_fingerprint = :fp
        """,
    )
    suspend fun markNoteUploaded(
        fp: String,
        atMs: Long,
        updatedAtMs: Long,
    ): Int

    @Query(
        """
        SELECT sync_fingerprint FROM pending_call_log_registration
        WHERE crm_note_uploaded_at_ms IS NOT NULL
        ORDER BY call_date_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun listRecentWithNote(limit: Int): List<String>

    @Query(
        """
        SELECT * FROM pending_call_log_registration
        WHERE upload_status = :syncedStatus
          AND duration_sec > :minDurationSec
          AND contact_id IS NOT NULL
          AND crm_note_uploaded_at_ms IS NULL
        ORDER BY call_date_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun listSyncedNeedingNote(
        syncedStatus: String,
        minDurationSec: Int,
        limit: Int,
    ): List<PendingCallLogRegistrationEntity>
}
