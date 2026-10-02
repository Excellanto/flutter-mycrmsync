package com.excellanto.mycrmsync.accessibility

import android.view.accessibility.AccessibilityNodeInfo

enum class WhatsAppCallScreenType {
    None,
    Incoming,
    Outgoing,
    Connected,
    Missed,
}

data class WhatsAppParsedCallScreen(
    val screenType: WhatsAppCallScreenType,
    val displayName: String?,
    val phoneSubtitle: String?,
    val timerSeconds: Int?,
    val hasAnswerButton: Boolean,
    val hasDeclineButton: Boolean,
    val hasEndCallButton: Boolean = false,
)

/**
 * Parses WhatsApp call UI from accessibility nodes.
 * Testable via [parseFromSnapshot] without Android node trees.
 */
object WhatsAppCallUiParser {

    data class NodeSnapshot(
        val text: String? = null,
        val contentDescription: String? = null,
        val viewIdResourceName: String? = null,
        val isClickable: Boolean = false,
        val children: List<NodeSnapshot> = emptyList(),
    )

    fun parse(root: AccessibilityNodeInfo?): WhatsAppParsedCallScreen {
        if (root == null) return emptyScreen()
        val snapshot = root.toSnapshot()
        return parseFromSnapshot(snapshot)
    }

    fun parseFromSnapshot(
        root: NodeSnapshot,
        windowClassName: String? = null,
    ): WhatsAppParsedCallScreen {
        val flat = flatten(root)
        val texts = flat.mapNotNull { it.text?.trim()?.ifBlank { null } }.distinct()
        val descriptions =
            flat.mapNotNull { it.contentDescription?.trim()?.ifBlank { null } }.distinct()

        val hasAnswer =
            (descriptions + texts).any {
                WhatsAppCallUiPatterns.isStatusLabel(
                    it,
                    WhatsAppCallUiPatterns.ANSWER_CONTENT_DESCRIPTIONS + listOf("answer", "swipe up to answer"),
                )
            }
        val hasDecline =
            (descriptions + texts).any {
                WhatsAppCallUiPatterns.isStatusLabel(it, WhatsAppCallUiPatterns.DECLINE_CONTENT_DESCRIPTIONS)
            }
        val hasEndCall =
            (descriptions + texts).any {
                WhatsAppCallUiPatterns.isStatusLabel(it, WhatsAppCallUiPatterns.END_CALL_CONTENT_DESCRIPTIONS)
            }
        val hasIncomingLabel =
            (texts + descriptions).any {
                WhatsAppCallUiPatterns.isStatusLabel(it, WhatsAppCallUiPatterns.INCOMING_CALL_TEXTS)
            }
        val hasOutgoingLabel =
            (texts + descriptions).any {
                WhatsAppCallUiPatterns.isStatusLabel(it, WhatsAppCallUiPatterns.OUTGOING_CALL_TEXTS)
            }
        val hasConnectedLabel =
            (texts + descriptions).any {
                WhatsAppCallUiPatterns.isStatusLabel(it, WhatsAppCallUiPatterns.CONNECTED_CALL_TEXTS)
            }
        val hasMissedLabel =
            (texts + descriptions).any {
                WhatsAppCallUiPatterns.isStatusLabel(it, WhatsAppCallUiPatterns.MISSED_CALL_TEXTS)
            }

        val isVoipActivity = WhatsAppCallUiPatterns.looksLikeVoipActivity(windowClassName)

        val timerFromId =
            flat.firstNotNullOfOrNull { node ->
                val id = node.viewIdResourceName?.lowercase().orEmpty()
                if (WhatsAppCallUiPatterns.CALL_TIMER_VIEW_ID_SUFFIXES.any { id.endsWith(it) }) {
                    node.text?.let { WhatsAppCallUiPatterns.parseTimerSeconds(it) }
                } else {
                    null
                }
            }
        val timerSeconds =
            timerFromId ?: texts.firstNotNullOfOrNull { WhatsAppCallUiPatterns.parseClockTimer(it) }

        val displayNameFromId =
            flat.firstNotNullOfOrNull { node ->
                val id = node.viewIdResourceName?.lowercase().orEmpty()
                if (WhatsAppCallUiPatterns.CONTACT_NAME_VIEW_ID_SUFFIXES.any { id.endsWith(it) }) {
                    node.text?.trim()?.ifBlank { null }
                } else {
                    null
                }
            }

        val phoneFromId =
            flat.firstNotNullOfOrNull { node ->
                val id = node.viewIdResourceName?.lowercase().orEmpty()
                if (WhatsAppCallUiPatterns.PHONE_SUBTITLE_VIEW_ID_SUFFIXES.any { id.endsWith(it) }) {
                    node.text?.trim()?.ifBlank { null }?.takeIf {
                        WhatsAppCallUiPatterns.looksLikePhoneNumber(it)
                    }
                } else {
                    null
                }
            }

        val screenType =
            when {
                hasMissedLabel && !hasAnswer && !hasDecline && timerSeconds == null && !hasIncomingLabel ->
                    WhatsAppCallScreenType.Missed
                hasIncomingLabel && hasAnswer && hasDecline ->
                    WhatsAppCallScreenType.Incoming
                hasAnswer && hasDecline ->
                    WhatsAppCallScreenType.Incoming
                hasOutgoingLabel && hasEndCall && !hasAnswer ->
                    WhatsAppCallScreenType.Outgoing
                hasConnectedLabel && hasEndCall ->
                    WhatsAppCallScreenType.Connected
                timerSeconds != null && hasEndCall && isVoipActivity && !hasAnswer ->
                    WhatsAppCallScreenType.Connected
                else -> WhatsAppCallScreenType.None
            }

        if (screenType == WhatsAppCallScreenType.None) {
            return emptyScreen()
        }

        val nameCandidates =
            texts.filter { candidate ->
                val lower = candidate.lowercase()
                !WhatsAppCallUiPatterns.containsAny(lower, WhatsAppCallUiPatterns.STATUS_TEXTS_TO_IGNORE) &&
                    !WhatsAppCallUiPatterns.containsAny(lower, WhatsAppCallUiPatterns.INCOMING_CALL_TEXTS) &&
                    !WhatsAppCallUiPatterns.containsAny(lower, WhatsAppCallUiPatterns.OUTGOING_CALL_TEXTS) &&
                    !WhatsAppCallUiPatterns.containsAny(lower, WhatsAppCallUiPatterns.CONNECTED_CALL_TEXTS) &&
                    !WhatsAppCallUiPatterns.containsAny(lower, WhatsAppCallUiPatterns.MISSED_CALL_TEXTS) &&
                    WhatsAppCallUiPatterns.parseTimerSeconds(candidate) == null &&
                    candidate.length in 2..80
            }

        val displayName =
            displayNameFromId
                ?: nameCandidates.firstOrNull { !WhatsAppCallUiPatterns.looksLikePhoneNumber(it) }
        val phoneSubtitle =
            phoneFromId
                ?: nameCandidates.firstOrNull { WhatsAppCallUiPatterns.looksLikePhoneNumber(it) }
                ?: displayNameFromId?.takeIf { WhatsAppCallUiPatterns.looksLikePhoneNumber(it) }

        return WhatsAppParsedCallScreen(
            screenType = screenType,
            displayName = displayName,
            phoneSubtitle = phoneSubtitle,
            timerSeconds = timerSeconds,
            hasAnswerButton = hasAnswer,
            hasDeclineButton = hasDecline,
            hasEndCallButton = hasEndCall,
        )
    }

    private fun emptyScreen() =
        WhatsAppParsedCallScreen(
            screenType = WhatsAppCallScreenType.None,
            displayName = null,
            phoneSubtitle = null,
            timerSeconds = null,
            hasAnswerButton = false,
            hasDeclineButton = false,
            hasEndCallButton = false,
        )

    private fun AccessibilityNodeInfo.toSnapshot(): NodeSnapshot {
        val children = ArrayList<NodeSnapshot>(childCount)
        for (i in 0 until childCount) {
            getChild(i)?.let { child ->
                children += child.toSnapshot()
                child.recycle()
            }
        }
        return NodeSnapshot(
            text = text?.toString(),
            contentDescription = contentDescription?.toString(),
            viewIdResourceName = viewIdResourceName,
            isClickable = isClickable,
            children = children,
        )
    }

    private fun flatten(node: NodeSnapshot): List<NodeSnapshot> {
        val out = ArrayList<NodeSnapshot>()
        fun walk(n: NodeSnapshot) {
            out += n
            n.children.forEach { walk(it) }
        }
        walk(node)
        return out
    }
}
