package com.translator.ui.state

enum class Language(val code: String, val displayName: String, val nativeName: String) {
    EN("en", "English", "English"),
    TR("tr", "Turkish", "Türkçe"),
    ES("es", "Spanish", "Español"),
    FR("fr", "French", "Français"),
    DE("de", "German", "Deutsch"),
    IT("it", "Italian", "Italiano"),
    PT("pt", "Portuguese", "Português"),
    RU("ru", "Russian", "Русский"),
    ZH("zh", "Chinese", "中文"),
    JA("ja", "Japanese", "日本語"),
    KO("ko", "Korean", "한국어"),
    AR("ar", "Arabic", "العربية"),
    HI("hi", "Hindi", "हिन्दी"),
    NL("nl", "Dutch", "Nederlands"),
    PL("pl", "Polish", "Polski"),
    SV("sv", "Swedish", "Svenska"),
    UK("uk", "Ukrainian", "Українська");

    companion object {
        val defaultSource = EN
        val defaultTarget = TR
        fun byCode(code: String): Language? = entries.find { it.code == code }
    }
}