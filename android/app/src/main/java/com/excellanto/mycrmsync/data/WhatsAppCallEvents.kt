package com.excellanto.mycrmsync.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class WhatsAppPostCallPayload(
    val syncFingerprint: String,
    val displayName: String,
    val direction: String,
    val durationSec: Int,
    val startedAtIso: String,
    val contactId: String?,
    val crmCallLogId: String?,
)

/** Notifies UI when a WhatsApp call ends and post-call prompt should appear. */
object WhatsAppCallEvents {
    private val _postCall = MutableSharedFlow<WhatsAppPostCallPayload>(extraBufferCapacity = 4)
    val postCall: SharedFlow<WhatsAppPostCallPayload> = _postCall.asSharedFlow()

    internal fun notifyPostCall(payload: WhatsAppPostCallPayload) {
        _postCall.tryEmit(payload)
    }
}

object WhatsAppCallFingerprint {
    fun build(
        phoneE164: String?,
        phoneRaw: String?,
        displayName: String?,
        startedMs: Long,
        durationSec: Int,
        direction: String,
    ): String {
        val key =
            phoneE164?.trim()?.ifBlank { null }
                ?: phoneRaw?.trim()?.ifBlank { null }
                ?: displayName?.trim()?.lowercase()?.ifBlank { null }
                ?: "na"
        return "whatsapp|$key|$startedMs|$durationSec|$direction"
    }
}
