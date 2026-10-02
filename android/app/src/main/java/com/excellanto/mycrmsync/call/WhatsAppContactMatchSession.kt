package com.excellanto.mycrmsync.call

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.excellanto.mycrmsync.data.CallLogRegistrationEvents
import com.excellanto.mycrmsync.data.WhatsAppCallRegistrationSync
import com.excellanto.mycrmsync.data.WhatsAppContactMatchAttacher
import com.excellanto.mycrmsync.data.WhatsAppContactMatchStore
import com.excellanto.mycrmsync.data.WhatsAppContactMatcher
import com.excellanto.mycrmsync.data.WhatsAppCrmMatch
import com.excellanto.mycrmsync.work.CallLogSyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WhatsAppContactMatchSession(
    context: Context,
    val displayName: String,
    val phoneRaw: String?,
    val direction: String,
    val startedAtMs: Long,
) {
    private val appContext = context.applicationContext
    private val matcher = WhatsAppContactMatcher(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null

    var isLoading by mutableStateOf(true)
        private set
    var query by mutableStateOf(displayName)
        private set
    var matches by mutableStateOf<List<WhatsAppCrmMatch>>(emptyList())
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var linkingId by mutableStateOf<String?>(null)
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set

    fun start(onReady: () -> Unit) {
        loadJob?.cancel()
        isLoading = true
        errorMessage = null
        matches = emptyList()
        linkingId = null
        selectedId = null
        loadJob =
            scope.launch {
                try {
                    val cached = WhatsAppContactMatchStore.cachedMatches(startedAtMs)
                    if (cached != null) {
                        matches = cached
                        isLoading = false
                        if (matches.isNotEmpty()) onReady()
                        return@launch
                    }
                    val outcome = matcher.match(displayName, phoneRaw)
                    query = outcome.query.ifBlank { displayName }
                    matches = if (outcome.signedIn) outcome.contacts else emptyList()
                    WhatsAppContactMatchStore.cacheMatches(startedAtMs, matches)
                    isLoading = false
                    if (matches.isNotEmpty()) onReady()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "CRM match failed for $displayName", error)
                    matches = emptyList()
                    isLoading = false
                }
            }
    }

    fun choose(match: WhatsAppCrmMatch) {
        if (linkingId != null) return
        selectedId = match.id
    }

    fun save(onDone: () -> Unit) {
        val match = matches.firstOrNull { it.id == selectedId } ?: return
        select(match, onDone)
    }

    fun select(match: WhatsAppCrmMatch, onDone: () -> Unit) {
        if (linkingId != null) return
        linkingId = match.id
        scope.launch {
            val selection =
                WhatsAppContactMatchStore.Selection(
                    startedAtMs = startedAtMs,
                    displayName = displayName,
                    phoneRaw = phoneRaw,
                    contactId = match.id,
                    contactName = match.name,
                    contactPhone = match.phone,
                )
            WhatsAppContactMatchStore.save(selection)
            showPreviousNotes(match)
            val completed = WhatsAppContactMatchStore.onUserConfirmed(selection)
            if (completed != null) {
                runCatching {
                    val entity =
                        WhatsAppCallRegistrationSync(appContext).persistCompletedCall(completed)
                    if (entity != null) {
                        CallLogRegistrationEvents.notifyRegistered()
                        CallLogSyncScheduler.enqueueAfterWhatsAppCall(appContext)
                    }
                }
            } else {
                runCatching { WhatsAppContactMatchAttacher.attachIfPresent(appContext, selection) }
            }
            onDone()
        }
    }

    fun close() {
        loadJob?.cancel()
        scope.cancel()
    }

    private fun showPreviousNotes(match: WhatsAppCrmMatch) {
        val phone = match.phone?.trim()?.ifBlank { null } ?: phoneRaw?.trim()?.ifBlank { null }
        val noteDirection =
            when (direction.trim().uppercase()) {
                "INCOMING", "IN", "MISSED" -> CallNoteSummaryLauncher.DIRECTION_INCOMING
                else -> CallNoteSummaryLauncher.DIRECTION_OUTGOING
            }
        CallNoteSummaryLauncher.show(
            context = appContext,
            phone = phone,
            direction = noteDirection,
            contactId = match.id,
            contactName = match.name,
        )
    }

    companion object {
        private const val TAG = "WhatsAppMatchSession"
    }
}
