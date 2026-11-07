package dev.ide.ui

import java.util.Locale

internal actual fun repairEmptyDefaultLocale() {
    val current = Locale.getDefault()
    if (current.language.isNotEmpty()) return
    Locale.setDefault(Locale.ENGLISH)
}
