package com.excellanto.mycrmsync.data.api

import com.excellanto.mycrmsync.data.Config
import com.excellanto.mycrmsync.data.model.CrmAddContactRequest
import com.excellanto.mycrmsync.data.model.CrmContactMutationResponse
import com.excellanto.mycrmsync.data.model.CrmDeleteContactRequest
import com.excellanto.mycrmsync.data.model.CrmUpdateContactRequest
import com.excellanto.mycrmsync.data.model.CrmContactDetailData
import com.excellanto.mycrmsync.data.model.CrmContactTagsRequest
import com.excellanto.mycrmsync.data.model.CrmContactTagsResponse
import com.excellanto.mycrmsync.data.model.CrmContactResponse
import com.excellanto.mycrmsync.data.model.CrmContactMatchRequest
import com.excellanto.mycrmsync.data.model.CrmContactSearchRequest
import com.excellanto.mycrmsync.data.model.CrmCreateNoteRequest
import com.excellanto.mycrmsync.data.model.CrmDeleteNoteRequest
import com.excellanto.mycrmsync.data.model.CrmUpdateNoteRequest
import com.excellanto.mycrmsync.data.model.CrmCallLogsGetData
import com.excellanto.mycrmsync.data.model.CrmCallLogsGetRequest
import com.excellanto.mycrmsync.data.model.RegisterCallLogData
import com.excellanto.mycrmsync.data.model.RegisterCallLogRequest
import com.excellanto.mycrmsync.data.model.AuthApiResponse
import com.excellanto.mycrmsync.data.model.CrmTagResponse
import com.excellanto.mycrmsync.data.model.CrmUsersListData
import com.excellanto.mycrmsync.data.model.BusinessCardParseData
import com.excellanto.mycrmsync.data.model.CallRecordingTranscribeData
import com.excellanto.mycrmsync.data.model.ContactFileProcessData
import com.excellanto.mycrmsync.data.model.MediaProcessBatchRequest
import com.excellanto.mycrmsync.data.model.VoiceNoteProcessData
import com.google.gson.JsonElement
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

interface CrmApiService {

    @GET("api/crm/contacts")
    suspend fun getContacts(
        @Header("Authorization") authorization: String,
        @Query("user_id") userId: String,
        @Query("limit") limit: Int,
        @Query("page") page: Int? = null,
    ): CrmContactResponse

    @POST("api/crm/contacts/search")
    suspend fun searchContacts(
        @Header("Authorization") authorization: String,
        @Body request: CrmContactSearchRequest,
    ): CrmContactResponse

    @POST("api/crm/contacts/match")
    suspend fun matchContacts(
        @Header("Authorization") authorization: String,
        @Body request: CrmContactMatchRequest,
    ): JsonElement

    @GET("api/crm/contact")
    suspend fun getContact(
        @Header("Authorization") authorization: String,
        @Query("user_id") userId: String,
        @Query("contactId") contactId: String,
    ): AuthApiResponse<CrmContactDetailData>

    @GET("api/crm/tags")
    suspend fun getTags(
        @Header("Authorization") authorization: String,
        @Query("user_id") userId: String,
    ): CrmTagResponse

    @GET("api/crm/users")
    suspend fun getUsers(
        @Header("Authorization") authorization: String,
        @Query("user_id") userId: String,
    ): AuthApiResponse<CrmUsersListData>

    @POST("api/crm/contacts/add/tags")
    suspend fun addOrUpdateContactTags(
        @Header("Authorization") authorization: String,
        @Body request: CrmContactTagsRequest,
    ): CrmContactTagsResponse

    @POST("api/crm/contacts/add")
    suspend fun addContact(
        @Header("Authorization") authorization: String,
        @Body request: CrmAddContactRequest,
    ): CrmContactMutationResponse

    @POST("api/crm/contacts/update")
    suspend fun updateContact(
        @Header("Authorization") authorization: String,
        @Body request: CrmUpdateContactRequest,
    ): CrmContactMutationResponse

    @POST("api/crm/contacts/delete")
    suspend fun deleteContact(
        @Header("Authorization") authorization: String,
        @Body request: CrmDeleteContactRequest,
    ): CrmContactMutationResponse

    @GET("api/crm/contacts/notes/list")
    suspend fun getContactNotes(
        @Header("Authorization") authorization: String,
        @Query("user_id") userId: String,
        @Query("contactId") contactId: String,
        @Query("contact_id") contactIdSnake: String,
    ): JsonElement

    @GET("api/crm/contacts/notes/summaries-by-phone")
    suspend fun getContactNoteSummariesByPhone(
        @Header("Authorization") authorization: String,
        @Query("user_id") userId: String,
        @Query("phone") phone: String,
    ): JsonElement

    @POST("api/crm/contacts/notes/add")
    suspend fun createContactNote(
        @Header("Authorization") authorization: String,
        @Body request: CrmCreateNoteRequest,
    ): JsonElement

    @POST("api/crm/contacts/notes/update")
    suspend fun updateContactNote(
        @Header("Authorization") authorization: String,
        @Body request: CrmUpdateNoteRequest,
    ): JsonElement

    @POST("api/crm/contacts/notes/delete")
    suspend fun deleteContactNote(
        @Header("Authorization") authorization: String,
        @Body request: CrmDeleteNoteRequest,
    ): JsonElement

    @POST("api/crm/call-logs/get")
    suspend fun getCallLogs(
        @Header("Authorization") authorization: String,
        @Body request: CrmCallLogsGetRequest,
    ): AuthApiResponse<CrmCallLogsGetData>

    @POST("api/crm/call-logs/register")
    suspend fun registerCallLog(
        @Body request: RegisterCallLogRequest,
    ): AuthApiResponse<RegisterCallLogData>

    @Multipart
    @POST("api/crm/voice-notes/process")
    suspend fun processVoiceNote(
        @Header("Authorization") authorization: String,
        @Part("contact_id") contactId: RequestBody,
        @Part("user_id") userId: RequestBody,
        @Part audio: MultipartBody.Part,
        @Part("existing_note_text") existingNoteText: RequestBody? = null,
    ): AuthApiResponse<VoiceNoteProcessData>

    @Multipart
    @POST("api/crm/contact-files/process")
    suspend fun processContactFile(
        @Header("Authorization") authorization: String,
        @Part("contact_id") contactId: RequestBody,
        @Part("user_id") userId: RequestBody,
        @Part file: MultipartBody.Part,
    ): AuthApiResponse<ContactFileProcessData>

    @Multipart
    @POST("api/crm/business-cards/parse")
    suspend fun parseBusinessCard(
        @Header("Authorization") authorization: String,
        @Part("user_id") userId: RequestBody,
        @Part image: MultipartBody.Part,
        @Part("locale") locale: RequestBody? = null,
    ): AuthApiResponse<BusinessCardParseData>

    @POST("api/crm/media/process-batch")
    suspend fun processMediaBatch(
        @Header("Authorization") authorization: String,
        @Body request: MediaProcessBatchRequest,
    ): JsonElement

    @Multipart
    @POST("api/crm/call-recordings/transcribe")
    suspend fun transcribeCallRecording(
        @Header("Authorization") authorization: String,
        @Part audio: MultipartBody.Part,
        @Part("call_log_id") callLogId: RequestBody,
        @Part("contact_id") contactId: RequestBody,
        @Part("language") language: RequestBody,
        @Part("store_recording") storeRecording: RequestBody,
        @Part("user_id") userId: RequestBody,
    ): AuthApiResponse<CallRecordingTranscribeData>

    companion object {
        fun create(): CrmApiService {
            return Retrofit.Builder()
                .baseUrl(Config.AUTH_API_BASE_URL)
                .client(ApiHttpClients.authenticated())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(CrmApiService::class.java)
        }
    }
}
