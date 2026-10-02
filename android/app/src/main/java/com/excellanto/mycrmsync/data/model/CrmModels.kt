package com.excellanto.mycrmsync.data.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class CrmContactResponse(
    @SerializedName("contacts") val contacts: List<CrmContact> = emptyList(),
    @SerializedName("meta") val meta: CrmMeta? = null,
)

data class CrmContact(
    val id: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val companyName: String? = null,
    val businessInfo: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val source: String? = null,
    val type: String? = null,
    val assignedTo: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val address: String? = null,
    val dateAdded: String? = null,
    val dateUpdated: String? = null,
    val dateOfBirth: String? = null,
    val tags: List<String>? = null,
    val country: String? = null,
    val website: String? = null,
    val timezone: String? = null,
    val profilePhoto: String? = null,
    val calls: CrmContactCallStats? = null,
) {
    val fullName: String
        get() {
            val combined = listOfNotNull(
                firstName?.trim()?.takeIf { it.isNotEmpty() },
                lastName?.trim()?.takeIf { it.isNotEmpty() },
            ).joinToString(" ")
            return combined.ifBlank { companyName?.takeIf { it.isNotBlank() } ?: "Unknown" }
        }

    val initials: String
        get() {
            val first = firstName?.trim()?.firstOrNull()?.uppercase().orEmpty()
            val last = lastName?.trim()?.firstOrNull()?.uppercase().orEmpty()
            if (first.isNotEmpty() || last.isNotEmpty()) return "$first$last"
            return fullName.split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.first().uppercase() }
                .ifBlank { "?" }
        }

    val role: String
        get() = companyName?.takeIf { it.isNotBlank() }
            ?: businessInfo?.takeIf { it.isNotBlank() }
            ?: "No Profession Specified"
}

/** Call activity stats for a CRM contact. */
data class CrmContactCallStats(
    @SerializedName("total_dialed") val totalDialed: Int = 0,
    @SerializedName("total_talk_time") val totalTalkTime: Int = 0,
    @SerializedName("total_received") val totalReceived: Int = 0,
    @SerializedName("total_notes") val totalNotes: Int = 0,
)

/** Data payload from `GET api/crm/contact`. */
data class CrmContactDetailData(
    val contact: CrmContact? = null,
    val calls: CrmContactCallStats? = null,
)

data class CrmMeta(
    val total: Int = 0,
    val page: Int? = null,
    @SerializedName("per_page") val perPage: Int? = null,
)

data class CrmContactSearchRequest(
    @SerializedName("user_id") val userId: String,
    val query: String? = null,
    val pageLimit: Int = 20,
    val page: Int? = null,
)

/** `POST api/crm/contacts/match` — name or phone in [query]. */
data class CrmContactMatchRequest(
    @SerializedName("user_id") val userId: Int,
    val query: String,
    val limit: Int = 5,
)

data class CrmTagResponse(
    val tags: List<CrmTag> = emptyList(),
    val meta: CrmMeta? = null,
)

data class CrmTag(
    val id: String = "",
    val name: String,
)

data class CrmContactTagsRequest(
    @SerializedName("user_id") val userId: String,
    @SerializedName("contactid") val contactId: String,
    val tags: List<String>,
)

data class CrmContactTagsResponse(
    val tags: List<String> = emptyList(),
    val message: String? = null,
    val status: Boolean? = null,
)

data class CrmAddContactRequest(
    @SerializedName("user_id") val userId: Int,
    val firstName: String,
    val lastName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val companyName: String? = null,
    val address: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val tags: List<String> = emptyList(),
)

data class CrmAddContactData(
    val contact: CrmContact? = null,
)

data class CrmUpdateContactRequest(
    @SerializedName("user_id") val userId: Int,
    @SerializedName(value = "contactid", alternate = ["contactId"]) val contactId: String,
    val firstName: String,
    val lastName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val companyName: String? = null,
    val address: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val tags: List<String> = emptyList(),
)

data class CrmDeleteContactRequest(
    @SerializedName("user_id") val userId: Int,
    @SerializedName(value = "contactid", alternate = ["contactId"]) val contactId: String,
)

/** Response from add / update / delete contact endpoints. */
data class CrmContactMutationResponse(
    val success: Boolean? = null,
    val status: Boolean? = null,
    val message: String? = null,
    val contact: CrmContact? = null,
    val data: CrmAddContactData? = null,
) {
    fun isSuccessful(): Boolean =
        success == true ||
            status == true ||
            contact != null ||
            data?.contact != null

    fun displayMessage(fallback: String): String =
        message?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}

data class CrmNoteResponse(
    val success: Boolean? = null,
    val status: Boolean? = null,
    val notes: List<CrmNote>? = null,
    val meta: CrmMeta? = null,
)

data class CrmNoteAttachment(
    val filetype: String? = null,
    @SerializedName("fileshorturl") val fileShortUrl: String? = null,
    @SerializedName("fileslongurl") val fileLongUrl: String? = null,
    @SerializedName("voice_note_id") val voiceNoteId: String? = null,
) {
    fun playbackUrl(): String =
        fileLongUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: fileShortUrl?.trim().orEmpty()

    fun linkUrl(): String =
        fileShortUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: fileLongUrl?.trim().orEmpty()
}

data class CrmNote(
    val id: String,
    val body: String? = null,
    val attachments: List<CrmNoteAttachment>? = null,
    val summary: String? = null,
    val transcription: String? = null,
    val title: String? = null,
    @SerializedName("user_name") val userName: String? = null,
    val userId: String? = null,
    val contactId: String,
    @SerializedName("voice_note_ids") val voiceNoteIds: List<String>? = null,
    val dateAdded: String? = null,
    val dateUpdated: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

data class CrmCreateNoteRequest(
    @SerializedName("user_id") val userId: String,
    @SerializedName("contactId") val contactId: String,
    val body: String,
    val urls: List<String> = emptyList(),
)

data class CrmUpdateNoteRequest(
    @SerializedName("user_id") val userId: String,
    @SerializedName("contactId") val contactId: String,
    @SerializedName("noteId") val noteId: String,
    val body: String,
    val urls: List<String>,
    @SerializedName("existing_note_text") val existingNoteText: String,
    @SerializedName("voice_note_ids") val voiceNoteIds: List<String>,
)

data class CrmDeleteNoteRequest(
    @SerializedName("user_id") val userId: String,
    @SerializedName("contactId") val contactId: String,
    @SerializedName("noteId") val noteId: String,
)

/** Contact row from `GET api/crm/contacts/notes/summaries-by-phone`. */
data class CrmNoteSummaryContact(
    val id: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
)

/** Single note summary from `GET api/crm/contacts/notes/summaries-by-phone`. */
data class CrmNoteSummaryItem(
    val id: String,
    val summary: String,
    val date: String? = null,
    val time: String? = null,
    val datetime: String? = null,
)

/** Data payload from `GET api/crm/contacts/notes/summaries-by-phone`. */
data class CrmContactNoteSummariesData(
    val contact: CrmNoteSummaryContact? = null,
    val summaries: List<CrmNoteSummaryItem> = emptyList(),
    val meta: CrmMeta? = null,
)

data class VoiceNoteProcessData(
    @SerializedName("voice_note_id") val voiceNoteId: String,
    @SerializedName("recording_url") val recordingUrl: String,
    @SerializedName("recording_url_long") val recordingUrlLong: String,
    @SerializedName("file_name") val fileName: String,
    @SerializedName("transcription_backend") val transcriptionBackend: String? = null,
    @SerializedName("transcription_engine_label") val transcriptionEngineLabel: String? = null,
    val transcription: String,
    val summary: String,
    @SerializedName("note_body") val noteBody: String,
    @SerializedName("duration_sec") val durationSec: Int? = null,
    @SerializedName("crm_note") val crmNote: JsonElement? = null,
)

/** Data payload from `POST api/crm/business-cards/parse`. */
data class BusinessCardParseData(
    @SerializedName("first_name") val firstName: String? = null,
    @SerializedName("last_name") val lastName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    @SerializedName("company_name") val companyName: String? = null,
    @SerializedName("job_title") val jobTitle: String? = null,
    val address: String? = null,
    val city: String? = null,
    val state: String? = null,
    @SerializedName("postal_code") val postalCode: String? = null,
    val country: String? = null,
    val website: String? = null,
)

data class ContactFileProcessData(
    @SerializedName("voice_note_id") val voiceNoteId: String,
    @SerializedName("file_url") val fileUrl: String? = null,
    @SerializedName("fileshorturl") val fileShortUrl: String? = null,
    @SerializedName("fileslongurl") val fileLongUrl: String? = null,
    @SerializedName("recording_url") val recordingUrl: String? = null,
    @SerializedName("recording_url_long") val recordingUrlLong: String? = null,
    @SerializedName("file_name") val fileName: String? = null,
    @SerializedName("file_type") val fileType: String? = null,
    @SerializedName("mime_type") val mimeType: String? = null,
) {
    fun previewUrl(): String =
        fileLongUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: fileShortUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: fileUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: recordingUrlLong?.trim()?.takeIf { it.isNotEmpty() }
            ?: recordingUrl?.trim().orEmpty()
}

data class MediaProcessBatchRequest(
    @SerializedName("user_id") val userId: String,
    @SerializedName("contact_id") val contactId: String,
    @SerializedName("existing_note_text") val existingNoteText: String? = null,
    @SerializedName("voice_note_ids") val voiceNoteIds: List<String>,
)

data class RegisterCallLogRequest(
    @SerializedName("user_id") val userId: Int,
    val direction: String,
    @SerializedName("phone_raw") val phoneRaw: String? = null,
    @SerializedName("phone_e164") val phoneE164: String? = null,
    @SerializedName("contact_id") val contactId: String? = null,
    @SerializedName("contact_name") val contactName: String? = null,
    @SerializedName("duration_sec") val durationSec: Int? = null,
    @SerializedName("started_at") val startedAt: String? = null,
    @SerializedName("ended_at") val endedAt: String? = null,
    @SerializedName("sim_account_id") val simAccountId: String? = null,
    @SerializedName("sync_fingerprint") val syncFingerprint: String? = null,
    val status: String? = null,
)

data class RegisterCallLogData(
    val id: String? = null,
    @SerializedName("user_id") val userId: Int? = null,
    @SerializedName("sync_fingerprint") val syncFingerprint: String? = null,
    val status: String? = null,
    val created: Boolean? = null,
)

/** Single row from `POST api/crm/call-logs/get`. */
data class CrmCallLogItem(
    val id: String? = null,
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("user_name") val userName: String? = null,
    @SerializedName("location_id") val locationId: String? = null,
    val direction: String? = null,
    @SerializedName("phone_raw") val phoneRaw: String? = null,
    @SerializedName("phone_e164") val phoneE164: String? = null,
    @SerializedName("contact_id") val contactId: String? = null,
    @SerializedName("contact_name") val contactName: String? = null,
    @SerializedName("duration_sec") val durationSec: Int? = null,
    @SerializedName("started_at") val startedAt: String? = null,
    @SerializedName("ended_at") val endedAt: String? = null,
    @SerializedName("sim_account_id") val simAccountId: String? = null,
    @SerializedName("sync_fingerprint") val syncFingerprint: String? = null,
    @SerializedName("ghl_note_id") val ghlNoteId: String? = null,
    val status: String? = null,
    val error: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
)

/** Request body for `POST api/crm/call-logs/get`. */
data class CrmCallLogsGetRequest(
    @SerializedName("user_id") val userId: Int,
    val mine: Boolean,
    val limit: Int? = null,
    val cursor: String? = null,
)

data class CrmCallLogsGetData(
    val items: List<CrmCallLogItem> = emptyList(),
    @SerializedName("next_cursor") val nextCursor: String? = null,
)

/** Tenant user row from `GET api/crm/users`. */
data class CrmUser(
    val id: Int? = null,
    @SerializedName("user_id") val userId: String? = null,
    val name: String? = null,
    val email: String? = null,
    @SerializedName("userrole") val userRole: String? = null,
) {
    fun displayId(): String? =
        id?.takeIf { it > 0 }?.toString()
            ?: userId?.trim()?.takeIf { it.isNotEmpty() }

    fun displayName(): String =
        name?.trim()?.takeIf { it.isNotEmpty() }
            ?: email?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Unknown"
}

data class CrmUsersListData(
    val users: List<CrmUser> = emptyList(),
)

/** Participant sentiment from `POST api/crm/call-recordings/transcribe`. */
data class CallRecordingSentimentParticipant(
    val label: String? = null,
    val score: Double? = null,
)

/** Sentiment block from call recording transcription. */
data class CallRecordingSentiment(
    val overall: String? = null,
    val score: Double? = null,
    val confidence: Double? = null,
    val customer: CallRecordingSentimentParticipant? = null,
    val agent: CallRecordingSentimentParticipant? = null,
    val highlights: List<String>? = null,
)

/** Data payload from `POST api/crm/call-recordings/transcribe`. */
data class CallRecordingTranscribeData(
    @SerializedName("call_recording_id") val callRecordingId: String? = null,
    @SerializedName("call_log_id") val callLogId: String? = null,
    @SerializedName("contact_id") val contactId: String? = null,
    @SerializedName("duration_sec") val durationSec: Int? = null,
    val transcription: String,
    val summary: String? = null,
    val sentiment: CallRecordingSentiment? = null,
    @SerializedName("transcription_backend") val transcriptionBackend: String? = null,
    @SerializedName("transcription_engine_label") val transcriptionEngineLabel: String? = null,
    @SerializedName("recording_url") val recordingUrl: String? = null,
    @SerializedName("recording_url_long") val recordingUrlLong: String? = null,
    @SerializedName("file_name") val fileName: String? = null,
    val filetype: String? = null,
    @SerializedName("mime_type") val mimeType: String? = null,
    val status: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
)
