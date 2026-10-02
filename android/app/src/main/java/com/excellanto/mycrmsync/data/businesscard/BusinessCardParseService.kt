package com.excellanto.mycrmsync.data.businesscard

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.data.model.BusinessCardParseData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

/** Uploads a scanned business-card image to `POST api/crm/business-cards/parse`. */
object BusinessCardParseService {

    private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

    suspend fun parse(
        context: Context,
        prefs: PrefsManager,
        imageUri: Uri,
        locale: String = defaultLocale(),
    ): Result<BusinessCardParseData> {
        var tempFile: File? = null
        return runCatching {
            val authorization =
                prefs.getBearerAuthorization()
                    ?: throw IllegalStateException("Not logged in. Please sign in again.")
            val userId =
                prefs.getAuthUserId() ?: prefs.getUserId()
                    ?: throw IllegalStateException("CRM user id not found. Please sign in again.")

            tempFile =
                withContext(Dispatchers.IO) {
                    copyImageToTempFile(context, imageUri)
                } ?: throw Exception("Could not read business card image")

            if (tempFile.length() > MAX_IMAGE_BYTES) {
                throw Exception("Business card image exceeds 10 MB limit")
            }

            val mimeType = imageMimeType(context, imageUri, tempFile.name)
            val textPlain = "text/plain".toMediaType()
            val imagePart =
                MultipartBody.Part.createFormData(
                    "image",
                    tempFile.name,
                    tempFile.asRequestBody(mimeType.toMediaType()),
                )

            val response =
                withContext(Dispatchers.IO) {
                    CrmApiService.create().parseBusinessCard(
                        authorization = authorization,
                        userId = userId.toRequestBody(textPlain),
                        image = imagePart,
                        locale = locale.toRequestBody(textPlain),
                    )
                }

            if (!response.success || response.data == null) {
                throw Exception(response.message.ifBlank { "Business card parsing failed" })
            }
            response.data
        }.also {
            tempFile?.let { file -> runCatching { if (file.exists()) file.delete() } }
        }
    }

    private fun defaultLocale(): String {
        val locale = Locale.getDefault()
        val language = locale.language.trim()
        val country = locale.country.trim()
        return when {
            language.isEmpty() -> "en_IN"
            country.isEmpty() -> language
            else -> "${language}_$country"
        }
    }

    private fun copyImageToTempFile(context: Context, uri: Uri): File? {
        val resolver = context.applicationContext.contentResolver
        val displayName = queryDisplayName(resolver, uri)
        val extension =
            displayName
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
                ?: "jpg"
        val tempFile = File(context.cacheDir, "business_card_${UUID.randomUUID()}.$extension")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) tempFile else null
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): String? {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    return cursor.getString(index)
                }
            }
        }
        return null
    }

    private fun imageMimeType(context: Context, uri: Uri, fileName: String): String {
        val fromResolver = context.contentResolver.getType(uri)?.trim().orEmpty()
        if (fromResolver.startsWith("image/")) return fromResolver
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg"
    }
}
