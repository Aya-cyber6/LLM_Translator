package com.translator.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class TtsPlayer(private val context: Context) {

    private var tts: TextToSpeech? = null
    var isReady: Boolean = false
        private set

    // We store the callback here so the global listener can trigger it
    private var onPlaybackDone: (() -> Unit)? = null

    fun init(onReady: (Boolean) -> Unit) {
        tts = TextToSpeech(context) { status ->
            isReady = status == TextToSpeech.SUCCESS

            if (isReady) {
                // Set the listener ONCE during initialization
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        onPlaybackDone?.invoke()
                    }
                    override fun onError(utteranceId: String?) {
                        onPlaybackDone?.invoke()
                    }
                })
            }
            onReady(isReady)
        }
    }

    fun speak(text: String, languageCode: String, onDone: () -> Unit) {
        val engine = tts ?: return
        if (!isReady) return

        this.onPlaybackDone = onDone

        val locale = Locale.forLanguageTag(languageCode)
        val result = engine.setLanguage(locale)

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }

        // Generate a unique ID for every single chunk added to the queue
        val uniqueUtteranceId = UUID.randomUUID().toString()
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, uniqueUtteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        isReady = false
    }
}