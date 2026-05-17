package com.translator.ui.state

/**
 * One interpreted exchange: what was heard → what was spoken.
 * Kept in a list so the UI can show a scrolling conversation log.
 */
data class InterpreterEntry(
    val id: String,
    val original: String,       // raw ASR text in source language
    val translated: String,     // LiteRT output in target language
)

/**
 * UI state for Interpreter Mode.
 *
 * Key differences from [AudioTranslationUiState]:
 *  - [isListening] replaces the RecordingState enum — the session is either on or off.
 *  - [history] is a list of entries rather than concatenated strings, enabling a
 *    "conversation log" layout.
 *  - No PlaybackState or ActiveSpeaker — TTS is fire-and-queue internally.
 */
data class InterpreterUiState(

    // ── Session ───────────────────────────────────────────────────────────────
    val isListening: Boolean = false,
    /** Partial ASR text currently being streamed from ML Kit. */
    val liveCaption: String = "",
    /** Committed interpret pairs, in chronological order. */
    val history: List<InterpreterEntry> = emptyList(),

    // ── Languages ─────────────────────────────────────────────────────────────
    val sourceLanguage: Language = Language.ENGLISH,
    val targetLanguage: Language = Language.SPANISH,

    // ── Model / engine readiness ──────────────────────────────────────────────
    val modelStatus: ModelStatus = ModelStatus.CHECKING,
    val modelError: String? = null,
    val downloadProgress: String? = null,
    val isEngineReady: Boolean = false,
    val engineError: String? = null,
    val isTtsReady: Boolean = false,

    // ── In-flight work ────────────────────────────────────────────────────────
    /** True while at least one LiteRT translation is in-flight. */
    val isTranslating: Boolean = false,

    // ── Errors ────────────────────────────────────────────────────────────────
    val translationError: String? = null,

    // ── Language picker ───────────────────────────────────────────────────────
    val pickerTarget: PickerTarget? = null,

) {
    /** All prerequisites met → user can press Start. */
    val canStart: Boolean
        get() = !isListening
                && modelStatus == ModelStatus.AVAILABLE
                && isEngineReady
                && isTtsReady

    /** Session is live → user can press Stop. */
    val canStop: Boolean get() = isListening

    /** Language pickers and swaps are locked while a session is live. */
    val isBusy: Boolean get() = isListening

    val isPickerOpen: Boolean get() = pickerTarget != null
    val showLiveCaption: Boolean get() = liveCaption.isNotBlank()
}
enum class ModelStatus { CHECKING, AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

enum class PickerTarget { SOURCE, TARGET }
