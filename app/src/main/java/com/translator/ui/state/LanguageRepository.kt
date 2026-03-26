package com.translator.ui.state

import java.util.Locale

// 1. The Enum is the only Language definition you need
enum class Language(val displayName: String, val code: String, val locale: Locale) {
    ENGLISH   ("English",    "en", Locale.forLanguageTag("en-US")),
    FRENCH    ("French",     "fr", Locale.forLanguageTag("fr-FR")),
    SPANISH   ("Spanish",    "es", Locale.forLanguageTag("es-ES")),
    GERMAN    ("German",     "de", Locale.forLanguageTag("de-DE")),
    TURKISH   ("Turkish",    "tr", Locale.forLanguageTag("tr-TR")),
    JAPANESE  ("Japanese",   "ja", Locale.forLanguageTag("ja-JP")),
    CHINESE   ("Chinese",    "zh", Locale.forLanguageTag("cmn-Hans-CN")),
    ARABIC    ("Arabic",     "ar", Locale.forLanguageTag("ar-SA")),
    RUSSIAN   ("Russian",    "ru", Locale.forLanguageTag("ru-RU")),
    ITALIAN   ("Italian",    "it", Locale.forLanguageTag("it-IT")),
    PORTUGUESE("Portuguese", "pt", Locale.forLanguageTag("pt-BR")),
    KOREAN    ("Korean",     "ko", Locale.forLanguageTag("ko-KR"));

    companion object {
        fun byCode(code: String): Language? = entries.find { it.code == code }
    }
}

// 2. Fixed State Class
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
    val sourceLanguage: Language = Language.ENGLISH,
    val targetLanguage: Language = Language.TURKISH,

    // FIXED: Use Language.entries to get the list of enums, and added the missing comma
    val availableLanguages: List<Language> = Language.entries,

    // Character count
    val maxChars: Int = 1000
) {
    val charCount: Int get() = sourceText.length
    val isOverLimit: Boolean get() = charCount > maxChars
    val canTranslate: Boolean get() = sourceText.isNotBlank() && isEngineReady && !isTranslating && !isOverLimit
}