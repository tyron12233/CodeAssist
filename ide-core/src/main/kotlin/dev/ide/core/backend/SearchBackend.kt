package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.SearchService
import dev.ide.ui.backend.SymbolHit
import dev.ide.ui.backend.UiSearchOptions
import dev.ide.ui.backend.UiTextMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** [SearchService] over the engine's index: live status + go-to-symbol / member / full-text search. */
internal class SearchBackend(private val ctx: BackendContext) : SearchService {
    override val indexStatus: StateFlow<IndexUiStatus> =
        ctx.engineFlow(IndexUiStatus()) { it.indexStatus }

    override fun reindex() = ctx.services.reindex()

    override suspend fun searchSymbols(query: String, limit: Int): List<SymbolHit> =
        ctx.services.searchSymbols(query, limit).map {
            SymbolHit(
                it.name,
                it.container ?: it.kind,
                it.kind,
                ctx.services.symbolFilePath(it.fileId),
                it.offset
            )
        }

    override suspend fun searchMembers(query: String, limit: Int): List<SymbolHit> =
        ctx.services.searchMembers(query, limit).map {
            SymbolHit(it.name, it.owner.substringAfterLast('.'), it.kind, null, null)
        }

    // Walks/reads files — keep off the caller's (possibly UI) dispatcher.
    override suspend fun findInFiles(
        query: String,
        options: UiSearchOptions,
        limit: Int
    ): List<UiTextMatch> =
        withContext(Dispatchers.IO) { ctx.services.findInFiles(query, options, limit) }
}
