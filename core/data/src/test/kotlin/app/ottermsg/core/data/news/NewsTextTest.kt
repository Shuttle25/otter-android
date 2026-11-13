package app.ottermsg.core.data.news

import org.junit.Assert.assertEquals
import org.junit.Test

class NewsTextTest {
    @Test fun russianPhonePrefersRussian() = assertEquals("Привет", NewsText.pick("Привет", "Hello", "ru"))
    @Test fun russianPhoneFallsBackToEnglish() = assertEquals("Hello", NewsText.pick(" ", "Hello", "ru"))
    @Test fun otherPhonePrefersEnglish() = assertEquals("Hello", NewsText.pick("Привет", "Hello", "de"))
    @Test fun otherPhoneFallsBackToRussian() = assertEquals("Привет", NewsText.pick("Привет", "", "en"))
}
