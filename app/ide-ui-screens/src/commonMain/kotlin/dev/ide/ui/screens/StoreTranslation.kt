package dev.ide.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.intl.Locale
import dev.ide.ui.backend.UiTranslation

/**
 * The language a Translate tap asks for: the reader's, as one of the tags the translate function offers.
 *
 * Mapped by hand because platform locales are not the tags the server takes: Android still reports
 * Indonesian as the retired code "in", Filipino as "tl", and Chinese with a region or a script rather than
 * as simplified or traditional. A language the server does not offer falls back to English, which is what
 * most of the store is written in anyway.
 */
internal fun translationTarget(locale: Locale = Locale.current): String {
    val language = when (val l = locale.language.lowercase()) {
        "in" -> "id"
        "tl" -> "fil"
        else -> l
    }
    val region = locale.region.uppercase()
    return when {
        language == "pt" && region == "BR" -> "pt-BR"
        language == "zh" && (locale.script.equals("Hant", ignoreCase = true) || region in setOf("TW", "HK", "MO")) -> "zh-TW"
        language in OFFERED -> language
        else -> "en"
    }
}

/**
 * Whether to offer Translate for [text] at all. A reader whose language is not English gets it on
 * everything, since the store is mostly English. An English reader gets it only on text that does not read
 * as English, so the button is not under every review saying something that cannot help.
 */
internal fun offerTranslation(text: String?, target: String): Boolean {
    if (text.isNullOrBlank()) return false
    return target != "en" || looksNonEnglish(text)
}

/**
 * One piece of text's translation: what was fetched, whether it is on screen, and whether a fetch is
 * running. Kept per piece so flipping back to the original and forward again costs nothing.
 */
@Stable
internal class TranslationState {
    var result by mutableStateOf<UiTranslation?>(null)
    var showing by mutableStateOf(false)
    var busy by mutableStateOf(false)

    /** The translated [field] when it is the one on screen, otherwise null (meaning: show the original). */
    fun shown(field: String): String? = if (showing) result?.fields?.get(field) else null

    /** The tap: show the original again, show what was already fetched, or fetch it with [fetch]. */
    suspend fun toggle(fetch: suspend () -> UiTranslation) {
        if (showing) { showing = false; return }
        if (result?.fields?.isNotEmpty() == true) { showing = true; return }
        busy = true
        val fetched = runCatching { fetch() }.getOrElse { UiTranslation(error = it.message) }
        busy = false
        result = fetched
        showing = fetched.fields.isNotEmpty()
    }

    val error: String? get() = result?.error?.takeIf { !showing }
}

/** Mirrors LANGUAGES in supabase/functions/translate/index.ts. A tag missing here is never asked for. */
private val OFFERED = setOf(
    "en", "es", "pt", "fr", "de", "it", "nl", "pl", "tr", "ru", "uk", "ar", "fa", "ur", "hi", "bn", "ta",
    "id", "ms", "fil", "vi", "th", "zh", "ja", "ko", "sw", "ha", "yo", "am",
)
