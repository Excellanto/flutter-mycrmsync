package com.excellanto.mycrmsync.data

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Warm call-note copy shared by the CRM poster and the timeline.
 * Outgoing, incoming, and missed each lead with their own mark.
 */
object CallNoteText {
    enum class Kind { OUTGOING, INCOMING, MISSED }

    private const val OUT = "↗"
    private const val IN = "↙"
    private const val MISS = "✕"

    fun format(
        direction: String,
        durationSec: Int?,
        startedAtIso: String,
        phone: String?,
        isWhatsAppCall: Boolean,
        hasRecording: Boolean = false,
    ): String {
        val kind = kindFromDirection(direction)
        val length = if (kind == Kind.MISSED) null else durationWords(durationSec ?: 0)
        val headline = headline(kind, isWhatsAppCall, length)
        val whenLabel = formatWhen(startedAtIso)
        val number = formatPhone(phone)
        val meta =
            listOfNotNull(
                number.takeIf { it.isNotBlank() },
                whenLabel.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
        return buildString {
            append(headline)
            if (meta.isNotBlank()) {
                append('\n')
                append(meta)
            }
            if (hasRecording) {
                append('\n')
                append("Recording is on this phone")
            }
        }
    }

    fun kindOf(text: String): Kind? {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return null
        val first = trimmed.lineSequence().first().trimStart()
        when {
            first.startsWith(OUT) || first.startsWith("↗️") -> return Kind.OUTGOING
            first.startsWith(IN) || first.startsWith("↙️") -> return Kind.INCOMING
            first.startsWith(MISS) || first.startsWith("❌") -> return Kind.MISSED
        }
        if (!isLegacyCallNote(trimmed)) return null
        val status = field(trimmed, "Status")
        if (status != null && status.uppercase().contains("MISS")) return Kind.MISSED
        val direction = field(trimmed, "Direction") ?: return null
        return kindFromDirection(direction)
    }

    /** Rewrites the old label block. Already-warm notes and other notes stay as they are. */
    fun present(text: String): String {
        if (!isLegacyCallNote(text)) return text
        val lines = text.trim().lines()
        if (lines.isEmpty()) return text
        val whatsApp = lines.first().trim().lowercase().startsWith("whatsapp")
        var direction = ""
        var whenIso = ""
        var durationRaw = ""
        var phone = ""
        var hasRecording = false
        val extra = mutableListOf<String>()
        var headerDone = false
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (!headerDone && isLegacyField(line)) {
                val lower = line.trim().lowercase()
                val value = line.substringAfter(":", "").trim()
                when {
                    lower.startsWith("direction:") -> direction = value
                    lower.startsWith("when:") -> whenIso = value
                    lower.startsWith("duration:") -> durationRaw = value
                    lower.startsWith("number:") -> phone = value
                    lower.startsWith("recording:") -> hasRecording = true
                    lower.startsWith("status:") && value.uppercase().contains("MISS") ->
                        direction = "MISSED"
                }
            } else if (!headerDone && line.isBlank()) {
                continue
            } else {
                headerDone = true
                extra += line
            }
        }
        val warm =
            format(
                direction = direction,
                durationSec = parseLegacyDuration(durationRaw) ?: 0,
                startedAtIso = whenIso,
                phone = phone.takeUnless { it == "—" || it == "-" },
                isWhatsAppCall = whatsApp,
                hasRecording = hasRecording,
            )
        val rest = extra.joinToString("\n").trim()
        return if (rest.isEmpty()) warm else "$warm\n\n$rest"
    }

    private fun kindFromDirection(direction: String): Kind? {
        val d = direction.uppercase()
        return when {
            d.contains("MISS") -> Kind.MISSED
            // "OUTGOING" also contains "IN", so outgoing has to be tested first.
            d.contains("OUT") -> Kind.OUTGOING
            d.contains("IN") -> Kind.INCOMING
            else -> null
        }
    }

    private fun headline(kind: Kind?, whatsApp: Boolean, duration: String?): String {
        val length = duration?.let { "  ·  $it" }.orEmpty()
        return when (kind) {
            Kind.MISSED ->
                if (whatsApp) "$MISS  You missed a WhatsApp call" else "$MISS  You missed a call"
            Kind.INCOMING ->
                if (whatsApp) "$IN  Called you on WhatsApp$length" else "$IN  Called you$length"
            Kind.OUTGOING ->
                if (whatsApp) "$OUT  You called on WhatsApp$length" else "$OUT  You called$length"
            null -> if (whatsApp) "WhatsApp call$length" else "Phone call$length"
        }
    }

    private fun durationWords(totalSec: Int): String {
        val sec = totalSec.coerceAtLeast(0)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 && m > 0 -> "$h hr $m min"
            h > 0 -> "$h hr"
            m > 0 && s > 0 -> "$m min $s sec"
            m > 0 -> "$m min"
            else -> "$s sec"
        }
    }

    private fun formatWhen(iso: String): String {
        val raw = iso.trim()
        if (raw.isEmpty()) return ""
        val formatter = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
        runCatching {
            return OffsetDateTime.parse(raw)
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(formatter)
        }
        runCatching { return LocalDateTime.parse(raw).format(formatter) }
        return raw
    }

    private fun formatPhone(phone: String?): String {
        val raw = phone?.trim().orEmpty()
        if (raw.isEmpty() || raw == "—" || raw == "-") return ""
        val compact = raw.replace(" ", "").replace("-", "")
        val match = Regex("^\\+91(\\d{10})$").find(compact) ?: return raw
        val n = match.groupValues[1]
        return "+91 ${n.substring(0, 5)} ${n.substring(5)}"
    }

    private fun isLegacyCallNote(text: String): Boolean {
        val first = text.trimStart().lineSequence().firstOrNull()?.trim()?.lowercase() ?: return false
        return first == "whatsapp voice call" || first == "device call log"
    }

    private fun isLegacyField(line: String): Boolean {
        val lower = line.trim().lowercase()
        return lower.startsWith("contact:") ||
            lower.startsWith("direction:") ||
            lower.startsWith("status:") ||
            lower.startsWith("when:") ||
            lower.startsWith("duration:") ||
            lower.startsWith("number:") ||
            lower.startsWith("recording:")
    }

    private fun field(text: String, label: String): String? {
        val match = Regex("""(?im)^${Regex.escape(label)}:\s*(.+)$""").find(text) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    private fun parseLegacyDuration(raw: String): Int? {
        val t = raw.trim()
        if (t.isEmpty() || t == "—" || t == "-" || t == "–") return null
        Regex("""^(\d+)\s*s$""", RegexOption.IGNORE_CASE).find(t)?.let {
            return it.groupValues[1].toInt()
        }
        Regex("""^(\d+):(\d{2})$""").find(t)?.let {
            return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()
        }
        return null
    }
}
