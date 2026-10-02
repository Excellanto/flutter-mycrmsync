package com.excellanto.mycrmsync.ui.viewmodel

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.TimelineAttachmentKind
import com.excellanto.mycrmsync.data.TimelineNoteParser
import com.excellanto.mycrmsync.data.VoiceRecorder
import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.data.api.GhlApiService
import com.excellanto.mycrmsync.data.model.CrmCreateNoteRequest
import com.excellanto.mycrmsync.data.model.CrmDeleteNoteRequest
import com.excellanto.mycrmsync.data.model.CrmNote
import com.excellanto.mycrmsync.data.model.CrmNoteAttachment
import com.excellanto.mycrmsync.data.model.CrmUpdateNoteRequest
import com.excellanto.mycrmsync.data.model.MediaProcessBatchRequest
import com.excellanto.mycrmsync.data.model.GhlUser
import com.excellanto.mycrmsync.data.model.VoiceNoteProcessData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

data class NoteAttachmentPreview(
    val voiceNoteId: String,
    val name: String,
    val url: String,
    val fileType: String,
    val previewUri: Uri? = null,
    val shortUrl: String? = null,
) {
    val isImage: Boolean
        get() = fileType.equals("image", ignoreCase = true) ||
            fileType.lowercase() in IMAGE_FILE_TYPES

    /** Coil model — local picker URI when available, otherwise remote URL. */
    val displayModel: Any
        get() = previewUri ?: url

    /** Remote URLs sent on note update (long + short when both exist). */
    fun urlsForApi(): List<String> =
        listOfNotNull(
            url.trim().takeIf { it.isNotBlank() },
            shortUrl?.trim()?.takeIf { it.isNotBlank() },
        ).distinct()

    companion object {
        private val IMAGE_FILE_TYPES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }
}

data class NoteDraft(
    val contactId: String = "",
    val userId: String = "",
    val typedText: String = "",
    val voiceNoteIds: List<String> = emptyList(),
    val attachments: List<NoteAttachmentPreview> = emptyList(),
)

data class ProcessedVoiceNote(
    val voiceNoteId: String,
    val recordingUrl: String,
    val recordingUrlLong: String,
    val fileName: String,
    val summary: String,
    val transcription: String,
    val noteBody: String,
    val durationSec: Int?,
    val transcriptionEngineLabel: String?,
)

private data class NoteEditSnapshot(
    val typedText: String,
    val voiceNoteIds: List<String>,
    val attachments: List<NoteAttachmentPreview>,
    val voiceSummary: String,
)

class ContactNotesViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "ContactNotesVM"
    private val crmApiService = CrmApiService.create()
    private val ghlApiService = GhlApiService.create()
    private val prefsManager = PrefsManager(application)
    private val voiceRecorder = VoiceRecorder(application)

    companion object {
        private const val MAX_FILE_BYTES = 50L * 1024 * 1024
    }
    
    private var mediaPlayer: MediaPlayer? = null

    private val _notes = MutableStateFlow<List<CrmNote>>(emptyList())
    val notes: StateFlow<List<CrmNote>> = _notes.asStateFlow()

    private val _users = MutableStateFlow<Map<String, GhlUser>>(emptyMap())
    val users: StateFlow<Map<String, GhlUser>> = _users.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isProcessingVoiceNote = MutableStateFlow(false)
    val isProcessingVoiceNote: StateFlow<Boolean> = _isProcessingVoiceNote.asStateFlow()

    private val _isSavingNote = MutableStateFlow(false)
    val isSavingNote: StateFlow<Boolean> = _isSavingNote.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _hasRecordedAudio = MutableStateFlow(false)
    val hasRecordedAudio: StateFlow<Boolean> = _hasRecordedAudio.asStateFlow()

    private val _amplitudes = MutableStateFlow<List<Float>>(emptyList())
    val amplitudes: StateFlow<List<Float>> = _amplitudes.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun consumeError() {
        _error.value = null
    }

    private val _processedVoiceNote = MutableStateFlow<ProcessedVoiceNote?>(null)
    val processedVoiceNote: StateFlow<ProcessedVoiceNote?> = _processedVoiceNote.asStateFlow()

    private val _draft = MutableStateFlow(NoteDraft())
    val draft: StateFlow<NoteDraft> = _draft.asStateFlow()

    private val _editSnapshot = MutableStateFlow<NoteEditSnapshot?>(null)

    val hasEditChanges: StateFlow<Boolean> =
        combine(_draft, _processedVoiceNote, _editSnapshot) { draft, voiceNote, baseline ->
            if (baseline == null) return@combine false
            captureEditSnapshot(draft, voiceNote) != baseline
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isUploadingAttachment = MutableStateFlow(false)
    val isUploadingAttachment: StateFlow<Boolean> = _isUploadingAttachment.asStateFlow()

    private var currentRecordingPath: String? = null
    private var amplitudeJob: Job? = null

    init {
        fetchUsers()
    }

    private fun fetchUsers() {
        viewModelScope.launch {
            try {
                val token = prefsManager.getApiKey() ?: return@launch
                val locationId = prefsManager.getLocationId() ?: return@launch
                val response = ghlApiService.getUsers("Bearer $token", locationId)
                _users.value = response.users.associateBy { it.id }
            } catch (e: Exception) {
                Log.e(TAG, "Sync Users Failed: ${e.message}")
            }
        }
    }

    fun initDraft(contactId: String) {
        val userId = prefsManager.getAuthUserId().orEmpty()
        _draft.value = NoteDraft(contactId = contactId, userId = userId)
    }

    fun updateTypedText(text: String) {
        _draft.value = _draft.value.copy(typedText = text)
    }

    fun clearDraft() {
        _editSnapshot.value = null
        _draft.value = NoteDraft(
            contactId = _draft.value.contactId,
            userId = _draft.value.userId,
        )
        _processedVoiceNote.value = null
        _hasRecordedAudio.value = false
        _amplitudes.value = emptyList()
        currentRecordingPath = null
    }

    private fun captureEditSnapshot(
        draft: NoteDraft = _draft.value,
        processedVoiceNote: ProcessedVoiceNote? = _processedVoiceNote.value,
    ): NoteEditSnapshot =
        NoteEditSnapshot(
            typedText = draft.typedText,
            voiceNoteIds = draft.voiceNoteIds.sorted(),
            attachments = draft.attachments,
            voiceSummary = processedVoiceNote?.summary.orEmpty(),
        )

    fun loadNoteForEdit(note: CrmNote) {
        val parsed = TimelineNoteParser.parse(note)
        val spareVoiceNoteIds =
            (
                note.voiceNoteIds.orEmpty() +
                    note.attachments.orEmpty().mapNotNull { it.voiceNoteId?.trim()?.takeIf { id -> id.isNotBlank() } }
            ).distinct().toMutableList()

        val attachmentPreviews = mutableListOf<NoteAttachmentPreview>()

        note.attachments.orEmpty().forEach { structured ->
            val timeline = TimelineNoteParser.classifyStructuredAttachment(structured) ?: return@forEach
            when (timeline.kind) {
                TimelineAttachmentKind.IMAGE,
                TimelineAttachmentKind.DOCUMENT,
                -> {
                    val voiceNoteId =
                        timeline.voiceNoteId?.takeIf { it.isNotBlank() }
                            ?: spareVoiceNoteIds.firstOrNull { id -> !id.startsWith("existing_") }?.also {
                                spareVoiceNoteIds.remove(it)
                            }
                            ?: "existing_${timeline.url.hashCode()}"
                    attachmentPreviews.add(
                        NoteAttachmentPreview(
                            voiceNoteId = voiceNoteId,
                            name = timeline.name,
                            url = timeline.url,
                            fileType =
                                timeline.fileType?.takeIf { it.isNotBlank() }
                                    ?: fileTypeFromTimelineKind(timeline.kind),
                            shortUrl = timeline.shortUrl,
                        ),
                    )
                }
                TimelineAttachmentKind.AUDIO -> Unit
            }
        }

        parsed.attachments
            .filter {
                it.kind == TimelineAttachmentKind.IMAGE ||
                    it.kind == TimelineAttachmentKind.DOCUMENT
            }
            .forEach { attachment ->
                if (attachmentPreviews.any { it.url == attachment.url }) return@forEach
                val voiceNoteId =
                    attachment.voiceNoteId?.takeIf { it.isNotBlank() }
                        ?: spareVoiceNoteIds.firstOrNull { id -> !id.startsWith("existing_") }?.also {
                            spareVoiceNoteIds.remove(it)
                        }
                        ?: "existing_${attachment.url.hashCode()}"
                attachmentPreviews.add(
                    NoteAttachmentPreview(
                        voiceNoteId = voiceNoteId,
                        name = attachment.name,
                        url = attachment.url,
                        fileType =
                            attachment.fileType?.takeIf { it.isNotBlank() }
                                ?: fileTypeFromTimelineKind(attachment.kind),
                        shortUrl = attachment.shortUrl,
                    ),
                )
            }

        val audioAttachment =
            TimelineNoteParser.voicePlaybackAttachments(note).firstOrNull()
                ?: parsed.attachments.firstOrNull { it.kind == TimelineAttachmentKind.AUDIO }

        val voiceNoteIds =
            (
                note.voiceNoteIds.orEmpty() +
                    attachmentPreviews.map { it.voiceNoteId } +
                    listOfNotNull(audioAttachment?.voiceNoteId)
            ).distinct().filter { it.isNotBlank() && !it.startsWith("pending_") }

        _draft.value =
            NoteDraft(
                contactId = note.contactId,
                userId = prefsManager.getAuthUserId().orEmpty(),
                typedText = parsed.introText,
                voiceNoteIds = voiceNoteIds,
                attachments = attachmentPreviews,
            )

        _processedVoiceNote.value =
            if (audioAttachment != null || parsed.hasVoiceContent) {
                val playbackLong =
                    audioAttachment?.url?.takeIf { it.isNotBlank() }
                        ?: audioAttachment?.shortUrl.orEmpty()
                val playbackShort =
                    audioAttachment?.shortUrl?.takeIf { it.isNotBlank() && it != playbackLong }
                        ?: audioAttachment?.url.orEmpty()
                ProcessedVoiceNote(
                    voiceNoteId =
                        audioAttachment?.voiceNoteId?.takeIf { it.isNotBlank() }
                            ?: note.voiceNoteIds.orEmpty().firstOrNull().orEmpty(),
                    recordingUrl = playbackShort,
                    recordingUrlLong = playbackLong,
                    fileName = audioAttachment?.name ?: "Voice Note",
                    summary = parsed.summary.orEmpty(),
                    transcription = parsed.transcription.orEmpty(),
                    noteBody = note.body.orEmpty(),
                    durationSec = null,
                    transcriptionEngineLabel = null,
                )
            } else {
                null
            }
        _hasRecordedAudio.value = false
        _amplitudes.value = emptyList()
        currentRecordingPath = null
        _editSnapshot.value = captureEditSnapshot()
    }

    private fun fileTypeFromTimelineKind(kind: TimelineAttachmentKind): String =
        when (kind) {
            TimelineAttachmentKind.IMAGE -> "image"
            TimelineAttachmentKind.AUDIO -> "mp3"
            TimelineAttachmentKind.DOCUMENT -> "pdf"
        }

    private fun draftVoiceNoteIdsForApi(draft: NoteDraft): List<String> =
        (draft.voiceNoteIds + draft.attachments.map { it.voiceNoteId })
            .distinct()
            .filter {
                it.isNotBlank() &&
                    !it.startsWith("existing_") &&
                    !it.startsWith("pending_")
            }

    /** Text sent as required `body` on note create/update. */
    private fun resolveNoteBodyForApi(draft: NoteDraft): String {
        val typed = draft.typedText.trim()
        if (typed.isNotEmpty()) return typed
        return _processedVoiceNote.value?.noteBody?.trim().orEmpty()
    }

    /** Attachment URLs still present in the edit draft (images, files, voice). */
    private fun draftUrlsForApi(draft: NoteDraft): List<String> {
        val urls = linkedSetOf<String>()

        fun addUrl(raw: String?) {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.startsWith("http", ignoreCase = true)) {
                urls.add(trimmed)
            }
        }

        draft.attachments.forEach { preview ->
            preview.urlsForApi().forEach { addUrl(it) }
        }

        _processedVoiceNote.value?.let { voice ->
            addUrl(voice.recordingUrlLong)
            addUrl(voice.recordingUrl)
        }

        return urls.toList()
    }

    fun removeAttachment(voiceNoteId: String) {
        val draft = _draft.value
        _draft.value = draft.copy(
            voiceNoteIds = draft.voiceNoteIds.filter { it != voiceNoteId },
            attachments = draft.attachments.filter { it.voiceNoteId != voiceNoteId },
        )
        if (_processedVoiceNote.value?.voiceNoteId == voiceNoteId) {
            _processedVoiceNote.value = null
        }
    }

    private fun addVoiceNoteId(voiceNoteId: String) {
        val draft = _draft.value
        if (voiceNoteId.isBlank() || voiceNoteId in draft.voiceNoteIds) return
        _draft.value = draft.copy(voiceNoteIds = draft.voiceNoteIds + voiceNoteId)
    }

    private fun addAttachmentPreview(preview: NoteAttachmentPreview) {
        val draft = _draft.value
        if (draft.attachments.any { it.voiceNoteId == preview.voiceNoteId }) return
        _draft.value = draft.copy(
            voiceNoteIds = (draft.voiceNoteIds + preview.voiceNoteId).distinct(),
            attachments = draft.attachments + preview,
        )
    }

    private fun replaceAttachmentPreview(replaceVoiceNoteId: String, preview: NoteAttachmentPreview) {
        val draft = _draft.value
        _draft.value = draft.copy(
            voiceNoteIds =
                (draft.voiceNoteIds.filter { it != replaceVoiceNoteId } + preview.voiceNoteId)
                    .distinct(),
            attachments =
                draft.attachments.map { attachment ->
                    if (attachment.voiceNoteId == replaceVoiceNoteId) preview else attachment
                },
        )
    }

    fun fetchNotes(contactId: String) {
        viewModelScope.launch {
            fetchNotesInternal(contactId, showLoading = true)
        }
    }

    private suspend fun fetchNotesInternal(contactId: String, showLoading: Boolean = true) {
        if (showLoading) _isLoading.value = true
        _error.value = null
        try {
            val authorization = prefsManager.getBearerAuthorization() ?: throw Exception("CRM access token not found")
            val userId = prefsManager.getAuthUserId() ?: throw Exception("CRM user id not found")
            val response = crmApiService.getContactNotes(
                authorization = authorization,
                userId = userId,
                contactId = contactId,
                contactIdSnake = contactId,
            )
            val notesList = parseNotesResponse(response, contactId)
            _notes.value = sortNotesDesc(notesList)
        } catch (e: Exception) {
            _error.value = e.toUserMessage("Failed to fetch notes")
        } finally {
            if (showLoading) _isLoading.value = false
        }
    }

    fun saveNote(contactId: String) {
        viewModelScope.launch {
            _isSavingNote.value = true
            _error.value = null
            try {
                val authorization =
                    prefsManager.getBearerAuthorization()
                        ?: throw Exception("CRM access token not found")
                val crmUserId =
                    prefsManager.getAuthUserId()
                        ?: throw Exception("CRM user id not found")
                val draft = _draft.value
                val typedText = draft.typedText.trim()
                val voiceNoteIds = draftVoiceNoteIdsForApi(draft)
                val hasMedia = voiceNoteIds.isNotEmpty()

                if (hasMedia) {
                    val response =
                        withContext(Dispatchers.IO) {
                            crmApiService.processMediaBatch(
                                authorization = authorization,
                                request = MediaProcessBatchRequest(
                                    userId = crmUserId,
                                    contactId = contactId,
                                    existingNoteText = typedText.takeIf { it.isNotEmpty() },
                                    voiceNoteIds = voiceNoteIds,
                                ),
                            )
                        }
                    val createdNotes = parseNotesResponse(response, contactId)
                    clearDraft()
                    refreshNotesAfterSave(contactId, createdNotes)
                } else if (typedText.isNotBlank()) {
                    val response =
                        withContext(Dispatchers.IO) {
                            crmApiService.createContactNote(
                                authorization = authorization,
                                request = CrmCreateNoteRequest(
                                    userId = crmUserId,
                                    contactId = contactId,
                                    body = typedText,
                                ),
                            )
                        }
                    val createdNotes = parseNotesResponse(response, contactId).ifEmpty {
                        listOf(
                            CrmNote(
                                id = "local_${System.currentTimeMillis()}",
                                body = typedText,
                                contactId = contactId,
                                userId = crmUserId,
                                dateAdded = Instant.now().toString(),
                                createdAt = Instant.now().toString(),
                            ),
                        )
                    }
                    clearDraft()
                    refreshNotesAfterSave(contactId, createdNotes)
                }
            } catch (e: Exception) {
                _error.value = e.toUserMessage("Failed to save note")
            } finally {
                _isSavingNote.value = false
            }
        }
    }

    fun startRecording() {
        try {
            stopPlayback()
            _error.value = null
            _processedVoiceNote.value = null
            currentRecordingPath = voiceRecorder.startRecording()
            _isRecording.value = true
            _hasRecordedAudio.value = false
            _amplitudes.value = emptyList()
            startAmplitudeTracking()
        } catch (e: Exception) {
            _error.value = "Could not start recording"
        }
    }

    private fun startAmplitudeTracking() {
        amplitudeJob?.cancel()
        amplitudeJob = viewModelScope.launch {
            while (_isRecording.value) {
                val amp = voiceRecorder.getMaxAmplitude().toFloat()
                val normalized = (amp / 32767f).coerceIn(0.1f, 1f)
                _amplitudes.value = (_amplitudes.value + normalized).takeLast(40)
                delay(100)
            }
        }
    }

    fun stopRecording() {
        _isRecording.value = false
        amplitudeJob?.cancel()
        voiceRecorder.stopRecording()
        _hasRecordedAudio.value = true
    }

    fun deleteRecording() {
        stopPlayback()
        currentRecordingPath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }
        _hasRecordedAudio.value = false
        _amplitudes.value = emptyList()
        currentRecordingPath = null
        _processedVoiceNote.value = null
    }

    fun clearProcessedVoiceNote() {
        stopPlayback()
        _processedVoiceNote.value?.voiceNoteId?.let { removeAttachment(it) }
        _processedVoiceNote.value = null
    }

    fun updateProcessedVoiceNoteSummary(newSummary: String) {
        val current = _processedVoiceNote.value ?: return
        val updatedNoteBody =
            if (current.summary.isNotBlank() && current.noteBody.contains(current.summary)) {
                current.noteBody.replace(current.summary, newSummary)
            } else {
                current.noteBody
            }
        _processedVoiceNote.value = current.copy(summary = newSummary, noteBody = updatedNoteBody)
    }

    fun playRecording() {
        val path = currentRecordingPath ?: return
        if (_isPlaying.value) {
            stopPlayback()
        } else {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
                _isPlaying.value = true
                setOnCompletionListener {
                    _isPlaying.value = false
                    stopPlayback()
                }
            }
        }
    }

    fun playProcessedVoiceNote() {
        val voice = _processedVoiceNote.value ?: return
        val candidates =
            listOfNotNull(
                voice.recordingUrlLong.trim().takeIf { it.isNotBlank() },
                voice.recordingUrl.trim().takeIf { it.isNotBlank() },
            ).distinct()
        if (candidates.isEmpty()) {
            _error.value = "No playback URL for this voice note"
            return
        }
        if (_isPlaying.value) {
            stopPlayback()
            return
        }
        playRemoteAudio(candidates, "Could not play voice note")
    }

    private fun playRemoteAudio(candidates: List<String>, errorMessage: String) {
        var index = 0
        fun tryNext() {
            if (index >= candidates.size) {
                _error.value = errorMessage
                stopPlayback()
                return
            }
            val url = candidates[index++]
            stopPlayback()
            mediaPlayer =
                MediaPlayer().apply {
                    setDataSource(url)
                    prepareAsync()
                    setOnPreparedListener {
                        start()
                        _isPlaying.value = true
                    }
                    setOnCompletionListener {
                        _isPlaying.value = false
                        stopPlayback()
                    }
                    setOnErrorListener { _, _, _ ->
                        tryNext()
                        true
                    }
                }
        }
        tryNext()
    }

    private fun stopPlayback() {
        runCatching { mediaPlayer?.stop() }
        mediaPlayer?.release()
        mediaPlayer = null
        _isPlaying.value = false
    }

    fun uploadAndProcessRecording(contactId: String, currentText: String) {
        viewModelScope.launch {
            _error.value = null
            try {
                stopPlayback()
                val path =
                    currentRecordingPath ?: voiceRecorder.getOutputPath()
                        ?: throw Exception("Recording not found. Please record again.")
                val file = File(path)
                if (!file.exists() || file.length() == 0L) {
                    _hasRecordedAudio.value = false
                    currentRecordingPath = null
                    throw Exception("Recording file is empty. Please record again.")
                }
                currentRecordingPath = path

                // Let the button click finish before the full-screen overlay can intercept touches.
                yield()
                _isProcessingVoiceNote.value = true

                val authorization =
                    prefsManager.getBearerAuthorization()
                        ?: throw Exception("CRM access token not found")
                val userId =
                    prefsManager.getAuthUserId()
                        ?: throw Exception("CRM user id not found")

                Log.d(TAG, "Upload to AI via /api/crm/voice-notes/process")

                val textPlain = "text/plain".toMediaType()
                val audioPart =
                    MultipartBody.Part.createFormData(
                        "audio",
                        file.name,
                        file.asRequestBody("audio/*".toMediaType()),
                    )
                val existingNotePart =
                    currentText.trim().takeIf { it.isNotEmpty() }
                        ?.toRequestBody(textPlain)

                val response =
                    withContext(Dispatchers.IO) {
                        crmApiService.processVoiceNote(
                            authorization = authorization,
                            contactId = contactId.toRequestBody(textPlain),
                            userId = userId.toRequestBody(textPlain),
                            audio = audioPart,
                            existingNoteText = existingNotePart,
                        )
                    }

                if (!response.success || response.data == null) {
                    throw Exception(response.message.ifBlank { "Voice note processing failed" })
                }

                val data = response.data
                _processedVoiceNote.value = data.toProcessedVoiceNote()
                addVoiceNoteId(data.voiceNoteId)
                _hasRecordedAudio.value = false
                _amplitudes.value = emptyList()
                currentRecordingPath?.let { p ->
                    File(p).takeIf { it.exists() }?.delete()
                }
                currentRecordingPath = null
            } catch (e: Exception) {
                Log.e(TAG, "Voice note processing failed: ${e.message}")
                _error.value = e.toUserMessage("Voice note processing failed")
            } finally {
                _isProcessingVoiceNote.value = false
            }
        }
    }

    private fun VoiceNoteProcessData.toProcessedVoiceNote() =
        ProcessedVoiceNote(
            voiceNoteId = voiceNoteId,
            recordingUrl = recordingUrl,
            recordingUrlLong = recordingUrlLong,
            fileName = fileName,
            summary = summary,
            transcription = transcription,
            noteBody = noteBody,
            durationSec = durationSec,
            transcriptionEngineLabel = transcriptionEngineLabel,
        )

    fun uploadContactFile(contactId: String, uri: Uri) {
        viewModelScope.launch {
            _isUploadingAttachment.value = true
            _error.value = null
            var tempFile: File? = null
            var pendingPreviewId: String? = null
            try {
                val context = getApplication<Application>().applicationContext
                tempFile = uriToFile(uri, context) ?: throw Exception("Failed to read file")
                if (tempFile.length() > MAX_FILE_BYTES) {
                    throw Exception("File exceeds 50 MB limit")
                }

                val mimeType =
                    context.contentResolver.getType(uri)
                        ?: guessMimeType(tempFile.name)
                val isImage = mimeType.startsWith("image/")
                if (isImage) {
                    pendingPreviewId = "pending_${System.nanoTime()}"
                    addAttachmentPreview(
                        NoteAttachmentPreview(
                            voiceNoteId = pendingPreviewId!!,
                            name = tempFile.name,
                            url = uri.toString(),
                            fileType = "image",
                            previewUri = uri,
                        ),
                    )
                }

                val authorization =
                    prefsManager.getBearerAuthorization()
                        ?: throw Exception("CRM access token not found")
                val userId =
                    prefsManager.getAuthUserId()
                        ?: throw Exception("CRM user id not found")

                val textPlain = "text/plain".toMediaType()
                val filePart =
                    MultipartBody.Part.createFormData(
                        "file",
                        tempFile.name,
                        tempFile.asRequestBody(mimeType.toMediaType()),
                    )

                val response =
                    withContext(Dispatchers.IO) {
                        crmApiService.processContactFile(
                            authorization = authorization,
                            contactId = contactId.toRequestBody(textPlain),
                            userId = userId.toRequestBody(textPlain),
                            file = filePart,
                        )
                    }

                if (!response.success || response.data == null) {
                    throw Exception(response.message.ifBlank { "File upload failed" })
                }

                val data = response.data
                val remoteUrl = data.previewUrl()
                val fileType =
                    data.fileType?.takeIf { it.isNotBlank() }
                        ?: mimeTypeToFileType(mimeType, tempFile.name)
                val resolvedIsImage = isImage || fileType.equals("image", ignoreCase = true)
                val preview =
                    NoteAttachmentPreview(
                        voiceNoteId = data.voiceNoteId,
                        name = data.fileName?.takeIf { it.isNotBlank() } ?: tempFile.name,
                        url = remoteUrl.ifBlank { uri.toString() },
                        fileType = fileType,
                        previewUri = if (resolvedIsImage) uri else null,
                    )
                if (pendingPreviewId != null) {
                    replaceAttachmentPreview(pendingPreviewId!!, preview)
                } else {
                    addAttachmentPreview(preview)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Contact file upload failed: ${e.message}")
                pendingPreviewId?.let { removeAttachment(it) }
                _error.value = e.toUserMessage("File upload failed")
            } finally {
                tempFile?.delete()
                _isUploadingAttachment.value = false
            }
        }
    }

    private fun mimeTypeToFileType(mimeType: String, fileName: String): String {
        if (mimeType.startsWith("image/")) return "image"
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "pdf"
            "doc" -> "doc"
            "docx" -> "docx"
            else -> ext.ifBlank { "file" }
        }
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun uriToFile(uri: Uri, context: android.content.Context): File? {
        val contentResolver = context.contentResolver
        val fileName = getFileName(uri, context) ?: "upload_${System.currentTimeMillis()}"
        val tempFile = File(context.cacheDir, fileName)
        
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileName(uri: Uri, context: android.content.Context): String? {
        var name: String? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }

    fun updateNote(contactId: String, noteId: String) {
        viewModelScope.launch {
            _isSavingNote.value = true
            _error.value = null

            try {
                val authorization =
                    prefsManager.getBearerAuthorization()
                        ?: throw Exception("CRM access token not found")
                val crmUserId =
                    prefsManager.getAuthUserId()
                        ?: throw Exception("CRM user id not found")
                val draft = _draft.value
                val typedText = draft.typedText.trim()
                val voiceNoteIds = draftVoiceNoteIdsForApi(draft)
                val noteBody = resolveNoteBodyForApi(draft)
                val attachmentUrls = draftUrlsForApi(draft)

                val response =
                    withContext(Dispatchers.IO) {
                        crmApiService.updateContactNote(
                            authorization = authorization,
                            request =
                                CrmUpdateNoteRequest(
                                    userId = crmUserId,
                                    contactId = contactId,
                                    noteId = noteId,
                                    body = noteBody,
                                    urls = attachmentUrls,
                                    existingNoteText = typedText,
                                    voiceNoteIds = voiceNoteIds,
                                ),
                        )
                    }
                val updatedNotes = parseNotesResponse(response, contactId)
                clearDraft()
                refreshNotesAfterSave(contactId, updatedNotes)
            } catch (e: Exception) {
                _error.value = e.toUserMessage("Failed to update note")
            } finally {
                _isSavingNote.value = false
            }
        }
    }

    fun deleteNote(contactId: String, noteId: String) {
        viewModelScope.launch {
            if (!isServerNoteId(noteId)) {
                _error.value = "Cannot delete this note. Refresh the timeline and try again."
                return@launch
            }

            val originalNotes = _notes.value
            // Optimistic delete
            _notes.value = _notes.value.filter { it.id != noteId }

            try {
                val authorization =
                    prefsManager.getBearerAuthorization()
                        ?: throw Exception("CRM access token not found")
                val crmUserId =
                    prefsManager.getAuthUserId()
                        ?: throw Exception("CRM user id not found")
                val response =
                    withContext(Dispatchers.IO) {
                        crmApiService.deleteContactNote(
                            authorization = authorization,
                            request = CrmDeleteNoteRequest(
                                userId = crmUserId,
                                contactId = contactId,
                                noteId = noteId,
                            ),
                        )
                    }
                if (response.isJsonObject &&
                    response.asJsonObject.get("success")?.asBooleanOrNull() == false
                ) {
                    throw Exception(
                        response.asJsonObject.stringOrNull("message") ?: "Failed to delete note",
                    )
                }
                fetchNotesInternal(contactId, showLoading = false)
            } catch (e: Exception) {
                _error.value = e.toUserMessage("Failed to delete note")
                // Rollback
                _notes.value = originalNotes
            }
        }
    }

    private fun isServerNoteId(noteId: String): Boolean {
        val trimmed = noteId.trim()
        return trimmed.isNotEmpty() &&
            !trimmed.startsWith("local_") &&
            !trimmed.startsWith("note_")
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }

    private suspend fun refreshNotesAfterSave(contactId: String, confirmedNotes: List<CrmNote>) {
        if (confirmedNotes.isNotEmpty()) {
            _notes.value = mergeNotes(_notes.value, confirmedNotes)
        }

        repeat(3) { attempt ->
            fetchNotesInternal(contactId, showLoading = false)
            if (confirmedNotes.isEmpty() ||
                confirmedNotes.all { created -> _notes.value.any { it.id == created.id } }
            ) {
                return
            }
            if (attempt < 2) delay(500L)
        }

        if (confirmedNotes.isNotEmpty()) {
            _notes.value = mergeNotes(_notes.value, confirmedNotes)
        }
    }

    private fun mergeNotes(existing: List<CrmNote>, incoming: List<CrmNote>): List<CrmNote> =
        (incoming + existing).distinctBy { it.id }.let(::sortNotesDesc)

    private fun sortNotesDesc(notes: List<CrmNote>): List<CrmNote> =
        notes.sortedByDescending { note ->
            note.createdAt?.takeIf { it.isNotBlank() }
                ?: note.dateAdded?.takeIf { it.isNotBlank() }
        }

    private fun parseNotesResponse(response: JsonElement, fallbackContactId: String): List<CrmNote> {
        if (response.isJsonNull) return emptyList()

        val notesElement = when {
            response.isJsonArray -> response
            response.isJsonObject -> {
                val obj = response.asJsonObject
                if (obj.get("success")?.asBooleanOrNull() == false) {
                    throw Exception(obj.stringOrNull("message") ?: "Failed to fetch notes")
                }
                obj.get("notes")
                    ?: obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.get("notes")
            }
            else -> null
        }

        if (notesElement == null || notesElement.isJsonNull) return emptyList()
        if (!notesElement.isJsonArray) return emptyList()

        return notesElement.asJsonArray.mapIndexedNotNull { index, element ->
            if (!element.isJsonObject) return@mapIndexedNotNull null
            val obj = element.asJsonObject
            CrmNote(
                id = obj.noteIdOrNull() ?: "note_$index",
                body = obj.stringOrNull("body"),
                attachments = parseNoteAttachments(obj.get("attachments")),
                summary = obj.stringOrNull("summary"),
                transcription = obj.stringOrNull("transcription"),
                title = obj.stringOrNull("title"),
                userName = obj.stringOrNull("user_name"),
                userId = obj.stringOrNull("userId"),
                contactId = obj.stringOrNull("contactId") ?: fallbackContactId,
                voiceNoteIds = parseStringList(obj.get("voice_note_ids")),
                dateAdded = obj.stringOrNull("dateAdded"),
                dateUpdated = obj.stringOrNull("dateUpdated"),
                createdAt = obj.stringOrNull("createdAt"),
                updatedAt = obj.stringOrNull("updatedAt"),
            )
        }
    }

    private fun parseNoteAttachments(element: JsonElement?): List<CrmNoteAttachment>? {
        if (element == null || element.isJsonNull || !element.isJsonArray) return null
        return element.asJsonArray.mapNotNull { attachment ->
            when {
                attachment.isJsonPrimitive -> {
                    val url = attachment.asStringOrNull() ?: return@mapNotNull null
                    CrmNoteAttachment(fileLongUrl = url)
                }
                attachment.isJsonObject -> {
                    val obj = attachment.asJsonObject
                    val parsed =
                        CrmNoteAttachment(
                            filetype = obj.stringOrNull("filetype"),
                            fileShortUrl =
                                obj.stringOrNull("fileshorturl")
                                    ?: obj.stringOrNull("file_short_url"),
                            fileLongUrl =
                                obj.stringOrNull("fileslongurl")
                                    ?: obj.stringOrNull("file_long_url")
                                    ?: obj.stringOrNull("url")
                                    ?: obj.stringOrNull("file_url")
                                    ?: obj.stringOrNull("recording_url")
                                    ?: obj.stringOrNull("recording_url_long"),
                            voiceNoteId =
                                obj.stringOrNull("voice_note_id")
                                    ?: obj.stringOrNull("voiceNoteId"),
                        )
                    parsed.takeIf { it.playbackUrl().isNotBlank() }
                }
                else -> null
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.asStringOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonObject.noteIdOrNull(): String? =
        stringOrNull("id")
            ?: stringOrNull("noteId")
            ?: stringOrNull("note_id")
            ?: stringOrNull("_id")

    private fun parseStringList(element: JsonElement?): List<String>? {
        if (element == null || element.isJsonNull || !element.isJsonArray) return null
        return element.asJsonArray.mapNotNull { item ->
            item.asStringOrNull()?.trim()?.takeIf { it.isNotBlank() }
        }.takeIf { it.isNotEmpty() }
    }

    private fun JsonElement.asStringOrNull(): String? =
        if (isJsonPrimitive && asJsonPrimitive.isString) asString else null

    private fun JsonElement.asBooleanOrNull(): Boolean? =
        if (isJsonPrimitive && asJsonPrimitive.isBoolean) asBoolean else null

    private fun Exception.toUserMessage(fallback: String): String {
        if (this !is HttpException) {
            return localizedMessage ?: fallback
        }

        val body = response()?.errorBody()?.string().orEmpty()
        val message = runCatching {
            JsonParser.parseString(body)
                .asJsonObject
                .get("message")
                ?.asString
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return message ?: "$fallback (HTTP ${code()})"
    }
}
