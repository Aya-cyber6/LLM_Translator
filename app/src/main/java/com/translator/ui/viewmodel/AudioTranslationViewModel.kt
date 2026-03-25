// AudioTranslationViewModel.kt
package com.translator.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import com.translator.audio.StreamingTranscriber
import com.translator.audio.TranscriptionSegment
import com.translator.audio.TtsPlayer
import com.translator.ui.state.AudioTranslationUiState
import com.translator.ui.state.Language
import com.translator.ui.state.PlaybackState
import com.translator.ui.state.RecordingState
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioTranslationViewModel(application: Application) : AndroidViewModel(application) {

    // -------------------------------------------------------------------------
    // UI State
    // -------------------------------------------------------------------------

    private val _uiState = MutableStateFlow(AudioTranslationUiState())
    val uiState: StateFlow<AudioTranslationUiState> = _uiState.asStateFlow()

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private var engine: Engine? = null
    private var whisperContext: WhisperContext? = null
    private val ttsPlayer = TtsPlayer(application)

    // -------------------------------------------------------------------------
    // Concurrent pipeline handles
    // -------------------------------------------------------------------------

    private var transcriptionJob: Job? = null
    private var translationJob: Job? = null

    /**
     * Committed Whisper segments are sent here; the translation job drains it.
     * UNLIMITED capacity so Whisper commits never block waiting for the LLM.
     * Closed by stopRecording() to signal the translation job to finish.
     */
    private var translationQueue: Channel<String> = Channel(Channel.UNLIMITED)

    // -------------------------------------------------------------------------
    // Init
    // -------------------------------------------------------------------------

    fun attachEngine(sharedEngine: Engine) {
        engine = sharedEngine
        _uiState.update {
            it.copy(llmState = it.llmState.copy(isEngineReady = true, error = null))
        }
    }

    fun loadWhisperModel(modelPath: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    asrState = state.asrState.copy(
                        isLoading = true,
                        error = null
                    )
                )
            }
            try {
                val ctx = withContext(Dispatchers.IO) {
                    WhisperContext.createContextFromFile(modelPath)
                }
                whisperContext = ctx
                _uiState.update {
                    it.copy(asrState = it.asrState.copy(isReady = true, isLoading = false))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        asrState = it.asrState.copy(
                            isLoading = false,
                            error     = "Failed to load Whisper model: ${e.message}"
                        )
                    )
                }
            }
        }
    }

    fun initTts() {
        ttsPlayer.init { ready ->
            _uiState.update { it.copy(ttsState = it.ttsState.copy(isReady = ready)) }
        }
    }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    fun startRecording() {
        if (!_uiState.value.canRecord) return
        if (ContextCompat.checkSelfPermission(
                getApplication(), Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            _uiState.update {
                it.copy(asrState = it.asrState.copy(error = "Microphone permission not granted."))
            }
            return
        }
        val ctx = whisperContext ?: run {
            _uiState.update {
                it.copy(asrState = it.asrState.copy(error = "Whisper model not loaded."))
            }
            return
        }

        // Fresh channel for this session
        translationQueue = Channel(Channel.UNLIMITED)

        _uiState.update {
            it.copy(
                recordingState = RecordingState.RECORDING,
                asrState = it.asrState.copy(
                    error = null,
                    liveCaption = "",
                    sourceTranscript = ""
                ),
                llmState = it.llmState.copy(
                    error = null,
                    isTranslating = false,
                    translatedText = ""
                )
            )
        }

        // ── Job 1: Transcription ──────────────────────────────────────────────
        transcriptionJob = viewModelScope.launch {
            StreamingTranscriber(ctx)
                .start()
                .catch { e ->
                    _uiState.update {
                        it.copy(
                            recordingState = RecordingState.IDLE,
                            asrState = it.asrState.copy(error = "Transcription error: ${e.message}")
                        )
                    }
                    translationQueue.close()
                }
                .collect { segment ->
                    when (segment) {
                        is TranscriptionSegment.Partial -> {
                            _uiState.update {
                                it.copy(asrState = it.asrState.copy(liveCaption = segment.text))
                            }
                        }

                        is TranscriptionSegment.Committed -> {
                            val chunk = segment.text.trim()
                            if (chunk.isNotBlank()) {
                                _uiState.update {
                                    val sep = if (it.asrState.sourceTranscript.isBlank()) "" else " "
                                    it.copy(
                                        asrState = it.asrState.copy(
                                            sourceTranscript = it.asrState.sourceTranscript + sep + chunk
                                        )
                                    )
                                }
                                translationQueue.send(chunk)
                            }
                        }
                    }
                }
        }

        // ── Job 2: Translation ────────────────────────────────────────────────
        translationJob = viewModelScope.launch {
            _uiState.update { it.copy(llmState = it.llmState.copy(isTranslating = true)) }
            try {
                for (chunk in translationQueue) {
                    translateChunk(
                        text   = chunk,
                        source = _uiState.value.sourceLanguage,
                        target = _uiState.value.targetLanguage,
                    )
                }
            } catch (e: ClosedReceiveChannelException) {
                // Normal shutdown path
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(llmState = it.llmState.copy(error = e.message))
                }
            } finally {
                _uiState.update {
                    it.copy(
                        recordingState = RecordingState.IDLE,
                        llmState = it.llmState.copy(isTranslating = false)
                    )
                }

                val finalText = _uiState.value.llmState.translatedText.trim()
                if (finalText.isNotBlank()) speakTranslation(finalText)
            }
        }
    }

    fun stopRecording() {
        if (!_uiState.value.canStopRecording) return

        transcriptionJob?.cancel()
        transcriptionJob = null

        val lastPartial = _uiState.value.asrState.liveCaption.trim()
        if (lastPartial.isNotBlank()) {
            translationQueue.trySend(lastPartial)
            _uiState.update {
                val sep = if (it.asrState.sourceTranscript.isBlank()) "" else " "
                it.copy(
                    asrState = it.asrState.copy(
                        sourceTranscript = it.asrState.sourceTranscript + sep + lastPartial,
                        liveCaption = ""
                    )
                )
            }
        }

        translationQueue.close()

        _uiState.update { it.copy(recordingState = RecordingState.PROCESSING) }
    }

    fun cancelRecording() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        translationJob?.cancel()
        translationJob = null
        translationQueue.close()

        _uiState.update {
            it.copy(
                recordingState = RecordingState.IDLE,
                asrState = it.asrState.copy(liveCaption = ""),
                llmState = it.llmState.copy(isTranslating = false)
            )
        }
    }

    // -------------------------------------------------------------------------
    // LLM
    // -------------------------------------------------------------------------

    private suspend fun translateChunk(
        text: String,
        source: Language,
        target: Language,
    ) = withContext(Dispatchers.IO) {
        val currentEngine = engine
            ?: throw IllegalStateException("Engine not ready")

        val config = ConversationConfig(
            systemInstruction = Contents.of(
                "You are a professional translation engine. " +
                        "Translate from ${source.displayName} to ${target.displayName}. " +
                        "Output only the translated text — no explanations, no alternatives.",
            ),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3),
        )

        currentEngine.createConversation(config).use { conversation ->
            val needsSeparator = _uiState.value.llmState.translatedText.isNotBlank()
            if (needsSeparator) {
                _uiState.update {
                    it.copy(llmState = it.llmState.copy(translatedText = it.llmState.translatedText + " "))
                }
            }

            conversation.sendMessageAsync(text)
                .catch { e -> throw Exception("Translation stream error: ${e.message}") }
                .collect { token ->
                    _uiState.update {
                        it.copy(llmState = it.llmState.copy(translatedText = it.llmState.translatedText + token.toString()))
                    }
                }
        }
    }

    // -------------------------------------------------------------------------
    // TTS
    // -------------------------------------------------------------------------

    fun speakTranslation(text: String = _uiState.value.llmState.translatedText) {
        if (text.isBlank() || !_uiState.value.ttsState.isReady) return
        _uiState.update { it.copy(playbackState = PlaybackState.PLAYING) }
        ttsPlayer.speak(
            text         = text,
            languageCode = _uiState.value.targetLanguage.code,
        ) {
            _uiState.update { it.copy(playbackState = PlaybackState.IDLE) }
        }
    }

    fun stopPlayback() {
        ttsPlayer.stop()
        _uiState.update { it.copy(playbackState = PlaybackState.IDLE) }
    }

    // -------------------------------------------------------------------------
    // Language
    // -------------------------------------------------------------------------

    fun setSourceLanguage(language: Language) {
        _uiState.update {
            if (language.code == it.targetLanguage.code)
                it.copy(sourceLanguage = language, targetLanguage = it.sourceLanguage)
            else
                it.copy(sourceLanguage = language)
        }
    }

    fun setTargetLanguage(language: Language) {
        _uiState.update {
            if (language.code == it.sourceLanguage.code)
                it.copy(targetLanguage = language, sourceLanguage = it.targetLanguage)
            else
                it.copy(targetLanguage = language)
        }
    }

    fun swapLanguages() {
        _uiState.update {
            it.copy(sourceLanguage = it.targetLanguage, targetLanguage = it.sourceLanguage)
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        transcriptionJob?.cancel()
        translationJob?.cancel()
        translationQueue.close()
        ttsPlayer.shutdown()
        viewModelScope.launch { whisperContext?.release() }
    }
}