package com.excellanto.mycrmsync.data.api

import android.content.Context
import android.net.Uri
import com.excellanto.mycrmsync.data.audio.RecordingToM4aConverter
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Normalizes local recordings before upload to the CRM transcribe API.
 */
internal object TranscriptionRecordingPrep {

    private val whisperExtensions: Set<String> =
        setOf(
            "flac",
            "mp3",
            "mp4",
            "mpeg",
            "mpg",
            "mpga",
            "m4a",
            "ogg",
            "oga",
            "wav",
            "webm",
        )

    private val whisperMimes: Set<String> =
        setOf(
            "audio/flac",
            "audio/mpeg",
            "audio/mp3",
            "audio/mp4",
            "audio/m4a",
            "audio/x-m4a",
            "audio/mpga",
            "audio/ogg",
            "application/ogg",
            "audio/wav",
            "audio/x-wav",
            "audio/wave",
            "audio/webm",
            "video/mp4",
            "video/webm",
        )

    /**
     * Superset for AssemblyAI uploads consumed by
     * [POST https://api.assemblyai.com/v2/transcript](https://api.assemblyai.com/docs/api-reference/overview);
     * unknown or unsupported OEM types still get transcoded to AAC M4A.
     */
    private val assemblyExtraExtensions: Set<String> =
        setOf(
            "aac",
            "amr",
            "3gp",
            "opus",
            "wma",
            "caf",
        )

    private val assemblyExtraMimes: Set<String> =
        setOf(
            "audio/aac",
            "audio/x-aac",
            "audio/amr",
            "audio/amr-wb",
            "audio/3gpp",
            "audio/3gpp2",
            "audio/opus",
            "audio/x-ms-wma",
            "audio/caf",
            "audio/x-caf",
        )

    /** Accepted as-is for `POST api/crm/call-recordings/transcribe`. */
    private val crmTranscribeExtensions: Set<String> = setOf("mp3", "m4a", "wav", "ogg")

    private val crmTranscribeMimes: Set<String> =
        setOf(
            "audio/mpeg",
            "audio/mp3",
            "audio/mp4",
            "audio/m4a",
            "audio/x-m4a",
            "audio/wav",
            "audio/x-wav",
            "audio/wave",
            "audio/ogg",
            "application/ogg",
        )

    internal enum class AudioAcceptPolicy {
        /** Aligns with the narrow Whisper-style list (used as default when unspecified). */
        WHISPER_LIKE,

        /** Broader list for AssemblyAI — same transcode fallback for anything else. */
        ASSEMBLY_LIKE,

        /** CRM transcribe API — mp3, m4a, wav, ogg only; everything else → M4A. */
        CRM_TRANSCRIBE,
    }

    private fun extensionsFor(policy: AudioAcceptPolicy): Set<String> =
        when (policy) {
            AudioAcceptPolicy.WHISPER_LIKE -> whisperExtensions
            AudioAcceptPolicy.ASSEMBLY_LIKE -> whisperExtensions + assemblyExtraExtensions
            AudioAcceptPolicy.CRM_TRANSCRIBE -> crmTranscribeExtensions
        }

    private fun mimesFor(policy: AudioAcceptPolicy): Set<String> =
        when (policy) {
            AudioAcceptPolicy.WHISPER_LIKE -> whisperMimes
            AudioAcceptPolicy.ASSEMBLY_LIKE -> whisperMimes + assemblyExtraMimes
            AudioAcceptPolicy.CRM_TRANSCRIBE -> crmTranscribeMimes
        }

    internal fun normalizeMime(mime: String?): String? {
        val base =
            mime?.lowercase()?.trim()?.substringBefore(';')?.trim().orEmpty()
        return base.takeIf { it.isNotBlank() }
    }

    internal fun extensionFromUri(uri: Uri): String {
        val seg = uri.lastPathSegment ?: return ""
        val decoded = Uri.decode(seg).substringBefore('?').substringBefore('#')
        return decoded.substringAfterLast('.', "").lowercase()
    }

    internal fun extensionForMime(mime: String?): String {
        return when (mime?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/webm" -> "webm"
            "audio/flac" -> "flac"
            "audio/aac", "audio/x-aac" -> "aac"
            "audio/amr", "audio/amr-wb" -> "amr"
            "audio/3gpp", "audio/3gpp2" -> "3gp"
            "audio/opus" -> "opus"
            "audio/x-ms-wma" -> "wma"
            "audio/caf", "audio/x-caf" -> "caf"
            else -> "m4a"
        }
    }

    /** Concrete MIME for CRM multipart upload (Laravel rejects wildcard audio Content-Type). */
    internal fun mimeTypeForCrmUpload(
        context: Context,
        originalUri: Uri,
        file: File,
    ): String {
        val fromFile = mimeTypeFromFileName(file.name)
        if (fromFile != "application/octet-stream") {
            return fromFile
        }
        val fromResolver = normalizeMime(context.applicationContext.contentResolver.getType(originalUri))
        if (!fromResolver.isNullOrBlank() && fromResolver in crmTranscribeMimes) {
            return fromResolver
        }
        return "application/octet-stream"
    }

    internal fun mimeTypeFromFileName(fileName: String): String =
        when {
            fileName.endsWith(".mp3", true) -> "audio/mpeg"
            fileName.endsWith(".m4a", true) -> "audio/mp4"
            fileName.endsWith(".wav", true) -> "audio/wav"
            fileName.endsWith(".ogg", true) -> "application/ogg"
            else -> "application/octet-stream"
        }

    private fun isListedFormat(
        policy: AudioAcceptPolicy,
        appCtx: Context,
        uri: Uri,
        rawFile: File,
    ): Boolean {
        val exts = extensionsFor(policy)
        val mimes = mimesFor(policy)
        val mime = normalizeMime(appCtx.contentResolver.getType(uri))
        if (mime != null && mime in mimes) return true

        val extUri = extensionFromUri(uri)
        if (extUri.isNotBlank() && extUri in exts) return true

        val extFile = rawFile.extension.lowercase()
        if (extFile.isNotBlank() && extFile in exts) return true

        return false
    }

    /**
     * Copies [recordingUri] to cache, always attempting M4A transcode first for CRM upload.
     * Falls back to the original copy only when transcode fails but the file is mp3/m4a/wav/ogg.
     */
    internal fun prepareForCrmTranscribe(
        context: Context,
        recordingUri: Uri,
    ): File? =
        runCatching {
            val rawFile =
                copyUriToTempAudioFile(context, recordingUri, "crm_call_transcribe")
                    ?: return null
            val appCtx = context.applicationContext
            val m4a = File(appCtx.cacheDir, "crm_call_transcribe_m4a_${UUID.randomUUID()}.m4a")
            val transcodedOk =
                RecordingToM4aConverter.tryConvert(rawFile, m4a) && m4a.length() >= 128L
            if (transcodedOk) {
                runCatching { rawFile.delete() }
                m4a
            } else {
                runCatching { if (m4a.exists()) m4a.delete() }
                if (isListedFormat(AudioAcceptPolicy.CRM_TRANSCRIBE, appCtx, recordingUri, rawFile)) {
                    rawFile
                } else {
                    runCatching { rawFile.delete() }
                    null
                }
            }
        }.getOrNull()

    /**
     * Copies [recordingUri] to cache, optionally transcoding to AAC M4A when the OEM format is
     * outside the provider’s accepted MIME/extension list. Caller must delete the returned file after use.
     */
    internal fun prepareLocalRecordingFile(
        context: Context,
        recordingUri: Uri,
        rawCopyPrefix: String,
        transcodedM4aPrefix: String,
        acceptPolicy: AudioAcceptPolicy = AudioAcceptPolicy.WHISPER_LIKE,
        transcodeFallbackToOriginal: Boolean = true,
    ): File? =
        runCatching {
            val rawFile =
                copyUriToTempAudioFile(context, recordingUri, rawCopyPrefix)
                    ?: return null
            val appCtx = context.applicationContext
            if (isListedFormat(acceptPolicy, appCtx, recordingUri, rawFile)) {
                rawFile
            } else {
                val m4a = File(appCtx.cacheDir, "${transcodedM4aPrefix}_${UUID.randomUUID()}.m4a")
                val transcodedOk =
                    RecordingToM4aConverter.tryConvert(rawFile, m4a) && m4a.length() >= 128L
                if (transcodedOk) {
                    runCatching { rawFile.delete() }
                    m4a
                } else {
                    runCatching { if (m4a.exists()) m4a.delete() }
                    if (transcodeFallbackToOriginal) rawFile else null
                }
            }
        }.getOrNull()

    private fun copyUriToTempAudioFile(context: Context, uri: Uri, filePrefix: String): File? {
        return runCatching {
            val resolver = context.applicationContext.contentResolver
            val mime = resolver.getType(uri)
            val ext =
                extensionFromUri(uri).takeIf { it.isNotBlank() }
                    ?: extensionForMime(mime)
            val outFile =
                File(
                    context.cacheDir,
                    "${filePrefix}_${UUID.randomUUID()}.$ext",
                )
            resolver.openInputStream(uri).use { input ->
                if (input == null) return null
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile
        }.getOrNull()
    }
}
