package com.excellanto.mycrmsync.accessibility

object WhatsAppCallUiPatterns {

    val WHATSAPP_PACKAGES =
        setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
        )

    const val WHATSAPP_PACKAGE = "com.whatsapp"

    /** Known WhatsApp VoIP / call screen view-id suffixes (package prefix varies). */
    val CONTACT_NAME_VIEW_ID_SUFFIXES =
        listOf(
            ":name",
            ":contact_name",
            ":title",
            ":contactName",
        )

    val PHONE_SUBTITLE_VIEW_ID_SUFFIXES =
        listOf(
            ":subtitle",
            ":sub_title",
            ":phone",
            ":number",
            ":contact_subtitle",
            ":secondary",
        )

    val CALL_TIMER_VIEW_ID_SUFFIXES =
        listOf(
            ":timer",
            ":duration",
            ":call_duration",
            ":time",
        )

    val VOIP_ACTIVITY_CLASS_MARKERS =
        listOf(
            "voip",
            "call",
        )

    val INCOMING_CALL_TEXTS =
        listOf(
            "incoming voice call",
            "incoming video call",
            "incoming call",
            "whatsapp audio",
            "whatsapp video",
        )

    val OUTGOING_CALL_TEXTS =
        listOf(
            "calling",
            "ringing",
            "connecting",
        )

    val CONNECTED_CALL_TEXTS =
        listOf(
            "ongoing voice call",
            "ongoing video call",
            "ongoing call",
        )

    val MISSED_CALL_TEXTS =
        listOf(
            "missed voice call",
            "missed video call",
            "missed call",
            "missed voice",
            "missed video",
        )

    val END_CALL_CONTENT_DESCRIPTIONS =
        listOf(
            "end call",
            "end voice call",
            "end video call",
            "hang up",
            "leave call",
        )

    val ANSWER_CONTENT_DESCRIPTIONS =
        listOf(
            "accept",
            "answer",
        )

    val DECLINE_CONTENT_DESCRIPTIONS =
        listOf(
            "decline",
            "reject",
        )

    val CALL_VIEW_ID_SUFFIXES =
        listOf(
            "call",
            "voip",
            "voice",
            "video",
        )

    val STATUS_TEXTS_TO_IGNORE =
        listOf(
            "speaker",
            "mute",
            "bluetooth",
            "keypad",
            "whatsapp",
            "message",
            "add participant",
            "switch camera",
            "flip",
        )

    fun looksLikePhoneNumber(text: String): Boolean {
        val digits = text.count { it.isDigit() }
        return digits >= 7 && digits >= text.filter { !it.isWhitespace() }.length / 2
    }

    fun containsAny(haystack: String, needles: Collection<String>): Boolean {
        val lower = haystack.lowercase()
        return needles.any { lower.contains(it) }
    }

    /**
     * True only for a short call-status label such as "Calling…" or "Incoming voice call".
     * Longer chat text that merely contains those words is ignored.
     */
    fun isStatusLabel(text: String, phrases: Collection<String>): Boolean {
        val lower = text.trim().lowercase()
        if (lower.isEmpty() || lower.length > 40) return false
        val normalized = lower.trimEnd('…', '.', '!', ' ')
        return phrases.any { phrase ->
            val needle = phrase.lowercase()
            normalized == needle || lower == needle || lower.startsWith("$needle ")
        }
    }

    /** mm:ss call duration. Clock-like chat timestamps are not treated as a call by themselves. */
    fun parseClockTimer(text: String): Int? {
        val trimmed = text.trim()
        if (!trimmed.contains(":")) return null
        val parts = trimmed.split(":")
        if (parts.size != 2) return null
        val minutes = parts[0].filter { it.isDigit() }.toIntOrNull() ?: return null
        val seconds = parts[1].filter { it.isDigit() }.toIntOrNull() ?: return null
        if (parts[1].filter { it.isDigit() }.length != 2) return null
        return minutes * 60 + seconds
    }

    fun parseTimerSeconds(text: String): Int? {
        parseClockTimer(text)?.let { return it }
        val trimmed = text.trim()
        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.length in 1..4 && digitsOnly == trimmed.replace(" ", "")) {
            return digitsOnly.toIntOrNull()
        }
        return null
    }

    fun isWhatsAppPackage(packageName: String?): Boolean {
        val pkg = packageName?.trim()?.ifBlank { null } ?: return false
        return pkg in WHATSAPP_PACKAGES
    }

    fun looksLikeVoipActivity(className: String?): Boolean {
        val lower = className?.lowercase().orEmpty()
        return VOIP_ACTIVITY_CLASS_MARKERS.any { lower.contains(it) }
    }
}
