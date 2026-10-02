package com.excellanto.mycrmsync.data

import android.util.Log
import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.data.local.CrmContactPhoneEntity

data class WhatsAppResolvedContact(
    val id: String,
    val name: String,
    val phoneRaw: String?,
    val phoneE164: String?,
)

sealed class WhatsAppContactResolveResult {
    data class Found(val contact: WhatsAppResolvedContact) : WhatsAppContactResolveResult()
    data object NotInCrm : WhatsAppContactResolveResult()
    data object NeedsNetwork : WhatsAppContactResolveResult()
}

/** Resolves WhatsApp calls to CRM contacts by phone number only. */
class WhatsAppContactResolver(
    private val crmApi: CrmApiService = CrmApiService.create(),
) {
    suspend fun resolve(
        phoneRawHint: String?,
        contactIndex: Map<String, CrmContactPhoneEntity>,
        bearerAuthorization: String?,
        crmSearchUserId: String?,
        allowApiSearch: Boolean = true,
    ): WhatsAppContactResolveResult {
        val phoneRaw = phoneRawHint?.trim()?.ifBlank { null }
        val phoneE164 = PhoneUtils.normalizeToE164(phoneRaw)
        if (phoneRaw == null && phoneE164 == null) {
            Log.i(TAG, "CRM search skipped — no WhatsApp number on call screen")
            return WhatsAppContactResolveResult.NotInCrm
        }

        Log.i(
            TAG,
            "CRM search using WhatsApp number: raw=${phoneRaw ?: "—"} e164=${phoneE164 ?: "—"}",
        )

        val local =
            CrmContactDirectorySync.lookupContactLocal(
                index = contactIndex,
                phoneRaw = phoneRaw,
                phoneE164 = phoneE164,
            )
        if (local != null) {
            Log.i(
                TAG,
                "CRM match found locally: contactId=${local.contactId} phone=${phoneE164 ?: phoneRaw}",
            )
            return WhatsAppContactResolveResult.Found(
                WhatsAppResolvedContact(
                    id = local.contactId,
                    name = local.displayName?.trim()?.ifBlank { null } ?: "Unknown contact",
                    phoneRaw = phoneRaw,
                    phoneE164 = phoneE164,
                ),
            )
        }

        if (!allowApiSearch || bearerAuthorization.isNullOrBlank() || crmSearchUserId.isNullOrBlank()) {
            Log.i(
                TAG,
                "CRM phone search incomplete (offline or empty index): phone=${phoneE164 ?: phoneRaw}",
            )
            return if (contactIndex.isEmpty()) {
                WhatsAppContactResolveResult.NeedsNetwork
            } else {
                WhatsAppContactResolveResult.NotInCrm
            }
        }

        return try {
            Log.i(TAG, "CRM API search query (WhatsApp number): ${phoneE164 ?: phoneRaw}")
            val contact =
                CrmContactDirectorySync.searchContactByPhone(
                    api = crmApi,
                    authorization = bearerAuthorization,
                    userId = crmSearchUserId,
                    phoneRaw = phoneRaw,
                    phoneE164 = phoneE164,
                )
            if (contact != null) {
                Log.i(
                    TAG,
                    "CRM match found via API: contactId=${contact.id} phone=${phoneE164 ?: phoneRaw}",
                )
                WhatsAppContactResolveResult.Found(
                    WhatsAppResolvedContact(
                        id = contact.id,
                        name = contact.fullName,
                        phoneRaw = phoneRaw ?: contact.phone,
                        phoneE164 = phoneE164 ?: PhoneUtils.normalizeToE164(contact.phone),
                    ),
                )
            } else {
                Log.i(TAG, "No CRM contact found for phone: raw=${phoneRaw ?: "—"} e164=${phoneE164 ?: "—"}")
                WhatsAppContactResolveResult.NotInCrm
            }
        } catch (e: Exception) {
            Log.w(TAG, "CRM phone search failed for ${phoneE164 ?: phoneRaw}: ${e.message}")
            if (contactIndex.isEmpty()) {
                WhatsAppContactResolveResult.NeedsNetwork
            } else {
                WhatsAppContactResolveResult.NotInCrm
            }
        }
    }

    companion object {
        private const val TAG = "WhatsAppContactResolver"
    }
}
