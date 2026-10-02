package com.excellanto.mycrmsync.data

import android.content.Context
import android.util.Log
import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.data.local.CrmContactPhoneEntity
import com.excellanto.mycrmsync.data.local.CrmDatabase
import com.excellanto.mycrmsync.data.model.CrmContactNoteSummariesData
import com.excellanto.mycrmsync.data.model.CrmNote
import com.excellanto.mycrmsync.data.model.CrmNoteSummaryContact
import com.excellanto.mycrmsync.data.model.CrmNoteSummaryItem
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

sealed class ContactNoteSummariesResult {
    data object Skipped : ContactNoteSummariesResult()

    data class Success(val data: CrmContactNoteSummariesData) : ContactNoteSummariesResult()

    data class Error(val message: String) : ContactNoteSummariesResult()
}

object ContactNoteSummariesLoader {
    private const val TAG = "ContactNoteSummaries"
    private const val MAX_PREVIOUS_NOTES = 12

    suspend fun load(
        context: Context,
        phoneRaw: String?,
        contactId: String? = null,
        contactName: String? = null,
    ): ContactNoteSummariesResult {
        val knownContactId = contactId?.trim()?.ifBlank { null }
        val knownName = contactName?.trim()?.ifBlank { null }
        val phone =
            PhoneUtils.normalizeToE164(phoneRaw)
                ?: phoneRaw?.trim()?.takeIf { it.isNotBlank() }

        if (phone == null && knownContactId == null) return ContactNoteSummariesResult.Skipped

        val prefs = PrefsManager(context.applicationContext)
        if (!prefs.isLoggedIn()) return ContactNoteSummariesResult.Skipped

        val authorization =
            prefs.getBearerAuthorization()
                ?: return ContactNoteSummariesResult.Skipped
        val userId =
            prefs.getAuthUserId() ?: prefs.getUserId()
                ?: return ContactNoteSummariesResult.Skipped

        val local = lookupLocalContact(context, phoneRaw, phone)
        val localName = local?.displayName?.trim()?.ifBlank { null }

        var phoneData: CrmContactNoteSummariesData? = null
        for (candidate in phoneCandidates(phone, phoneRaw)) {
            when (val fetched = fetchByPhone(authorization, userId, candidate)) {
                is ContactNoteSummariesResult.Success -> {
                    phoneData = fetched.data
                    if (fetched.data.contact != null) break
                }
                is ContactNoteSummariesResult.Error -> Log.w(TAG, fetched.message)
                ContactNoteSummariesResult.Skipped -> Unit
            }
        }

        val apiContact = phoneData?.contact
        var matchedId = knownContactId ?: apiContact?.id?.trim()?.ifBlank { null }
        var matchedName =
            knownName
                ?: apiContact?.name?.trim()?.ifBlank { null }
                ?: localName
        var matchedPhone = apiContact?.phone?.trim()?.ifBlank { null } ?: phone

        if (matchedId == null && phone != null) {
            val searched =
                runCatching {
                    CrmContactDirectorySync.searchContactByPhone(
                        api = CrmApiService.create(),
                        authorization = authorization,
                        userId = userId,
                        phoneRaw = phoneRaw,
                        phoneE164 = phone,
                    )
                }.getOrNull()
            if (searched != null && phonesMatch(searched.phone, phoneRaw, phone)) {
                matchedId = searched.id
                matchedName = searched.fullName
                matchedPhone = searched.phone?.trim()?.ifBlank { null } ?: phone
            }
        }

        if (matchedId == null) {
            Log.i(TAG, "No CRM contact for ${phoneRaw ?: phone} — notes popup will not open")
            return ContactNoteSummariesResult.Skipped
        }

        val summaries =
            if (phoneData != null && phoneData.summaries.isNotEmpty() && apiContact != null) {
                phoneData.summaries
            } else {
                Log.i(TAG, "CRM contact $matchedId matched — loading previous notes")
                fetchPreviousNotes(authorization, userId, matchedId).ifEmpty {
                    phoneData?.summaries.orEmpty()
                }
            }
        return ContactNoteSummariesResult.Success(
            CrmContactNoteSummariesData(
                contact =
                    syntheticContact(
                        id = matchedId,
                        name = matchedName ?: localName,
                        phone = matchedPhone,
                    ) ?: apiContact,
                summaries = summaries,
            ),
        )
    }

    private fun phonesMatch(contactPhone: String?, phoneRaw: String?, phoneE164: String?): Boolean {
        val callKeys = PhoneUtils.lookupKeys(phoneRaw) + PhoneUtils.lookupKeys(phoneE164)
        if (callKeys.isEmpty()) return false
        val contactKeys = PhoneUtils.lookupKeys(contactPhone)
        return callKeys.any { it in contactKeys }
    }

    private fun phoneCandidates(phone: String?, phoneRaw: String?): List<String> {
        val candidates = LinkedHashSet<String>()
        phone?.trim()?.takeIf { it.isNotBlank() }?.let { candidates += it }
        phoneRaw?.trim()?.takeIf { it.isNotBlank() }?.let { candidates += it }
        val digits = phoneRaw?.filter { it.isDigit() }.orEmpty()
        if (digits.length >= 10) candidates += digits.takeLast(10)
        return candidates.toList()
    }

    private suspend fun lookupLocalContact(
        context: Context,
        phoneRaw: String?,
        phone: String?,
    ): CrmContactPhoneEntity? {
        return try {
            val rows = CrmDatabase.getInstance(context).crmContactPhoneDao().listAll()
            if (rows.isEmpty()) return null
            val index = rows.associateBy { it.lookupKey }
            CrmContactDirectorySync.lookupContactLocal(index, phoneRaw, phone)
        } catch (e: Exception) {
            Log.w(TAG, "Local contact lookup failed", e)
            null
        }
    }

    private fun syntheticContact(
        id: String?,
        name: String?,
        phone: String?,
    ): CrmNoteSummaryContact? {
        val contactId = id?.trim()?.ifBlank { null } ?: return null
        return CrmNoteSummaryContact(
            id = contactId,
            name = name?.trim()?.ifBlank { null } ?: phone.orEmpty(),
            phone = phone,
        )
    }

    private suspend fun fetchByPhone(
        authorization: String,
        userId: String,
        phone: String,
    ): ContactNoteSummariesResult {
        return try {
            val response =
                CrmApiService.create().getContactNoteSummariesByPhone(
                    authorization = authorization,
                    userId = userId,
                    phone = phone,
                )
            val parsed = parseSummariesPayload(response)
            if (parsed != null) {
                ContactNoteSummariesResult.Success(parsed)
            } else {
                ContactNoteSummariesResult.Error(errorMessage(response) ?: "Failed to load note summaries")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Summaries lookup failed for $phone", e)
            ContactNoteSummariesResult.Error(e.message ?: "Failed to load note summaries")
        }
    }

    private fun parseSummariesPayload(element: JsonElement): CrmContactNoteSummariesData? {
        if (!element.isJsonObject) return null
        val root = element.asJsonObject
        if (isFailure(root)) return null
        val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: root
        val contact = parseContact(data.get("contact") ?: root.get("contact"))
        val summaries =
            summaryItems(findArray(data, "summaries", "notes", "items"))
                .ifEmpty { summaryItems(findArray(root, "summaries", "notes", "items")) }
        if (contact == null && summaries.isEmpty() && root.get("data") == null && root.get("contact") == null) {
            return null
        }
        return CrmContactNoteSummariesData(contact = contact, summaries = summaries)
    }

    private fun parseContact(element: JsonElement?): CrmNoteSummaryContact? {
        if (element == null || element.isJsonNull || !element.isJsonObject) return null
        val obj = element.asJsonObject
        val id = firstString(obj, "id", "contactId", "contactid", "contact_id") ?: return null
        val first = firstString(obj, "firstName", "first_name")
        val last = firstString(obj, "lastName", "last_name")
        val combined = listOfNotNull(first, last).joinToString(" ").ifBlank { null }
        val name =
            firstString(obj, "fullName", "full_name", "name", "contactName", "contact_name", "displayName")
                ?: combined
                ?: ""
        return CrmNoteSummaryContact(
            id = id,
            name = name,
            phone = firstString(obj, "phone", "phoneNumber", "phone_number"),
            email = firstString(obj, "email"),
        )
    }

    private fun summaryItems(array: JsonArray?): List<CrmNoteSummaryItem> {
        if (array == null) return emptyList()
        return array.mapIndexedNotNull { index, element ->
            if (!element.isJsonObject) return@mapIndexedNotNull null
            val obj = element.asJsonObject
            val text =
                firstString(obj, "summary", "body", "note", "content", "text", "intro", "introText")
                    ?: return@mapIndexedNotNull null
            val id = firstString(obj, "id", "noteId", "note_id") ?: "summary_$index"
            val readable =
                TimelineNoteParser.parse(
                    CrmNote(id = id, body = text, summary = firstString(obj, "summary"), contactId = "preview"),
                ).let { parsed ->
                    parsed.introText.trim().ifBlank { null }
                        ?: parsed.summary?.trim()?.ifBlank { null }
                        ?: text
                }
            CrmNoteSummaryItem(
                id = id,
                summary = readable,
                date = firstString(obj, "date"),
                time = firstString(obj, "time"),
                datetime = firstString(obj, "datetime", "createdAt", "created_at", "dateAdded", "date_added"),
            )
        }
    }

    private fun findArray(obj: JsonObject, vararg keys: String): JsonArray? {
        for (key in keys) {
            val value = obj.get(key) ?: continue
            if (value.isJsonArray) return value.asJsonArray
            if (value.isJsonObject) {
                val nested = value.asJsonObject.get("data")
                if (nested != null && nested.isJsonArray) return nested.asJsonArray
            }
        }
        val data = obj.get("data")
        if (data != null && data.isJsonArray) return data.asJsonArray
        return null
    }

    private fun isFailure(obj: JsonObject): Boolean {
        val success = obj.get("success") ?: obj.get("status") ?: return false
        if (!success.isJsonPrimitive) return false
        val primitive = success.asJsonPrimitive
        return when {
            primitive.isBoolean -> !primitive.asBoolean
            primitive.isNumber -> primitive.asInt == 0
            primitive.isString -> primitive.asString.equals("false", ignoreCase = true)
            else -> false
        }
    }

    private fun errorMessage(element: JsonElement): String? {
        if (!element.isJsonObject) return null
        return firstString(element.asJsonObject, "message", "error")
    }

    private suspend fun fetchPreviousNotes(
        authorization: String,
        userId: String,
        contactId: String,
    ): List<CrmNoteSummaryItem> {
        return try {
            val response =
                CrmApiService.create().getContactNotes(
                    authorization = authorization,
                    userId = userId,
                    contactId = contactId,
                    contactIdSnake = contactId,
                )
            parseNotes(response, contactId)
                .sortedByDescending { note ->
                    note.createdAt?.takeIf { it.isNotBlank() }
                        ?: note.dateAdded?.takeIf { it.isNotBlank() }
                        ?: ""
                }
                .mapNotNull { note -> toSummaryItem(note) }
                .take(MAX_PREVIOUS_NOTES)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load notes for contact $contactId", e)
            emptyList()
        }
    }

    private fun toSummaryItem(note: CrmNote): CrmNoteSummaryItem? {
        val parsed = TimelineNoteParser.parse(note)
        val text =
            parsed.introText.trim().ifBlank { null }
                ?: parsed.summary?.trim()?.ifBlank { null }
                ?: note.body?.trim()?.ifBlank { null }
                ?: return null
        val whenText =
            note.createdAt?.trim()?.ifBlank { null }
                ?: note.dateAdded?.trim()?.ifBlank { null }
                ?: note.dateUpdated?.trim()?.ifBlank { null }
        return CrmNoteSummaryItem(
            id = note.id,
            summary = text,
            datetime = whenText,
        )
    }

    private fun parseNotes(response: JsonElement, fallbackContactId: String): List<CrmNote> {
        if (response.isJsonNull) return emptyList()
        val notesElement =
            when {
                response.isJsonArray -> response
                response.isJsonObject -> {
                    val obj = response.asJsonObject
                    findArray(obj, "notes", "items", "summaries")
                }
                else -> null
            }
        if (notesElement == null || notesElement.isJsonNull || !notesElement.isJsonArray) {
            return emptyList()
        }
        return notesElement.asJsonArray.mapIndexedNotNull { index, element ->
            if (!element.isJsonObject) return@mapIndexedNotNull null
            val obj = element.asJsonObject
            CrmNote(
                id = firstString(obj, "id", "noteId", "note_id") ?: "note_$index",
                body = firstString(obj, "body", "note", "content", "text"),
                summary = firstString(obj, "summary"),
                contactId = firstString(obj, "contactId", "contact_id") ?: fallbackContactId,
                dateAdded = firstString(obj, "dateAdded", "date_added"),
                dateUpdated = firstString(obj, "dateUpdated", "date_updated"),
                createdAt = firstString(obj, "createdAt", "created_at"),
                updatedAt = firstString(obj, "updatedAt", "updated_at"),
            )
        }
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.get(key) ?: continue
            if (value.isJsonNull || !value.isJsonPrimitive) continue
            val primitive = value.asJsonPrimitive
            val text =
                when {
                    primitive.isString -> primitive.asString
                    primitive.isNumber -> primitive.asNumber.toString()
                    else -> continue
                }.trim()
            if (text.isNotEmpty() && text != "null") return text
        }
        return null
    }
}
