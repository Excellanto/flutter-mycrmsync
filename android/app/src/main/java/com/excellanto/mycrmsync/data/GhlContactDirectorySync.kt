package com.excellanto.mycrmsync.data

import com.excellanto.mycrmsync.data.api.GhlApiService
import com.excellanto.mycrmsync.data.local.CrmContactPhoneDao
import com.excellanto.mycrmsync.data.local.CrmContactPhoneEntity
import com.excellanto.mycrmsync.data.model.GhlContact
import com.excellanto.mycrmsync.data.model.GhlSearchRequest

/**
 * Loads searchable GHL contacts for the location and fills [crm_contact_phone] with
 * normalization keys ([PhoneUtils.lookupKeys]) for CRM-only call-log filtering.
 */
object GhlContactDirectorySync {

    suspend fun refreshPhoneLookup(
        api: GhlApiService,
        bearerTokenWithPrefix: String,
        locationId: String,
        contactDao: CrmContactPhoneDao,
        maxContacts: Int = 8000,
    ) {
        val bearer =
            bearerTokenWithPrefix.trim().let { raw ->
                if (raw.startsWith("Bearer ", ignoreCase = true)) raw else "Bearer $raw"
            }

        val rows = ArrayList<CrmContactPhoneEntity>(4096)
        var cursor: List<String>? = null
        val pageLimit = 100
        var totalFetched = 0

        while (totalFetched < maxContacts) {
            val response =
                api.searchContacts(
                    token = bearer,
                    request =
                        GhlSearchRequest(
                            locationId = locationId,
                            pageLimit = pageLimit,
                            searchAfter = cursor,
                            query = null,
                            page = if (cursor == null) 1 else null,
                            filters = null,
                        ),
                )
            if (response.contacts.isEmpty()) break

            for (contact in response.contacts) {
                ingestContact(contact, rows)
            }
            totalFetched += response.contacts.size
            cursor =
                response.searchAfter
                    ?: response.meta?.searchAfter
                    ?: response.contacts.lastOrNull()?.searchAfter

            val donePage = response.contacts.size < pageLimit
            if (donePage || cursor == null) break
        }

        contactDao.clear()
        if (rows.isNotEmpty()) {
            contactDao.insertAll(rows.distinctBy { it.lookupKey })
        }
    }

    private fun ingestContact(contact: GhlContact, out: MutableList<CrmContactPhoneEntity>) {
        val raw =
            contact.phone?.trim()?.ifBlank { null }
                ?: return
        val name = contact.contactName?.trim()?.ifBlank { null } ?: contact.fullName
        val keys = PhoneUtils.lookupKeys(raw)
        if (keys.isEmpty()) return
        for (k in keys) {
            out +=
                CrmContactPhoneEntity(
                    lookupKey = k,
                    contactId = contact.id,
                    displayName = name,
                )
        }
    }
}
