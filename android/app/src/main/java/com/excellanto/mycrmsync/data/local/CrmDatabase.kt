package com.excellanto.mycrmsync.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RecordingIndexEntity::class,
        RecordingSyncStateEntity::class,
        LocalCallLogEntity::class,
        CrmContactPhoneEntity::class,
        PendingCallLogRegistrationEntity::class,
        WhatsAppCallEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class CrmDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun localCallLogDao(): LocalCallLogDao
    abstract fun crmContactPhoneDao(): CrmContactPhoneDao
    abstract fun pendingCallLogRegistrationDao(): PendingCallLogRegistrationDao
    abstract fun whatsAppCallDao(): WhatsAppCallDao

    companion object {
        @Volatile private var instance: CrmDatabase? = null

        fun getInstance(context: Context): CrmDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CrmDatabase::class.java,
                    "crm_recordings.db",
                ).fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
