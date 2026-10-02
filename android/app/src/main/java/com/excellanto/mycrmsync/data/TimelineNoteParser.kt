package com.excellanto.mycrmsync.data

import com.excellanto.mycrmsync.data.model.CrmNote
import com.excellanto.mycrmsync.data.model.CrmNoteAttachment

enum class TimelineAttachmentKind {
    IMAGE,
    AUDIO,
    DOCUMENT,
}

data class TimelineAttachment(
    val url: String,
    val shortUrl: String? = null,
    val name: String,
    val kind: TimelineAttachmentKind,
    val fileType: String? = null,
    val voiceNoteId: String? = null,
)

data class ParsedTimelineNote(
    val introText: String = "",
    val summary: String? = null,
    val transcription: String? = null,
    val attachments: List<TimelineAttachment> = emptyList(),
) {
    val hasVoiceContent: Boolean
        get() = !summary.isNullOrBlank() || !transcription.isNullOrBlank()

    val primaryIconKind: TimelineAttachmentKind?
        get() = when {
            attachments.any { it.kind == TimelineAttachmentKind.AUDIO } || hasVoiceContent ->
                TimelineAttachmentKind.AUDIO
            attachments.any { it.kind == TimelineAttachmentKind.IMAGE } ->
                TimelineAttachmentKind.IMAGE
            attachments.any { it.kind == TimelineAttachmentKind.DOCUMENT } ->
                TimelineAttachmentKind.DOCUMENT
            else -> null
        }
}

object TimelineNoteParser {
    private val imageFileTypes = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private val audioFileTypes = setOf("mp3", "wav", "m4a", "aac", "ogg", "webm", "amr")
    private val documentFileTypes = setOf("pdf", "doc", "docx")

    private val imgSrcRegex =
        """<img[^>]+src=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
    private val hrefRegex =
        """<a[^>]+href=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
    private val bareUrlRegex =
        """(https?://[^\s<>"']+)""".toRegex(RegexOption.IGNORE_CASE)
    private val fileAttachmentLineRegex =
        """(?im)^\s*File Attachment:\s*.+$""".toRegex()
    private val attachmentUrlLineRegex =
        """(?im)^\s*(?:Attachment|Recording URL|Recording):\s*(https?://\S+)\s*$""".toRegex()
    private val transcriptionSectionSplitRegex =
        """(?im)\n\s*(?:\*\*)?(?:Transcription|Transcript)(?:\*\*)?\s*:\s*""".toRegex()

    fun parse(note: CrmNote): ParsedTimelineNote {
        val rawBody = note.body.orEmpty()
        val structuredAttachments =
            note.attachments.orEmpty().mapNotNull { classifyAttachment(it) }

        val legacyUrlAttachments = mutableListOf<TimelineAttachment>()
        imgSrcRegex.findAll(rawBody).forEach { legacyUrlAttachments.add(classifyUrl(it.groupValues[1])) }
        hrefRegex.findAll(rawBody).forEach { legacyUrlAttachments.add(classifyUrl(it.groupValues[1])) }
        attachmentUrlLineRegex.findAll(rawBody).forEach {
            legacyUrlAttachments.add(classifyUrl(it.groupValues[1]))
        }
        bareUrlRegex.findAll(rawBody).forEach { match ->
            val url = match.groupValues[1].trimEnd('.', ',', ';', ')')
            if (looksLikeAttachmentUrl(url)) {
                legacyUrlAttachments.add(classifyUrl(url))
            }
        }

        val attachments =
            (structuredAttachments + legacyUrlAttachments).distinctBy { it.url }

        var workingHtml = rawBody
        attachments.forEach { attachment ->
            workingHtml = removeUrlFromHtml(workingHtml, attachment.url)
            attachment.shortUrl?.let { workingHtml = removeUrlFromHtml(workingHtml, it) }
        }
        workingHtml =
            workingHtml
                .replace(imgSrcRegex, "")
                .replace(hrefRegex, "")
                .replace(fileAttachmentLineRegex, "")
                .replace(attachmentUrlLineRegex, "")

        val plainBody = htmlToPlainText(workingHtml).trim()
        val explicitSummary = note.summary?.trim()?.takeIf { it.isNotBlank() }
        val explicitTranscription = note.transcription?.trim()?.takeIf { it.isNotBlank() }

        val parsedSections = extractSections(plainBody)
        val summary = explicitSummary ?: parsedSections.summary
        val transcription = explicitTranscription ?: parsedSections.transcription
        val introText = parsedSections.introText.trim()

        return ParsedTimelineNote(
            introText = introText,
            summary = summary,
            transcription = transcription,
            attachments = attachments,
        )
    }

    /** Classify a structured API attachment object. */
    fun classifyStructuredAttachment(att: CrmNoteAttachment): TimelineAttachment? =
        classifyAttachment(att)

    /** Best audio attachment for timeline playback (structured API data first). */
    fun resolveVoicePlaybackAttachment(note: CrmNote): TimelineAttachment? {
        val parsed = parse(note)
        parsed.attachments.firstOrNull { it.kind == TimelineAttachmentKind.AUDIO }?.let { return it }
        return note.attachments.orEmpty()
            .mapNotNull { classifyAttachment(it) }
            .firstOrNull { it.kind == TimelineAttachmentKind.AUDIO }
    }

    /** All audio attachments for a note (timeline + structured). */
    fun voicePlaybackAttachments(note: CrmNote): List<TimelineAttachment> {
        val parsed = parse(note)
        val fromParsed = parsed.attachments.filter { it.kind == TimelineAttachmentKind.AUDIO }
        if (fromParsed.isNotEmpty()) return fromParsed
        return listOfNotNull(resolveVoicePlaybackAttachment(note))
    }

    private data class SectionParseResult(
        val introText: String,
        val summary: String?,
        val transcription: String?,
    )

    private fun extractSections(plainBody: String): SectionParseResult {
        if (plainBody.isBlank()) {
            return SectionParseResult(introText = "", summary = null, transcription = null)
        }

        val callTranscriptionMatch =
            Regex(
                pattern = "(?s)(.*?)(?:\\n\\s*)?📝\\s*Call transcription \\(AI,[^)]+\\)\\s*\\n(.+)",
                option = RegexOption.IGNORE_CASE,
            ).find(plainBody)

        if (callTranscriptionMatch != null) {
            return SectionParseResult(
                introText = callTranscriptionMatch.groupValues[1].trim(),
                summary = null,
                transcription = callTranscriptionMatch.groupValues[2].trim(),
            )
        }

        val transcriptionParts = plainBody.split(transcriptionSectionSplitRegex, limit = 2)
        if (transcriptionParts.size == 2) {
            val beforeTranscription = transcriptionParts[0].trim()
            val transcription = transcriptionParts[1].trim().takeIf { it.isNotBlank() }
            val summaryInline =
                Regex(
                    pattern = """(?is)^\s*(.*?)(?:\*\*)?Summary(?:\*\*)?\s*:\s*(.+)$""",
                ).find(beforeTranscription)

            if (summaryInline != null) {
                return SectionParseResult(
                    introText = summaryInline.groupValues[1].trim(),
                    summary = summaryInline.groupValues[2].trim().takeIf { it.isNotBlank() },
                    transcription = transcription,
                )
            }
        }

        val summaryHeader =
            Regex(
                pattern = """(?im)(?:^|\n)\s*(?:\*\*)?Summary(?:\*\*)?\s*:?\s*\n""",
            ).find(plainBody)
        val transcriptionHeader =
            Regex(
                pattern = """(?im)(?:^|\n)\s*(?:\*\*)?(?:Transcription|Transcript)(?:\*\*)?\s*:?\s*\n""",
            ).find(plainBody)

        if (summaryHeader != null || transcriptionHeader != null) {
            val summaryStart = summaryHeader?.range?.last?.plus(1)
            val transcriptionStart = transcriptionHeader?.range?.last?.plus(1)
            val introEnd =
                listOfNotNull(summaryHeader?.range?.first, transcriptionHeader?.range?.first)
                    .minOrNull()
                    ?: plainBody.length
            val introText = plainBody.substring(0, introEnd).trim()

            val summary =
                summaryStart?.let { start ->
                    val end = transcriptionHeader?.range?.first ?: plainBody.length
                    plainBody.substring(start, end).trim().takeIf { it.isNotBlank() }
                }
            val transcription =
                transcriptionStart?.let { start ->
                    plainBody.substring(start).trim().takeIf { it.isNotBlank() }
                }

            return SectionParseResult(
                introText = introText,
                summary = summary,
                transcription = transcription,
            )
        }

        return SectionParseResult(
            introText = plainBody,
            summary = null,
            transcription = null,
        )
    }

    private fun classifyAttachment(att: CrmNoteAttachment): TimelineAttachment? {
        val playbackUrl = att.playbackUrl().takeIf { it.isNotBlank() } ?: return null
        val linkUrl = att.linkUrl().takeIf { it.isNotBlank() }
        val fileType = att.filetype?.trim()?.lowercase().orEmpty()
        val kind = kindFromFileType(fileType, playbackUrl)
        val name = displayNameFromUrl(playbackUrl).let { parsed ->
            if (parsed != "Attachment") parsed else "${fileType.ifBlank { "file" }} attachment"
        }
        return TimelineAttachment(
            url = playbackUrl,
            shortUrl = linkUrl?.takeIf { it != playbackUrl },
            name = name,
            kind = kind,
            fileType = fileType.takeIf { it.isNotBlank() },
            voiceNoteId = att.voiceNoteId?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private fun kindFromFileType(fileType: String, url: String): TimelineAttachmentKind =
        when {
            fileType in imageFileTypes || isImageUrl(url) -> TimelineAttachmentKind.IMAGE
            fileType in audioFileTypes || isAudioUrl(url) -> TimelineAttachmentKind.AUDIO
            fileType in documentFileTypes || isDocumentUrl(url) -> TimelineAttachmentKind.DOCUMENT
            else -> TimelineAttachmentKind.DOCUMENT
        }

    private fun looksLikeAttachmentUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".jpg") ||
            lower.contains(".jpeg") ||
            lower.contains(".png") ||
            lower.contains(".gif") ||
            lower.contains(".webp") ||
            lower.contains(".pdf") ||
            lower.contains(".doc") ||
            lower.contains(".docx") ||
            lower.contains(".mp3") ||
            lower.contains(".wav") ||
            lower.contains(".m4a") ||
            lower.contains(".aac") ||
            lower.contains(".ogg") ||
            lower.contains("voice-note") ||
            lower.contains("voicenote") ||
            lower.contains("/vn") ||
            lower.contains("/surl/") ||
            lower.contains("recording")
    }

    private fun classifyUrl(url: String): TimelineAttachment {
        val name = displayNameFromUrl(url)
        val kind =
            when {
                isImageUrl(url) -> TimelineAttachmentKind.IMAGE
                isAudioUrl(url) -> TimelineAttachmentKind.AUDIO
                isDocumentUrl(url) -> TimelineAttachmentKind.DOCUMENT
                else -> TimelineAttachmentKind.DOCUMENT
            }
        return TimelineAttachment(url = url, name = name, kind = kind)
    }

    private fun isImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(Regex("""\.(jpg|jpeg|png|gif|webp|bmp)(\?|$)"""))
    }

    private fun isAudioUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(Regex("""\.(mp3|wav|m4a|aac|ogg|webm|amr)(\?|$)""")) ||
            lower.contains("voice-note") ||
            lower.contains("voicenote") ||
            lower.contains("/vn") ||
            lower.contains("/surl/") ||
            lower.contains("recording")
    }

    private fun isDocumentUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(Regex("""\.(pdf|doc|docx)(\?|$)"""))
    }

    private fun displayNameFromUrl(url: String): String {
        val pathName = url.substringBefore('?').substringAfterLast('/')
        return pathName.takeIf { it.isNotBlank() && it.contains('.') } ?: "Attachment"
    }

    private fun removeUrlFromHtml(html: String, url: String): String {
        if (url.isBlank()) return html
        return html
            .replace(url, "", ignoreCase = true)
            .replace(Regex("""<img[^>]*src=["']${Regex.escape(url)}["'][^>]*>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<a[^>]*href=["']${Regex.escape(url)}["'][^>]*>.*?</a>""", RegexOption.IGNORE_CASE), "")
    }

    private fun htmlToPlainText(html: String): String {
        if (html.isBlank()) return ""
        return html
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</p\s*>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}
