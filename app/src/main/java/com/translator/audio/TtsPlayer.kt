package com.translator.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Wraps [TextToSpeech] with a simple callback-based interface.
 * Must call [init] before speaking, and [shutdown] when done.
 */
class TtsPlayer(private val context: Context) {

    private var tts: TextToSpeech? = null
    var isReady: Boolean = false
        private set

    fun init(onReady: (Boolean) -> Unit) {
        tts = TextToSpeech(context) { status ->
            isReady = status == TextToSpeech.SUCCESS
            onReady(isReady)
        }
    }

    /**
     * Speak [text] in [languageCode] (BCP-47 tag, e.g. "tr", "fr").
     * [onDone] is called when playback finishes or on error.
     */
    fun speak(text: String, languageCode: String, onDone: () -> Unit) {
        val engine = tts ?: return
        val locale = Locale.forLanguageTag(languageCode)
        val result = engine.setLanguage(locale)

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fall back to default locale
            engine.setLanguage(Locale.getDefault())
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = onDone()
            override fun onError(utteranceId: String?) = onDone()
        })

        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        isReady = false
    }

    companion object {
        private const val UTTERANCE_ID = "llm_translator_tts"
    }
}
