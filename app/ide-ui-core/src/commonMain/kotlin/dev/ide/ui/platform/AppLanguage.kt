package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.key
import androidx.compose.ui.unit.LayoutDirection

/**
 * Runs [content] in the interface language [tag] (a BCP 47 tag such as "ar" or "pt-BR"), or in the device's
 * language when [tag] is null.
 *
 * The language is applied to the whole process, since string resources, plural rules and number formatting
 * all read the default locale, and the layout direction follows it. [content] is re-created when the language
 * changes, so every string in it is read again; state that should outlive a language change belongs above
 * this call.
 */
@Composable
fun AppLanguage(tag: String?, content: @Composable () -> Unit) {
    val locals = appLanguageLocals(tag)
    CompositionLocalProvider(*locals.toTypedArray()) {
        key(tag) { content() }
    }
}

/**
 * Makes [tag] the process language (null restores the device's) and returns what the composition needs
 * provided for it. Called on every composition of [AppLanguage], so it also re-applies the language after the
 * platform has reset it.
 */
@Composable
internal expect fun appLanguageLocals(tag: String?): List<ProvidedValue<*>>

/** The direction text in [language] (an ISO 639 code, in either its current or its legacy spelling) reads in. */
internal fun layoutDirectionOf(language: String): LayoutDirection =
    if (language.lowercase() in RTL_LANGUAGES) LayoutDirection.Rtl else LayoutDirection.Ltr

private val RTL_LANGUAGES = setOf("ar", "fa", "he", "iw", "ur", "yi", "ji", "ps", "sd", "ug", "dv")
