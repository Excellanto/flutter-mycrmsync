package com.excellanto.mycrmsync.data.businesscard

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR for cropped business-card images using ML Kit Text Recognition.
 */
object BusinessCardOcrService {

    suspend fun recognizeText(context: Context, imageUri: Uri): String =
        withContext(Dispatchers.IO) {
            val image = InputImage.fromFilePath(context, imageUri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                suspendCancellableCoroutine { continuation ->
                    recognizer
                        .process(image)
                        .addOnSuccessListener { result ->
                            continuation.resume(result.text.orEmpty())
                        }
                        .addOnFailureListener { error ->
                            continuation.resumeWithException(error)
                        }
                    continuation.invokeOnCancellation {
                        recognizer.close()
                    }
                }
            } finally {
                recognizer.close()
            }
        }
}
