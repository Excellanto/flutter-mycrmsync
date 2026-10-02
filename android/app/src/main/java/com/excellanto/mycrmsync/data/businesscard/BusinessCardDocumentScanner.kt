package com.excellanto.mycrmsync.data.businesscard

import android.app.Activity
import android.net.Uri
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_BASE
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps ML Kit Document Scanner for single-page business-card capture.
 */
object BusinessCardDocumentScanner {

    private val scannerOptions: GmsDocumentScannerOptions =
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setResultFormats(RESULT_FORMAT_JPEG)
            .setScannerMode(SCANNER_MODE_BASE)
            .build()

    val scanner: GmsDocumentScanner = GmsDocumentScanning.getClient(scannerOptions)

    suspend fun getScanIntentSender(activity: Activity) =
        suspendCancellableCoroutine { continuation ->
            scanner
                .getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    continuation.resume(intentSender)
                }
                .addOnFailureListener { error ->
                    continuation.resumeWithException(error)
                }
        }

    fun firstPageUri(resultCode: Int, data: android.content.Intent?): Uri? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(data) ?: return null
        return scanResult.pages?.firstOrNull()?.imageUri
    }
}
