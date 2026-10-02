package com.excellanto.mycrmsync.data

import com.excellanto.mycrmsync.accessibility.WhatsAppCallUiPatterns

/**
 * Chooses the `query` sent to `POST api/crm/contacts/match`.
 * A real display name is preferred. A phone number is used when that is all
 * WhatsApp showed, and as a fallback when the name search returns nothing.
 */
object WhatsAppContactMatchQuery {
    const val LIMIT = 5

    data class Built(
        val primary: String,
        val fallback: String?,
    )

    fun build(displayName: String?, phoneRaw: String?): Built? {
        val name = displayName?.trim()?.ifBlank { null }?.takeUnless { isBlockedName(it) }
        val phone =
            phoneRaw?.trim()?.ifBlank { null }
                ?: name?.takeIf { WhatsAppCallUiPatterns.looksLikePhoneNumber(it) }
        val nameQuery = name?.takeUnless { WhatsAppCallUiPatterns.looksLikePhoneNumber(it) }
        val phoneQuery = phone?.takeIf { WhatsAppCallUiPatterns.looksLikePhoneNumber(it) } ?: phone
        val primary = nameQuery ?: phoneQuery ?: return null
        val fallback = phoneQuery?.takeIf { nameQuery != null && it != nameQuery }
        return Built(primary = primary, fallback = fallback)
    }

    private fun isBlockedName(value: String): Boolean {
        return value.trim().lowercase() in BLOCKED_NAMES
    }

    private val BLOCKED_NAMES =
        setOf(
            "unknown",
            "whatsapp",
            "whatsapp contact",
        )
}
