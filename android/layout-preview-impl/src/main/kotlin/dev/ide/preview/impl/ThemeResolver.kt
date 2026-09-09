package dev.ide.preview.impl

import dev.ide.android.support.resources.ResourceRepository
import dev.ide.preview.PreviewResources
import dev.ide.preview.ResolvedValue
import dev.ide.preview.ValueFormat

/**
 * Resolves theme attributes by walking a `<style>`'s parent chain — explicit `parent="…"` first, then the
 * implicit dotted parent (`Theme.App.NoActionBar` → `Theme.App` → …) — stopping at a framework parent
 * (`android:…`, `Theme.Material*`, `Theme.AppCompat*`, `Theme.MaterialComponents*`) we don't model. A found
 * raw value is then resolved through [PreviewResources] (so `@color/primary`/`#hex` collapse to an ARGB).
 * Enough to drive the preview's system chrome (app-bar colour, status-bar colour, action-bar on/off).
 */
class ThemeResolver(private val repo: ResourceRepository, private val res: PreviewResources) {

    /** The raw value of [attr] (any of its [aliases]) following [themeName]'s parent chain, or null. */
    fun rawAttr(themeName: String, vararg aliases: String): String? {
        var current: String? = themeName
        var guard = 0
        val seen = HashSet<String>()
        while (current != null && guard++ < 32 && seen.add(current)) {
            val style = repo.style(current) ?: if (isFramework(current)) return null else { current = implicitParent(current); continue }
            for (a in aliases) style.items[a]?.let { return it }
            current = style.parent ?: implicitParent(current)
        }
        return null
    }

    /** Resolve a theme colour attribute to ARGB, or null. */
    fun color(themeName: String, vararg aliases: String): Int? =
        rawAttr(themeName, *aliases)?.let { (res.resolve(it, ValueFormat.COLOR) as? ResolvedValue.Color)?.argb }

    fun flag(themeName: String, vararg aliases: String): Boolean? =
        rawAttr(themeName, *aliases)?.let { it.equals("true", ignoreCase = true) }

    /** Whether the theme (or any ancestor name) opts out of the action bar. */
    fun hasNoActionBar(themeName: String): Boolean {
        var current: String? = themeName
        var guard = 0
        val seen = HashSet<String>()
        while (current != null && guard++ < 32 && seen.add(current)) {
            if (current.contains("NoActionBar") || current.contains("Light.NoTitleBar")) return true
            val style = repo.style(current)
            current = style?.parent ?: implicitParent(current)
        }
        return false
    }

    private fun implicitParent(name: String): String? = if ('.' in name) name.substringBeforeLast('.') else null

    private fun isFramework(name: String): Boolean = name.startsWith("android:") ||
        name.startsWith("Theme.Material") || name.startsWith("Theme.AppCompat") ||
        name.startsWith("Theme.MaterialComponents") || name.startsWith("Platform.") || name == "Theme"
}
