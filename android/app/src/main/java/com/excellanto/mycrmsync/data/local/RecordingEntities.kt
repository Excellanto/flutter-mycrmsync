package com.excellanto.mycrmsync.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recording_index")
data class RecordingIndexEntity(
    @PrimaryKey val contentUriStr: String,
    val androidMediaId: Long,
    val displayName: String,
    val relativePath: String?,
    val dataPath: String?,
    val sizeBytes: Long,
    /** Seconds since Unix epoch per [android.provider.MediaStore] convention. */
    val dateModifiedSec: Long,
    val dateAddedSec: Long,
    val durationMs: Long?,
    val mimeType: String?,
)

@Entity(tableName = "recording_sync_state")
data class RecordingSyncStateEntity(
    @PrimaryKey val id: Int = 1,
    /** Max [RecordingIndexEntity.dateModifiedSec] last applied to watermark (incremental scans). */
    val watermarkModifiedSec: Long,
)
