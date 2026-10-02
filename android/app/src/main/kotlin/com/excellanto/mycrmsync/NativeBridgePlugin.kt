package com.excellanto.mycrmsync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import com.excellanto.mycrmsync.accessibility.WhatsAppAccessibilityHelper
import com.excellanto.mycrmsync.accessibility.WhatsAppPostCallNotifier
import com.excellanto.mycrmsync.call.OutgoingCallTracker
import com.excellanto.mycrmsync.call.OverlayPermissionHelper
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.WhatsAppCallEvents
import com.excellanto.mycrmsync.data.auth.AuthSessionManager
import com.excellanto.mycrmsync.data.local.CallLogUploadStatus
import com.excellanto.mycrmsync.data.local.CrmDatabase
import com.excellanto.mycrmsync.work.CallLogSyncScheduler
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Platform channels bridging Flutter UI to existing Android native services.
 */
object NativeBridgePlugin {
    private const val SESSION = "com.excellanto.mycrmsync/session"
    private const val PERMISSIONS = "com.excellanto.mycrmsync/permissions"
    private const val CALLS = "com.excellanto.mycrmsync/calls"
    private const val SYNC = "com.excellanto.mycrmsync/sync"
    private const val WHATSAPP = "com.excellanto.mycrmsync/whatsapp"
    private const val CALLLOG_READ = "com.excellanto.mycrmsync/calllog_read"
    private const val RECORDINGS = "com.excellanto.mycrmsync/recordings"
    private const val POST_CALL_EVENTS = "com.excellanto.mycrmsync/whatsapp_post_call"
    const val BUSINESS_CARD = "com.excellanto.mycrmsync/business_card"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun register(activity: MainActivity, flutterEngine: FlutterEngine) {
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        val appContext = activity.applicationContext

        MethodChannel(messenger, SESSION).setMethodCallHandler { call, result ->
            when (call.method) {
                "setSession" -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = call.arguments as? Map<String, Any?> ?: emptyMap()
                    applySession(appContext, map)
                    result.success(true)
                }
                "clearSession" -> {
                    PrefsManager(appContext).clearAll()
                    CallLogSyncScheduler.schedulePeriodicIfLoggedIn(appContext)
                    result.success(true)
                }
                "getNativeSession" -> {
                    val prefs = PrefsManager(appContext)
                    result.success(
                        mapOf(
                            "accessToken" to prefs.getAccessToken(),
                            "userId" to prefs.getUserId(),
                            "authUserId" to prefs.getAuthUserId(),
                            "userName" to prefs.getUserName(),
                            "isLoggedIn" to prefs.isLoggedIn(),
                        ),
                    )
                }
                else -> result.notImplemented()
            }
        }

        MethodChannel(messenger, PERMISSIONS).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestRuntimePermissions" -> {
                    val perms = buildList {
                        add(Manifest.permission.CALL_PHONE)
                        add(Manifest.permission.READ_PHONE_STATE)
                        add(Manifest.permission.READ_CALL_LOG)
                        add(Manifest.permission.READ_CONTACTS)
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.READ_MEDIA_AUDIO)
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            add(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }.toTypedArray()
                    ActivityCompat.requestPermissions(activity, perms, 1001)
                    result.success(true)
                }
                "canDrawOverlays" -> result.success(OverlayPermissionHelper.canDrawOverlays(appContext))
                "openOverlaySettings" -> {
                    OverlayPermissionHelper.openSettings(appContext)
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }

        MethodChannel(messenger, CALLS).setMethodCallHandler { call, result ->
            when (call.method) {
                "setPendingOutgoing" -> {
                    val phone = call.argument<String>("phone").orEmpty()
                    OutgoingCallTracker.setPendingOutgoing(phone)
                    result.success(true)
                }
                "placeCall" -> {
                    val phone = call.argument<String>("phone").orEmpty()
                    try {
                        OutgoingCallTracker.setPendingOutgoing(phone)
                        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
                        activity.startActivity(intent)
                        result.success(true)
                    } catch (e: SecurityException) {
                        OutgoingCallTracker.consumePendingOutgoing()
                        result.error("PERMISSION", "Call permission not granted", null)
                    } catch (e: Exception) {
                        OutgoingCallTracker.consumePendingOutgoing()
                        result.error("CALL_FAILED", e.message, null)
                    }
                }
                else -> result.notImplemented()
            }
        }

        MethodChannel(messenger, SYNC).setMethodCallHandler { call, result ->
            when (call.method) {
                "enqueueNow" -> {
                    CallLogSyncScheduler.enqueueNow(appContext)
                    result.success(true)
                }
                "schedulePeriodicIfLoggedIn" -> {
                    CallLogSyncScheduler.schedulePeriodicIfLoggedIn(appContext)
                    result.success(true)
                }
                "enqueueAfterCall" -> {
                    CallLogSyncScheduler.enqueueAfterCall(appContext)
                    result.success(true)
                }
                "enqueueOemRecordingSyncNow" -> {
                    CallLogSyncScheduler.enqueueOemRecordingSyncNow(appContext)
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }

        MethodChannel(messenger, WHATSAPP).setMethodCallHandler { call, result ->
            val prefs = PrefsManager(appContext)
            when (call.method) {
                "isAccessibilityEnabled" ->
                    result.success(WhatsAppAccessibilityHelper.isServiceEnabled(appContext))
                "openAccessibilitySettings" -> {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(intent)
                    result.success(true)
                }
                "shouldShowSetupPrompt" ->
                    result.success(WhatsAppAccessibilityHelper.shouldShowSetupPrompt(appContext))
                "rememberIfEnabled" ->
                    result.success(WhatsAppAccessibilityHelper.rememberIfEnabled(appContext))
                "isTrackingEnabled" ->
                    result.success(prefs.isWhatsAppCallTrackingEnabled())
                "setTrackingEnabled" -> {
                    prefs.setWhatsAppCallTrackingEnabled(call.argument<Boolean>("enabled") == true)
                    result.success(true)
                }
                "isPostCallNotificationsEnabled" ->
                    result.success(prefs.isWhatsAppPostCallNotificationsEnabled())
                "setPostCallNotificationsEnabled" -> {
                    prefs.setWhatsAppPostCallNotificationsEnabled(call.argument<Boolean>("enabled") == true)
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }

        MethodChannel(messenger, CALLLOG_READ).setMethodCallHandler { call, result ->
            when (call.method) {
                "getPendingCounts" -> {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val db = CrmDatabase.getInstance(appContext)
                            val pendingStatuses = listOf(
                                CallLogUploadStatus.PENDING,
                                CallLogUploadStatus.PENDING_CONTACT,
                            )
                            val pendingCalls = db.pendingCallLogRegistrationDao().countByStatus(pendingStatuses)
                            val pendingWa = db.whatsAppCallDao().countByStatus(pendingStatuses)
                            result.success(
                                mapOf(
                                    "pendingCallRegistrations" to pendingCalls,
                                    "pendingWhatsAppCalls" to pendingWa,
                                ),
                            )
                        } catch (e: Exception) {
                            result.error("DB", e.message, null)
                        }
                    }
                }
                "listNoteUploadedFingerprints" -> {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val db = CrmDatabase.getInstance(appContext)
                            val fingerprints = mutableSetOf<String>()
                            fingerprints.addAll(db.whatsAppCallDao().listRecentWithNote(300))
                            fingerprints.addAll(db.pendingCallLogRegistrationDao().listRecentWithNote(300))
                            result.success(fingerprints.toList())
                        } catch (e: Exception) {
                            result.error("DB", e.message, null)
                        }
                    }
                }
                "markCallNoteSaved" -> {
                    val fingerprint = call.argument<String>("fingerprint")?.trim().orEmpty()
                    val isWhatsApp = call.argument<Boolean>("isWhatsApp") == true
                    if (fingerprint.isEmpty()) {
                        result.error("ARGS", "fingerprint required", null)
                        return@setMethodCallHandler
                    }
                    scope.launch(Dispatchers.IO) {
                        try {
                            val db = CrmDatabase.getInstance(appContext)
                            val now = System.currentTimeMillis()
                            if (isWhatsApp) {
                                db.whatsAppCallDao().markNoteUploaded(fingerprint, now, now)
                            } else {
                                db.pendingCallLogRegistrationDao().markNoteUploaded(fingerprint, now, now)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("DB", e.message, null)
                        }
                    }
                }
                "listRecentWhatsApp" -> {
                    val limit = call.argument<Int>("limit") ?: 20
                    scope.launch(Dispatchers.IO) {
                        try {
                            val rows = CrmDatabase.getInstance(appContext).whatsAppCallDao().listRecent(limit)
                            result.success(
                                rows.map {
                                    mapOf(
                                        "syncFingerprint" to it.syncFingerprint,
                                        "displayName" to it.displayName,
                                        "direction" to it.direction,
                                        "durationSec" to it.durationSec,
                                        "startedAtIso" to it.startedAtIso,
                                        "uploadStatus" to it.uploadStatus,
                                        "contactId" to it.contactId,
                                        "crmCallLogId" to it.crmCallLogId,
                                        "noteUploaded" to (it.noteUploadedAtMs != null),
                                    )
                                },
                            )
                        } catch (e: Exception) {
                            result.error("DB", e.message, null)
                        }
                    }
                }
                "listLocalWithRecordings" -> {
                    val limit = call.argument<Int>("limit") ?: 200
                    scope.launch(Dispatchers.IO) {
                        try {
                            val rows = CrmDatabase.getInstance(appContext)
                                .localCallLogDao()
                                .listRecent(limit)
                                .filter { !it.recordingUriStr.isNullOrBlank() }
                            result.success(
                                rows.map {
                                    mapOf(
                                        "androidCallLogId" to it.androidCallLogId,
                                        "phoneNumber" to it.phoneNumber,
                                        "cachedName" to it.cachedName,
                                        "directionLow" to it.directionLow,
                                        "dateMs" to it.dateMs,
                                        "durationSec" to it.durationSec,
                                        "recordingUri" to it.recordingUriStr,
                                        "recordingDurationSec" to it.recordingDurationSec,
                                        "noteUploaded" to (it.crmCallStatusNoteUploadedAtMs != null),
                                    )
                                },
                            )
                        } catch (e: Exception) {
                            result.error("DB", e.message, null)
                        }
                    }
                }
                else -> result.notImplemented()
            }
        }

        MethodChannel(messenger, RECORDINGS).setMethodCallHandler { call, result ->
            when (call.method) {
                "syncOemNow" -> {
                    CallLogSyncScheduler.enqueueOemRecordingSyncNow(appContext)
                    result.success(true)
                }
                "resolvePlayablePath" -> {
                    val uriStr = call.argument<String>("uri")?.trim().orEmpty()
                    if (uriStr.isEmpty()) {
                        result.error("ARGS", "uri required", null)
                        return@setMethodCallHandler
                    }
                    scope.launch(Dispatchers.IO) {
                        try {
                            result.success(resolvePlayablePath(appContext, uriStr))
                        } catch (e: Exception) {
                            result.error("PLAY", e.message, null)
                        }
                    }
                }
                else -> result.notImplemented()
            }
        }

        EventChannel(messenger, POST_CALL_EVENTS).setStreamHandler(
            object : EventChannel.StreamHandler {
                private var job: kotlinx.coroutines.Job? = null

                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    if (events == null) return
                    job = scope.launch {
                        WhatsAppCallEvents.postCall.collect { payload ->
                            events.success(
                                mapOf(
                                    "syncFingerprint" to payload.syncFingerprint,
                                    "displayName" to payload.displayName,
                                    "direction" to payload.direction,
                                    "durationSec" to payload.durationSec,
                                    "startedAtIso" to payload.startedAtIso,
                                    "contactId" to payload.contactId,
                                    "crmCallLogId" to payload.crmCallLogId,
                                ),
                            )
                        }
                    }
                }

                override fun onCancel(arguments: Any?) {
                    job?.cancel()
                    job = null
                }
            },
        )
    }

    fun applySession(context: android.content.Context, map: Map<String, Any?>) {
        val prefs = context.getSharedPreferences("crm_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().apply {
            (map["accessToken"] as? String)?.let { putString("access_token", it) }
            (map["tokenType"] as? String)?.let { putString("token_type", it) }
            (map["expiresAt"] as? String)?.let { putString("token_expires_at", it) }
            (map["authUserId"] as? Number)?.toInt()?.let { putInt("auth_user_id", it) }
                ?: (map["authUserId"] as? String)?.toIntOrNull()?.let { putInt("auth_user_id", it) }
            (map["authUserName"] as? String)?.let { putString("auth_user_name", it) }
            (map["authUserEmail"] as? String)?.let { putString("auth_user_email", it) }
            (map["authTenantId"] as? Number)?.toInt()?.let { putInt("auth_tenant_id", it) }
                ?: (map["authTenantId"] as? String)?.toIntOrNull()?.let { putInt("auth_tenant_id", it) }
            (map["authIntsysuser"] as? String)?.let { putString("auth_intsysuser", it) }
            (map["authUserRole"] as? String)?.let { putString("auth_user_role", it.lowercase()) }
            (map["integratedUserId"] as? String)?.let { putString("integrated_user_id", it) }
            (map["integratedUserName"] as? String)?.let { putString("integrated_user_name", it) }
            (map["integratedUserEmail"] as? String)?.let { putString("integrated_user_email", it) }
            (map["integratedUserRole"] as? String)?.let { putString("integrated_user_role", it) }
            (map["integratedSystemSlug"] as? String)?.let { putString("integrated_system_slug", it) }
            (map["integratedSystemName"] as? String)?.let { putString("integrated_system_name", it) }
            val userId = (map["userId"] as? String)
                ?: (map["integratedUserId"] as? String)
                ?: (map["authUserId"]?.toString())
            userId?.let { putString("user_id", it) }
            (map["userName"] as? String)?.let { putString("user_name", it) }
            (map["userRole"] as? String)?.let { putString("user_role", it.lowercase()) }
            apply()
        }
        AuthSessionManager.resetAfterLogin()
        CallLogSyncScheduler.schedulePeriodicIfLoggedIn(context)
    }

    fun readPostCallFromIntent(intent: Intent?): String? {
        if (intent?.getBooleanExtra(WhatsAppPostCallNotifier.EXTRA_OPEN_POST_CALL, false) != true) {
            return null
        }
        return intent.getStringExtra(WhatsAppPostCallNotifier.EXTRA_SYNC_FINGERPRINT)
            ?.trim()
            ?.ifBlank { null }
    }

    /**
     * Turns a content:// / file:// / absolute path into something [audioplayers] can open.
     * HTTP(S) URLs are returned unchanged so Flutter can use UrlSource.
     */
    private fun resolvePlayablePath(context: Context, uriStr: String): String {
        if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
            return uriStr
        }
        val uri = Uri.parse(uriStr)
        when (uri.scheme?.lowercase()) {
            "file" -> {
                val path = uri.path
                if (!path.isNullOrBlank()) return path
            }
            "content" -> {
                val nameHint = uri.lastPathSegment?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    ?: "recording_${System.currentTimeMillis()}"
                val out = File(context.cacheDir, "crm_play_$nameHint")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Unable to open recording")
                return out.absolutePath
            }
            null -> {
                val asFile = File(uriStr)
                if (asFile.exists()) return asFile.absolutePath
            }
        }
        val asFile = File(uriStr)
        if (asFile.exists()) return asFile.absolutePath
        error("Unsupported recording URI")
    }
}
