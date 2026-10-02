package com.excellanto.mycrmsync.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.excellanto.mycrmsync.data.CallNoteText
import com.excellanto.mycrmsync.data.PrefsManager
import com.excellanto.mycrmsync.data.WhatsAppContactResolveResult
import com.excellanto.mycrmsync.data.WhatsAppContactResolver
import com.excellanto.mycrmsync.data.WhatsAppPhoneHint
import com.excellanto.mycrmsync.data.api.CrmApiService
import com.excellanto.mycrmsync.data.local.CrmDatabase
import com.excellanto.mycrmsync.data.local.WhatsAppCallEntity
import com.excellanto.mycrmsync.data.model.CrmCreateNoteRequest
import com.excellanto.mycrmsync.work.CallLogSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WhatsAppPostCallUiState(
    val isLoading: Boolean = true,
    val entity: WhatsAppCallEntity? = null,
    val noteText: String = "",
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val canUploadNotes: Boolean = false,
)

class WhatsAppPostCallViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = PrefsManager(app)
    private val db = CrmDatabase.getInstance(app)
    private val dao = db.whatsAppCallDao()
    private val crmApi = CrmApiService.create()
    private val contactResolver = WhatsAppContactResolver(crmApi)

    private val _ui = MutableStateFlow(WhatsAppPostCallUiState())
    val ui: StateFlow<WhatsAppPostCallUiState> = _ui.asStateFlow()

    fun load(syncFingerprint: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(isLoading = true, error = null)
            runCatching {
                var entity = dao.getByFingerprint(syncFingerprint)
                if (entity == null) {
                    throw IllegalStateException("Call not found")
                }
                if (entity.contactId.isNullOrBlank()) {
                    entity = retryContactResolution(entity)
                }
                CallLogSyncScheduler.enqueueAfterWhatsAppCall(getApplication())
                _ui.value =
                    _ui.value.copy(
                        isLoading = false,
                        entity = entity,
                        canUploadNotes = !entity.contactId.isNullOrBlank(),
                    )
            }.onFailure { e ->
                _ui.value =
                    _ui.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load call",
                    )
            }
        }
    }

    fun updateNoteText(text: String) {
        _ui.value = _ui.value.copy(noteText = text)
    }

    fun applyAiFormattedNote() {
        val entity = _ui.value.entity ?: return
        val formatted = formatWhatsAppCallNoteBody(entity, includeUserNote = false)
        _ui.value = _ui.value.copy(noteText = formatted.trim())
    }

    fun saveNote(onDone: () -> Unit) {
        val entity = _ui.value.entity ?: return
        val contactId = entity.contactId?.trim()?.ifBlank { null }
        if (contactId == null) {
            _ui.value = _ui.value.copy(error = "Contact not linked to CRM")
            return
        }
        val body =
            _ui.value.noteText.trim().ifBlank {
                formatWhatsAppCallNoteBody(entity, includeUserNote = false).trim()
            }
        if (body.isBlank()) {
            dismiss(onDone)
            return
        }

        viewModelScope.launch {
            _ui.value = _ui.value.copy(isSaving = true, error = null)
            runCatching {
                val authorization =
                    prefs.getBearerAuthorization()
                        ?: throw IllegalStateException("Not logged in")
                val userId =
                    prefs.getAuthUserId()
                        ?: throw IllegalStateException("CRM user id missing")

                val fullBody =
                    if (_ui.value.noteText.trim().isEmpty()) {
                        body
                    } else {
                        buildString {
                            appendLine(formatWhatsAppCallNoteBody(entity, includeUserNote = false).trimEnd())
                            appendLine()
                            append(_ui.value.noteText.trim())
                        }
                    }

                withContext(Dispatchers.IO) {
                    crmApi.createContactNote(
                        authorization = authorization,
                        request =
                            CrmCreateNoteRequest(
                                userId = userId,
                                contactId = contactId,
                                body = fullBody.trim(),
                            ),
                    )
                }

                val now = System.currentTimeMillis()
                dao.markNoteUploaded(entity.syncFingerprint, now, now)
                dao.markPostCallDismissed(entity.syncFingerprint, now, now)
                _ui.value = _ui.value.copy(isSaving = false, saved = true)
                onDone()
            }.onFailure { e ->
                _ui.value =
                    _ui.value.copy(
                        isSaving = false,
                        error = e.message ?: "Failed to save note",
                    )
            }
        }
    }

    fun dismiss(onDone: () -> Unit) {
        val entity = _ui.value.entity ?: run {
            onDone()
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            dao.markPostCallDismissed(entity.syncFingerprint, now, now)
            onDone()
        }
    }

    private suspend fun retryContactResolution(entity: WhatsAppCallEntity): WhatsAppCallEntity {
        val bearerAuthorization = prefs.getBearerAuthorization()
        val crmSearchUserId = prefs.getAuthUserId() ?: prefs.getUserId()
        val contactIndex = db.crmContactPhoneDao().listAll().associateBy { it.lookupKey }
        return when (
            val result =
                contactResolver.resolve(
                    phoneRawHint =
                        WhatsAppPhoneHint.fromWhatsAppUi(
                            phoneRaw = entity.phoneRaw ?: entity.phoneE164,
                            displayName = entity.displayName,
                        ),
                    contactIndex = contactIndex,
                    bearerAuthorization = bearerAuthorization,
                    crmSearchUserId = crmSearchUserId,
                )
        ) {
            is WhatsAppContactResolveResult.Found -> {
                val updated =
                    entity.copy(
                        contactId = result.contact.id,
                        contactName = result.contact.name,
                        phoneRaw = result.contact.phoneRaw ?: entity.phoneRaw,
                        phoneE164 = result.contact.phoneE164 ?: entity.phoneE164,
                        uploadStatus = com.excellanto.mycrmsync.data.local.CallLogUploadStatus.PENDING,
                    )
                dao.upsert(updated)
                updated
            }
            else -> entity
        }
    }

    companion object {
        fun formatWhatsAppCallNoteBody(
            entity: WhatsAppCallEntity,
            includeUserNote: Boolean,
        ): String {
            val body =
                CallNoteText.format(
                    direction = entity.direction,
                    durationSec = entity.durationSec,
                    startedAtIso = entity.startedAtIso,
                    phone = entity.phoneE164 ?: entity.phoneRaw,
                    isWhatsAppCall = true,
                )
            if (!includeUserNote) return body
            return body + "\n\n(Add your notes above this line.)"
        }
    }
}
