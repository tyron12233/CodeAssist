package dev.ide.ui.theme.colors

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.ide.ui.concurrent.UiLock
import dev.ide.ui.ext.Registration

/**
 * A whole app theme a plugin ships: the Material color roles for dark and light, by their Material names
 * (`primary`, `onSurface`, `surfaceContainerHigh`, …), plus an optional [seed] the roles it leaves out are
 * generated from.
 *
 * Kept as plain role maps rather than a Material `ColorScheme` because this module has no Material
 * dependency; `ide-ui-core` turns one into the scheme the app renders with. A role it does not recognize is
 * ignored there, so a theme made for a newer Material with roles this build lacks still applies.
 */
@Immutable
data class ContributedAppTheme(
    val id: String,
    val name: String,
    val seed: Color?,
    val dark: Map<String, Color>,
    val light: Map<String, Color>,
) {
    fun roles(isDark: Boolean): Map<String, Color> = if (isDark) dark else light
}

/**
 * Reads an app theme document. The shape is Material Theme Builder's export (`material-theme.json`), so a
 * theme designed there ships as exported:
 *
 * ```
 * { "seed": "#6750A4", "schemes": { "light": { "primary": "#…", … }, "dark": { … } } }
 * ```
 *
 * The builder's contrast variants (`light-medium-contrast`, …) are ignored; `light` and `dark` are what apply.
 * Also accepted, for a theme written by hand: `light`/`dark` at the top level, and a document holding only a
 * `seed`. Null for anything that names neither a seed nor a single readable role.
 */
object AppThemeJson {
    fun decode(text: String, id: String, name: String): ContributedAppTheme? {
        val root = runCatching { JsonReader(text).readValue() }.getOrNull() as? Map<*, *> ?: return null
        val schemes = root["schemes"] as? Map<*, *> ?: root
        val seed = (root["seed"] as? String)?.let(::hexToColor)
        val dark = roles(schemes["dark"])
        val light = roles(schemes["light"])
        if (seed == null && dark.isEmpty() && light.isEmpty()) return null
        val title = name.ifBlank { (root["name"] as? String)?.trim().orEmpty() }.ifBlank { id }
        return ContributedAppTheme(id, title, seed, dark, light)
    }

    private fun roles(node: Any?): Map<String, Color> {
        val map = node as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Color>()
        for ((key, value) in map) {
            val role = key as? String ?: continue
            val color = (value as? String)?.let(::hexToColor) ?: continue
            out[role] = color
        }
        return out
    }
}

/**
 * The app themes plugins ship, listed after the Accent setting's own choices.
 *
 * Nothing here is stored: the user's choice is the accent setting naming a theme by id, and a theme whose
 * plugin is turned off or uninstalled simply stops resolving, which the app treats as the default accent. Ids
 * are namespaced by plugin ([idFor]) so two plugins cannot collide.
 */
object ContributedAppThemes {
    private val themes = ArrayList<ContributedAppTheme>()
    private val lock = UiLock()

    /** Bumped on every registration and removal. */
    var version: Int = 0
        private set

    fun idFor(pluginId: String, themeId: String): String =
        pluginId.map { if (it.isLetterOrDigit() || it == '.' || it == '_') it else '-' }.joinToString("") +
            "." + ColorSchemeJson.slugOf(themeId)

    /** Lists [theme] until the handle is disposed. A second registration of an id replaces the first. */
    fun register(theme: ContributedAppTheme): Registration {
        lock.withLock {
            themes.removeAll { it.id == theme.id }
            themes.add(theme)
            version++
        }
        return Registration { lock.withLock { if (themes.removeAll { it === theme }) version++ } }
    }

    val all: List<ContributedAppTheme> get() = lock.withLock { themes.toList() }

    fun byId(id: String): ContributedAppTheme? = lock.withLock { themes.firstOrNull { it.id == id } }
}
