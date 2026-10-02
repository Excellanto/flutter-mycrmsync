package com.excellanto.mycrmsync.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recording_sync_state WHERE id = 1 LIMIT 1")
    suspend fun getSyncState(): RecordingSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: RecordingSyncStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecordings(rows: List<RecordingIndexEntity>)

    @Query("SELECT * FROM recording_index ORDER BY dateModifiedSec DESC LIMIT :limit")
    suspend fun listRecentRecordings(limit: Int): List<RecordingIndexEntity>

}
