package com.translator.ui.state

object LanguageRepository {

    val languages: List<Language> = listOf(
        Language("en", "English", "English"),
        Language("tr", "Turkish", "Türkçe"),
        Language("es", "Spanish", "Español"),
        Language("fr", "French", "Français"),
        Language("de", "German", "Deutsch"),
        Language("it", "Italian", "Italiano"),
        Language("pt", "Portuguese", "Português"),
        Language("ru", "Russian", "Русский"),
        Language("zh", "Chinese", "中文"),
        Language("ja", "Japanese", "日本語"),
        Language("ko", "Korean", "한국어"),
        Language("ar", "Arabic", "العربية"),
        Language("hi", "Hindi", "हिन्दी"),
        Language("nl", "Dutch", "Nederlands"),
        Language("pl", "Polish", "Polski"),
        Language("sv", "Swedish", "Svenska"),
        Language("uk", "Ukrainian", "Українська") // ✅ removed comma
    )

    val defaultSource: Language = languages.first { it.code == "en" }
    val defaultTarget: Language = languages.first { it.code == "tr" }

    fun byCode(code: String): Language? = languages.find { it.code == code }
}