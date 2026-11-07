package dev.ide.ui

import android.content.res.Resources
import java.util.Locale

internal actual fun repairEmptyDefaultLocale() {
    val current = Locale.getDefault()
    if (current.language.isNotEmpty()) return
    val system = Resources.getSystem().configuration.locales
    val fallback = (0 until system.size()).map { system[it] }.firstOrNull { it.language.isNotEmpty() } ?: Locale.ENGLISH
    android.util.Log.w("ui.locale", "default locale '${current.toLanguageTag()}' has no language; restoring ${fallback.toLanguageTag()}")
    Locale.setDefault(fallback)
}
