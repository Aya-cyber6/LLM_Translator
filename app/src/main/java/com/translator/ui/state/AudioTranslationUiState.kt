// AudioTranslationUiState.kt

package com.translator.ui.state

import java.util.Locale

data class AudioTranslationUiState(
    // ---- LiteRT-LM Engine ----
    val isEngineReady: Boolean = false,
    val engineError: String?   = null,

    // ---- ML Kit on-device model ----
    val modelStatus: ModelStatus  = ModelStatus.CHECKING,
    val modelError: String?       = null,
    /** Non-null while a download is in progress, e.g. "Downloading 12.3 MB…" */
    val downloadProgress: String? = null,

    // ---- TTS ----
    val isTtsReady: Boolean = false,

    // ---- Recording FSM ----
    val recordingState: RecordingState = RecordingState.IDLE,
    val recordingError: String?        = null,

    // ---- Live transcription ----
    /** Current partial from ML Kit — overwritten on every PartialTextResponse. */
    val liveCaption: String = "",
    /** Accumulated FinalTextResponse segments — the authoritative source text. */
    val sourceTranscript: String = "",

    // ---- Live translation ----
    /** True while the translation job is processing queued chunks. */
    val isTranslating: Boolean = false,
    /** Grows in real-time as the LLM streams tokens for each committed chunk. */
    val translatedText: String = "",

    val transcriptionError: String? = null,
    val translationError: String?   = null,

    // ---- Playback ----
    val playbackState: PlaybackState = PlaybackState.IDLE,

    // ---- Language pair ----
    val sourceLanguage: Language = Language.ENGLISH,
    val targetLanguage: Language = Language.FRENCH,
) {
    val canRecord: Boolean
        get() = isEngineReady &&
                modelStatus == ModelStatus.AVAILABLE &&
                recordingState == RecordingState.IDLE

    val canStopRecording: Boolean
        get() = recordingState == RecordingState.RECORDING

    val showLiveCaption: Boolean
        get() = recordingState == RecordingState.RECORDING && liveCaption.isNotBlank()

    val isBusy: Boolean
        get() = recordingState != RecordingState.IDLE || isTranslating
}

// ---------------------------------------------------------------------------
// Supporting enums
// ---------------------------------------------------------------------------

enum class RecordingState { IDLE, RECORDING, PROCESSING }
enum class PlaybackState  { IDLE, PLAYING }

/** Mirrors ML Kit's FeatureStatus, plus a CHECKING state for the initial probe. */
enum class ModelStatus { CHECKING, AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

