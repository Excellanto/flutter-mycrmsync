package com.excellanto.mycrmsync.data.api

import com.excellanto.mycrmsync.data.model.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

interface GhlApiService {

    @Headers("Version: 2021-07-28")
    @GET("contacts/")
    suspend fun getContacts(
        @Header("Authorization") token: String,
        @Query("locationId") locationId: String,
        @Query("limit") limit: Int? = null,
        @Query("startAfterId") startAfterId: String? = null,
        @Query("startAfter") startAfter: Long? = null,
        @Query("query") query: String? = null
    ): GhlContactResponse

    @Headers("Version: 2021-07-28")
    @POST("contacts/search")
    suspend fun searchContacts(
        @Header("Authorization") token: String,
        @Body request: GhlSearchRequest
    ): GhlContactResponse

    @Headers("Version: 2021-07-28")
    @GET("users/")
    suspend fun getUsers(
        @Header("Authorization") token: String,
        @Query("locationId") locationId: String
    ): GhlUserResponse

    @Headers("Version: 2021-04-15")
    @GET("locations/{locationId}/tags")
    suspend fun getTags(
        @Header("Authorization") token: String,
        @Path("locationId") locationId: String
    ): GhlTagResponse

    @Headers("Version: 2021-07-28")
    @POST("contacts/{contactId}/tags")
    suspend fun addContactTag(
        @Header("Authorization") token: String,
        @Path("contactId") contactId: String,
        @Body request: GhlTagRequest
    ): Map<String, Any>

    @Headers("Version: 2021-07-28")
    @HTTP(method = "DELETE", path = "contacts/{contactId}/tags", hasBody = true)
    suspend fun removeContactTag(
        @Header("Authorization") token: String,
        @Path("contactId") contactId: String,
        @Body request: GhlTagRequest
    ): Map<String, Any>

    // Notes API
    @Headers("Version: 2021-07-28")
    @GET("contacts/{contactId}/notes")
    suspend fun getContactNotes(
        @Header("Authorization") token: String,
        @Path("contactId") contactId: String
    ): GhlNoteResponse

    @Headers("Version: 2021-07-28")
    @POST("contacts/{contactId}/notes")
    suspend fun createContactNote(
        @Header("Authorization") token: String,
        @Path("contactId") contactId: String,
        @Body request: GhlNoteRequest
    ): GhlNote

    @Headers("Version: 2021-07-28")
    @PATCH("contacts/{contactId}/notes/{noteId}")
    suspend fun updateContactNote(
        @Header("Authorization") token: String,
        @Path("contactId") contactId: String,
        @Path("noteId") noteId: String,
        @Body request: GhlNoteRequest
    ): GhlNote

    @Headers("Version: 2021-07-28")
    @DELETE("contacts/{contactId}/notes/{noteId}")
    suspend fun deleteContactNote(
        @Header("Authorization") token: String,
        @Path("contactId") contactId: String,
        @Path("noteId") noteId: String
    ): Map<String, Any>

    companion object {
        private const val BASE_URL = "https://services.leadconnectorhq.com/"

        fun create(): GhlApiService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(ApiHttpClients.authenticated())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GhlApiService::class.java)
        }
    }
}
