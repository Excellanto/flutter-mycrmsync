package com.excellanto.mycrmsync.data.api

import android.content.Context
import android.net.Uri
import com.excellanto.mycrmsync.data.model.CallRecordingTranscribeData
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** Uploads a local call recording to `POST api/crm/call-recordings/transcribe`. */
object CrmCallRecordingTranscription {

    private const val MAX_FILE_BYTES = 50L * 1024 * 1024

    suspend fun transcribe(
        context: Context,
        api: CrmApiService,
        authorization: String,
        userId: String,
        callLogId: String,
        contactId: String,
        recordingUri: Uri,
        language: String = "en",
        storeRecording: Boolean = true,
    ): Result<CallRecordingTranscribeData> {
        var tempFile: File? = null
        return runCatching {
            tempFile =
                TranscriptionRecordingPrep.prepareForCrmTranscribe(
                    context = context,
                    recordingUri = recordingUri,
                ) ?: throw Exception("Could not read or convert call recording to M4A")

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

            val response =
                api.transcribeCallRecording(
                    authorization = authorization,
                    audio = audioPart,
                    callLogId = callLogId.toRequestBody(textPlain),
                    contactId = contactId.toRequestBody(textPlain),
                    language = language.toRequestBody(textPlain),
                    storeRecording = (if (storeRecording) "1" else "0").toRequestBody(textPlain),
                    userId = userId.toRequestBody(textPlain),
                )

            if (!response.success || response.data == null) {
                throw Exception(response.message.ifBlank { "Call recording transcription failed" })
            }
            response.data
        }.also {
            tempFile?.let { file -> runCatching { if (file.exists()) file.delete() } }
        }
    }
}
