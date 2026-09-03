package dev.ide.index

import dev.ide.platform.ServiceKey

/**
 * Symbol and member lookup over the workspace indexes: the slice of the engine's search service a plugin
 * can name. WORKSPACE-scoped.
 *
 * Find-in-files is deliberately absent. The engine's own method for it is phrased in the search-option and
 * text-match types the IDE's UI port owns, which are not plugin API; a plugin that wants to grep the
 * project can read files through the `Workspace` it already has. The index queries here are phrased in this
 * module's own [SymbolValue] and [MemberValue], so they promote unchanged.
 */
interface SymbolSearch {

    /** Declarations whose name matches [query], best match first, at most [limit] of them. */
    fun searchSymbols(query: String, limit: Int = 50): List<SymbolValue>

    /** Members (methods, fields, properties) whose name matches [query], at most [limit] of them. */
    fun searchMembers(query: String, limit: Int = 50): List<MemberValue>

    /** The absolute path behind a [SymbolValue.fileId], or null when the index no longer holds that file. */
    fun symbolFilePath(fileId: Int): String?
}

/** WORKSPACE-scoped [SymbolSearch] over the open project's indexes. */
val SYMBOL_SEARCH = ServiceKey<SymbolSearch>("platform.symbolSearch")
