package com.translator.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.*
import com.translator.audio.TtsPlayer
import com.translator.ui.state.*
import com.whispercpp.whisper.WhisperContext
import com.whispercppdemo.recorder.Recorder
import com.whispercppdemo.media.decodeWaveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    private val recorder = Recorder()
    private val ttsPlayer = TtsPlayer(application)

    private var audioFile: File? = null

    // -------------------------------------------------------------------------
    // Engine / model init
    // -------------------------------------------------------------------------

    fun attachEngine(sharedEngine: Engine) {
        engine = sharedEngine
        _uiState.update { it.copy(isEngineReady = true, engineError = null) }
    }

    fun loadWhisperModel(modelPath: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isWhisperLoading = true, whisperError = null) }
            try {
                val ctx = withContext(Dispatchers.IO) {
                    WhisperContext.createContextFromFile(modelPath)
                }
                whisperContext = ctx
                _uiState.update { it.copy(isWhisperReady = true, isWhisperLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isWhisperLoading = false,
                        whisperError = "Failed to load Whisper model: ${e.message}"
                    )
                }
            }
        }
    }

    fun initTts() {
        ttsPlayer.init { ready ->
            _uiState.update { it.copy(isTtsReady = ready) }
        }
    }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    fun startRecording() {
        if (!_uiState.value.canRecord) return

        if (ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            _uiState.update { it.copy(recordingError = "Microphone permission not granted.") }
            return
        }

        val file = File(getApplication<Application>().cacheDir, "recording.wav")
        audioFile = file

        _uiState.update {
            it.copy(
                recordingState = RecordingState.RECORDING,
                recordingError = null,
                sourceTranscript = "",
                translatedText = "",
                transcriptionError = null,
                translationError = null
            )
        }

        viewModelScope.launch {
            recorder.startRecording(file) { e ->
                _uiState.update { it.copy(recordingError = e.message) }
            }
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            recorder.stopRecording()
            _uiState.update { it.copy(recordingState = RecordingState.PROCESSING) }
            processAudioFile()
        }
    }

    fun cancelRecording() {
        viewModelScope.launch {
            recorder.stopRecording()
            audioFile?.delete()
            _uiState.update { it.copy(recordingState = RecordingState.IDLE) }
        }
    }

    // -------------------------------------------------------------------------
    // Processing Pipeline
    // -------------------------------------------------------------------------

    private fun processAudioFile() {
        viewModelScope.launch {
            try {
                val file = audioFile ?: throw Exception("No audio file")

                val samples = decodeWaveFile(file)

                val transcript = withContext(Dispatchers.Default) {
                    whisperContext?.transcribeData(samples)
                        ?: throw Exception("Whisper not initialized")
                }.trim()

                _uiState.update { it.copy(sourceTranscript = transcript) }

                if (transcript.isBlank()) {
                    _uiState.update {
                        it.copy(
                            recordingState = RecordingState.IDLE,
                            transcriptionError = "No speech detected."
                        )
                    }
                    return@launch
                }

                streamTranslation(
                    text = transcript,
                    source = _uiState.value.sourceLanguage,
                    target = _uiState.value.targetLanguage
                )

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        recordingState = RecordingState.IDLE,
                        transcriptionError = e.message
                    )
                }
            } finally {
                audioFile?.delete()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Translation (LLM)
    // -------------------------------------------------------------------------

    private suspend fun streamTranslation(
        text: String,
        source: Language,
        target: Language
    ) = withContext(Dispatchers.IO) {

        val currentEngine = engine
            ?: throw IllegalStateException("Engine not ready")

        val config = ConversationConfig(
            systemInstruction = Contents.of(
                "Translate from ${source.displayName} to ${target.displayName}. Output only translation."
            ),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3)
        )

        val result = StringBuilder()

        currentEngine.createConversation(config).use { conversation ->
            conversation.sendMessageAsync(text)
                .catch { e ->
                    throw Exception("Translation error: ${e.message}")
                }
                .collect { token ->
                    val chunk = token.toString()
                    result.append(chunk)
                    _uiState.update { it.copy(translatedText = it.translatedText + chunk) }
                }
        }

        val finalText = result.toString().trim()

        _uiState.update {
            it.copy(
                translatedText = finalText,
                recordingState = RecordingState.IDLE
            )
        }

        if (finalText.isNotBlank()) {
            speakTranslation(finalText)
        }
    }

    // -------------------------------------------------------------------------
    // TTS
    // -------------------------------------------------------------------------

    fun speakTranslation(text: String = _uiState.value.translatedText) {
        if (text.isBlank() || !_uiState.value.isTtsReady) return

        _uiState.update { it.copy(playbackState = PlaybackState.PLAYING) }

        ttsPlayer.speak(
            text = text,
            languageCode = _uiState.value.targetLanguage.code
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
            it.copy(
                sourceLanguage = it.targetLanguage,
                targetLanguage = it.sourceLanguage
            )
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        ttsPlayer.shutdown()

        viewModelScope.launch {
            whisperContext?.release()
        }
    }
}