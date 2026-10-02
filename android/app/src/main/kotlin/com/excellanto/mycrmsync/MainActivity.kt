package com.excellanto.mycrmsync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.businesscard.BusinessCardDocumentScanner
import com.excellanto.mycrmsync.data.businesscard.BusinessCardOcrService
import com.excellanto.mycrmsync.data.businesscard.BusinessCardParseService
import com.excellanto.mycrmsync.data.businesscard.BusinessCardTextParser
import com.excellanto.mycrmsync.data.businesscard.toAddContactForm
import com.excellanto.mycrmsync.ui.viewmodel.AddContactForm
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FlutterFragmentActivity() {
    private var pendingPostCallFingerprint: String? = null
    private var pendingScanResult: MethodChannel.Result? = null

    private val businessCardScanLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
            val pending = pendingScanResult
            pendingScanResult = null
            if (pending == null) return@registerForActivityResult
            val uri =
                BusinessCardDocumentScanner.firstPageUri(
                    activityResult.resultCode,
                    activityResult.data,
                )
            pending.success(uri?.toString())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPostCallFingerprint = NativeBridgePlugin.readPostCallFromIntent(intent)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        NativeBridgePlugin.register(this, flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.excellanto.mycrmsync/launch",
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "consumePostCallFingerprint" -> {
                    val fp = pendingPostCallFingerprint
                    pendingPostCallFingerprint = null
                    result.success(fp)
                }
                else -> result.notImplemented()
            }
        }

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            NativeBridgePlugin.BUSINESS_CARD,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "launchScanner" -> launchBusinessCardScanner(result)
                "parseImage" -> {
                    val uriStr = call.argument<String>("uri")?.trim().orEmpty()
                    if (uriStr.isEmpty()) {
                        result.error("ARGS", "uri required", null)
                        return@setMethodCallHandler
                    }
                    parseBusinessCardImage(uriStr, result)
                }
                "recognizeText" -> {
                    val uriStr = call.argument<String>("uri")?.trim().orEmpty()
                    if (uriStr.isEmpty()) {
                        result.error("ARGS", "uri required", null)
                        return@setMethodCallHandler
                    }
                    recognizeBusinessCardText(uriStr, result)
                }
                else -> result.notImplemented()
            }
        }
    }

    fun launchBusinessCardScanner(result: MethodChannel.Result) {
        if (pendingScanResult != null) {
            result.error("BUSY", "A scan is already in progress", null)
            return
        }
        pendingScanResult = result
        lifecycleScope.launch {
            try {
                val intentSender = BusinessCardDocumentScanner.getScanIntentSender(this@MainActivity)
                businessCardScanLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build(),
                )
            } catch (e: Exception) {
                pendingScanResult = null
                result.error("SCAN", e.message ?: "Unable to start document scanner.", null)
            }
        }
    }

    private fun recognizeBusinessCardText(uriStr: String, result: MethodChannel.Result) {
        lifecycleScope.launch {
            try {
                val text =
                    withContext(Dispatchers.IO) {
                        BusinessCardOcrService.recognizeText(applicationContext, Uri.parse(uriStr))
                    }
                result.success(text)
            } catch (e: Exception) {
                result.error("OCR", e.message ?: "Failed to read business card text.", null)
            }
        }
    }

    private fun parseBusinessCardImage(uriStr: String, result: MethodChannel.Result) {
        lifecycleScope.launch {
            try {
                val map =
                    withContext(Dispatchers.IO) {
                        val uri = Uri.parse(uriStr)
                        val prefs = PrefsManager(applicationContext)
                        val apiResult = BusinessCardParseService.parse(applicationContext, prefs, uri)
                        if (apiResult.isSuccess) {
                            return@withContext formToMap(apiResult.getOrThrow().toAddContactForm())
                        }

                        val apiError = apiResult.exceptionOrNull()?.message
                        val rawText = BusinessCardOcrService.recognizeText(applicationContext, uri)
                        if (rawText.isBlank()) {
                            val warning =
                                apiError?.let { "$it You can enter details manually." }
                                    ?: "No text found on the business card. You can enter details manually."
                            return@withContext formToMap(AddContactForm(), warning)
                        }

                        val warning =
                            apiError?.let { "$it Used on-device scan instead — please verify fields." }
                                ?: "Used on-device scan — please verify fields."
                        formToMap(BusinessCardTextParser.parse(rawText).toAddContactForm(), warning)
                    }
                result.success(map)
            } catch (e: Exception) {
                result.error("PARSE", e.message ?: "Failed to read business card.", null)
            }
        }
    }

    private fun formToMap(form: AddContactForm, warning: String? = null): Map<String, Any?> =
        mapOf(
            "firstName" to form.firstName,
            "lastName" to form.lastName,
            "email" to form.email,
            "phone" to form.phone,
            "companyName" to form.companyName,
            "address" to form.address,
            "city" to form.city,
            "state" to form.state,
            "postalCode" to form.postalCode,
            "country" to form.country,
            "tags" to form.tags,
            "warning" to warning,
        )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingPostCallFingerprint = NativeBridgePlugin.readPostCallFromIntent(intent)
    }
}
