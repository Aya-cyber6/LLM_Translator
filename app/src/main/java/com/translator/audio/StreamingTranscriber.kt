// StreamingTranscriber.kt
//
// Ports stream.cpp's sliding-window algorithm to Android/Kotlin.
//
// Two coroutines run in parallel inside a channelFlow:
//
//   [Capture]  AudioRecord → raw 1024-sample chunks → Channel<FloatArray>
//   [Process]  Accumulate chunks → build window → WhisperContext → emit segments
//
// Sliding-window algorithm (mirrors stream.cpp non-VAD path exactly):
//
//   pcmf32_new  = stepMs of fresh audio        (48 000 samples @ 16 kHz, 3 s)
//   pcmf32_old  = context from previous iter   (grows up to lengthMs, resets after commit)
//   window      = tail-of-old + new            (fed to whisper_full)
//
//   Every nNewLine steps text is "committed" and pcmf32_old is trimmed to
//   keepMs overlap — reducing word-boundary errors across step boundaries.
//
// stream.cpp reference defaults:
//   step_ms = 3 000  |  length_ms = 10 000  |  keep_ms = 200
//   n_new_line = max(1, length_ms / step_ms − 1)  =  2

package com.translator.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

sealed class TranscriptionSegment {
    /**
     * Emitted every [StreamingTranscriber.stepMs].
     * Contains Whisper's output for the current sliding window.
     *
     * The UI should OVERWRITE (not append) the live-caption field with this
     * value — mirrors stream.cpp's terminal "\33[2K\r" clear-line behaviour.
     */
    data class Partial(val text: String) : TranscriptionSegment()

    /**
     * Emitted every [StreamingTranscriber.nNewLine] steps (~6 s with defaults).
     * The window text at this point is considered stable.
     * The UI / ViewModel should APPEND this to the running transcript.
     */
    data class Committed(val text: String) : TranscriptionSegment()
}

// ---------------------------------------------------------------------------
// StreamingTranscriber
// ---------------------------------------------------------------------------

class StreamingTranscriber(
    private val whisperContext: WhisperContext,
    val stepMs: Int   = 3_000,   // how often to run Whisper  (stream.cpp --step)
    val lengthMs: Int = 10_000,  // max context window        (stream.cpp --length)
    val keepMs: Int   = 200,     // overlap after commit      (stream.cpp --keep)
) {
    companion object {
        const val SAMPLE_RATE  = 16_000  // Hz — Whisper hard requirement
        private const val READ_CHUNK  = 1_024  // samples per AudioRecord.read() ≈ 64 ms
        private const val CHANNEL_CAP = 8     // audio channel buffer (steps)
    }

    // Pre-computed sample counts  (mirrors stream.cpp n_samples_* variables)
    val stepSamples:   Int = SAMPLE_RATE * stepMs   / 1_000   // 48 000
    val lengthSamples: Int = SAMPLE_RATE * lengthMs / 1_000   // 160 000
    val keepSamples:   Int = SAMPLE_RATE * keepMs   / 1_000   // 3 200

    // Steps between commits  (stream.cpp: n_new_line = max(1, length/step − 1))
    val nNewLine: Int = maxOf(1, lengthMs / stepMs - 1)        // 2

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns a cold [Flow] that, when collected:
     *  1. Opens the microphone via [AudioRecord].
     *  2. Runs continuous [stepMs] sliding-window transcription.
     *  3. Emits [TranscriptionSegment.Partial] on every step.
     *  4. Emits [TranscriptionSegment.Committed] every [nNewLine] steps.
     *  5. Closes the mic cleanly when the collector is cancelled.
     *
     * Dispatches audio capture to [Dispatchers.IO] and inference to
     * [Dispatchers.Default] — safe to collect from any context.
     */
    fun start(): Flow<TranscriptionSegment> = channelFlow {

        // Channel connecting capture ↔ process coroutines.
        val audioChannel = Channel<FloatArray>(capacity = CHANNEL_CAP)

        // ---------------------------------------------------------------------
        // Coroutine 1 — Audio capture  (Dispatchers.IO)
        // Reads microphone in READ_CHUNK bursts and forwards to audioChannel.
        // ---------------------------------------------------------------------
        val captureJob = launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(minBuf, READ_CHUNK * 4) * 4,
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                close(IllegalStateException("AudioRecord failed to initialise."))
                return@launch
            }

            record.startRecording()

            try {
                val readBuf = FloatArray(READ_CHUNK)
                while (isActive) {
                    val n = record.read(readBuf, 0, readBuf.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) continue

                    val chunk = readBuf.copyOf(n)

                    // If the process side is falling behind (channel full), drop
                    // the oldest chunk — mirrors stream.cpp "dropping audio" warning.
                    if (!audioChannel.trySend(chunk).isSuccess) {
                        audioChannel.tryReceive()  // discard one stale chunk
                        audioChannel.trySend(chunk)
                    }
                }
            } finally {
                record.stop()
                record.release()
                audioChannel.close()
            }
        }

        // ---------------------------------------------------------------------
        // Coroutine 2 — Sliding-window inference  (Dispatchers.Default)
        //
        // Mirrors stream.cpp main loop (non-VAD path):
        //
        //   audio.get(step_ms, pcmf32_new)            ← stepBuf accumulation below
        //   n_samples_take = min(old, max(0, keep + len − new))
        //   pcmf32 = pcmf32_old[end−take : end] + pcmf32_new
        //   pcmf32_old = pcmf32
        //   whisper_full(ctx, wparams, pcmf32.data(), pcmf32.size())
        //   emit text
        //   if (n_iter % n_new_line == 0) { commit; trim pcmf32_old to keep }
        // ---------------------------------------------------------------------
        launch(Dispatchers.Default) {

            // stream.cpp: pcmf32_old — grows between commits, reset after each
            var pcmf32Old = FloatArray(0)
            var nIter = 0

            // Step accumulator backed by a plain array for zero-boxing performance.
            // stepBuf[0..stepFilled) holds audio waiting to form the next step.
            val stepBuf    = FloatArray(stepSamples)
            var stepFilled = 0

            for (chunk in audioChannel) {

                // Drain the chunk into stepBuf, potentially triggering multiple
                // inference passes if the chunk spans more than one step (rare
                // but possible after a processing stall).
                var chunkOffset    = 0
                var chunkRemaining = chunk.size

                while (chunkRemaining > 0) {
                    val toCopy = minOf(chunkRemaining, stepSamples - stepFilled)
                    System.arraycopy(chunk, chunkOffset, stepBuf, stepFilled, toCopy)
                    stepFilled     += toCopy
                    chunkOffset    += toCopy
                    chunkRemaining -= toCopy

                    if (stepFilled < stepSamples) break   // step not yet complete

                    // ----------------------------------------------------------
                    // We have a full step — run the sliding-window algorithm.
                    // ----------------------------------------------------------

                    val pcmf32New = stepBuf.copyOf()  // snapshot before we clear
                    stepFilled    = 0                  // reset accumulator

                    // Build window: tail-of-old + new
                    // (stream.cpp lines 279-291)
                    //
                    //   n_samples_take = min(pcmf32_old.size,
                    //                        max(0, n_keep + n_len − n_new))
                    val nSamplesTake = minOf(
                        pcmf32Old.size,
                        maxOf(0, keepSamples + lengthSamples - stepSamples),
                    )

                    val window = FloatArray(nSamplesTake + stepSamples)

                    if (nSamplesTake > 0) {
                        System.arraycopy(
                            pcmf32Old, pcmf32Old.size - nSamplesTake,
                            window, 0,
                            nSamplesTake,
                        )
                    }
                    System.arraycopy(pcmf32New, 0, window, nSamplesTake, stepSamples)

                    // stream.cpp: pcmf32_old = pcmf32 (save full window for next iter)
                    pcmf32Old = window

                    // ----------------------------------------------------------
                    // Whisper inference
                    // ----------------------------------------------------------
                    val text = whisperContext.transcribeData(window).trim()

                    if (text.isNotBlank()) {
                        send(TranscriptionSegment.Partial(text))
                    }

                    nIter++

                    // ----------------------------------------------------------
                    // Commit every nNewLine steps
                    // (stream.cpp lines 408-426)
                    // ----------------------------------------------------------
                    if (nIter % nNewLine == 0) {
                        if (text.isNotBlank()) {
                            send(TranscriptionSegment.Committed(text))
                        }

                        // Trim pcmf32_old to keepSamples tail
                        // (stream.cpp: pcmf32_old = last n_keep samples of pcmf32)
                        pcmf32Old = if (window.size > keepSamples) {
                            window.copyOfRange(window.size - keepSamples, window.size)
                        } else {
                            window
                        }
                    }
                }   // while (chunkRemaining > 0)
            }   // for (chunk in audioChannel)
        }

        captureJob.join()
    }
}