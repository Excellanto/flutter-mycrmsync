package com.excellanto.mycrmsync.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocalCallLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<LocalCallLogEntity>)

    @Query("SELECT * FROM local_call_log WHERE androidCallLogId = :id LIMIT 1")
    suspend fun getByAndroidId(id: Long): LocalCallLogEntity?

    @Query(
        """
        UPDATE local_call_log SET
          recordingUriStr = :uri,
          matchedConfidence = :confidence,
          recordingDurationSec = :durationSec,
          matchedAtUtcMs = :matchedAtUtcMs
        WHERE androidCallLogId = :id
            AND (
              matchedConfidence IS NULL OR :confidence >= matchedConfidence
            )
        """,
    )
    suspend fun applyRecordingMatchIfBetter(
        id: Long,
        uri: String,
        confidence: Float,
        durationSec: Int?,
        matchedAtUtcMs: Long,
    ): Int

    @Query(
        """
        UPDATE local_call_log SET
          recordingUriStr = NULL,
          matchedConfidence = NULL,
          recordingDurationSec = NULL,
          matchedAtUtcMs = NULL,
          pendingCrmRecordingUpload = 0
        WHERE androidCallLogId = :id
        """,
    )
    suspend fun clearRecording(id: Long): Int

    @Query(
        """
        UPDATE local_call_log SET pendingCrmRecordingUpload = :pending
        WHERE androidCallLogId = :id
        """,
    )
    suspend fun setPendingCrmRecordingUpload(id: Long, pending: Boolean): Int

    @Query(
        """
        UPDATE local_call_log SET crmCallStatusNoteUploadedAtMs = :atMs
        WHERE androidCallLogId = :id
        """,
    )
    suspend fun setCallStatusNoteUploadedAt(id: Long, atMs: Long): Int

    @Query("SELECT * FROM local_call_log ORDER BY dateMs DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<LocalCallLogEntity>

    @Query(
        """
        SELECT * FROM local_call_log
        WHERE recordingUriStr IS NULL
        ORDER BY dateMs DESC
        LIMIT :limit
        """,
    )
    suspend fun listWithoutRecording(limit: Int): List<LocalCallLogEntity>

    @Query(
        "SELECT * FROM local_call_log WHERE pendingCrmRecordingUpload = 1 AND recordingUriStr IS NOT NULL " +
            "ORDER BY dateMs DESC",
    )
    suspend fun listPendingCrmRecordingUploads(): List<LocalCallLogEntity>
}
