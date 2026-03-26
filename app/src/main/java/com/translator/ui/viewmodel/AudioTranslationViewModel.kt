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
import com.google.ai.edge.litertlm.Conversation
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import com.translator.util.PerformanceLogger
class AudioTranslationViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AudioTranslationUiState())
    val uiState: StateFlow<AudioTranslationUiState> = _uiState.asStateFlow()

    private var engine: Engine? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val ttsPlayer = TtsPlayer(application)
    private var transcriptionJob: Job? = null
    private var translationJob: Job? = null
    private val perfLogger = PerformanceLogger()
    private var translationQueue: Channel<Pair<String, String>> = Channel(Channel.UNLIMITED)
    private var activeConversation: Conversation? = null
    // -------------------------------------------------------------------------
    // Auto-init on ViewModel creation
    // -------------------------------------------------------------------------

    init {
        loadModel(Language.ENGLISH.locale)
        initTts()
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
                            val chunkId = UUID.randomUUID().toString()
                            perfLogger.startChunk(chunkId, chunk) // ⏱️ Start the ASR->TTS timer!

                            if (chunk.isNotBlank()) {
                                _uiState.update {
                                    val sep = if (it.sourceTranscript.isBlank()) "" else " "
                                    it.copy(
                                        sourceTranscript = it.sourceTranscript + sep + chunk,
                                        liveCaption      = "",
                                    )
                                }
                                translationQueue.send(Pair(chunkId, chunk)) // Send the ID and text
                            }
                        }

                        is SpeechRecognizerResponse.CompletedResponse -> {
                            val lastPartial = _uiState.value.liveCaption.trim()

                            val chunkId = UUID.randomUUID().toString()
                            perfLogger.startChunk(chunkId, lastPartial) // ⏱️ Start the ASR->TTS timer!
                            if (lastPartial.isNotBlank()) {
                                _uiState.update {
                                    val sep = if (it.sourceTranscript.isBlank()) "" else " "
                                    it.copy(
                                        sourceTranscript = it.sourceTranscript + sep + lastPartial,
                                        liveCaption      = "",
                                    )
                                }
                                translationQueue.send(Pair(chunkId, lastPartial)) // Send the ID and text
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
        // ── Translation job ───────────────────────────────────────────────────
        translationJob = viewModelScope.launch {
            _uiState.update { it.copy(isTranslating = true) }

            // 1. Initialize the conversation ONCE for this entire recording session
            val currentEngine = engine
            if (currentEngine != null) {
                val config = ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are an expert bilingual interpreter translating real-time spoken audio. " +
                                "Translate the following text strictly from ${_uiState.value.sourceLanguage.displayName} to ${_uiState.value.targetLanguage.displayName}. " +
                                "Output only the translated text."
                    ),
                    // Use strict, deterministic sampling to prevent hallucinations
                    samplerConfig = SamplerConfig(topK = 10, topP = 0.9, temperature = 0.1)
                )
                activeConversation = currentEngine.createConversation(config)
            }

            try {
                for (queueItem in translationQueue) {
                    val chunkId = queueItem.first
                    val textToTranslate = queueItem.second

                    // Notice we don't pass source/target anymore, the activeConversation already knows!
                    val translatedChunk = translateChunk(
                        chunkId = chunkId,
                        text    = textToTranslate
                    )

                    if (translatedChunk.isNotBlank()) {
                        perfLogger.markTtsStart(chunkId)
                        speakTranslation(translatedChunk)
                    }
                }
            } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                // Normal exit
            } catch (e: Exception) {
                android.util.Log.e("AudioTranslator", "Translation error", e)
                _uiState.update { it.copy(translationError = e.message) }
            } finally {
                _uiState.update {
                    it.copy(isTranslating = false, recordingState = RecordingState.IDLE)
                }

                // CRITICAL: Close and clear the conversation when recording stops to free up RAM!
                activeConversation?.close()
                activeConversation = null
            }
        }
       }

    fun stopRecording() {
        if (!_uiState.value.canStopRecording) return

        // Tell the recognizer to stop listening
        viewModelScope.launch { speechRecognizer?.stopRecognition() }

        // The recognizer will eventually fire SpeechRecognizerResponse.CompletedResponse
        // Let THAT response close the queue, do NOT close it here manually.
        _uiState.update { it.copy(recordingState = RecordingState.PROCESSING) }
    }

    fun cancelRecording() {
        viewModelScope.launch { speechRecognizer?.stopRecognition() }
        transcriptionJob?.cancel()
        translationJob?.cancel()
        translationQueue.close()
        activeConversation?.close()
        activeConversation = null
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
        loadModel(language.locale)
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
    private suspend fun translateChunk(chunkId: String, text: String): String =
        withContext(Dispatchers.IO) {
            // Grab the already-open conversation
            val conversation = activeConversation
                ?: throw IllegalStateException("Conversation was not initialized")

            var newlyTranslatedChunk = ""

            perfLogger.markLlmStart(chunkId)

            if (_uiState.value.translatedText.isNotBlank()) {
                _uiState.update { it.copy(translatedText = it.translatedText + " ") }
            }

            // DO NOT use `.use {}` here. We want this conversation to stay open!
            conversation.sendMessageAsync(text)
                .catch { e -> throw Exception("Translation error: ${e.message}") }
                .collect { token ->
                    perfLogger.markLlmFirstToken(chunkId)

                    val tokenStr = token.toString()
                    newlyTranslatedChunk += tokenStr
                    _uiState.update { it.copy(translatedText = it.translatedText + tokenStr) }
                }

            perfLogger.markLlmEnd(chunkId)

            return@withContext newlyTranslatedChunk
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
        activeConversation?.close()
        activeConversation = null
    }
}