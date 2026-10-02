package com.excellanto.mycrmsync.data

import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.data.local.CrmContactPhoneDao
import com.excellanto.mycrmsync.data.local.CrmContactPhoneEntity
import com.excellanto.mycrmsync.data.model.CrmContact
import com.excellanto.mycrmsync.data.model.CrmContactSearchRequest

/**
 * Loads CRM contacts from the backend and fills [crm_contact_phone] with
 * normalization keys ([PhoneUtils.lookupKeys]) for device call-log filtering.
 */
object CrmContactDirectorySync {

    /** Skip network refresh if the local index was filled within this window. */
    const val DIRECTORY_TTL_MS = 30 * 60 * 1000L

    /** One list/search request — enough for call-log phone matching on device. */
    const val DIRECTORY_FETCH_LIMIT = 100

    /**
     * Refreshes the phone index only when empty, [force], or older than [maxAgeMs].
     * Uses a single contacts API call (no per-number search).
     */
    suspend fun ensurePhoneLookupIndex(
        api: CrmApiService,
        authorization: String,
        userId: String,
        contactDao: CrmContactPhoneDao,
        lastSyncedAtMs: Long,
        maxAgeMs: Long = DIRECTORY_TTL_MS,
        force: Boolean = false,
    ): Boolean {
        val now = System.currentTimeMillis()
        val empty = contactDao.listAll().isEmpty()
        val stale = lastSyncedAtMs == 0L || now - lastSyncedAtMs > maxAgeMs
        if (!force && !empty && !stale) {
            return false
        }
        refreshPhoneLookup(api, authorization, userId, contactDao)
        return true
    }

    suspend fun refreshPhoneLookup(
        api: CrmApiService,
        authorization: String,
        userId: String,
        contactDao: CrmContactPhoneDao,
    ) {
        require(authorization.isNotBlank()) { "CRM access token is missing" }
        require(userId.isNotBlank()) { "CRM user id is missing" }

        val rows = ArrayList<CrmContactPhoneEntity>(512)
        val contacts = fetchDirectoryContacts(api, authorization, userId)
        for (contact in contacts) {
            ingestContact(contact, rows)
        }

        contactDao.clear()
        if (rows.isNotEmpty()) {
            contactDao.insertAll(rows.distinctBy { it.lookupKey })
        }
    }

    /** Single list request for the contact phone index — never [searchContacts] without a query. */
    private suspend fun fetchDirectoryContacts(
        api: CrmApiService,
        authorization: String,
        userId: String,
    ): List<CrmContact> =
        runCatching {
            api.getContacts(
                authorization = authorization,
                userId = userId,
                limit = DIRECTORY_FETCH_LIMIT,
            ).contacts
        }.getOrDefault(emptyList())

    /** Fast local match from [crm_contact_phone] (no network). */
    fun lookupContactLocal(
        index: Map<String, CrmContactPhoneEntity>,
        phoneRaw: String?,
        phoneE164: String?,
    ): CrmContactPhoneEntity? {
        for (k in PhoneUtils.lookupKeys(phoneRaw) + PhoneUtils.lookupKeys(phoneE164)) {
            index[k]?.let { return it }
        }
        return null
    }

    /**
     * Optional single-query phone lookup (background sync only — never call per UI row).
     */
    suspend fun searchContactByPhone(
        api: CrmApiService,
        authorization: String,
        userId: String,
        phoneRaw: String?,
        phoneE164: String?,
    ): CrmContact? {
        val query = bestPhoneSearchQuery(phoneE164, phoneRaw) ?: return null
        val response =
            api.searchContacts(
                authorization = authorization,
                request =
                    CrmContactSearchRequest(
                        userId = userId,
                        query = query,
                        pageLimit = 10,
                    ),
            )
        return response.contacts.firstOrNull { contact ->
            contactMatchesPhone(contact, phoneE164, phoneRaw)
        } ?: response.contacts.firstOrNull()
    }

    fun ingestContact(contact: CrmContact, out: MutableList<CrmContactPhoneEntity>) {
        val raw = contact.phone?.trim()?.ifBlank { null } ?: return
        val keys = PhoneUtils.lookupKeys(raw)
        if (keys.isEmpty()) return
        for (k in keys) {
            out +=
                CrmContactPhoneEntity(
                    lookupKey = k,
                    contactId = contact.id,
                    displayName = contact.fullName,
                )
        }
    }

    private fun bestPhoneSearchQuery(phoneE164: String?, phoneRaw: String?): String? {
        val digits = phoneE164?.filter { it.isDigit() } ?: phoneRaw?.filter { it.isDigit() }
        if (!digits.isNullOrBlank() && digits.length >= 10) {
            return digits.takeLast(10)
        }
        return phoneE164?.trim()?.ifBlank { null } ?: phoneRaw?.trim()?.ifBlank { null }
    }

    private fun contactMatchesPhone(
        contact: CrmContact,
        phoneE164: String?,
        phoneRaw: String?,
    ): Boolean {
        val callKeys = PhoneUtils.lookupKeys(phoneRaw) + PhoneUtils.lookupKeys(phoneE164)
        if (callKeys.isEmpty()) return false
        val contactKeys = PhoneUtils.lookupKeys(contact.phone)
        return callKeys.any { it in contactKeys }
    }
}
