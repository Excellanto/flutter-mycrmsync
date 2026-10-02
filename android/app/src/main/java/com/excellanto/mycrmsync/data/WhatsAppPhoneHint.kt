package com.excellanto.mycrmsync.data

import com.excellanto.mycrmsync.accessibility.WhatsAppCallUiPatterns

/** Phone number shown by WhatsApp call UI — used as the only source for CRM search. */
object WhatsAppPhoneHint {

    fun fromWhatsAppUi(phoneRaw: String?, displayName: String): String? {
        phoneRaw?.trim()?.ifBlank { null }?.let { return it }
        return displayName.trim().takeIf { WhatsAppCallUiPatterns.looksLikePhoneNumber(it) }
    }
}
