package dev.ide.ui.backend

/**
 * Find-in-files policy: what a query MEANS, which files are worth opening, and how a hit becomes a
 * [UiTextMatch].
 *
 * Only the file WALK differs between hosts (the JVM one crosses a Gradle module's content roots, the iOS one
 * a single project directory), so that is the part each caller supplies. Everything a user would notice if it
 * drifted (whether `.` is a wildcard, whether a `.png` is opened, what "whole word" means, which offset the
 * editor is asked to navigate to) lives here, in the module that already owns [UiSearchOptions] and
 * [UiTextMatch].
 *
 * `:ide-ios` is the only caller so far. `:ide-core`'s `SearchService.findInFiles` predates this and still
 * carries its own copy of the same rules; collapsing it onto this object is a small, separate change that
 * was not made here only because that module does not currently compile on this branch.
 */
object TextSearch {

    /** Cap on a single file's size; past this it is a generated blob, not something anyone searches. */
    const val MAX_FILE_CHARS: Int = 2_000_000

    /** Extensions never scanned. Cheaper than sniffing, and wrong only for a file lying about its name. */
    val BINARY_EXTENSIONS: Set<String> = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg",
        "jar", "aar", "class", "dex", "zip", "apk", "so", "o", "a",
        "keystore", "ks", "jks", "ttf", "otf", "woff", "woff2", "bin", "pdf",
    )

    /** Whether [fileName] names a file find-in-files should not open. */
    fun isLikelyBinary(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in BINARY_EXTENSIONS

    /**
     * The query as a [Regex], or null when the user typed a regex that does not compile.
     *
     * Null is a real answer, not an error: a half-typed pattern (`foo(`) is the ordinary state of a search
     * field, and the caller shows no results rather than an error the next keystroke will invalidate.
     */
    fun regexFor(query: String, options: UiSearchOptions): Regex? {
        val core = when {
            options.regex -> query
            options.wholeWord -> "\\b" + Regex.escape(query) + "\\b"
            else -> Regex.escape(query)
        }
        val flags = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return runCatching { Regex(core, flags) }.getOrNull()
    }

    /**
     * Whether [text] is worth scanning at all: within [MAX_FILE_CHARS] and not carrying a NUL, which is what
     * a binary file that slipped past [isLikelyBinary] looks like once it has been decoded as text.
     */
    fun isSearchable(text: String): Boolean =
        text.length <= MAX_FILE_CHARS && text.none { it.code == 0 }

    /**
     * Append every match of [regex] in [text] to [out], stopping at [limit].
     *
     * Returns false once [limit] is reached, so a caller walking many files knows to stop rather than reading
     * the rest of the project to throw it away. Scans line by line because a match is REPORTED by line: the
     * row shows the line it sits on with the hit range inside it, and the editor navigates to the absolute
     * offset. Empty matches are skipped, or a regex like `x*` would report one hit per character.
     */
    fun scanFile(
        filePath: String,
        fileName: String,
        text: String,
        regex: Regex,
        limit: Int,
        out: MutableList<UiTextMatch>,
    ): Boolean {
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
                        filePath = filePath,
                        fileName = fileName,
                        line = lineNo,
                        col = m.range.first + 1,
                        lineText = line,
                        matchStart = m.range.first,
                        matchEnd = m.range.last + 1,
                        offset = lineStart + m.range.first,
                    )
                    if (out.size >= limit) return false
                }
                lineStart = i + 1
                lineNo++
            }
            i++
        }
        return true
    }
}
