package dev.ide.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.intl.Locale
import org.jetbrains.compose.resources.PluralStringResource

/**
 * Compose Resources' `pluralStringResource`, minus its crash when the process default locale has no language.
 *
 * Compose picks the plural rules for `Locale.current`'s language, and for a language it has no rules for (the
 * empty one of `Locale.ROOT` above all) it uses an empty rule list whose lookup throws `NoSuchElementException`
 * mid-composition. Something in the process set such a default on a 3.41.0 device (an earlier cause, KSP
 * resetting the default locale, was fixed in 3.34.0), and the chat crashed on its tool-run header. Before
 * resolving, an empty default is put back to the system's language (see [repairEmptyDefaultLocale]).
 *
 * Same name and parameters as the library's, so a call site switches by its import alone.
 */
@Composable
fun pluralStringResource(resource: PluralStringResource, quantity: Int): String {
    if (Locale.current.language.isEmpty()) repairEmptyDefaultLocale()
    return org.jetbrains.compose.resources.pluralStringResource(resource, quantity)
}

/** The formatting overload of [pluralStringResource]; same guard. */
@Composable
fun pluralStringResource(resource: PluralStringResource, quantity: Int, vararg formatArgs: Any): String {
    if (Locale.current.language.isEmpty()) repairEmptyDefaultLocale()
    return org.jetbrains.compose.resources.pluralStringResource(resource, quantity, *formatArgs)
}

/**
 * When the process default locale has an empty language, set it back to the first system locale that has one
 * (English if none does). Compose reads `Locale.current` from that default, so the next read sees the repair.
 */
internal expect fun repairEmptyDefaultLocale()
