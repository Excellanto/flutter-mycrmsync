package com.excellanto.mycrmsync.data.oem

import android.content.ContentUris
import android.content.Context
import android.Manifest
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

object OemRecordingDiscovery {

    private val OEM_NEEDLES =
        listOf(
            "call",
            "record",
            "conversation",
            "phone",
            "call_rec",
            "callrecording",
            "call recording",
            "miui",
            "sound_recorder",
            "incoming",
            "outgoing",
            "voicemail",
            "truecaller",
            "acr",
        )

    private val LIKE_CAPITAL_HINTS =
        listOf(
            "%Call%",
            "%CALL%",
            "%Record%",
            "%Phone%",
            "%MIUI%",
            "%CallRecord%",
            "%sound_recorder%",
            "%Sound%",
        )

    /** Display / path / suffix heuristics common on OEM dialers. */
    private val CALLISH_AUDIO_SUFFIX = Regex("(?i)\\.(m4a|mp3|aac|amr|3gp|wav|ogg|opus)$")

    fun canReadAudioIndex(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    fun looksLikeCallRecording(displayName: String?, pathLike: String?): Boolean {
        val blob =
            buildString {
                append(displayName.orEmpty().lowercase())
                append(' ')
                append(pathLike.orEmpty().lowercase())
            }
        if (OEM_NEEDLES.any { needle -> blob.contains(needle.lowercase()) }) return true
        val name = displayName?.trim().orEmpty()
        if (name.isNotEmpty() && CALLISH_AUDIO_SUFFIX.containsMatchIn(name)) {
            val digitCountInName = name.filter { it.isDigit() }.length
            if (digitCountInName >= 8) return true
        }
        return false
    }

    fun probeDurationMs(context: Context, uri: Uri): Long? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context.applicationContext, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0L }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

/**
 * Queries [MediaStore.Audio.Media.EXTERNAL_CONTENT_URI], applies LIKE/MIME/size/modified filters,
 * post-filters with [looksLikeCallRecording], sorts by DATE_MODIFIED DESC, caps rows.
 *
 * *(Flutter/RN analogue: platform channel handler `discoverOemRecordings`.)*
 */
    fun discoverJson(
        context: Context,
        sinceModifiedSec: Long,
        minAgeSecSinceEpoch: Long,
        maxRows: Int,
    ): String {
        if (!canReadAudioIndex(context)) return "[]"

        val cap = maxRows.coerceIn(
            OemRecordingConstants.DISCOVERY_MAX_ROWS_CAP_LOW,
            OemRecordingConstants.DISCOVERY_MAX_ROWS_CAP_HIGH,
        )
        val scanCap = OemRecordingConstants.DISCOVERY_MAX_MEDIA_STORE_SCAN_ROWS

        val likePatterns = buildSet {
            for (n in OEM_NEEDLES) add("%$n%")
            addAll(LIKE_CAPITAL_HINTS)
        }.toList()

        fun addPredicate(column: String, patterns: List<String>): Pair<String, List<String>> {
            val inner = patterns.joinToString(" OR ") { "$column LIKE ?" }
            return "($inner)" to patterns
        }

        val columns = buildList {
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                add(MediaStore.Audio.Media.DATA)
            }
        }

        val predPairs = columns.map { addPredicate(it, likePatterns) }
        val orGroup = predPairs.joinToString(" OR ") { it.first }

        val currentSec = System.currentTimeMillis() / 1000L
        val selection = StringBuilder().apply {
            append("($orGroup)")
            append(" AND ${MediaStore.Audio.Media.SIZE} >= ?")
            append(
                " AND (${MediaStore.Audio.Media.MIME_TYPE} IS NULL OR " +
                    "${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%')",
            )
            append(" AND ${MediaStore.Audio.Media.DATE_MODIFIED} >= ?")
            if (minAgeSecSinceEpoch > 0L) {
                append(" AND ${MediaStore.Audio.Media.DATE_MODIFIED} <= ?")
            }
        }.toString()

        val args = mutableListOf<String>()
        predPairs.forEach { args.addAll(it.second) }
        args.add(OemRecordingConstants.MEDIA_STORE_MIN_SIZE_BYTES.toString())
        args.add(sinceModifiedSec.coerceAtLeast(0L).toString())
        if (minAgeSecSinceEpoch > 0L) {
            args.add((currentSec - minAgeSecSinceEpoch).toString())
        }

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.DURATION)
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()

        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        val out = JSONArray()
        val resolver = context.applicationContext.contentResolver

        try {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args.toTypedArray(),
                sortOrder,
            )?.use { cursor ->
                fun idx(name: String): Int =
                    cursor.getColumnIndex(name).takeIf { it >= 0 } ?: -1

                val iId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val iSize = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val iMod = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val iAdded = idx(MediaStore.Audio.Media.DATE_ADDED)
                val iMime = idx(MediaStore.Audio.Media.MIME_TYPE)
                val iDur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    idx(MediaStore.Audio.Media.DURATION)
                } else {
                    -1
                }
                val iRel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    idx(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    -1
                }
                val iData = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    idx(MediaStore.Audio.Media.DATA)
                } else {
                    -1
                }

                var rowsScanned = 0
                while (cursor.moveToNext()) {
                    rowsScanned++
                    if (rowsScanned > scanCap) break
                    if (out.length() >= cap) break
                    val id = cursor.getLong(iId)
                    val displayName = cursor.getString(iName) ?: continue
                    val size = cursor.getLong(iSize)
                    val modSec = cursor.getLong(iMod)
                    val addedSec = if (iAdded >= 0) cursor.getLong(iAdded) else 0L
                    val mime = if (iMime >= 0) cursor.getString(iMime) else null
                    var durationMs = if (iDur >= 0) {
                        cursor.getLong(iDur).takeIf { it > 0L }
                    } else {
                        null
                    }

                    val relativePath = if (iRel >= 0) cursor.getString(iRel) else null
                    val dataPath = if (iData >= 0) cursor.getString(iData) else null

                    val pathProbe = buildString {
                        append(relativePath.orEmpty())
                        append(dataPath.orEmpty())
                    }
                    if (!looksLikeCallRecording(displayName, pathProbe)) continue

                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id,
                    )

                    if (durationMs == null || durationMs == 0L) {
                        durationMs = probeDurationMs(context, uri)
                    }

                    val jo = JSONObject().apply {
                        put("contentUri", uri.toString())
                        put("displayName", displayName)
                        put("dataPath", dataPath ?: JSONObject.NULL)
                        put("relativePath", relativePath ?: JSONObject.NULL)
                        put("dateModifiedSec", modSec)
                        put("dateAddedSec", addedSec)
                        put("sizeBytes", size)
                        put("mimeType", mime ?: JSONObject.NULL)
                        put("durationMs", durationMs ?: JSONObject.NULL)
                    }
                    out.put(jo)
                }
            }
        } catch (_: SecurityException) {
            return "[]"
        }

        return out.toString()
    }
}
