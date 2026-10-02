package com.excellanto.mycrmsync.ui.businesscard

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.businesscard.BusinessCardDocumentScanner
import com.excellanto.mycrmsync.data.businesscard.BusinessCardOcrService
import com.excellanto.mycrmsync.data.businesscard.BusinessCardParseService
import com.excellanto.mycrmsync.data.businesscard.BusinessCardTextParser
import com.excellanto.mycrmsync.data.businesscard.toAddContactForm
import com.excellanto.mycrmsync.ui.viewmodel.AddContactForm
import kotlinx.coroutines.launch

/**
 * Composable hook that launches ML Kit Document Scanner, uploads the cropped image to the CRM
 * business-card parse API, and returns a pre-filled [AddContactForm]. Falls back to on-device
 * OCR when the API is unavailable.
 */
@Composable
fun rememberBusinessCardScanHandler(
    onScanStarted: () -> Unit,
    onParsed: (AddContactForm) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val prefsManager = remember { PrefsManager(context) }

    val scanResultLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult(),
        ) { activityResult ->
            val imageUri =
                BusinessCardDocumentScanner.firstPageUri(
                    activityResult.resultCode,
                    activityResult.data,
                )
            if (imageUri == null) {
                return@rememberLauncherForActivityResult
            }

            onScanStarted()

            scope.launch {
                try {
                    val apiResult = BusinessCardParseService.parse(context, prefsManager, imageUri)
                    if (apiResult.isSuccess) {
                        onParsed(apiResult.getOrThrow().toAddContactForm())
                        return@launch
                    }

                    val apiError = apiResult.exceptionOrNull()?.message
                    val rawText = BusinessCardOcrService.recognizeText(context, imageUri)
                    if (rawText.isBlank()) {
                        onError(
                            apiError?.let { "$it You can enter details manually." }
                                ?: "No text found on the business card. You can enter details manually.",
                        )
                        onParsed(AddContactForm())
                        return@launch
                    }

                    onError(
                        apiError?.let { "$it Used on-device scan instead — please verify fields." }
                            ?: "Used on-device scan — please verify fields.",
                    )
                    onParsed(BusinessCardTextParser.parse(rawText).toAddContactForm())
                } catch (e: Exception) {
                    onError(e.message ?: "Failed to read business card.")
                    onParsed(AddContactForm())
                }
            }
        }

    return remember(activity, scanResultLauncher, scope) {
        {
            val hostActivity = activity
            if (hostActivity == null) {
                onError("Unable to open scanner.")
            } else {
                scope.launch {
                    try {
                        val intentSender = BusinessCardDocumentScanner.getScanIntentSender(hostActivity)
                        scanResultLauncher.launch(
                            IntentSenderRequest.Builder(intentSender).build(),
                        )
                    } catch (e: Exception) {
                        onError(e.message ?: "Unable to start document scanner.")
                    }
                }
            }
        }
    }
}
