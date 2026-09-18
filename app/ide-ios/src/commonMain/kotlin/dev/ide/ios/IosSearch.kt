package dev.ide.ios

import dev.ide.kotlin.syntax.KotlinOutline
import dev.ide.ui.backend.SymbolHit
import dev.ide.ui.backend.TextSearch
import dev.ide.ui.backend.UiSearchOptions
import dev.ide.ui.backend.UiTextMatch
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Search over the open project: full-text find-in-files, and go-to-symbol over its declarations.
 *
 * Both are a WALK, not an index, and that is the honest shape for this host rather than a shortcut. The other
 * hosts answer go-to-symbol from `SourceSymbolIndex` because they index a workspace anyway; here the only
 * index is over the classpath jars ([IosKotlinAnalysis]), which holds library names and knows nothing about
 * where a project declaration sits in a file. A project here is a folder on a phone, so walking it per query
 * costs about what reading it for find-in-files costs, which the Text tab already does.
 *
 * [overlayText] is what makes a search see the editor: a file open with unsaved edits must be searched as it
 * READS, not as it was last written, or the one file the user is looking at is the one the results are wrong
 * about.
 */
internal class IosSearch(
    private val projectRoot: String,
    private val overlayText: (String) -> String?,
) {

    /** Full-text search, up to [limit] matches, in the order the walk finds them. */
    suspend fun findInFiles(query: String, options: UiSearchOptions, limit: Int): List<UiTextMatch> {
        if (query.isBlank()) return emptyList()
        val regex = TextSearch.regexFor(query, options) ?: return emptyList()
        val out = ArrayList<UiTextMatch>()
        walk { path, name ->
            if (TextSearch.isLikelyBinary(name)) return@walk true
            val text = overlayText(path) ?: IosFiles.readText(path)
            if (!TextSearch.isSearchable(text)) return@walk true
            TextSearch.scanFile(path, name, text, regex, limit, out)
        }
        return out
    }

    /**
     * Declarations whose name matches [query], as navigable hits.
     *
     * Matching is a case-insensitive substring rather than the other hosts' fuzzy index query: fuzzy ranking
     * is a property of the index that answers it, and inventing a different ranking here would make the same
     * keystrokes order results differently per platform. A substring match is the subset every ranking agrees
     * on, so nothing it offers is surprising.
     */
    suspend fun searchSymbols(query: String, limit: Int): List<SymbolHit> {
        if (query.isBlank()) return emptyList()
        val out = ArrayList<SymbolHit>()
        walk { path, name ->
            if (!name.endsWith(".kt", ignoreCase = true)) return@walk true
            val text = overlayText(path) ?: IosFiles.readText(path)
            if (!TextSearch.isSearchable(text)) return@walk true
            for (symbol in runCatching { KotlinOutline.symbols(text) }.getOrDefault(emptyList())) {
                if (!symbol.name.contains(query, ignoreCase = true)) continue
                out += SymbolHit(
                    name = symbol.name,
                    // What the row shows under the name: the declaration's own detail (a signature) when it
                    // has one, else the file it is in, which is the next most useful thing for telling two
                    // same-named declarations apart.
                    detail = symbol.detail ?: name,
                    kind = symbol.kind,
                    filePath = path,
                    offset = symbol.nameOffset,
                )
                if (out.size >= limit) return@walk false
            }
            true
        }
        return out
    }

    /**
     * Every file under the project root, depth-first, passed to [visit] as (absolute path, file name).
     * [visit] returns false to stop the walk, which is how a limit is honored without reading the rest.
     *
     * Dot-entries are skipped, which keeps search agreeing with the file tree (`IosBackend.fileTree` hides
     * them too, so search finds what the project shows). It also matters more than tidiness here: `.platform`
     * holds this host's own caches, the resolved-dependency jars and the index segments, so descending into
     * it would search megabytes of machine-written state on every query and report hits inside a downloaded
     * library as if they were the user's own code.
     */
    private suspend fun walk(visit: suspend (path: String, name: String) -> Boolean) {
        suspend fun rec(dir: String): Boolean {
            for (name in IosFiles.list(dir).sorted()) {
                // The Search screen re-queries on every keystroke, so most walks are abandoned before they
                // finish. Nothing here suspends, so without this an abandoned search would still read the
                // whole project on the IO pool while the one the user is waiting for queues behind it.
                coroutineContext.ensureActive()
                if (name.startsWith(".")) continue
                val path = IosFiles.join(dir, name)
                val keepGoing = if (IosFiles.isDirectory(path)) rec(path) else visit(path, name)
                if (!keepGoing) return false
            }
            return true
        }
        if (IosFiles.isDirectory(projectRoot)) rec(projectRoot)
    }
}
