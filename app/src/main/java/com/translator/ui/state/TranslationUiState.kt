package com.translator.ui.state

data class Language(
    val code: String,
    val displayName: String,
    val nativeName: String
)

data class TranslationUiState(
    // Engine
    val isEngineReady: Boolean = false,
    val engineError: String? = null,

    // Text-to-text
    val sourceText: String = "",
    val translatedText: String = "",
    val isTranslating: Boolean = false,
    val translationError: String? = null,

    // Language selection
    val sourceLanguage: Language = LanguageRepository.defaultSource,
    val targetLanguage: Language = LanguageRepository.defaultTarget,
    val availableLanguages: List<Language> = LanguageRepository.languages,

    // Character count
    val maxChars: Int = 1000
) {
    val charCount: Int get() = sourceText.length
    val isOverLimit: Boolean get() = charCount > maxChars
    val canTranslate: Boolean get() = sourceText.isNotBlank() && isEngineReady && !isTranslating && !isOverLimit
}
