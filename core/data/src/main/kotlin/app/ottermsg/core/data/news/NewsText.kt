package app.ottermsg.core.data.news

object NewsText {
    /** D69: Russian on a Russian phone, otherwise English; whichever exists as a fallback. */
    fun pick(ru: String, en: String, language: String): String =
        if (language == "ru") ru.ifBlank { en } else en.ifBlank { ru }
}
