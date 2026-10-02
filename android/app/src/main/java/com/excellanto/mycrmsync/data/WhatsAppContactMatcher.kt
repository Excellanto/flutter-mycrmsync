package com.excellanto.mycrmsync.data

import android.content.Context
import android.util.Log
import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.data.model.CrmContactMatchRequest

data class WhatsAppContactMatchOutcome(
    val query: String,
    val contacts: List<WhatsAppCrmMatch>,
    val signedIn: Boolean,
)

class WhatsAppContactMatcher(
    context: Context,
    private val api: CrmApiService = CrmApiService.create(),
) {
    private val prefs = PrefsManager(context.applicationContext)

    suspend fun match(displayName: String?, phoneRaw: String?): WhatsAppContactMatchOutcome {
        val built =
            WhatsAppContactMatchQuery.build(displayName, phoneRaw)
                ?: run {
                    console("Contact match API not called — no usable name or number")
                    return WhatsAppContactMatchOutcome(query = "", contacts = emptyList(), signedIn = true)
                }
        val authorization = prefs.getBearerAuthorization()
        val userId = prefs.getRegisterUserId()
        if (authorization.isNullOrBlank() || userId == null) {
            console("Contact match API not called — not signed in")
            return WhatsAppContactMatchOutcome(query = built.primary, contacts = emptyList(), signedIn = false)
        }

        val primary = request(authorization, userId, built.primary)
        if (primary.isNotEmpty() || built.fallback.isNullOrBlank()) {
            return WhatsAppContactMatchOutcome(query = built.primary, contacts = primary, signedIn = true)
        }
        console("Name match empty, retrying contact match API with number")
        val fallback = request(authorization, userId, built.fallback)
        return WhatsAppContactMatchOutcome(
            query = built.fallback,
            contacts = fallback,
            signedIn = true,
        )
    }

    private suspend fun request(
        authorization: String,
        userId: Int,
        query: String,
    ): List<WhatsAppCrmMatch> {
        console("Contact match API called: POST api/crm/contacts/match query=$query userId=$userId")
        val element =
            api.matchContacts(
                authorization = authorization,
                request =
                    CrmContactMatchRequest(
                        userId = userId,
                        query = query,
                        limit = WhatsAppContactMatchQuery.LIMIT,
                    ),
            )
        val matches = CrmContactMatchParser.parse(element).take(WhatsAppContactMatchQuery.LIMIT)
        console("Contact match API returned ${matches.size} contact(s) for query=$query")
        return matches
    }

    private fun console(message: String) {
        Log.i(TAG, message)
        println(message)
    }

    companion object {
        private const val TAG = "WhatsAppContactMatch"
    }
}
