package dev.ide.ui.theme.colors

/**
 * Where a user's color schemes live.
 *
 * Deliberately on the flat app preference store rather than a new backend service: `preference`/
 * `setPreference` is the one storage seam every host already implements — `prefs.properties` on desktop and
 * Android, `NSUserDefaults` on iOS — so schemes work identically on all three with no per-host code. A
 * scheme is a few hundred bytes of JSON; this is the right size of thing for that store.
 *
 * The built-in presets are not stored at all. They are code, they are read-only, and a user "editing" one
 * duplicates it first ([duplicate]), which is also why deleting every user scheme can never leave the IDE
 * with nothing to render.
 */
class ColorSchemeStore(
    private val get: (String) -> String?,
    private val set: (String, String) -> Unit,
) {
    /** The scheme the editor is rendering with; the default preset when nothing is set or the id is stale. */
    fun activeId(): String {
        val id = get(ACTIVE_KEY)?.trim().orEmpty()
        return if (id.isNotEmpty() && byId(id) != null) id else BuiltInColorSchemes.DEFAULT_ID
    }

    fun setActiveId(id: String) = set(ACTIVE_KEY, id)

    /** The active scheme, resolved. Never null: a missing or unparseable id falls back to the default. */
    fun active(): EditorColorScheme = byId(activeId()) ?: BuiltInColorSchemes.DEFAULT

    /** The user's own schemes, in the order they were created. */
    fun userSchemes(): List<EditorColorScheme> = ids().mapNotNull { id ->
        get(defKey(id))?.takeIf { it.isNotBlank() }?.let { ColorSchemeJson.decode(it, fallbackId = id) }
    }

    /** Presets first, then the user's — the order the picker shows. */
    fun all(): List<EditorColorScheme> = BuiltInColorSchemes.all + userSchemes()

    fun byId(id: String): EditorColorScheme? =
        BuiltInColorSchemes.byId(id) ?: get(defKey(id))
            ?.takeIf { it.isNotBlank() }
            ?.let { ColorSchemeJson.decode(it, fallbackId = id) }

    /**
     * Write [scheme], adding it to the index if it is new. A built-in id is refused rather than shadowed:
     * the presets are the one thing a user can always get back to.
     */
    fun save(scheme: EditorColorScheme) {
        if (BuiltInColorSchemes.byId(scheme.id) != null) return
        set(defKey(scheme.id), ColorSchemeJson.encode(scheme, pretty = false))
        val ids = ids()
        if (scheme.id !in ids) set(IDS_KEY, (ids + scheme.id).joinToString(","))
    }

    /**
     * Forget a user scheme. The stored document is blanked as well as unindexed, because the preference
     * store can only be written to, not deleted from — leaving the JSON behind would resurrect the scheme
     * the moment an id was reused.
     */
    fun delete(id: String) {
        if (BuiltInColorSchemes.byId(id) != null) return
        set(defKey(id), "")
        set(IDS_KEY, ids().filter { it != id }.joinToString(","))
        if (activeId() == id) setActiveId(BuiltInColorSchemes.DEFAULT_ID)
    }

    /**
     * Copy [source] under a new name, save it, and return the copy — the path every edit of a preset takes.
     * The copy records what it came from so "Reset" has a target.
     */
    fun duplicate(source: EditorColorScheme, name: String): EditorColorScheme {
        val copy = source.copy(
            id = uniqueId(ColorSchemeJson.slugOf(name)),
            name = uniqueName(name),
            builtIn = false,
            basedOn = source.basedOn ?: source.id.takeIf { source.builtIn },
        )
        save(copy)
        return copy
    }

    /**
     * Take in an exported scheme. A collision renames rather than overwrites: an import is someone handing
     * you a file, and it must never be able to silently replace work already in the list.
     */
    fun import(json: String, fallbackName: String? = null): EditorColorScheme? {
        val parsed = ColorSchemeJson.decode(json, fallbackName = fallbackName) ?: return null
        val scheme = parsed.copy(id = uniqueId(parsed.id), name = uniqueName(parsed.name))
        save(scheme)
        return scheme
    }

    private fun uniqueId(base: String): String {
        val taken = (ids() + BuiltInColorSchemes.all.map { it.id }).toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    private fun uniqueName(base: String): String {
        val taken = all().map { it.name }.toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base ($n)" in taken) n++
        return "$base ($n)"
    }

    private fun ids(): List<String> =
        get(IDS_KEY)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun defKey(id: String) = "$DEF_PREFIX$id"

    private companion object {
        const val ACTIVE_KEY = "colorScheme.active"
        const val IDS_KEY = "colorScheme.ids"
        const val DEF_PREFIX = "colorScheme.def."
    }
}
