package com.translator.ui.state

data class AudioTranslationUiState(
    val asrState: AsrState = AsrState(),
    val llmState: LlmState = LlmState(),
    val ttsState: TtsState = TtsState(),

    val recordingState: RecordingState = RecordingState.IDLE,
    val playbackState: PlaybackState = PlaybackState.IDLE,

    val sourceLanguage: Language = Language.defaultSource,
    val targetLanguage: Language = Language.defaultTarget
) {
    val canRecord: Boolean
        get() = asrState.isReady && recordingState == RecordingState.IDLE

    val canStopRecording: Boolean
        get() = recordingState == RecordingState.RECORDING

    val showLiveCaption: Boolean
        get() = recordingState == RecordingState.RECORDING && asrState.liveCaption.isNotBlank()

    val isBusy: Boolean
        get() = recordingState != RecordingState.IDLE || llmState.isTranslating
}

// Grouped sub-states mapping directly to your pipeline:
data class AsrState(
    val isReady: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val liveCaption: String = "",
    val sourceTranscript: String = ""
)

data class LlmState(
    val isEngineReady: Boolean = false,
    val isTranslating: Boolean = false,
    val error: String? = null,
    val translatedText: String = ""
)

data class TtsState(
    val isReady: Boolean = false,
    val error: String? = null
)

enum class RecordingState { IDLE, RECORDING, PROCESSING }
enum class PlaybackState  { IDLE, PLAYING }