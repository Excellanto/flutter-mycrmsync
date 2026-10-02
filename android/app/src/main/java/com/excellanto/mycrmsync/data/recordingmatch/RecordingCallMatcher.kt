package com.excellanto.mycrmsync.data.recordingmatch

import com.excellanto.mycrmsync.data.oem.DiscoveredRecording
import com.excellanto.mycrmsync.data.oem.OemMatch
import com.excellanto.mycrmsync.data.oem.OemRecordingConstants
import com.excellanto.mycrmsync.data.oem.SystemCallLogRow

/**
 * OEM/system call-log ↔ discovered recording matching (pure Kotlin; static-only API).
 */
class RecordingCallMatcher private constructor() {
    companion object {

        fun normalizeDigits(raw: String?): String {
            val d = raw?.filter { it.isDigit() }.orEmpty()
            return if (d.length > 7) d.takeLast(7) else d
        }

        private fun callStartSec(c: SystemCallLogRow): Long = c.dateMs / 1000L

        private fun callEndSec(c: SystemCallLogRow): Long {
            val start = callStartSec(c)
            return if (c.direction == "missed" || c.durationSec <= 0) start else start + c.durationSec
        }

        internal fun digitHaystack(r: DiscoveredRecording): String =
            r.haystack.filter { it.isDigit() }

        fun digitMatch(c: SystemCallLogRow, r: DiscoveredRecording): Boolean {
            val norm = normalizeDigits(c.number)
            if (norm.length < 5) return false
            return digitHaystack(r).contains(norm)
        }

        private fun nameTokens(cachedName: String?): List<String> {
            if (cachedName.isNullOrBlank()) return emptyList()
            return Regex("[\\s\\p{Punct}]+").split(cachedName.trim())
                .map { it.trim().lowercase() }
                .filter { it.length >= 2 }
        }

        fun nameMatch(c: SystemCallLogRow, r: DiscoveredRecording): Boolean {
            val h = r.haystack
            return nameTokens(c.cachedName).any { tok -> h.contains(tok) }
        }

        fun fileSecPreferModified(r: DiscoveredRecording): Long =
            if (r.dateModifiedSec > 0L) r.dateModifiedSec else r.dateAddedSec

        fun timeScore(c: SystemCallLogRow, r: DiscoveredRecording): Double {
            val fileSec = fileSecPreferModified(r).toDouble()
            val start = callStartSec(c).toDouble()
            val end = callEndSec(c).toDouble()
            val dt = minOf(kotlin.math.abs(fileSec - end), kotlin.math.abs(fileSec - start))
            return 1.0 / (1.0 + dt / OemRecordingConstants.TIME_SCORE_DIVISOR)
        }

        fun scorePair(call: SystemCallLogRow, file: DiscoveredRecording): Double {
            val fileSec = fileSecPreferModified(file)
            val start = callStartSec(call)
            val end = callEndSec(call)
            val relaxedLo = start - OemRecordingConstants.SECONDARY_START_SLACK_SEC
            val relaxedHi = end + OemRecordingConstants.SECONDARY_END_SLACK_SEC
            if (fileSec !in relaxedLo..relaxedHi) return 0.0

            val strictLo = start - OemRecordingConstants.PRIMARY_WINDOW_SEC
            val strictHi = end + OemRecordingConstants.EXTENDED_WINDOW_SEC
            val inStrict = fileSec in strictLo..strictHi

            var score = timeScore(call, file)
            if (inStrict) {
                if (digitMatch(call, file)) score += OemRecordingConstants.DIGIT_BONUS
                if (nameMatch(call, file)) score += OemRecordingConstants.NAME_BONUS
            }
            if (call.direction == "missed" &&
                (file.durationMs ?: 0L) > OemRecordingConstants.MISSED_LONG_FILE_PENALTY_MS
            ) {
                score -= OemRecordingConstants.MISSED_LONG_FILE_SCORE_PENALTY
            }
            return score.coerceIn(0.0, 3.0)
        }

        fun matchGreedy(
            calls: List<SystemCallLogRow>,
            files: List<DiscoveredRecording>,
            minConfidence: Double = OemRecordingConstants.GREEDY_MIN_CONFIDENCE,
        ): List<OemMatch> {
            data class Cand(val androidCallId: String, val uri: String, val score: Double)

            val scored = mutableListOf<Cand>()
            for (c in calls) {
                for (r in files) {
                    val s = scorePair(c, r)
                    if (s >= minConfidence) scored += Cand(c.androidId, r.contentUri, s)
                }
            }
            scored.sortByDescending { it.score }

            val usedCalls = mutableSetOf<String>()
            val usedUris = mutableSetOf<String>()
            val out = mutableListOf<OemMatch>()
            for (row in scored) {
                if (row.androidCallId in usedCalls) continue
                if (row.uri in usedUris) continue
                usedCalls += row.androidCallId
                usedUris += row.uri
                out += OemMatch(row.androidCallId, row.uri, row.score)
            }
            return out
        }

        fun matchSecondary(
            calls: List<SystemCallLogRow>,
            files: List<DiscoveredRecording>,
            alreadyMatchedUris: Set<String>,
            alreadyMatchedCalls: Set<String>,
        ): List<OemMatch> {
            data class Cand(val androidCallId: String, val uri: String, val score: Double)

            val scored = mutableListOf<Cand>()
            for (c in calls) {
                if (c.androidId in alreadyMatchedCalls) continue
                for (r in files) {
                    if (r.contentUri in alreadyMatchedUris) continue

                    val fileSec = fileSecPreferModified(r)
                    val start = callStartSec(c)
                    val end = callEndSec(c)
                    val lo = start - OemRecordingConstants.SECONDARY_START_SLACK_SEC
                    val hi = end + OemRecordingConstants.SECONDARY_END_SLACK_SEC
                    if (fileSec !in lo..hi) continue

                    if (!digitMatch(c, r) && !nameMatch(c, r)) continue

                    val sp = scorePair(c, r)
                    val finalScore = if (sp > 0.0) sp else timeScore(c, r) * OemRecordingConstants.SECONDARY_FALLBACK_MULTIPLIER
                    if (finalScore < OemRecordingConstants.SECONDARY_MIN_SCORE) continue

                    scored += Cand(c.androidId, r.contentUri, finalScore)
                }
            }
            scored.sortByDescending { it.score }

            val usedCalls = alreadyMatchedCalls.toMutableSet()
            val usedUris = alreadyMatchedUris.toMutableSet()
            val out = mutableListOf<OemMatch>()
            for (row in scored) {
                if (row.androidCallId in usedCalls) continue
                if (row.uri in usedUris) continue
                usedCalls += row.androidCallId
                usedUris += row.uri
                out += OemMatch(row.androidCallId, row.uri, row.score)
            }
            return out
        }
    }
}
