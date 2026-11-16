package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.platform.LocalLayoutDirection
import platform.Foundation.NSUserDefaults

/** The preference the system resolves an app's languages from; overriding it is how an app picks its own. */
private const val APPLE_LANGUAGES = "AppleLanguages"

@Composable
internal actual fun appLanguageLocals(tag: String?): List<ProvidedValue<*>> {
    val defaults = NSUserDefaults.standardUserDefaults
    if (tag == null) {
        // Removing the app's own value lets the system's preferred languages show through again.
        defaults.removeObjectForKey(APPLE_LANGUAGES)
        return emptyList()
    }
    defaults.setObject(listOf(tag), APPLE_LANGUAGES)
    return listOf(LocalLayoutDirection provides layoutDirectionOf(tag.substringBefore('-')))
}
