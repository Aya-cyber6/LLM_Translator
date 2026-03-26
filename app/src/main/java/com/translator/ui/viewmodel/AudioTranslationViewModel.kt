// AudioTranslationViewModel.kt

package com.translator.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.translator.audio.TtsPlayer
import com.translator.ui.state.AudioTranslationUiState
import com.translator.ui.state.Language
import com.translator.ui.state.ModelStatus
import com.translator.ui.state.PlaybackState
import com.translator.ui.state.RecordingState
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

class AudioTranslationViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AudioTranslationUiState())
    val uiState: StateFlow<AudioTranslationUiState> = _uiState.asStateFlow()

    private var engine: Engine? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val ttsPlayer = TtsPlayer(application)

    private var transcriptionJob: Job? = null
    private var translationJob: Job? = null
    private var translationQueue: Channel<String> = Channel(Channel.UNLIMITED)

    // -------------------------------------------------------------------------
    // Auto-init on ViewModel creation
    // -------------------------------------------------------------------------

    init {
        // Kick off model check immediately — the UI starts in CHECKING and this
        // is the only thing that moves it forward.
        loadModel(Language.ENGLISH.locale)
    }

    // -------------------------------------------------------------------------
    // Engine / TTS init
    // -------------------------------------------------------------------------

    fun attachEngine(sharedEngine: Engine) {
        engine = sharedEngine
        _uiState.update { it.copy(isEngineReady = true, engineError = null) }
    }

    fun initTts() {
        ttsPlayer.init { ready -> _uiState.update { it.copy(isTtsReady = ready) } }
    }

    // -------------------------------------------------------------------------
    // ML Kit model — create, check status, download
    // -------------------------------------------------------------------------

    /**
     * Creates a [SpeechRecognizer] for [locale] and checks whether the
     * on-device model is ready. Called from [init] and on every source-language
     * change.
     *
     * Two critical fixes vs the previous version:
     *  1. MODE_BASIC instead of MODE_ADVANCED. ADVANCED is Pixel 10 only — on
     *     every other device checkStatus() returns UNAVAILABLE immediately.
     *  2. 10 s timeout around checkStatus(). AICore can block indefinitely right
     *     after fresh device setup or an AICore data-clear, which would leave
     *     modelStatus stuck on CHECKING forever.
     */
    fun loadModel(locale: Locale = Language.ENGLISH.locale) {
        viewModelScope.launch {
            _uiState.update { it.copy(modelStatus = ModelStatus.CHECKING, modelError = null) }
            try {
                speechRecognizer?.close()
                speechRecognizer = SpeechRecognition.getClient(
                    speechRecognizerOptions {
                        this.locale = locale
                        // MODE_BASIC works on all API 31+ devices.
                        // Switch to MODE_ADVANCED only if targeting Pixel 10 exclusively.
                        this.preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
                    }
                )

                val status = withTimeoutOrNull(10_000L) {
                    speechRecognizer?.checkStatus()
                }

                _uiState.update {
                    when (status) {
                        null ->
                            it.copy(
                                modelStatus = ModelStatus.UNAVAILABLE,
                                modelError  = "Model check timed out — AICore may still be " +
                                        "initialising. Wait a moment and try again.",
                            )
                        FeatureStatus.AVAILABLE    -> it.copy(modelStatus = ModelStatus.AVAILABLE)
                        FeatureStatus.DOWNLOADABLE -> it.copy(modelStatus = ModelStatus.DOWNLOADABLE)
                        FeatureStatus.DOWNLOADING  -> it.copy(modelStatus = ModelStatus.DOWNLOADING)
                        FeatureStatus.UNAVAILABLE  ->
                            it.copy(
                                modelStatus = ModelStatus.UNAVAILABLE,
                                modelError  = "Speech recognition not available on this device.",
                            )
                        else ->
                            it.copy(
                                modelStatus = ModelStatus.UNAVAILABLE,
                                modelError  = "Unknown feature status: $status",
                            )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        modelStatus = ModelStatus.UNAVAILABLE,
                        modelError  = "Failed to initialise speech model: ${e.message}",
                    )
                }
            }
        }
    }

    /**
     * Downloads the on-device model. Only meaningful when
     * modelStatus == DOWNLOADABLE.
     */
    fun downloadModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(modelStatus = ModelStatus.DOWNLOADING, modelError = null) }
            speechRecognizer?.download()
                ?.catch { e ->
                    _uiState.update {
                        it.copy(
                            modelStatus = ModelStatus.DOWNLOADABLE,
                            modelError  = "Download failed: ${e.message}",
                        )
                    }
                }
                ?.collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted ->
                            _uiState.update { it.copy(downloadProgress = "Starting download…") }
                        is DownloadStatus.DownloadProgress -> {
                            val mb = status.totalBytesDownloaded / (1024.0 * 1024.0)
                            _uiState.update { it.copy(downloadProgress = "Downloading %.1f MB…".format(mb)) }
                        }
                        is DownloadStatus.DownloadCompleted ->
                            _uiState.update {
                                it.copy(modelStatus = ModelStatus.AVAILABLE, downloadProgress = null)
                            }
                        is DownloadStatus.DownloadFailed ->
                            _uiState.update {
                                it.copy(
                                    modelStatus      = ModelStatus.DOWNLOADABLE,
                                    downloadProgress = null,
                                    modelError       = "Download failed: ${status.e.message}",
                                )
                            }
                    }
                }
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
            _uiState.update { it.copy(recordingError = "Microphone permission not granted.") }
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            _uiState.update {
                it.copy(recordingError = "Live mic input requires Android 12 (API 31) or higher.")
            }
            return
        }

        translationQueue = Channel(Channel.UNLIMITED)

        _uiState.update {
            it.copy(
                recordingState     = RecordingState.RECORDING,
                recordingError     = null,
                liveCaption        = "",
                sourceTranscript   = "",
                translatedText     = "",
                transcriptionError = null,
                translationError   = null,
                isTranslating      = false,
            )
        }

        // ── Transcription job ─────────────────────────────────────────────────
        transcriptionJob = viewModelScope.launch {
            val recognizer = speechRecognizer ?: run {
                _uiState.update {
                    it.copy(
                        recordingState     = RecordingState.IDLE,
                        transcriptionError = "Recognizer not ready. Call loadModel() first.",
                    )
                }
                translationQueue.close()
                return@launch
            }

            recognizer.startRecognition(
                speechRecognizerRequest { audioSource = AudioSource.fromMic() }
            )
                .catch { e ->
                    _uiState.update {
                        it.copy(
                            recordingState     = RecordingState.IDLE,
                            transcriptionError = "Recognition error: ${e.message}",
                        )
                    }
                    translationQueue.close()
                }
                .collect { response ->
                    when (response) {
                        is SpeechRecognizerResponse.PartialTextResponse ->
                            _uiState.update { it.copy(liveCaption = response.text) }

                        is SpeechRecognizerResponse.FinalTextResponse -> {
                            val chunk = response.text.trim()
                            if (chunk.isNotBlank()) {
                                _uiState.update {
                                    val sep = if (it.sourceTranscript.isBlank()) "" else " "
                                    it.copy(
                                        sourceTranscript = it.sourceTranscript + sep + chunk,
                                        liveCaption      = "",
                                    )
                                }
                                translationQueue.send(chunk)
                            }
                        }

                        is SpeechRecognizerResponse.CompletedResponse -> {
                            val lastPartial = _uiState.value.liveCaption.trim()
                            if (lastPartial.isNotBlank()) {
                                _uiState.update {
                                    val sep = if (it.sourceTranscript.isBlank()) "" else " "
                                    it.copy(
                                        sourceTranscript = it.sourceTranscript + sep + lastPartial,
                                        liveCaption      = "",
                                    )
                                }
                                translationQueue.send(lastPartial)
                            }
                            translationQueue.close()
                            _uiState.update { it.copy(recordingState = RecordingState.PROCESSING) }
                        }

                        is SpeechRecognizerResponse.ErrorResponse -> {
                            _uiState.update {
                                it.copy(
                                    recordingState     = RecordingState.IDLE,
                                    liveCaption        = "",
                                    transcriptionError = "Recognition failed (code ${response.e.errorCode}): ${response.e.message}",
                                )
                            }
                            translationQueue.close()
                        }
                    }
                }
        }

        // ── Translation job ───────────────────────────────────────────────────
        translationJob = viewModelScope.launch {
            _uiState.update { it.copy(isTranslating = true) }
            try {
                for (chunk in translationQueue) {
                    translateChunk(
                        text   = chunk,
                        source = _uiState.value.sourceLanguage,
                        target = _uiState.value.targetLanguage,
                    )
                }
            } catch (_: ClosedReceiveChannelException) {
                // Normal exit
            } catch (e: Exception) {
                _uiState.update { it.copy(translationError = e.message) }
            } finally {
                _uiState.update {
                    it.copy(isTranslating = false, recordingState = RecordingState.IDLE)
                }
                val finalText = _uiState.value.translatedText.trim()
                if (finalText.isNotBlank()) speakTranslation(finalText)
            }
        }
    }

    fun stopRecording() {
        if (!_uiState.value.canStopRecording) return
        viewModelScope.launch { speechRecognizer?.stopRecognition() }
    }

    fun cancelRecording() {
        viewModelScope.launch { speechRecognizer?.stopRecognition() }
        transcriptionJob?.cancel()
        translationJob?.cancel()
        translationQueue.close()
        _uiState.update {
            it.copy(
                recordingState = RecordingState.IDLE,
                liveCaption    = "",
                isTranslating  = false,
            )
        }
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
        loadModel(language.locale)   // pass the full BCP-47 locale, not Locale(code)
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
        val current = _uiState.value
        _uiState.update {
            it.copy(sourceLanguage = current.targetLanguage, targetLanguage = current.sourceLanguage)
        }
        loadModel(current.targetLanguage.locale)
    }

    // -------------------------------------------------------------------------
    // LLM translation
    // -------------------------------------------------------------------------

    private suspend fun translateChunk(text: String, source: Language, target: Language) =
        withContext(Dispatchers.IO) {
            val currentEngine = engine ?: throw IllegalStateException("Engine not ready")

            val config = ConversationConfig(
                systemInstruction = Contents.of(
                    "You are a professional translation engine. " +
                            "Translate from ${source.displayName} to ${target.displayName}. " +
                            "Output only the translated text — no explanations, no alternatives.",
                ),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3),
            )

            currentEngine.createConversation(config).use { conversation ->
                if (_uiState.value.translatedText.isNotBlank()) {
                    _uiState.update { it.copy(translatedText = it.translatedText + " ") }
                }
                conversation.sendMessageAsync(text)
                    .catch { e -> throw Exception("Translation error: ${e.message}") }
                    .collect { token ->
                        _uiState.update { it.copy(translatedText = it.translatedText + token.toString()) }
                    }
            }
        }

    // -------------------------------------------------------------------------
    // TTS
    // -------------------------------------------------------------------------

    fun speakTranslation(text: String = _uiState.value.translatedText) {
        if (text.isBlank() || !_uiState.value.isTtsReady) return
        _uiState.update { it.copy(playbackState = PlaybackState.PLAYING) }
        ttsPlayer.speak(text = text, languageCode = _uiState.value.targetLanguage.code) {
            _uiState.update { it.copy(playbackState = PlaybackState.IDLE) }
        }
    }

    fun stopPlayback() {
        ttsPlayer.stop()
        _uiState.update { it.copy(playbackState = PlaybackState.IDLE) }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        transcriptionJob?.cancel()
        translationJob?.cancel()
        translationQueue.close()
        speechRecognizer?.close()
        ttsPlayer.shutdown()
    }
}