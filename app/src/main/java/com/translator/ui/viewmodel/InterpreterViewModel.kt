package com.translator.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
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
import com.translator.ui.state.InterpreterEntry
import com.translator.ui.state.InterpreterUiState
import com.translator.ui.state.Language
import com.translator.ui.state.ModelStatus
import com.translator.ui.state.PickerTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "InterpreterVM"

/**
 * ViewModel for Interpreter Mode.
 *
 * Architecture — three concurrent coroutines for the lifetime of one session:
 *
 *  ┌─────────────────────┐       Channel<Pair<id,text>>       ┌──────────────────────┐
 *  │ runTranscriptionLoop│ ──────────────────────────────────▶│ runTranslationWorker │
 *  │  (ML Kit ASR loop)  │  FinalTextResponse chunks          │  (LiteRT per-chunk)  │
 *  └─────────────────────┘                                    └──────────┬───────────┘
 *                                                                        │ translated strings
 *                                                               Channel<String>
 *                                                                        │
 *                                                             ┌──────────▼───────────┐
 *                                                             │   runTtsPlayback     │
 *                                                             │  (serial TTS queue)  │
 *                                                             └──────────────────────┘
 *
 *  - [runTranscriptionLoop] restarts ML Kit recognition after every CompletedResponse
 *    so the microphone is *never* idle between sentences.
 *  - [runTranslationWorker] keeps one LiteRT Conversation open for the entire session
 *    (cheaper than creating a new one per chunk) and translates chunks as they arrive.
 *  - [runTtsPlayback] serialises TTS so sentences are spoken one-at-a-time in order,
 *    while translation of the next chunk proceeds in parallel.
 *
 *  Stopping:
 *   1. [stopListening] sets isListening=false and calls stopRecognition().
 *   2. ML Kit fires CompletedResponse → transcription loop exits its while check.
 *   3. sessionJob joins transcription, then closes translationChannel.
 *   4. Translation worker drains remaining items, closes ttsChannel.
 *   5. TTS worker drains and exits. Session fully over.
 */
class InterpreterViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(InterpreterUiState())
    val uiState: StateFlow<InterpreterUiState> = _uiState.asStateFlow()

    private var engine: Engine? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val ttsPlayer = TtsPlayer(application)

    /** Parent job that owns all three session coroutines. */
    private var sessionJob: Job? = null
    private var translationChannel: Channel<Pair<String, String>> = Channel(Channel.UNLIMITED)
    private var ttsChannel: Channel<String> = Channel(Channel.UNLIMITED)
    private var activeConversation: Conversation? = null

    init {
        loadASRModel(Language.ENGLISH.locale)
        ttsPlayer.init { ready -> _uiState.update { it.copy(isTtsReady = ready) } }
    }
    // ── Engine attachment (called from MainActivity after LiteRT init) ─────────

    fun attachEngine(sharedEngine: Engine) {
        engine = sharedEngine
        _uiState.update { it.copy(isEngineReady = true, engineError = null) }
    }

    // ── ASR model lifecycle ───────────────────────────────────────────────────
    fun loadASRModel(locale: java.util.Locale = Language.ENGLISH.locale) {
        viewModelScope.launch {
            _uiState.update { it.copy(modelStatus = ModelStatus.CHECKING, modelError = null) }
            try {
                speechRecognizer?.close()
                speechRecognizer = SpeechRecognition.getClient(
                    speechRecognizerOptions {
                        this.locale = locale
                        this.preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
                    }
                )
                val status = withTimeoutOrNull(10_000L) { speechRecognizer?.checkStatus() }
                _uiState.update {
                    when (status) {
                        null ->
                            it.copy(
                                modelStatus = ModelStatus.UNAVAILABLE,
                                modelError  = "Model check timed out — AICore may still be initialising.",
                            )
                        FeatureStatus.AVAILABLE    -> it.copy(modelStatus = ModelStatus.AVAILABLE)
                        FeatureStatus.DOWNLOADABLE -> it.copy(modelStatus = ModelStatus.DOWNLOADABLE)
                        FeatureStatus.DOWNLOADING  -> it.copy(modelStatus = ModelStatus.DOWNLOADING)
                        else ->
                            it.copy(
                                modelStatus = ModelStatus.UNAVAILABLE,
                                modelError  = "Speech recognition not available on this device.",
                            )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(modelStatus = ModelStatus.UNAVAILABLE, modelError = e.message)
                }
            }
        }
    }
    fun downloadASRModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(modelStatus = ModelStatus.DOWNLOADING, modelError = null) }
            speechRecognizer?.download()
                ?.catch { e ->
                    _uiState.update {
                        it.copy(modelStatus = ModelStatus.DOWNLOADABLE, modelError = e.message)
                    }
                }
                ?.collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted ->
                            _uiState.update { it.copy(downloadProgress = "Starting download…") }
                        is DownloadStatus.DownloadProgress -> {
                            val mb = status.totalBytesDownloaded / (1024.0 * 1024.0)
                            _uiState.update { it.copy(downloadProgress = "%.1f MB…".format(mb)) }
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
                                    modelError       = status.e.message,
                                )
                            }
                    }
                }
        }
    }
    // ── Session entry / exit ──────────────────────────────────────────────────
    fun startListening() {
        if (!_uiState.value.canStart) return

        if (ContextCompat.checkSelfPermission(
                getApplication(), Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            _uiState.update { it.copy(translationError = "Microphone permission not granted.") }
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            _uiState.update {
                it.copy(translationError = "Live mic input requires Android 12 (API 31) or higher.")
            }
            return
        }

        val currentEngine = engine ?: run {
            _uiState.update { it.copy(translationError = "Translation engine not ready yet.") }
            return
        }

        // Fresh channels for this session
        translationChannel = Channel(Channel.UNLIMITED)
        ttsChannel         = Channel(Channel.UNLIMITED)

        _uiState.update {
            it.copy(
                isListening      = true,
                liveCaption      = "",
                history          = emptyList(),
                translationError = null,
                isTranslating    = false,
            )
        }

        // One LiteRT conversation for the whole session — cheaper and maintains
        // contextual coherence (proper nouns, speaker style) across sentences.
        activeConversation?.close()
        activeConversation = currentEngine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(buildSystemInstruction()),
                samplerConfig     = SamplerConfig(topK = 10, topP = 0.9, temperature = 0.1),
            )
        )

        sessionJob = viewModelScope.launch {

            // ── Three workers, all owned by this scope ────────────────────────
            val transcriptionJob = launch { runTranscriptionLoop() }
            val translationJob   = launch { runTranslationWorker() }
            val ttsJob           = launch { runTtsPlayback() }

            // Wait for transcription to finish (triggered by stopListening or error)
            transcriptionJob.join()

            // Signal translation worker: no more ASR input
            translationChannel.close()
            translationJob.join()

            // Signal TTS worker: no more translated text
            ttsChannel.close()
            ttsJob.join()

            // Clean up conversation and reset UI
            activeConversation?.close()
            activeConversation = null
            _uiState.update {
                it.copy(isListening = false, isTranslating = false, liveCaption = "")
            }
        }
    }

    fun stopListening() {
        if (!_uiState.value.canStop) return

        // Flip the flag — runTranscriptionLoop's while-condition will see this
        // after ML Kit fires CompletedResponse and the current recognition flow ends.
        _uiState.update { it.copy(isListening = false) }

        // Tell ML Kit to wrap up the current recognition pass.
        // It will emit CompletedResponse, completing the flow naturally.
        viewModelScope.launch { speechRecognizer?.stopRecognition() }
    }

    // ── Worker 1: Continuous ASR loop ─────────────────────────────────────────

    /**
     * Runs ML Kit recognition in a loop.
     *
     * Each call to [startRecognition] produces a flow that ends with
     * [CompletedResponse] (when ML Kit detects end-of-speech or when
     * [stopRecognition] is called). After each flow completes, we check
     * [InterpreterUiState.isListening] and re-arm recognition if still live.
     *
     * This keeps the microphone continuously hot with zero dead time between
     * sentences. The 150 ms delay before each restart is enough for the OS to
     * release and re-acquire the audio focus without a glitch.
     */
    private suspend fun runTranscriptionLoop() {
        val recognizer = speechRecognizer ?: return

        while (_uiState.value.isListening) {
            try {
                recognizer.startRecognition(
                    speechRecognizerRequest { audioSource = AudioSource.fromMic() }
                )
                    .catch { e ->
                        Log.w(TAG, "ASR flow error: ${e.message}")
                    }
                    .collect { response ->
                        when (response) {

                            is SpeechRecognizerResponse.PartialTextResponse ->
                                // Update the "live caption" ticker while the user is speaking
                                _uiState.update { it.copy(liveCaption = response.text) }

                            is SpeechRecognizerResponse.FinalTextResponse -> {
                                val chunk = response.text.trim()
                                if (chunk.isNotBlank()) {
                                    // Clear the live caption — this sentence is now committed
                                    _uiState.update { it.copy(liveCaption = "") }
                                    translationChannel.send(Pair(UUID.randomUUID().toString(), chunk))
                                }
                                // Do NOT return here — ML Kit may still emit more finals
                                // before CompletedResponse on MODE_BASIC.
                            }
                            is SpeechRecognizerResponse.CompletedResponse -> {
                                // Flush any partial that ML Kit didn't finalise
                                val tail = _uiState.value.liveCaption.trim()
                                if (tail.isNotBlank()) {
                                    _uiState.update { it.copy(liveCaption = "") }
                                    translationChannel.send(Pair(UUID.randomUUID().toString(), tail))
                                }
                                // Flow will complete after this block; the while-loop re-evaluates.
                            }

                            is SpeechRecognizerResponse.ErrorResponse -> {
                                Log.w(TAG, "ASR error code: ${response.e.errorCode} — ${response.e.message}")
                                // Don't surface every transient error to the user; the loop will retry.
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Recognition exception: ${e.message}")
            }

            // Brief pause before re-arming: lets the OS release audio focus cleanly
            // and prevents a tight spin if recognition errors loop rapidly.
            if (_uiState.value.isListening) delay(150)
        }
    }

    // ── Worker 2: Translation consumer ───────────────────────────────────────

    /**
     * Drains [translationChannel] one chunk at a time.
     *
     * Translation is serialised (not parallelised) to preserve sentence order
     * in the conversation context. LiteRT's conversation carries state, so
     * in-order delivery keeps the context coherent.
     *
     * TTS is decoupled via [ttsChannel] so translation of chunk N+1 begins
     * while TTS is still speaking chunk N — i.e., the pipeline is:
     *
     *   ASR ──▶ translateChunk(N) ──▶ ttsChannel ──▶ speak(N)
     *                   ▲                                 |
     *                   └── translateChunk(N+1) starts ───┘ (overlap)
     */
    private suspend fun runTranslationWorker() {
        _uiState.update { it.copy(isTranslating = true) }
        try {
            for ((chunkId, text) in translationChannel) {
                val translated = translateChunk(chunkId, text)
                if (translated.isNotBlank()) {
                    // Append to the conversation log immediately so the UI updates
                    _uiState.update { current ->
                        current.copy(
                            history = current.history + InterpreterEntry(
                                id         = chunkId,
                                original   = translated,
                                translated = text,
                            )
                        )
                    }
                    // Hand off to TTS; we don't block here — translation of next chunk
                    // starts right away while TTS speaks this one.
                    ttsChannel.send(translated)
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            // Normal: translationChannel was closed by the session join sequence.
        } catch (e: Exception) {
            Log.e(TAG, "Translation error", e)
            _uiState.update { it.copy(translationError = e.message) }
        } finally {
            _uiState.update { it.copy(isTranslating = false) }
        }
    }

    // ── Worker 3: Serial TTS playback ─────────────────────────────────────────

    /**
     * Speaks translated chunks one at a time in arrival order.
     *
     * Using [suspendCancellableCoroutine] to bridge TtsPlayer's callback API into
     * a suspend function means the loop naturally waits for each utterance to
     * finish before starting the next, without blocking [runTranslationWorker].
     */
    private suspend fun runTtsPlayback() {
        try {
            for (text in ttsChannel) {
                speakAndAwait(text)
            }
        } catch (e: ClosedReceiveChannelException) {
            // Normal exit.
        }
    }

    private suspend fun speakAndAwait(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        ttsPlayer.speak(
            text         = text,
            languageCode = _uiState.value.targetLanguage.code,
        ) {
            // TtsPlayer calls this lambda when the utterance is done
            if (cont.isActive) cont.resume(Unit)
        }
        cont.invokeOnCancellation { ttsPlayer.stop() }
    }

    // ── LiteRT helper ─────────────────────────────────────────────────────────

    private suspend fun translateChunk(chunkId: String, text: String): String =
        withContext(Dispatchers.IO) {
            val conversation = activeConversation
                ?: throw IllegalStateException("No active LiteRT conversation")
            val sb = StringBuilder()
            conversation
                .sendMessageAsync(text)
                .catch { e -> throw Exception("LiteRT error: ${e.message}") }
                .collect { token -> sb.append(token.toString()) }
            sb.toString().trim()
        }

    private fun buildSystemInstruction(): String {
        val src = _uiState.value.sourceLanguage.displayName
        val tgt = _uiState.value.targetLanguage.displayName
        return "You are a real-time spoken interpreter. " +
               "Translate every message strictly from $src to $tgt. " +
               "Output only the translated text — no explanations, no alternatives, no preamble."
    }

    // ── Language management ───────────────────────────────────────────────────

    fun openSourcePicker() {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(pickerTarget = PickerTarget.SOURCE) }
    }

    fun openTargetPicker() {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(pickerTarget = PickerTarget.TARGET) }
    }

    fun dismissPicker() {
        _uiState.update { it.copy(pickerTarget = null) }
    }

    fun selectLanguage(language: Language) {
        val target = _uiState.value.pickerTarget ?: return
        _uiState.update {
            when (target) {
                PickerTarget.SOURCE ->
                    if (language.code == it.targetLanguage.code)
                        it.copy(sourceLanguage = language, targetLanguage = it.sourceLanguage)
                    else
                        it.copy(sourceLanguage = language)
                PickerTarget.TARGET ->
                    if (language.code == it.sourceLanguage.code)
                        it.copy(targetLanguage = language, sourceLanguage = it.targetLanguage)
                    else
                        it.copy(targetLanguage = language)
            }.copy(pickerTarget = null)
        }
        if (target == PickerTarget.SOURCE) loadASRModel(_uiState.value.sourceLanguage.locale)
    }

    fun swapLanguages() {
        val current = _uiState.value
        _uiState.update {
            it.copy(sourceLanguage = current.targetLanguage, targetLanguage = current.sourceLanguage)
        }
        loadASRModel(current.targetLanguage.locale)
    }

    // ── Cleanup ────
    // ───────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        sessionJob?.cancel()
        translationChannel.close()
        ttsChannel.close()
        speechRecognizer?.close()
        ttsPlayer.shutdown()
        activeConversation?.close()
        activeConversation = null
    }
}
