package com.excellanto.mycrmsync.data.api

import android.content.Context
import android.net.Uri
import com.excellanto.mycrmsync.data.model.VoiceNoteProcessData
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Uploads a local call recording through the same CRM path as contact voice notes
 * (`POST api/crm/voice-notes/process`).
 */
object CrmCallRecordingVoiceNote {

    private const val MAX_FILE_BYTES = 50L * 1024 * 1024

    suspend fun process(
        context: Context,
        api: CrmApiService,
        authorization: String,
        userId: String,
        contactId: String,
        recordingUri: Uri,
        existingNoteText: String? = null,
    ): Result<VoiceNoteProcessData> {
        var tempFile: File? = null
        return runCatching {
            tempFile =
                TranscriptionRecordingPrep.prepareForCrmTranscribe(
                    context = context,
                    recordingUri = recordingUri,
                ) ?: throw Exception("Could not read or convert call recording")

            if (tempFile!!.length() > MAX_FILE_BYTES) {
                throw Exception("Recording exceeds 50 MB limit")
            }

            val uploadMime =
                TranscriptionRecordingPrep.mimeTypeForCrmUpload(
                    context = context,
                    originalUri = recordingUri,
                    file = tempFile!!,
                )
            val textPlain = "text/plain".toMediaType()
            val audioPart =
                MultipartBody.Part.createFormData(
                    "audio",
                    tempFile!!.name,
                    tempFile!!.asRequestBody(uploadMime.toMediaType()),
                )
            val existingNotePart =
                existingNoteText?.trim()?.takeIf { it.isNotEmpty() }
                    ?.toRequestBody(textPlain)

            val response =
                api.processVoiceNote(
                    authorization = authorization,
                    contactId = contactId.toRequestBody(textPlain),
                    userId = userId.toRequestBody(textPlain),
                    audio = audioPart,
                    existingNoteText = existingNotePart,
                )

            if (!response.success || response.data == null) {
                throw Exception(response.message.ifBlank { "Voice note processing failed" })
            }
            response.data
        }.also {
            tempFile?.let { file -> runCatching { if (file.exists()) file.delete() } }
        }
    }
}
