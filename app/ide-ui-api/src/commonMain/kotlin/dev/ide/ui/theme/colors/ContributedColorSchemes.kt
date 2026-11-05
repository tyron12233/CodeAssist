package dev.ide.ui.theme.colors

import dev.ide.ui.concurrent.UiLock
import dev.ide.ui.ext.Registration

/**
 * Whole color schemes that plugins ship, listed beside the built-in presets.
 *
 * They behave as presets do: read-only, offered in the picker, and edited by way of a copy (the screen keys
 * that off [EditorColorScheme.builtIn], which a contributed scheme carries). They are never written to the
 * preference store, so a plugin that is turned off or uninstalled takes its schemes with it; a user whose
 * active scheme was one of them falls back to the default, as for any id that no longer resolves, and a copy
 * they made of it is theirs and stays.
 *
 * Ids are namespaced by the contributing plugin ([idFor]), so two plugins cannot collide and a contributed
 * scheme can never shadow a preset or a user's own.
 */
object ContributedColorSchemes {
    private val schemes = ArrayList<EditorColorScheme>()
    private val lock = UiLock()

    /** Bumped on every registration and removal. */
    var version: Int = 0
        private set

    /** The id a plugin's scheme is listed under: `plugin.<pluginId>.<schemeId>`, both reduced to a slug. */
    fun idFor(pluginId: String, schemeId: String): String =
        "plugin." + pluginId.map { if (it.isLetterOrDigit() || it == '.' || it == '_') it else '-' }.joinToString("") +
            "." + ColorSchemeJson.slugOf(schemeId)

    /** Lists [scheme] (as read-only) until the handle is disposed. A second registration of an id replaces the first. */
    fun register(scheme: EditorColorScheme): Registration {
        val entry = scheme.copy(builtIn = true)
        lock.withLock {
            schemes.removeAll { it.id == entry.id }
            schemes.add(entry)
            version++
        }
        return Registration {
            lock.withLock { if (schemes.removeAll { it === entry }) version++ }
        }
    }

    val all: List<EditorColorScheme> get() = lock.withLock { schemes.toList() }

    fun byId(id: String): EditorColorScheme? = lock.withLock { schemes.firstOrNull { it.id == id } }
}
