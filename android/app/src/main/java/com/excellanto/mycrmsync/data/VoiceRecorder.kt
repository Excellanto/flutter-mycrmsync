package com.excellanto.mycrmsync.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class VoiceRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFilePath: String? = null

    fun startRecording(): String {
        val outputFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.mp3")
        outputFilePath = outputFile.absolutePath

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(outputFilePath)
            prepare()
            start()
        }
        return outputFilePath!!
    }

    fun stopRecording() {
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    fun getOutputPath(): String? = outputFilePath?.takeIf { path ->
        File(path).exists()
    }

    /**
     * Returns the maximum absolute amplitude that was sampled since the last call to this method.
     * Returns 0 if not recording.
     */
    fun getMaxAmplitude(): Int {
        return try {
            mediaRecorder?.maxAmplitude ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
