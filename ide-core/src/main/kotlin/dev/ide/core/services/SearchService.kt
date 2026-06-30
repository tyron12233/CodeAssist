package dev.ide.core.services

import dev.ide.core.EngineContext
import dev.ide.index.IndexId
import dev.ide.index.MemberValue
import dev.ide.index.SymbolValue
import dev.ide.ui.backend.UiSearchOptions
import dev.ide.ui.backend.UiTextMatch
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.readText

/**
 * WORKSPACE-scoped engine service: go-to-symbol / member search over the index, plus full-text find-in-files.
 * Carved out of [dev.ide.core.IdeServices].
 *
 * The [indexService][dev.ide.index.IndexService] it queries stays shared infrastructure (completion/analysis/
 * preview read it too) and is reached through [EngineContext], as are `modules()`/`treeRoots()`/the live
 * editor overlay. Index *lifecycle* (the `reindex()` control + the `indexStatus` flow) stays on the engine
 * — it backs more than search — so this service holds only the read-side queries.
 */
internal class SearchService(private val ctx: EngineContext) {

    /** Go-to-symbol over project declarations (navigable). */
    fun searchSymbols(query: String, limit: Int = 50): List<SymbolValue> =
        ctx.indexService.fuzzy<SymbolValue>(IndexId("java.sourceSymbols"), query, limit)
            .map { it.value }.toList()

    /** Resolve a [SymbolValue.fileId] (interned, path stored once) back to its file path for navigation. */
    fun symbolFilePath(fileId: Int): String? = ctx.indexService.filePath(fileId)

    /** Member search across the classpath (informational). */
    fun searchMembers(query: String, limit: Int = 50): List<MemberValue> =
        ctx.indexService.fuzzy<MemberValue>(IndexId("java.members"), query, limit).map { it.value }
            .toList()

    /**
     * Full-text find-in-files over every surfaced workspace file (code, resources, assets). Reads the live
     * editor overlay when a file is open (so unsaved edits are searched), else disk. Skips binary/oversized
     * files. Returns up to [limit] matches.
     */
    fun findInFiles(query: String, options: UiSearchOptions, limit: Int): List<UiTextMatch> {
        if (query.isBlank()) return emptyList()
        val regex = buildSearchRegex(query, options) ?: return emptyList()
        val out = ArrayList<UiTextMatch>()
        val seen = HashSet<String>()
        for (module in ctx.modules()) {
            for (root in ctx.treeRoots(module)) {
                if (!Files.isDirectory(root)) continue
                val files = runCatching {
                    Files.walk(root).use { s ->
                        s.filter { Files.isRegularFile(it) }.collect(Collectors.toList())
                    }
                }.getOrDefault(emptyList())
                for (file in files) {
                    val abs = file.toAbsolutePath().normalize()
                    if (!seen.add(abs.toString())) continue
                    if (isLikelyBinary(abs)) continue
                    val text = ctx.overlayText(abs) ?: runCatching { file.readText() }.getOrNull()
                    ?: continue
                    if (text.length > MAX_SEARCH_FILE_CHARS || text.any { it.code == 0 }) continue
                    val name = file.fileName.toString()
                    var lineStart = 0
                    var lineNo = 1
                    var i = 0
                    val n = text.length
                    while (i <= n) {
                        if (i == n || text[i] == '\n') {
                            val line = text.substring(lineStart, i)
                            for (m in regex.findAll(line)) {
                                if (m.range.isEmpty()) continue
                                out += UiTextMatch(
                                    filePath = abs.toString(), fileName = name,
                                    line = lineNo, col = m.range.first + 1, lineText = line,
                                    matchStart = m.range.first, matchEnd = m.range.last + 1,
                                    offset = lineStart + m.range.first,
                                )
                                if (out.size >= limit) return out
                            }
                            lineStart = i + 1
                            lineNo++
                        }
                        i++
                    }
                }
            }
        }
        return out
    }

    private fun buildSearchRegex(query: String, options: UiSearchOptions): Regex? {
        val core = when {
            options.regex -> query
            options.wholeWord -> "\\b" + Regex.escape(query) + "\\b"
            else -> Regex.escape(query)
        }
        val opts = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return runCatching { Regex(core, opts) }.getOrNull()
    }

    private fun isLikelyBinary(path: Path): Boolean {
        val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
        return ext in BINARY_EXTENSIONS
    }

    private companion object {
        /** Cap on a single file's size for find-in-files (skip generated/huge blobs). */
        private const val MAX_SEARCH_FILE_CHARS = 2_000_000

        /** File extensions never scanned by find-in-files. */
        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg",
            "jar", "aar", "class", "dex", "zip", "apk", "so", "o", "a",
            "keystore", "ks", "jks", "ttf", "otf", "woff", "woff2", "bin", "pdf",
        )
    }
}
