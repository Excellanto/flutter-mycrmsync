package com.excellanto.mycrmsync.data.businesscard

import com.excellanto.mycrmsync.data.PhoneUtils

/**
 * Rule-based parser for business-card OCR output. Uses regex and heuristics only.
 */
object BusinessCardTextParser {

    private val emailRegex =
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""", RegexOption.IGNORE_CASE)

    private val urlRegex =
        Regex(
            """(?:https?://|www\.)[^\s,;]+""",
            RegexOption.IGNORE_CASE,
        )

    private val phoneRegex =
        Regex(
            """(?:\+?\d[\d\s().-]{6,}\d)""",
        )

    private val postalCodeRegex =
        Regex("""\b(\d{5}(?:-\d{4})?|\d{6}|[A-Z]\d[A-Z]\s?\d[A-Z]\d)\b""", RegexOption.IGNORE_CASE)

    private val companySuffixRegex =
        Regex(
            """\b(inc\.?|llc\.?|ltd\.?|corp\.?|corporation|company|co\.?|gmbh|pvt\.?\s*ltd\.?|plc|llp)\b""",
            RegexOption.IGNORE_CASE,
        )

    private val jobTitleRegex =
        Regex(
            """\b(director|manager|president|ceo|cfo|cto|engineer|consultant|founder|partner|vp|vice president|head of)\b""",
            RegexOption.IGNORE_CASE,
        )

    private val streetRegex =
        Regex(
            """\b(st\.?|street|ave\.?|avenue|road|rd\.?|lane|ln\.?|drive|dr\.?|boulevard|blvd\.?|suite|ste\.?|floor|fl\.?|#)\b""",
            RegexOption.IGNORE_CASE,
        )

    private val mobileHintRegex =
        Regex("""\b(mobile|cell|mob\.?|m\.)\b""", RegexOption.IGNORE_CASE)

    private val officeHintRegex =
        Regex("""\b(office|tel\.?|phone|ph\.?|t\.|work|direct)\b""", RegexOption.IGNORE_CASE)

    private val countryNames =
        setOf(
            "india", "usa", "united states", "uk", "united kingdom", "canada",
            "australia", "germany", "france", "uae", "singapore",
        )

    fun parse(rawText: String): ParsedBusinessCard {
        val lines =
            rawText
                .lines()
                .map { normalizeLine(it) }
                .filter { it.isNotBlank() }

        if (lines.isEmpty()) return ParsedBusinessCard()

        val consumed = mutableSetOf<Int>()
        val emails = mutableListOf<String>()
        val phones = mutableListOf<Pair<String, Boolean>>() // number, isMobilePreferred
        val urls = mutableListOf<String>()

        lines.forEachIndexed { index, line ->
            emailRegex.findAll(line).forEach { match ->
                emails += match.value.lowercase()
                consumed += index
            }
            urlRegex.findAll(line).forEach { match ->
                urls += match.value
                consumed += index
            }
            extractPhonesFromLine(line).forEach { (number, mobilePreferred) ->
                phones += number to mobilePreferred
                consumed += index
            }
        }

        val email = emails.firstOrNull().orEmpty()
        val phone = pickPrimaryPhone(phones)

        val remainingLines =
            lines.mapIndexedNotNull { index, line ->
                if (index in consumed) return@mapIndexedNotNull null
                val stripped =
                    line
                        .replace(emailRegex, "")
                        .replace(urlRegex, "")
                        .replace(phoneRegex, "")
                        .trim()
                        .trim('-', '|', ',', ';')
                        .trim()
                if (stripped.isBlank()) null else index to stripped
            }

        val companyLineIndex =
            remainingLines.indexOfFirst { (_, line) -> companySuffixRegex.containsMatchIn(line) }
        val companyName =
            if (companyLineIndex >= 0) {
                remainingLines[companyLineIndex].second.also {
                    consumed += remainingLines[companyLineIndex].first
                }
            } else {
                ""
            }

        val nameCandidate =
            remainingLines
                .firstOrNull { (index, line) ->
                    index !in consumed &&
                        !jobTitleRegex.containsMatchIn(line) &&
                        !streetRegex.containsMatchIn(line) &&
                        !postalCodeRegex.containsMatchIn(line) &&
                        !countryNames.contains(line.lowercase()) &&
                        looksLikeName(line)
                }
                ?.second
                .orEmpty()

        val (firstName, lastName) = splitName(nameCandidate)

        if (nameCandidate.isNotBlank()) {
            remainingLines
                .firstOrNull { it.second.equals(nameCandidate, ignoreCase = true) }
                ?.first
                ?.let { consumed += it }
        }

        val addressLines = mutableListOf<String>()
        var city = ""
        var state = ""
        var postalCode = ""
        var country = ""

        val unconsumed =
            remainingLines.filter { (index, _) -> index !in consumed }

        unconsumed.forEach { (index, line) ->
            postalCodeRegex.find(line)?.value?.let { found ->
                if (postalCode.isBlank()) postalCode = found.uppercase().replace(" ", "")
            }

            val lower = line.lowercase()
            if (country.isBlank() && countryNames.contains(lower)) {
                country = line
                consumed += index
                return@forEach
            }

            val cityStateMatch =
                Regex("""^(.+?),\s*([A-Za-z]{2,})\s+(\d{5}(?:-\d{4})?)""").find(line)
            if (cityStateMatch != null) {
                city = cityStateMatch.groupValues[1].trim()
                state = cityStateMatch.groupValues[2].trim()
                postalCode = cityStateMatch.groupValues[3].trim()
                consumed += index
                return@forEach
            }

            val cityStateOnly =
                Regex("""^(.+?),\s*([A-Za-z]{2,})$""").find(line)
            if (cityStateOnly != null && city.isBlank()) {
                city = cityStateOnly.groupValues[1].trim()
                state = cityStateOnly.groupValues[2].trim()
                consumed += index
                return@forEach
            }

            if (streetRegex.containsMatchIn(line)) {
                addressLines += line
                consumed += index
            }
        }

        unconsumed.forEach { (index, line) ->
            if (index in consumed) return@forEach
            if (addressLines.isNotEmpty() && !countryNames.contains(line.lowercase())) {
                addressLines += line
                consumed += index
            }
        }

        val resolvedCompany =
            companyName.ifBlank {
                remainingLines
                    .firstOrNull { (index, line) ->
                        index !in consumed &&
                            !jobTitleRegex.containsMatchIn(line) &&
                            line.length >= 3 &&
                            !looksLikeName(line)
                    }
                    ?.second
                    .orEmpty()
            }

        return ParsedBusinessCard(
            firstName = firstName,
            lastName = lastName,
            email = email,
            phone = phone,
            companyName = resolvedCompany,
            address = addressLines.joinToString(", "),
            city = city,
            state = state,
            postalCode = postalCode,
            country = country,
        )
    }

    private fun normalizeLine(line: String): String =
        line
            .trim()
            .replace(Regex("""\s+"""), " ")

    private fun extractPhonesFromLine(line: String): List<Pair<String, Boolean>> {
        val mobilePreferred = mobileHintRegex.containsMatchIn(line)
        val officePreferred = officeHintRegex.containsMatchIn(line)

        return phoneRegex
            .findAll(line)
            .mapNotNull { match ->
                val raw = match.value.trim()
                val digits = raw.filter { it.isDigit() }
                if (digits.length < 7) return@mapNotNull null
                val normalized = PhoneUtils.normalizeToE164(raw) ?: raw
                val preferMobile = mobilePreferred && !officePreferred
                normalized to preferMobile
            }
            .toList()
    }

    private fun pickPrimaryPhone(phones: List<Pair<String, Boolean>>): String {
        if (phones.isEmpty()) return ""
        val mobile = phones.firstOrNull { it.second }?.first
        if (mobile != null) return mobile
        return phones.first().first
    }

    private fun looksLikeName(line: String): Boolean {
        if (line.length > 60) return false
        val words = line.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 5) return false
        if (line.any { it.isDigit() }) return false
        return words.all { word ->
            word.replace(".", "").all { it.isLetter() } &&
                word.first().isUpperCase()
        }
    }

    private fun splitName(fullName: String): Pair<String, String> {
        val parts = fullName.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "" to ""
            parts.size == 1 -> parts[0] to ""
            else -> parts.first() to parts.drop(1).joinToString(" ")
        }
    }
}
