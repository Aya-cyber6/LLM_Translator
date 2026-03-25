package com.translator.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Thin wrapper around [MediaRecorder] that records to a temporary file
 * in the app's cache directory.  Call [start], then [stop] — [stop] returns
 * the path to the recorded file so the ViewModel can pass it to the engine.
 *
 * The caller is responsible for deleting the file when done.
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    /** Starts recording. Returns the path that will hold the audio. */
    fun start(): String {
        val file = File(context.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        currentFile = file

        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        r.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)   // 16 kHz — good for speech models
            setAudioChannels(1)           // mono
            setAudioEncodingBitRate(64000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        recorder = r
        return file.absolutePath
    }

    /**
     * Stops recording and releases resources.
     * @return absolute path of the recorded file, or null if nothing was recording.
     */
    fun stop(): String? {
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            currentFile?.absolutePath
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            null
        }
    }

    /** Cancel and discard the current recording. */
    fun cancel() {
        try {
            recorder?.apply { stop(); release() }
        } catch (_: Exception) { /* ignore */ }
        recorder = null
        currentFile?.delete()
        currentFile = null
    }
}
