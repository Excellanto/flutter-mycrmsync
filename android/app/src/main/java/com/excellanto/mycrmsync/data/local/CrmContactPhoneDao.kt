package com.excellanto.mycrmsync.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CrmContactPhoneDao {
    @Query("DELETE FROM crm_contact_phone")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CrmContactPhoneEntity>)

    @Query("SELECT * FROM crm_contact_phone")
    suspend fun listAll(): List<CrmContactPhoneEntity>
}
