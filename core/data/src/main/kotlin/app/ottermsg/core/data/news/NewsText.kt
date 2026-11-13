package app.ottermsg.core.data.news

object NewsText {
    /** D69: Russian on a Russian phone, otherwise English; whichever exists as a fallback. */
    fun pick(ru: String, en: String, language: String): String =
        if (language == "ru") ru.ifBlank { en } else en.ifBlank { ru }

    /** The text of a stored news message (`system_data` = Announcement bytes), or null if it does not parse. */
    fun of(systemData: ByteArray?, language: String): String? = systemData?.let {
        runCatching { app.ottermsg.proto.v1.Announcement.parseFrom(it) }.getOrNull()
    }?.let { pick(it.textRu, it.textEn, language) }
}
