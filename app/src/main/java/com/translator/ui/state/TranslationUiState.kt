package com.translator.ui.state

data class TranslationUiState(
    val llmState: LlmState = LlmState(), // Reused from above
    val sourceText: String = "",
    val sourceLanguage: Language = Language.defaultSource,
    val targetLanguage: Language = Language.defaultTarget,
    val maxChars: Int = 1000
) {
    val charCount: Int
        get() = sourceText.length

    val isOverLimit: Boolean
        get() = charCount > maxChars

    val canTranslate: Boolean
        get() = sourceText.isNotBlank() && llmState.isEngineReady && !llmState.isTranslating && !isOverLimit
}