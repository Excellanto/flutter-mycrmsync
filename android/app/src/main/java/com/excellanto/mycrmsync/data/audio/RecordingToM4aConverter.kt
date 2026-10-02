package com.excellanto.mycrmsync.data.audio

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Transcodes device audio to **AAC in MPEG‑4** (**.m4a**) for OpenAI Whisper.
 * On failure returns `false` — caller uploads the original file instead.
 */
object RecordingToM4aConverter {
    private const val TAG = "RecordingToM4a"
    private const val MICROS = 1_000_000L
    private const val TIMEOUT_US = 10_000L

    fun tryConvert(inputFile: File, outputFile: File): Boolean {
        if (!inputFile.canRead()) return false
        runCatching { if (outputFile.exists()) outputFile.delete() }

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null

        return try {
            extractor = MediaExtractor().apply { setDataSource(inputFile.absolutePath) }
            val audioTrack =
                (0 until extractor.trackCount).firstOrNull { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return false

            extractor.selectTrack(audioTrack)
            val inFormat = extractor.getTrackFormat(audioTrack)
            val inMime = inFormat.getString(MediaFormat.KEY_MIME) ?: return false

            val sampleRate =
                if (inFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else {
                    44_100
                }
            val channels =
                if (inFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    max(1, inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                } else {
                    1
                }

            val bitrate = (64_000 * channels).coerceIn(64_000, 192_000)

            decoder = MediaCodec.createDecoderByType(inMime).apply {
                configure(inFormat, null, null, 0)
                start()
            }

            val encFormat =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
                }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            transcodeLoop(
                extractor = extractor,
                decoder = decoder,
                encoder = encoder,
                muxer = muxer,
                sampleRate = sampleRate,
                channelCount = channels,
            )

            encoder.stop()
            decoder.stop()
            muxer.stop()

            encoder.release()
            decoder.release()
            extractor.release()
            muxer.release()
            encoder = null
            decoder = null
            extractor = null
            muxer = null

            /** Minimal muxed output — short utterances produce small files but are still valid. */
            outputFile.length() >= 128
        } catch (e: Exception) {
            Log.w(TAG, "M4A transcode failed", e)
            runCatching { encoder?.stop(); encoder?.release() }
            runCatching { decoder?.stop(); decoder?.release() }
            runCatching { extractor?.release() }
            runCatching { muxer?.stop(); muxer?.release() }
            runCatching { if (outputFile.exists()) outputFile.delete() }
            false
        }
    }

    private fun transcodeLoop(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        sampleRate: Int,
        channelCount: Int,
    ) {
        val decInfo = BufferInfo()
        val encInfo = BufferInfo()

        var muxerStarted = false
        var audioTrackMux = -1

        var extractorDone = false
        var decoderDone = false
        var encoderInputEosSent = false
        var encoderOutputEos = false

        val bytesPerFrame = max(2, 2 * channelCount)

        /** Holds PCM waiting for encoder queue space. */
        var pendingPcm: ByteBuffer? = null
        /** Presentation time matching [pendingPcm]'s current position(); -1 until first PCM. */
        var pendingPtsUs: Long = -1L
        /** End-of-stream PTS for encoder after all PCM pumped. */
        var finalEncoderPtsUs: Long = 0L

        fun ptsDeltaFor(chunkBytes: Int): Long {
            if (sampleRate <= 0) return 1000L
            val frames = (chunkBytes / bytesPerFrame).coerceAtLeast(1)
            return (frames * MICROS) / sampleRate
        }

        /** Push as much of [pendingPcm] as possible into encoder. */
        fun pumpPendingIntoEncoder(): Boolean {
            val p = pendingPcm ?: return false
            while (p.hasRemaining()) {
                val inIdx = encoder.dequeueInputBuffer(0)
                if (inIdx < 0) return false
                val inBuf = encoder.getInputBuffer(inIdx)!!
                inBuf.clear()
                val chunk = p.remaining().coerceAtMost(inBuf.capacity())

                val end = p.position() + chunk
                val oldLim = p.limit()
                p.limit(end)
                inBuf.put(p)
                p.limit(oldLim)

                encoder.queueInputBuffer(inIdx, 0, chunk, pendingPtsUs, 0)
                pendingPtsUs += ptsDeltaFor(chunk)
                finalEncoderPtsUs = max(finalEncoderPtsUs, pendingPtsUs)
            }
            pendingPcm = null
            return true
        }

        while (!encoderOutputEos) {
            pumpPendingIntoEncoder()

            // 1 Extractor → decoder
            if (!extractorDone) {
                val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val inBuf = decoder.getInputBuffer(inIdx)!!.apply { clear() }
                    val n = extractor.readSampleData(inBuf, 0)
                    if (n < 0) {
                        decoder.queueInputBuffer(
                            inIdx,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        extractorDone = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, n, extractor.sampleTime.coerceAtLeast(0), 0)
                        extractor.advance()
                    }
                }
            }

            // 2 Decoder output → pending PCM blob
            var decOutIdx = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
            if (decOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                decOutIdx = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
            }
            while (decOutIdx >= 0) {
                val outBuf = decoder.getOutputBuffer(decOutIdx)
                if (outBuf != null && decInfo.size > 0) {
                    val off = max(0, decInfo.offset)
                    val sz = decInfo.size
                    outBuf.clear()
                    outBuf.position(off)
                    outBuf.limit(off + sz)
                    val copy = ByteBuffer.allocateDirect(sz).put(outBuf)
                    copy.flip()
                    pendingPcm =
                        pendingPcm?.let { prev ->
                            val merged =
                                ByteBuffer.allocateDirect(prev.remaining() + copy.remaining())
                            merged.put(prev)
                            merged.put(copy)
                            merged.flip()
                            merged
                        } ?: copy
                    if (pendingPtsUs < 0L) {
                        pendingPtsUs = decInfo.presentationTimeUs.coerceAtLeast(0L)
                    }
                }
                if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    decoderDone = true
                }
                decoder.releaseOutputBuffer(decOutIdx, false)
                decOutIdx = decoder.dequeueOutputBuffer(decInfo, 0)
            }

            pumpPendingIntoEncoder()
            if (decoderDone && pendingPcm == null && !encoderInputEosSent) {
                val ix = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (ix >= 0) {
                    val eosPts =
                        if (pendingPtsUs >= 0L) {
                            max(finalEncoderPtsUs, pendingPtsUs)
                        } else {
                            max(finalEncoderPtsUs, 0L)
                        }
                    encoder.queueInputBuffer(
                        ix,
                        0,
                        0,
                        eosPts,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    encoderInputEosSent = true
                }
            }

            var encOutIdx = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US)
            if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val fmt = encoder.outputFormat
                audioTrackMux = muxer.addTrack(fmt)
                muxer.start()
                muxerStarted = true
                encOutIdx = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US)
            }
            while (encOutIdx >= 0) {
                val codecCfg = (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val hasData = encInfo.size > 0
                if (muxerStarted && audioTrackMux >= 0 && hasData && !codecCfg) {
                    val eb = encoder.getOutputBuffer(encOutIdx)!!.apply {
                        position(encInfo.offset)
                        limit(encInfo.offset + encInfo.size)
                    }
                    muxer.writeSampleData(audioTrackMux, eb, encInfo)
                }
                if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    encoder.releaseOutputBuffer(encOutIdx, false)
                    encoderOutputEos = true
                    break
                }
                encoder.releaseOutputBuffer(encOutIdx, false)
                encOutIdx = encoder.dequeueOutputBuffer(encInfo, 0)
            }

            if (!encoderOutputEos && extractorDone && decoderDone && pendingPcm == null && encoderInputEosSent) {
                Thread.yield()
            }
        }
    }
}
