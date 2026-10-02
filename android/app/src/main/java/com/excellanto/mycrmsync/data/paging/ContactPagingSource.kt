package com.excellanto.mycrmsync.data.paging

import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.gson.JsonParser
import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.data.model.CrmContact
import com.excellanto.mycrmsync.data.model.CrmContactSearchRequest
import retrofit2.HttpException

enum class ContactReachedFilter {
    ZERO_CALLS_MADE,
    AT_LEAST_ONE_CALL_RECEIVED,
}

class ContactPagingSource(
    private val crmApiService: CrmApiService,
    private val authorization: String,
    private val userId: String,
    private val pageSize: Int,
    private val query: String? = null,
    private val tag: String? = null,
    private val contactReached: ContactReachedFilter? = null,
    private val onTotalLoaded: (Int) -> Unit = {},
) : PagingSource<Int, CrmContact>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CrmContact> {
        return try {
            require(authorization.isNotBlank()) { "CRM access token is missing. Please log in again." }
            require(userId.isNotBlank()) { "CRM user id is missing. Please log in again." }

            val page = params.key ?: 1
            val pageLimit = pageSize.coerceIn(1, 100)
            val trimmedQuery = query?.trim()?.takeIf { it.isNotEmpty() }

            Log.d(
                "CRM_API_DEBUG",
                "REQUEST: ${if (trimmedQuery == null) "GET /api/crm/contacts" else "POST /api/crm/contacts/search"} " +
                    "user_id=$userId page=$page limit=$pageLimit query=$trimmedQuery tag=$tag contactReached=$contactReached",
            )

            val response = if (trimmedQuery == null) {
                crmApiService.getContacts(
                    authorization = authorization,
                    userId = userId,
                    limit = pageLimit,
                    page = page,
                )
            } else {
                crmApiService.searchContacts(
                    authorization = authorization,
                    request = CrmContactSearchRequest(
                        userId = userId,
                        query = trimmedQuery,
                        pageLimit = pageLimit,
                        page = page,
                    ),
                )
            }

            val filteredContacts = response.contacts
                .filter { contact -> tag == null || contact.tags.orEmpty().any { it.equals(tag, true) } }
                .filter { contact -> matchesContactReached(contact, contactReached) }

            val total = response.meta?.total ?: filteredContacts.size
            if (page == 1) {
                onTotalLoaded(total)
            }

            val responsePage = response.meta?.page ?: page
            val perPage = response.meta?.perPage ?: pageLimit
            val hasMore = response.contacts.isNotEmpty() &&
                if (total > 0) {
                    responsePage * perPage < total
                } else {
                    response.contacts.size >= pageLimit
                }

            Log.d(
                "CRM_API_DEBUG",
                "RESPONSE: page=$responsePage contactsCount=${filteredContacts.size}, total=$total, hasMore=$hasMore",
            )

            LoadResult.Page(
                data = filteredContacts,
                prevKey = if (page == 1) null else page - 1,
                nextKey = if (hasMore) page + 1 else null,
            )
        } catch (e: Exception) {
            val error = Exception(e.toUserMessage(), e)
            Log.e("CRM_API_DEBUG", "ERROR: Failed to load CRM contacts", error)
            LoadResult.Error(error)
        }
    }

    private fun matchesContactReached(
        contact: CrmContact,
        filter: ContactReachedFilter?,
    ): Boolean {
        if (filter == null) return true
        val stats = contact.calls
        return when (filter) {
            ContactReachedFilter.ZERO_CALLS_MADE ->
                (stats?.totalDialed ?: 0) == 0
            ContactReachedFilter.AT_LEAST_ONE_CALL_RECEIVED ->
                (stats?.totalReceived ?: 0) >= 1
        }
    }

    override fun getRefreshKey(state: PagingState<Int, CrmContact>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }

    private fun Exception.toUserMessage(): String {
        if (this !is HttpException) {
            return localizedMessage ?: "Failed to load CRM contacts"
        }

        val body = response()?.errorBody()?.string().orEmpty()
        val message = runCatching {
            JsonParser.parseString(body)
                .asJsonObject
                .get("message")
                ?.asString
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return message ?: "CRM contacts request failed (HTTP ${code()})."
    }
}
