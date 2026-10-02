package com.excellanto.mycrmsync.data.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Derives ~[barCount] normalized peaks from compressed audio frame sizes (no PCM decode).
 * Fast and works for file, content, and http(s) sources when [MediaExtractor] can open them.
 */
object WaveformPeaksLoader {
    const val DEFAULT_BARS = 80

    suspend fun loadNormalizedPeaks(
        context: Context,
        uri: Uri,
        barCount: Int = DEFAULT_BARS,
    ): Result<FloatArray> = withContext(Dispatchers.IO) {
        runCatching { extractPeaksFromFrameSizes(context, uri, barCount) }
    }

    private fun extractPeaksFromFrameSizes(context: Context, uri: Uri, barCount: Int): FloatArray {
        val extractor = MediaExtractor()
        try {
            when (uri.scheme?.lowercase()) {
                "file" -> {
                    val p = uri.path ?: error("Missing path")
                    extractor.setDataSource(p)
                }
                "content" -> extractor.setDataSource(context, uri, null)
                "http", "https" -> extractor.setDataSource(uri.toString(), emptyMap())
                else -> error("Unsupported URI for waveform")
            }

            val track = (0 until extractor.trackCount).firstOrNull { t ->
                extractor.getTrackFormat(t).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track")

            extractor.selectTrack(track)
            val sizes = ArrayList<Long>(1024)
            var maxSz = 1L
            while (true) {
                val sz = extractor.sampleSize
                if (sz <= 0) break
                sizes.add(sz)
                maxSz = max(maxSz, sz)
                if (!extractor.advance()) break
            }

            if (sizes.isEmpty()) return FloatArray(barCount) { 0.12f }
            return bucketNormalize(sizes, barCount, maxSz.toFloat())
        } finally {
            extractor.release()
        }
    }

    private fun bucketNormalize(sizes: List<Long>, barCount: Int, maxVal: Float): FloatArray {
        val n = sizes.size
        val out = FloatArray(barCount)
        val chunk = n.toFloat() / barCount
        val denom = sqrt(maxVal.coerceAtLeast(1f))
        for (i in 0 until barCount) {
            val start = (i * chunk).toInt().coerceIn(0, n - 1)
            val endExclusive = ((i + 1) * chunk).toInt().coerceAtLeast(start + 1).coerceAtMost(n)
            var acc = 0.0
            for (j in start until endExclusive) acc += sizes[j]
            val avg = (acc / (endExclusive - start)).toFloat()
            val norm = (sqrt(avg) / denom).coerceIn(0f, 1f)
            out[i] = norm.coerceAtLeast(0.06f)
        }
        return out
    }
}
