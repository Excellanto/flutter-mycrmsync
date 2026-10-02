package com.excellanto.mycrmsync.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WhatsAppCallDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WhatsAppCallEntity)

    @Query("SELECT * FROM whatsapp_call WHERE sync_fingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): WhatsAppCallEntity?

    @Query(
        """
        SELECT * FROM whatsapp_call
        WHERE upload_status IN (:statuses)
          AND next_retry_at_ms <= :nowMs
        ORDER BY ended_at_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun listRetryable(
        statuses: List<String>,
        nowMs: Long,
        limit: Int,
    ): List<WhatsAppCallEntity>

    @Query(
        """
        UPDATE whatsapp_call SET
          upload_status = :status,
          contact_id = :contactId,
          contact_name = :contactName,
          crm_call_log_id = :crmCallLogId,
          attempt_count = :attemptCount,
          last_error = :lastError,
          next_retry_at_ms = :nextRetryAtMs,
          updated_at_ms = :updatedAtMs
        WHERE sync_fingerprint = :fingerprint
        """,
    )
    suspend fun updateSyncState(
        fingerprint: String,
        status: String,
        contactId: String?,
        contactName: String?,
        crmCallLogId: String?,
        attemptCount: Int,
        lastError: String?,
        nextRetryAtMs: Long,
        updatedAtMs: Long,
    )

    @Query(
        """
        UPDATE whatsapp_call SET
          note_uploaded_at_ms = :noteUploadedAtMs,
          updated_at_ms = :updatedAtMs
        WHERE sync_fingerprint = :fingerprint
        """,
    )
    suspend fun markNoteUploaded(
        fingerprint: String,
        noteUploadedAtMs: Long,
        updatedAtMs: Long,
    )

    @Query(
        """
        UPDATE whatsapp_call SET
          post_call_prompt_dismissed_at_ms = :dismissedAtMs,
          updated_at_ms = :updatedAtMs
        WHERE sync_fingerprint = :fingerprint
        """,
    )
    suspend fun markPostCallDismissed(
        fingerprint: String,
        dismissedAtMs: Long,
        updatedAtMs: Long,
    )

    @Query("SELECT COUNT(*) FROM whatsapp_call WHERE upload_status IN (:statuses)")
    suspend fun countByStatus(statuses: List<String>): Int

    @Query("SELECT * FROM whatsapp_call ORDER BY ended_at_ms DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<WhatsAppCallEntity>

    @Query(
        """
        SELECT * FROM whatsapp_call
        WHERE started_at_ms BETWEEN :fromMs AND :toMs
        ORDER BY started_at_ms DESC
        LIMIT 8
        """,
    )
    suspend fun listStartedBetween(fromMs: Long, toMs: Long): List<WhatsAppCallEntity>

    @Query(
        """
        SELECT sync_fingerprint FROM whatsapp_call
        WHERE note_uploaded_at_ms IS NOT NULL
        ORDER BY ended_at_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun listRecentWithNote(limit: Int): List<String>

    @Query(
        """
        SELECT * FROM whatsapp_call
        WHERE upload_status = :syncedStatus
          AND duration_sec > :minDurationSec
          AND contact_id IS NOT NULL
          AND note_uploaded_at_ms IS NULL
        ORDER BY ended_at_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun listSyncedNeedingNote(
        syncedStatus: String,
        minDurationSec: Int,
        limit: Int,
    ): List<WhatsAppCallEntity>

    @Query("DELETE FROM whatsapp_call WHERE sync_fingerprint = :fingerprint")
    suspend fun deleteByFingerprint(fingerprint: String)
}
