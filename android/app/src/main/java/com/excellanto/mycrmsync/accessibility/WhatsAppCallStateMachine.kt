package com.excellanto.mycrmsync.accessibility

/**
 * Tracks WhatsApp call lifecycle from parsed accessibility snapshots.
 */
class WhatsAppCallStateMachine(
    private val missedTimeoutMs: Long = DEFAULT_MISSED_TIMEOUT_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    enum class Phase {
        Idle,
        IncomingRinging,
        OutgoingRinging,
        Connected,
    }

    data class ActiveSession(
        val displayName: String,
        val phoneRaw: String?,
        val direction: String,
        val startedAtMs: Long,
        val connectedAtMs: Long?,
        val phase: Phase,
    )

    data class CompletedCall(
        val displayName: String,
        val phoneRaw: String?,
        val direction: String,
        val startedAtMs: Long,
        val connectedAtMs: Long?,
        val endedAtMs: Long,
        val durationSec: Int,
        val wasRejected: Boolean,
    )

    private var phase: Phase = Phase.Idle
    private var displayName: String? = null
    private var phoneRaw: String? = null
    private var direction: String? = null
    private var startedAtMs: Long = 0L
    private var connectedAtMs: Long? = null
    private var lastSeenCallUiMs: Long = 0L
    private var lastTimerSeconds: Int? = null
    private var sawIncomingIndicators: Boolean = false

    fun onSnapshot(screen: WhatsAppParsedCallScreen): CompletedCall? {
        val now = nowMs()
        if (screen.screenType != WhatsAppCallScreenType.None) {
            lastSeenCallUiMs = now
        }
        if (screen.hasAnswerButton || screen.screenType == WhatsAppCallScreenType.Incoming) {
            sawIncomingIndicators = true
        }
        screen.timerSeconds?.takeIf { it >= 0 }?.let { lastTimerSeconds = it }

        val startingSession =
            phase == Phase.Idle &&
                screen.screenType in
                    setOf(
                        WhatsAppCallScreenType.Incoming,
                        WhatsAppCallScreenType.Outgoing,
                        WhatsAppCallScreenType.Connected,
                    )
        if (startingSession) {
            displayName = screen.displayName?.trim()?.ifBlank { null }
            phoneRaw = screen.phoneSubtitle?.trim()?.ifBlank { null }
        } else if (screen.screenType != WhatsAppCallScreenType.None) {
            screen.displayName?.trim()?.ifBlank { null }?.let { displayName = it }
            screen.phoneSubtitle?.trim()?.ifBlank { null }?.let { phoneRaw = it }
        }

        when (screen.screenType) {
            WhatsAppCallScreenType.Incoming -> {
                if (phase == Phase.Idle) {
                    phase = Phase.IncomingRinging
                    direction = "INCOMING"
                    sawIncomingIndicators = true
                    startedAtMs = now
                } else if (phase == Phase.IncomingRinging && screen.timerSeconds != null) {
                    phase = Phase.Connected
                    connectedAtMs = connectedAtMs ?: now
                    direction = "INCOMING"
                }
            }
            WhatsAppCallScreenType.Outgoing -> {
                if (phase == Phase.Idle) {
                    phase = Phase.OutgoingRinging
                    direction = "OUTGOING"
                    sawIncomingIndicators = false
                    startedAtMs = now
                }
            }
            WhatsAppCallScreenType.Connected -> {
                when (phase) {
                    Phase.IncomingRinging, Phase.OutgoingRinging -> {
                        connectedAtMs = now
                        phase = Phase.Connected
                    }
                    Phase.Idle -> {
                        phase = Phase.Connected
                        direction =
                            when {
                                sawIncomingIndicators -> "INCOMING"
                                else -> direction ?: "OUTGOING"
                            }
                        startedAtMs = startedAtMs.takeIf { it > 0L } ?: now
                        connectedAtMs = now
                    }
                    Phase.Connected -> Unit
                }
            }
            WhatsAppCallScreenType.Missed -> Unit
            WhatsAppCallScreenType.None -> {
                if (phase != Phase.Idle) {
                    val idleDuration = now - lastSeenCallUiMs
                    if (idleDuration > UI_GONE_DEBOUNCE_MS) {
                        return finalizeCall(now, rejected = false)
                    }
                } else if (phase == Phase.IncomingRinging && now - startedAtMs > missedTimeoutMs) {
                    return finalizeCall(now, rejected = false, forceMissed = true)
                }
            }
        }

        if (phase == Phase.IncomingRinging &&
            screen.screenType == WhatsAppCallScreenType.None &&
            now - startedAtMs > missedTimeoutMs
        ) {
            return finalizeCall(now, rejected = false, forceMissed = true)
        }

        return null
    }

    fun onMissedCallDetected(screen: WhatsAppParsedCallScreen): CompletedCall? {
        if (phase != Phase.Idle) return null
        val name =
            screen.displayName?.trim()?.ifBlank { null }
                ?: screen.phoneSubtitle?.trim()?.ifBlank { null }
                ?: return null
        val now = nowMs()
        return CompletedCall(
            displayName = name,
            phoneRaw = screen.phoneSubtitle,
            direction = "MISSED",
            startedAtMs = now,
            connectedAtMs = null,
            endedAtMs = now,
            durationSec = 0,
            wasRejected = false,
        )
    }

    fun onDeclineDetected(): CompletedCall? {
        if (phase != Phase.IncomingRinging) return null
        return finalizeCall(nowMs(), rejected = true)
    }

    fun activeSession(): ActiveSession? {
        val name =
            displayName?.trim()?.ifBlank { null }
                ?: phoneRaw?.trim()?.ifBlank { null }
                ?: return null
        val dir = direction?.trim()?.ifBlank { null } ?: return null
        if (phase == Phase.Idle) return null
        return ActiveSession(
            displayName = name,
            phoneRaw = phoneRaw,
            direction = dir,
            startedAtMs = startedAtMs,
            connectedAtMs = connectedAtMs,
            phase = phase,
        )
    }

    private fun finalizeCall(
        endedAtMs: Long,
        rejected: Boolean,
        forceMissed: Boolean = false,
    ): CompletedCall? {
        val name =
            displayName?.trim()?.ifBlank { null }
                ?: phoneRaw?.trim()?.ifBlank { null }
                ?: "Unknown"
        val dir =
            when {
                rejected -> "MISSED"
                forceMissed -> "MISSED"
                phase == Phase.IncomingRinging && connectedAtMs == null -> "MISSED"
                phase == Phase.OutgoingRinging && connectedAtMs == null -> "OUTGOING"
                else -> direction?.trim()?.ifBlank { null } ?: "UNKNOWN"
            }
        val connected = connectedAtMs
        val durationSec =
            if (connected != null && dir != "MISSED") {
                lastTimerSeconds
                    ?: ((endedAtMs - connected).coerceAtLeast(0L) / 1000L).toInt()
            } else {
                0
            }
        val result =
            CompletedCall(
                displayName = name,
                phoneRaw = phoneRaw,
                direction = dir,
                startedAtMs = startedAtMs.takeIf { it > 0L } ?: endedAtMs,
                connectedAtMs = connected,
                endedAtMs = endedAtMs,
                durationSec = durationSec,
                wasRejected = rejected,
            )
        reset()
        return result
    }

    fun reset() {
        phase = Phase.Idle
        displayName = null
        phoneRaw = null
        direction = null
        startedAtMs = 0L
        connectedAtMs = null
        lastSeenCallUiMs = 0L
        lastTimerSeconds = null
        sawIncomingIndicators = false
    }

    companion object {
        const val DEFAULT_MISSED_TIMEOUT_MS = 45_000L
        private const val UI_GONE_DEBOUNCE_MS = 1_500L
    }
}
