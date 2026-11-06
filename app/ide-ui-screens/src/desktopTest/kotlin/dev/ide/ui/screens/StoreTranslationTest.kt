package dev.ide.ui.screens

import androidx.compose.ui.text.intl.Locale
import dev.ide.ui.backend.UiTranslation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTranslationTest {

    @Test
    fun platformLocalesMapToTheTagsTheServerOffers() {
        assertEquals("es", translationTarget(Locale("es-MX")))
        assertEquals("pt-BR", translationTarget(Locale("pt-BR")))
        assertEquals("pt", translationTarget(Locale("pt-PT")))
        // Android still reports Indonesian under its retired code.
        assertEquals("id", translationTarget(Locale("in-ID")))
        assertEquals("zh", translationTarget(Locale("zh-CN")))
        assertEquals("zh-TW", translationTarget(Locale("zh-TW")))
        assertEquals("zh-TW", translationTarget(Locale("zh-Hant-HK")))
        // Not offered: English, which most of the store is written in.
        assertEquals("en", translationTarget(Locale("is-IS")))
    }

    @Test
    fun anEnglishReaderIsOnlyOfferedTranslationForTextThatIsNotEnglish() {
        assertFalse(offerTranslation("Thank you moon, for this excellent plugin! Works great.", "en"))
        assertTrue(offerTranslation("Un plugin simple que colorear parejas de Brakets del mismo color", "en"))
        // Everyone else is offered it on everything: the store is mostly English.
        assertTrue(offerTranslation("Thank you moon, for this excellent plugin!", "es"))
        assertFalse(offerTranslation(null, "es"))
    }

    @Test
    fun flippingBackAndForthFetchesOnce() = runBlocking {
        val state = TranslationState()
        var calls = 0
        val fetch: suspend () -> UiTranslation = { calls++; UiTranslation(mapOf("review" to "¿cómo uso el plugin?")) }

        state.toggle(fetch)
        assertEquals("¿cómo uso el plugin?", state.shown("review"))
        state.toggle(fetch)
        assertNull(state.shown("review"))
        state.toggle(fetch)
        assertEquals("¿cómo uso el plugin?", state.shown("review"))
        assertEquals(1, calls)
    }

    @Test
    fun aFailureIsShownAndTheNextTapTriesAgain() = runBlocking {
        val state = TranslationState()
        var calls = 0
        state.toggle { calls++; UiTranslation(error = "Translation is busy right now.") }
        assertFalse(state.showing)
        assertEquals("Translation is busy right now.", state.error)
        state.toggle { calls++; UiTranslation(mapOf("description" to "Hola")) }
        assertEquals("Hola", state.shown("description"))
        assertNull(state.error)
        assertEquals(2, calls)
    }
}
