package com.excellanto.mycrmsync.data

import com.excellanto.mycrmsync.data.api.CrmApiService

/** Loads integrated users from `GET api/crm/users` for dashboard staff picker. */
object CrmUsersLoader {

    suspend fun fetchStaffOptions(
        api: CrmApiService,
        authorization: String,
        userId: String,
    ): List<Pair<String, String>> {
        val response =
            api.getUsers(
                authorization = authorization,
                userId = userId,
            )
        if (!response.success) {
            throw IllegalStateException(response.message.ifBlank { "Failed to load CRM users" })
        }
        return response.data?.users.orEmpty()
            .mapNotNull { user ->
                val id = user.displayId() ?: return@mapNotNull null
                id to user.displayName()
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
}
