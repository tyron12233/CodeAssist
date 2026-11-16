package dev.ide.ui.platform

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import java.util.Locale

/** Set once a language has been chosen, so going back to the device's language undoes what was applied. */
private var languageApplied = false

@Composable
internal actual fun appLanguageLocals(tag: String?): List<ProvidedValue<*>> {
    if (tag == null && !languageApplied) return emptyList()
    languageApplied = true
    val base = LocalConfiguration.current
    val context = LocalContext.current
    // The system resources keep the device's own locale whatever this process sets as its default.
    val locale = remember(tag, base) { tag?.let(Locale::forLanguageTag) ?: Resources.getSystem().configuration.locales[0] }
    val configuration = remember(locale, base) { Configuration(base).apply { setLocale(locale) } }
    if (Locale.getDefault() != locale) Locale.setDefault(locale)
    // A dialog or popup is its own view and reads the activity's resources, not the configuration provided
    // below, so the activity's resources carry the language too.
    val resources = context.resources
    if (resources.configuration.locales[0] != locale) {
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }
    return listOf(
        LocalConfiguration provides configuration,
        LocalLayoutDirection provides layoutDirectionOf(locale.language),
    )
}
