package com.excellanto.mycrmsync.data

import com.excellanto.mycrmsync.accessibility.WhatsAppCallStateMachine

/** Contact the user confirmed for the in-progress WhatsApp call. */
object WhatsAppContactMatchStore {
    data class Selection(
        val startedAtMs: Long,
        val displayName: String,
        val phoneRaw: String?,
        val contactId: String,
        val contactName: String,
        val contactPhone: String?,
    )

    private val lock = Any()
    private var selection: Selection? = null
    private var confirmedStartedAtMs: Long? = null
    private var offeredStartedAtMs: Long? = null
    private var cachedStartedAtMs: Long? = null
    private var cachedMatches: List<WhatsAppCrmMatch>? = null
    private var pendingCall: WhatsAppCallStateMachine.CompletedCall? = null

    fun cacheMatches(startedAtMs: Long, matches: List<WhatsAppCrmMatch>) {
        synchronized(lock) {
            cachedStartedAtMs = startedAtMs
            cachedMatches = matches
        }
    }

    fun cachedMatches(startedAtMs: Long): List<WhatsAppCrmMatch>? =
        synchronized(lock) {
            val cachedAt = cachedStartedAtMs ?: return@synchronized null
            if (kotlin.math.abs(cachedAt - startedAtMs) > WhatsAppContactMatchStoreWindow.MS) return@synchronized null
            cachedMatches
        }

    fun markOffered(startedAtMs: Long) {
        synchronized(lock) { offeredStartedAtMs = startedAtMs }
    }

    fun wasOffered(startedAtMs: Long): Boolean =
        synchronized(lock) {
            val offered = offeredStartedAtMs ?: return@synchronized false
            kotlin.math.abs(offered - startedAtMs) <= WhatsAppContactMatchStoreWindow.MS
        }

    fun save(value: Selection) {
        synchronized(lock) {
            selection = value
            confirmedStartedAtMs = value.startedAtMs
        }
    }

    /**
     * Call ended. Returns the user's popup selection when they already confirmed.
     * Otherwise holds the call in memory and writes nothing.
     */
    fun onCallEnded(call: WhatsAppCallStateMachine.CompletedCall): Selection? =
        synchronized(lock) {
            val chosen =
                selection?.takeIf { it.matches(call.displayName, call.phoneRaw, call.startedAtMs) }
            if (chosen != null) {
                pendingCall = null
                chosen
            } else {
                pendingCall = call
                null
            }
        }

    /**
     * User picked a CRM contact. Returns the ended call when it is already waiting,
     * so the row can be written now. If the call is still active, only the selection is kept.
     */
    fun onUserConfirmed(value: Selection): WhatsAppCallStateMachine.CompletedCall? =
        synchronized(lock) {
            selection = value
            confirmedStartedAtMs = value.startedAtMs
            val call =
                pendingCall?.takeIf { value.matches(it.displayName, it.phoneRaw, it.startedAtMs) }
            if (call != null) pendingCall = null
            call
        }

    fun isConfirmed(startedAtMs: Long): Boolean =
        synchronized(lock) {
            val confirmed = confirmedStartedAtMs ?: return@synchronized false
            kotlin.math.abs(confirmed - startedAtMs) <= WhatsAppContactMatchStoreWindow.MS
        }

    fun peek(displayName: String, phoneRaw: String?, startedAtMs: Long): Selection? =
        synchronized(lock) {
            selection?.takeIf { it.matches(displayName, phoneRaw, startedAtMs) }
        }

    fun consume(displayName: String, phoneRaw: String?, startedAtMs: Long): Selection? =
        synchronized(lock) {
            val current = selection?.takeIf { it.matches(displayName, phoneRaw, startedAtMs) }
                ?: return@synchronized null
            selection = null
            current
        }

    private fun Selection.matches(displayName: String, phoneRaw: String?, startedAtMs: Long): Boolean {
        if (kotlin.math.abs(this.startedAtMs - startedAtMs) > WhatsAppContactMatchStoreWindow.MS) return false
        val name = displayName.trim()
        if (name.isNotEmpty() && name.equals(this.displayName, ignoreCase = true)) return true
        val phone = phoneRaw?.trim()?.ifBlank { null } ?: return false
        val saved = this.phoneRaw?.trim()?.ifBlank { null } ?: return false
        return phone == saved
    }
}
