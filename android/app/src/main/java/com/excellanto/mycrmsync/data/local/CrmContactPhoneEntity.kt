package com.excellanto.mycrmsync.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached CRM phone lookups (normalized keys) for filtering device calls.
 */
@Entity(tableName = "crm_contact_phone")
data class CrmContactPhoneEntity(
    @PrimaryKey val lookupKey: String,
    val contactId: String,
    val displayName: String?,
)
