// AudioTranslationUiState.kt
// State for the audio-translation screen, extended with Whisper model-loading
// fields required by the new realtime pipeline.

package com.translator.ui.state

data class AudioTranslationUiState(
    // --- Language pair ---
    val sourceLanguage: Language = LanguageRepository.defaultSource,
    val targetLanguage: Language = LanguageRepository.defaultTarget,

    // --- Engine (LiteRT-LM) ---
    val isEngineReady: Boolean  = false,
    val engineError: String?    = null,

    // --- Whisper model ---
    val isWhisperReady: Boolean   = false,
    val isWhisperLoading: Boolean = false,
    val whisperError: String?     = null,

    // --- TTS ---
    val isTtsReady: Boolean = false,

    // --- Recording ---
    val recordingState: RecordingState = RecordingState.IDLE,
    val recordingError: String?        = null,

    // --- Transcript / translation output ---
    val sourceTranscript: String   = "",
    val translatedText: String     = "",
    val transcriptionError: String? = null,
    val translationError: String?   = null,

    // --- Playback ---
    val playbackState: PlaybackState = PlaybackState.IDLE
) {
    /** True when the user may press the record button. */
    val canRecord: Boolean
        get() = isEngineReady && isWhisperReady && !isBusy

    /** True while audio is being captured. */
    val canStopRecording: Boolean
        get() = recordingState == RecordingState.RECORDING

    /** True when any background work is in progress. */
    val isBusy: Boolean
        get() = recordingState != RecordingState.IDLE || playbackState == PlaybackState.PLAYING

    /** True when a live partial transcript is being built. */
    val isTranscribing: Boolean
        get() = recordingState == RecordingState.RECORDING ||
                recordingState == RecordingState.PROCESSING
}

// ---------------------------------------------------------------------------
// Supporting enums
// ---------------------------------------------------------------------------

enum class RecordingState { IDLE, RECORDING, PROCESSING }

enum class PlaybackState { IDLE, PLAYING }
