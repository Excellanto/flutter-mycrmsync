package com.excellanto.mycrmsync.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.excellanto.mycrmsync.data.CallLogListLoader
import com.excellanto.mycrmsync.data.CallLogListScope
import com.excellanto.mycrmsync.data.CallLogRegistrationEvents
import com.excellanto.mycrmsync.data.CallLogRegistrationSync
import com.excellanto.mycrmsync.data.CallStatusNotePoster
import com.excellanto.mycrmsync.data.CrmContactDirectorySync
import com.excellanto.mycrmsync.data.WhatsAppCallRegistrationSync
import com.excellanto.mycrmsync.work.CallLogSyncScheduler
import com.excellanto.mycrmsync.data.DeviceCallLogLimits
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.api.CrmCallRecordingVoiceNote
import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.data.model.CallRecordingTranscribeData
import com.excellanto.mycrmsync.data.model.CrmCreateNoteRequest
import com.excellanto.mycrmsync.data.model.MediaProcessBatchRequest
import com.excellanto.mycrmsync.data.local.CrmDatabase
import com.excellanto.mycrmsync.data.oem.DeviceCallFingerprint
import com.excellanto.mycrmsync.data.oem.OemCallRecordingSyncService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class UiCallLogRow(
    val androidCallLogId: Long,
    /** Server row id from `POST api/crm/call-logs/get` when available. */
    val crmCallLogId: String? = null,
    val phoneRaw: String?,
    val phoneE164: String?,
    val crmContactId: String,
    val contactDisplayName: String,
    val direction: String,
    val durationSec: Int?,
    val startedAtIso: String,
    val recordingUri: String?,
    val matchedConfidence: Float?,
    val recordingDurationSec: Int?,
    val pendingCrmRecordingUpload: Boolean,
    /** True after this device successfully posted the CRM call-status note for this row. */
    val crmCallStatusNoteUploaded: Boolean,
    /** Staff name from API (shown when viewing all users' logs). */
    val staffUserName: String? = null,
    /** True when call was synced from WhatsApp accessibility tracking. */
    val isWhatsAppCall: Boolean = false,
    /** Local WhatsApp row not yet confirmed on CRM API (pending / no contact / failed). */
    val whatsAppSyncPending: Boolean = false,
) {
    /** Stable unique key for LazyColumn items; [androidCallLogId] alone is not unique. */
    fun listItemKey(): String {
        crmCallLogId?.trim()?.takeIf { it.isNotEmpty() }?.let { return "crm:$it" }
        return buildString {
            append(if (isWhatsAppCall) "wa" else "loc")
            append(':')
            append(androidCallLogId)
            append(':')
            append(startedAtIso)
            append(':')
            append(crmContactId)
            append(':')
            append(direction)
        }
    }
}

class CallLogsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = PrefsManager(app)
    private val crmApi = CrmApiService.create()
    private val db = CrmDatabase.getInstance(app)
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    fun consumeSnackbarMessage() {
        _snackbarMessage.value = null
    }

    fun consumeError() {
        _error.value = null
    }

    private val _rows = MutableStateFlow<List<UiCallLogRow>>(emptyList())
    val rows: StateFlow<List<UiCallLogRow>> = _rows.asStateFlow()

    private val _scope = MutableStateFlow(CallLogListScope.ME)
    val scope: StateFlow<CallLogListScope> = _scope.asStateFlow()

    private val _aiEnhanceState = MutableStateFlow<AiEnhanceUiState>(AiEnhanceUiState.Hidden)
    val aiEnhanceState: StateFlow<AiEnhanceUiState> = _aiEnhanceState.asStateFlow()

    private val refreshMutex = Mutex()

    fun dismissAiEnhance() {
        _aiEnhanceState.value = AiEnhanceUiState.Hidden
    }

    fun startAiEnhanceTranscription(row: UiCallLogRow) {
        uploadRecordingToAi(row)
    }

    /**
     * Same path as contact Timeline "Upload to AI" voice notes:
     * process recording → attach via media batch → appears on contact Timeline.
     */
    fun uploadRecordingToAi(row: UiCallLogRow) {
        val uriStr = row.recordingUri?.trim().orEmpty()
        if (uriStr.isEmpty()) {
            _snackbarMessage.value = "No recording attached to this call"
            return
        }
        val contactId = row.crmContactId.trim()
        if (contactId.isEmpty()) {
            _snackbarMessage.value = "No CRM contact linked to this call"
            return
        }
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull()
        if (uri == null) {
            _snackbarMessage.value = "Invalid recording location"
            return
        }
        viewModelScope.launch {
            _aiEnhanceState.value = AiEnhanceUiState.Transcribing(row.contactDisplayName)
            try {
                val (authorization, userId) = prefs.requireCrmSession()
                val callStatusBody =
                    CallStatusNotePoster.formatBody(
                        contactDisplayName = row.contactDisplayName,
                        direction = row.direction,
                        durationSec = row.durationSec,
                        startedAtIso = row.startedAtIso,
                        phoneE164 = row.phoneE164,
                        phoneRaw = row.phoneRaw,
                        isWhatsAppCall = row.isWhatsAppCall,
                        hasRecording = true,
                    )
                val processed =
                    withContext(Dispatchers.IO) {
                        CrmCallRecordingVoiceNote.process(
                            context = getApplication(),
                            api = crmApi,
                            authorization = authorization,
                            userId = userId,
                            contactId = contactId,
                            recordingUri = uri,
                            existingNoteText = callStatusBody,
                        ).getOrThrow()
                    }
                withContext(Dispatchers.IO) {
                    crmApi.processMediaBatch(
                        authorization = authorization,
                        request =
                            MediaProcessBatchRequest(
                                userId = userId,
                                contactId = contactId,
                                existingNoteText =
                                    processed.noteBody.trim().ifBlank {
                                        buildString {
                                            appendLine(callStatusBody.trimEnd())
                                            appendLine()
                                            appendLine(processed.summary.trim())
                                            appendLine()
                                            appendLine(processed.transcription.trim())
                                        }
                                    },
                                voiceNoteIds = listOf(processed.voiceNoteId),
                            ),
                    )
                    markCallStatusNoteUploaded(row)
                }
                _rows.value =
                    _rows.value.map {
                        if (it.listItemKey() == row.listItemKey() ||
                            it.androidCallLogId == row.androidCallLogId
                        ) {
                            it.copy(crmCallStatusNoteUploaded = true)
                        } else {
                            it
                        }
                    }
                _snackbarMessage.value = "Recording uploaded to AI and posted to Timeline"
                _aiEnhanceState.value = AiEnhanceUiState.Hidden
            } catch (e: Exception) {
                _aiEnhanceState.value =
                    AiEnhanceUiState.Failed(e.message ?: "Upload to AI failed")
            }
        }
    }

    private suspend fun markCallStatusNoteUploaded(row: UiCallLogRow) {
        val now = System.currentTimeMillis()
        db.localCallLogDao().setCallStatusNoteUploadedAt(row.androidCallLogId, now)
        val local = db.localCallLogDao().getByAndroidId(row.androidCallLogId)
        val fp =
            local?.let { DeviceCallFingerprint.fromLocalEntity(it) }
                ?: if (row.isWhatsAppCall) {
                    db.whatsAppCallDao()
                        .listRecent(50)
                        .firstOrNull {
                            it.crmCallLogId == row.crmCallLogId ||
                                (it.startedAtIso == row.startedAtIso &&
                                    it.contactId == row.crmContactId)
                        }
                        ?.syncFingerprint
                } else {
                    null
                }
        if (row.isWhatsAppCall && fp != null) {
            db.whatsAppCallDao().markNoteUploaded(fp, now, now)
        } else if (fp != null) {
            db.pendingCallLogRegistrationDao().markNoteUploaded(fp, now, now)
        }
    }

    fun postAiEnhancementToCrm() {
        val state = _aiEnhanceState.value
        if (state !is AiEnhanceUiState.Ready) return
        val row = state.row
        val result = state.result
        viewModelScope.launch {
            try {
                val (authorization, userId) = prefs.requireCrmSession()
                val body = formatCallWithTranscriptionNoteBody(row, result)
                withContext(Dispatchers.IO) {
                    crmApi.createContactNote(
                        authorization = authorization,
                        request =
                            CrmCreateNoteRequest(
                                userId = userId,
                                contactId = row.crmContactId,
                                body = body,
                            ),
                    )
                    markCallStatusNoteUploaded(row)
                }
                _rows.value =
                    _rows.value.map {
                        if (it.androidCallLogId == row.androidCallLogId) {
                            it.copy(crmCallStatusNoteUploaded = true)
                        } else {
                            it
                        }
                    }
                _snackbarMessage.value = "CRM note with transcription posted"
                _aiEnhanceState.value = AiEnhanceUiState.Hidden
            } catch (e: Exception) {
                _snackbarMessage.value = e.localizedMessage ?: "Could not post CRM note"
            }
        }
    }

    fun setScope(scope: CallLogListScope) {
        if (_scope.value == scope) return
        _scope.value = scope
        refresh(showBlockingLoader = _rows.value.isEmpty())
    }

    fun refresh(showBlockingLoader: Boolean = true, skipRegistrationSync: Boolean = false) {
        viewModelScope.launch {
            refreshMutex.withLock {
                val listEmpty = _rows.value.isEmpty()
                if (showBlockingLoader && listEmpty) {
                    _isLoading.value = true
                } else {
                    _isRefreshing.value = true
                }
                _error.value = null
                try {
                val (authorization, userId) = prefs.requireCrmSession()
                val listScope = _scope.value
                val meUserId =
                    prefs.getRegisterUserId()?.toString()
                        ?: userId

                withContext(Dispatchers.IO) {
                    runCatching { OemCallRecordingSyncService.instance.syncOnce(getApplication(), db) }
                }

                if (!skipRegistrationSync) {
                    withContext(Dispatchers.IO) {
                        runCatching { CallLogRegistrationSync(getApplication()).sync() }
                        runCatching { WhatsAppCallRegistrationSync(getApplication()).sync() }
                    }
                    CallLogSyncScheduler.enqueueNow(getApplication())
                }

                withContext(Dispatchers.IO) {
                    val refreshed =
                        CrmContactDirectorySync.ensurePhoneLookupIndex(
                            api = crmApi,
                            authorization = authorization,
                            userId = userId,
                            contactDao = db.crmContactPhoneDao(),
                            lastSyncedAtMs = prefs.getCrmContactDirectorySyncedAt(),
                        )
                    if (refreshed) {
                        prefs.setCrmContactDirectorySyncedAt(System.currentTimeMillis())
                    }
                }

                val fetchResult =
                    withContext(Dispatchers.IO) {
                        CallLogListLoader.fetchFromApi(
                            api = crmApi,
                            authorization = authorization,
                            scope = listScope,
                            meUserId = meUserId,
                            limit = DeviceCallLogLimits.UI_LIST_MAX,
                        )
                    }

                _rows.value =
                    withContext(Dispatchers.IO) {
                        CallLogListLoader.toUiRows(fetchResult.items, db)
                    }
                } catch (e: Exception) {
                    _error.value = e.localizedMessage ?: "Failed to load call logs"
                } finally {
                    _isLoading.value = false
                    _isRefreshing.value = false
                }
            }
        }
    }

    fun setPendingCrmRecordingUpload(androidCallLogId: Long, pending: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.localCallLogDao().setPendingCrmRecordingUpload(androidCallLogId, pending)
            }
            refresh(showBlockingLoader = false)
        }
    }

    fun deleteLocalRecording(androidCallLogId: Long, onFinished: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val match = db.localCallLogDao().getByAndroidId(androidCallLogId)
                    val uriStr = match?.recordingUriStr
                    if (!uriStr.isNullOrBlank()) {
                        runCatching {
                            val uri = Uri.parse(uriStr)
                            when (uri.scheme?.lowercase()) {
                                "file" -> uri.path?.let { File(it).delete() }
                                "content" ->
                                    getApplication<Application>().contentResolver.delete(uri, null, null)
                            }
                        }
                    }
                    db.localCallLogDao().clearRecording(androidCallLogId)
                }
                refresh(showBlockingLoader = false)
            } finally {
                onFinished()
            }
        }
    }

    fun uploadNotesForFilteredRows(visibleRows: List<UiCallLogRow>) {
        val pending = visibleRows.filterNot { it.crmCallStatusNoteUploaded }
        if (pending.isEmpty()) {
            _snackbarMessage.value = "Nothing to upload (filtered list is empty or all already posted)"
            return
        }
        uploadNotesForRows(pending)
    }

    fun uploadNoteForSingleRow(row: UiCallLogRow) {
        if (row.crmCallStatusNoteUploaded) return
        uploadNotesForRows(listOf(row))
    }

    private fun uploadNotesForRows(rows: List<UiCallLogRow>) {
        if (rows.isEmpty()) return
        viewModelScope.launch {
            val authorization: String
            val userId: String
            try {
                val session = prefs.requireCrmSession()
                authorization = session.first
                userId = session.second
            } catch (e: Exception) {
                _snackbarMessage.value = e.localizedMessage ?: "Not logged in"
                return@launch
            }
            val succeeded = ArrayList<Long>(rows.size)
            var firstError: String? = null
            withContext(Dispatchers.IO) {
                val dao = db.localCallLogDao()
                for (row in rows) {
                    val result =
                        runCatching {
                            val body =
                                CallStatusNotePoster.formatBody(
                                    contactDisplayName = row.contactDisplayName,
                                    direction = row.direction,
                                    durationSec = row.durationSec,
                                    startedAtIso = row.startedAtIso,
                                    phoneE164 = row.phoneE164,
                                    phoneRaw = row.phoneRaw,
                                    isWhatsAppCall = row.isWhatsAppCall,
                                    hasRecording = !row.recordingUri.isNullOrBlank(),
                                )
                            crmApi.createContactNote(
                                authorization = authorization,
                                request =
                                    CrmCreateNoteRequest(
                                        userId = userId,
                                        contactId = row.crmContactId,
                                        body = body,
                                    ),
                            )
                            markCallStatusNoteUploaded(row)
                        }
                    result.onSuccess { succeeded.add(row.androidCallLogId) }
                    result.onFailure { e ->
                        if (firstError == null) {
                            firstError = e.localizedMessage
                        }
                    }
                }
            }
            if (succeeded.isNotEmpty()) {
                _rows.value =
                    _rows.value.map {
                        if (it.androidCallLogId in succeeded) {
                            it.copy(crmCallStatusNoteUploaded = true)
                        } else {
                            it
                        }
                    }
            }
            _snackbarMessage.value =
                when {
                    succeeded.size == rows.size -> "Posted ${succeeded.size} CRM note(s)"
                    succeeded.isEmpty() -> firstError ?: "Could not post CRM notes"
                    else ->
                        buildString {
                            append("Posted ${succeeded.size} of ${rows.size} CRM note(s)")
                            firstError?.let { append(" — "); append(it) }
                        }
                }
        }
    }

    init {
        refresh()
        viewModelScope.launch {
            CallLogRegistrationEvents.registrations.collect {
                refresh(showBlockingLoader = false, skipRegistrationSync = true)
            }
        }
    }
}

private fun formatCallWithTranscriptionNoteBody(
    row: UiCallLogRow,
    result: CallRecordingTranscribeData,
): String {
    val engineLabel =
        result.transcriptionEngineLabel?.trim()?.takeIf { it.isNotEmpty() }
            ?: result.transcriptionBackend?.trim()?.takeIf { it.isNotEmpty() }
            ?: "AI"
    return buildString {
        appendLine(
            CallStatusNotePoster.formatBody(
                contactDisplayName = row.contactDisplayName,
                direction = row.direction,
                durationSec = row.durationSec,
                startedAtIso = row.startedAtIso,
                phoneE164 = row.phoneE164,
                phoneRaw = row.phoneRaw,
                isWhatsAppCall = row.isWhatsAppCall,
                hasRecording = !row.recordingUri.isNullOrBlank(),
            ).trimEnd(),
        )
        appendLine()
        appendLine("📝 Call transcription ($engineLabel)")
        result.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { summary ->
            appendLine()
            appendLine("Summary:")
            appendLine(summary)
        }
        appendLine()
        appendLine("Transcription:")
        appendLine(result.transcription.trim())
        result.sentiment?.let { sentiment ->
            appendLine()
            appendLine("Sentiment: ${sentiment.overall?.replaceFirstChar { it.uppercase() } ?: "—"}")
            sentiment.customer?.label?.let { label ->
                appendLine("Customer: $label")
            }
            sentiment.agent?.label?.let { label ->
                appendLine("Agent: $label")
            }
            sentiment.highlights?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { highlights ->
                appendLine("Highlights:")
                highlights.forEach { appendLine("• $it") }
            }
        }
        result.recordingUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { url ->
            appendLine()
            appendLine("Recording: $url")
        }
    }
}
