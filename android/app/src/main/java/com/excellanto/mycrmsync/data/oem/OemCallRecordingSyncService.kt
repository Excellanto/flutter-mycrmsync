package com.excellanto.mycrmsync.data.oem

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import com.excellanto.mycrmsync.data.DeviceCallLogLimits
import com.excellanto.mycrmsync.data.local.CrmDatabase
import com.excellanto.mycrmsync.data.local.LocalCallLogDao
import com.excellanto.mycrmsync.data.local.LocalCallLogEntity
import com.excellanto.mycrmsync.data.local.RecordingIndexEntity
import com.excellanto.mycrmsync.data.local.RecordingSyncStateEntity
import com.excellanto.mycrmsync.data.recordingmatch.RecordingCallMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.math.max

/**
 * Mirrors system [android.provider.CallLog] into Room, discovers OEM recordings, and links
 * recordings to calls by Android call-log id (RecDialer-style — no Supabase fingerprint bridge).
 */
class OemCallRecordingSyncService private constructor() {

    suspend fun syncOnce(
        context: Context,
        db: CrmDatabase,
    ) = withContext(Dispatchers.IO) {
        val localDao = db.localCallLogDao()
        val isFirstBootstrap = localDao.listRecent(1).isEmpty()
        val mirrorLimit =
            if (isFirstBootstrap) {
                DeviceCallLogLimits.INITIAL_BOOTSTRAP
            } else {
                DeviceCallLogLimits.LOCAL_MIRROR_INCREMENTAL
            }
        val callsRaw =
            SystemCallLogReader.readCallLogJson(
                context.applicationContext,
                limit = mirrorLimit,
            )
        val calls = parseCallsJson(callsRaw)
        val existingById = localDao.listRecent(2000).associateBy { it.androidCallLogId }
        val noteUploadedByFp =
            db.pendingCallLogRegistrationDao()
                .listRecentWithNote(limit = 300)
                .toSet()
        val merged =
            calls.mapNotNull { row ->
                val id = row.androidId.toLongOrNull() ?: return@mapNotNull null
                mergeLocalRow(existingById[id], row, noteUploadedByFp)
            }
        if (merged.isNotEmpty()) localDao.upsertAll(merged)

        val recDao = db.recordingDao()

        /** Full scan baseline so older recordings aren't excluded once the watermark moves forward. */
        val discoveryRaw =
            OemRecordingDiscovery.discoverJson(
                context.applicationContext,
                sinceModifiedSec = 0L,
                /** Allow files finalized seconds ago (OEM flush latency). */
                minAgeSecSinceEpoch = 0L,
                maxRows = OemRecordingConstants.SYNC_TYPICAL_MAX_ROWS,
            )
        val files = dedupe(parseDiscoveryJson(discoveryRaw))

        val stateWatermark = recDao.getSyncState()?.watermarkModifiedSec ?: 0L
        var maxMod = stateWatermark
        val rows =
            files.map { f ->
                maxMod = max(maxMod, f.dateModifiedSec)
                val mediaId =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching { ContentUris.parseId(Uri.parse(f.contentUri)) }.getOrDefault(-1L)
                    } else {
                        Uri.parse(f.contentUri).lastPathSegment?.toLongOrNull() ?: -1L
                    }
                RecordingIndexEntity(
                    contentUriStr = f.contentUri,
                    androidMediaId = mediaId,
                    displayName = f.displayName,
                    relativePath = f.relativePath,
                    dataPath = f.dataPath,
                    sizeBytes = f.sizeBytes,
                    dateModifiedSec = f.dateModifiedSec,
                    dateAddedSec = f.dateAddedSec,
                    durationMs = f.durationMs,
                    mimeType = f.mimeType,
                )
            }
        if (rows.isNotEmpty()) recDao.upsertRecordings(rows)
        recDao.upsertSyncState(RecordingSyncStateEntity(id = 1, watermarkModifiedSec = maxMod))

        if (files.isEmpty()) return@withContext

        val unmatchedLocals = localDao.listWithoutRecording(DeviceCallLogLimits.LOCAL_MIRROR_INCREMENTAL)
        val callsForMatching =
            (calls + unmatchedLocals.map { it.toSystemCallLogRow() })
                .distinctBy { it.androidId }

        if (callsForMatching.isEmpty()) return@withContext

        applyRecordingMatches(localDao, callsForMatching, files)
    }

    private suspend fun applyRecordingMatches(
        localDao: LocalCallLogDao,
        calls: List<SystemCallLogRow>,
        files: List<DiscoveredRecording>,
    ) {
        val greedy = RecordingCallMatcher.matchGreedy(calls, files)
        val matchedUris = greedy.map { it.recordingUri }.toMutableSet()
        val matchedCalls = greedy.map { it.androidCallId }.toMutableSet()
        val secondary =
            RecordingCallMatcher.matchSecondary(calls, files, matchedUris, matchedCalls)
        val all = greedy + secondary

        val byUri = files.associateBy { it.contentUri }
        val now = System.currentTimeMillis()
        for (m in all) {
            val id = m.androidCallId.toLongOrNull() ?: continue
            val disc = byUri[m.recordingUri] ?: continue
            val durSec =
                disc.durationMs?.let { (it / 1000L).toInt().coerceAtLeast(0) }?.takeIf { it > 0 }
            localDao.applyRecordingMatchIfBetter(
                id = id,
                uri = m.recordingUri,
                confidence = m.confidence.toFloat(),
                durationSec = durSec,
                matchedAtUtcMs = now,
            )
        }
    }

    fun sync(scope: CoroutineScope, context: Context, db: CrmDatabase, scheduleDelays: Boolean) {
        scope.launch(Dispatchers.IO) {
            syncOnce(context, db)
            if (!scheduleDelays) return@launch
            launch {
                delay(5_000)
                syncOnce(context, db)
            }
            launch {
                delay(15_000)
                syncOnce(context, db)
            }
        }
    }

    private fun mergeLocalRow(
        existing: LocalCallLogEntity?,
        row: SystemCallLogRow,
        noteUploadedByFp: Set<String>,
    ): LocalCallLogEntity {
        val id = row.androidId.toLongOrNull() ?: error("androidId parsed but not numeric")
        val ex = existing
        val fp = DeviceCallFingerprint.fromSystemRow(row)
        val noteAt =
            ex?.crmCallStatusNoteUploadedAtMs
                ?: if (fp in noteUploadedByFp) System.currentTimeMillis() else null
        return LocalCallLogEntity(
            androidCallLogId = id,
            phoneNumber = row.number,
            cachedName = row.cachedName,
            directionLow = row.direction,
            dateMs = row.dateMs,
            durationSec = row.durationSec,
            recordingUriStr = ex?.recordingUriStr,
            matchedConfidence = ex?.matchedConfidence,
            recordingDurationSec = ex?.recordingDurationSec,
            matchedAtUtcMs = ex?.matchedAtUtcMs,
            crmCallStatusNoteUploadedAtMs = noteAt,
            pendingCrmRecordingUpload = ex?.pendingCrmRecordingUpload ?: false,
        )
    }

    private fun parseCallsJson(raw: String): List<SystemCallLogRow> {
        val arr =
            try {
                JSONArray(raw)
            } catch (_: Exception) {
                return emptyList()
            }
        val out = ArrayList<SystemCallLogRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            SystemCallLogRow.tryParse(o)?.let { out += it }
        }
        return out
    }

    private fun parseDiscoveryJson(raw: String): List<DiscoveredRecording> {
        val arr =
            try {
                JSONArray(raw)
            } catch (_: Exception) {
                return emptyList()
            }
        val out = ArrayList<DiscoveredRecording>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            DiscoveredRecording.tryParse(o)?.let { out += it }
        }
        return out
    }

    private fun dedupe(items: List<DiscoveredRecording>): List<DiscoveredRecording> {
        val seen = mutableSetOf<String>()
        val out = ArrayList<DiscoveredRecording>(items.size)
        for (item in items) {
            if (seen.add(item.sortKey)) out += item
        }
        return out
    }

    companion object {
        val instance: OemCallRecordingSyncService = OemCallRecordingSyncService()
    }
}
