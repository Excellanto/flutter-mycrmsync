package com.excellanto.mycrmsync.data.model

import com.google.gson.annotations.SerializedName

data class GhlContactResponse(
    @SerializedName("contacts") val contacts: List<GhlContact> = emptyList(),
    @SerializedName("total") val total: Int? = null,
    @SerializedName("searchAfter") val searchAfter: List<String>? = null,
    @SerializedName("meta") val meta: GhlMeta? = null
)

data class GhlContact(
    val id: String,
    val email: String?,
    val phone: String?,
    @SerializedName("firstNameLowerCase") val firstNameLowerCase: String?,
    @SerializedName("lastNameLowerCase") val lastNameLowerCase: String?,
    val locationId: String?,
    @SerializedName("contactName") val contactName: String?,
    val companyName: String?,
    val tags: List<String>?,
    val type: String?,
    @SerializedName("profilePicture") val profilePicture: String? = null,
    @SerializedName("searchAfter") val searchAfter: List<String>? = null,
    @SerializedName("customFields") val customFields: List<GhlCustomField>? = null
) {
    val fullName: String
        get() {
            if (!contactName.isNullOrBlank()) {
                return contactName.split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
            }
            val first = firstNameLowerCase?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""
            val last = lastNameLowerCase?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""
            val combined = "$first $last".trim()
            return combined.ifBlank { "Unknown" }
        }

    val initials: String
        get() {
            val firstChar = firstNameLowerCase?.firstOrNull()?.uppercase() ?: ""
            val lastChar = lastNameLowerCase?.firstOrNull()?.uppercase() ?: ""
            if (firstChar.isNotEmpty() || lastChar.isNotEmpty()) {
                return "$firstChar$lastChar"
            }
            return contactName?.split(" ")
                ?.filter { it.isNotBlank() }
                ?.take(2)
                ?.map { it.first().uppercase() }
                ?.joinToString("") ?: "?"
        }
    
    val role: String
        get() = companyName ?: "No Profession Specified"
}

data class GhlCustomField(
    val id: String,
    val value: Any?
)

data class GhlMeta(
    val total: Int = 0,
    val pageLimit: Int? = null,
    val page: Int? = null,
    val searchAfter: List<String>? = null
)

data class GhlSearchRequest(
    val locationId: String,
    val pageLimit: Int = 20,
    val searchAfter: List<String>? = null,
    val query: String? = null,
    val page: Int? = null,
    val filters: List<GhlFilterGroup>? = null
)

data class GhlFilterGroup(
    val group: String = "AND",
    val filters: List<GhlFilter>
)

data class GhlFilter(
    val field: String,
    val operator: String,
    val value: Any
)

data class GhlTagResponse(
    val tags: List<GhlTag>
)

data class GhlTag(
    val id: String,
    val name: String,
    val locationId: String
)

data class GhlTagRequest(
    val tags: List<String>
)

// Notes Models
data class GhlNoteResponse(
    val notes: List<GhlNote>
)

data class GhlNote(
    val id: String,
    val body: String?,
    val contactId: String,
    val userId: String?,
    val dateAdded: String?,
    val createdAt: String?,
    val updatedAt: String?
)

data class GhlNoteRequest(
    val body: String,
    val userId: String? = null
)
