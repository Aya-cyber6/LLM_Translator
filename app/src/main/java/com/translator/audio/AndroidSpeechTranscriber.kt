package com.translator.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed class TranscriptionSegment {
    data class Partial(val text: String) : TranscriptionSegment()
    data class Committed(val text: String) : TranscriptionSegment()
    data class Error(val message: String) : TranscriptionSegment()
}

class AndroidSpeechTranscriber(private val context: Context) {

    fun start(languageCode: String): Flow<TranscriptionSegment> = callbackFlow {
        // Ensure the device actually supports speech recognition
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(TranscriptionSegment.Error("Speech recognition is not available on this device."))
            close()
            return@callbackFlow
        }

        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No recognition result matched"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
                    SpeechRecognizer.ERROR_SERVER -> "Error from server"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                    else -> "Unknown error: $error"
                }
                trySend(TranscriptionSegment.Error(message))
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    trySend(TranscriptionSegment.Committed(matches[0]))
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    trySend(TranscriptionSegment.Partial(matches[0]))
                }
            }
        }

        speechRecognizer.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Enforces the offline architecture requirement
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        // Must run on the main thread
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            speechRecognizer.startListening(intent)
        }

        // Cleanup when the flow is cancelled (e.g., when stopRecording is called)
        awaitClose {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                speechRecognizer.stopListening()
                speechRecognizer.destroy()
            }
        }
    }
}