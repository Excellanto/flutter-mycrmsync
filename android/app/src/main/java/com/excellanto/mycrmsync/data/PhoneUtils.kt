package com.excellanto.mycrmsync.data

object PhoneUtils {
    /**
     * Best-effort phone normalization to a minimal E.164-like form.
     *
     * - Keeps digits and a single leading '+'
     * - If no '+', assumes India (+91) by default
     * - If number starts with '0', drops it before applying country code
     *
     * This is intentionally conservative to avoid mangling input; for production-grade parsing,
     * consider libphonenumber.
     */
    fun normalizeToE164(raw: String?, defaultCountryCode: String = "+91"): String? {
        val cleaned = raw
            ?.trim()
            ?.replace(Regex("[^0-9+]"), "")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        // Already has a country code
        if (cleaned.startsWith("+")) {
            val digits = cleaned.drop(1).filter { it.isDigit() }
            return if (digits.length in 7..20) "+$digits" else null
        }

        var digitsOnly = cleaned.filter { it.isDigit() }
        if (digitsOnly.startsWith("0")) {
            digitsOnly = digitsOnly.dropWhile { it == '0' }
        }

        val ccDigits = defaultCountryCode.trim().removePrefix("+").filter { it.isDigit() }
        val normalized = "+$ccDigits$digitsOnly"
        val normalizedDigits = normalized.drop(1).filter { it.isDigit() }
        return if (normalizedDigits.length in 8..24) normalized else null
    }

    /**
     * Variant keys used to align device call-log numbers with cached CRM contacts.
     */
    fun lookupKeys(raw: String?, defaultCountryCode: String = "+91"): Set<String> {
        val keys = mutableSetOf<String>()
        val e164 = normalizeToE164(raw, defaultCountryCode)?.trim()?.ifBlank { null }
        if (e164 != null) keys += e164
        val digits = raw?.filter { it.isDigit() }.orEmpty()
        if (digits.length >= 7) keys += digits
        if (digits.length >= 10) keys += digits.takeLast(10)
        return keys
    }
}

