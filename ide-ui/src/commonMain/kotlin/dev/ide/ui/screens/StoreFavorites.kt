package dev.ide.ui.screens

import dev.ide.ui.backend.IdeBackend

/**
 * The set of store items the user has bookmarked, persisted as one app preference.
 *
 * Local on purpose, for now. The Supabase schema has a `store_favorites` table keyed to an account, but
 * favorites are useful before anyone signs in and a bookmark should not be the thing that forces a login.
 * When accounts land this becomes the local half of a sync: the same ids, reconciled against the server
 * set rather than replaced by it.
 *
 * Stored as a newline-separated list because item ids are slugs and cannot contain a newline; a comma
 * would be a guess about the id format.
 */
internal object StoreFavorites {
    private const val PREF = "store.favorites"

    fun all(backend: IdeBackend): Set<String> =
        backend.settings.preference(PREF)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    fun contains(backend: IdeBackend, id: String): Boolean = id in all(backend)

    /** Toggle [id] and return whether it is saved afterwards. */
    fun toggle(backend: IdeBackend, id: String): Boolean {
        val current = all(backend)
        val next = if (id in current) current - id else current + id
        backend.settings.setPreference(PREF, next.joinToString("\n"))
        return id in next
    }
}
