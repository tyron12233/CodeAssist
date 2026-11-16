package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.platform.LocalLayoutDirection
import java.util.Locale

/** The locale the JVM started with, read before any language is applied. */
private val systemLocale: Locale = Locale.getDefault()

@Composable
internal actual fun appLanguageLocals(tag: String?): List<ProvidedValue<*>> {
    val locale = tag?.let(Locale::forLanguageTag) ?: systemLocale
    if (Locale.getDefault() != locale) Locale.setDefault(locale)
    // Without a chosen language the window's own direction, taken from the system locale, already applies.
    if (tag == null) return emptyList()
    return listOf(LocalLayoutDirection provides layoutDirectionOf(locale.language))
}
