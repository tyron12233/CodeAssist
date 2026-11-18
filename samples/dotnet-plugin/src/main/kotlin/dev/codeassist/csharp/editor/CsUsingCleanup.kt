package dev.codeassist.csharp.editor


import java.util.concurrent.ConcurrentHashMap

/**
 * Removes unnecessary using directives and sorts the remaining ones.
 *
 * This is pure text processing and does not depend on the IDE or plugin API.
 *
 * CS8019 reports only "Unnecessary using directive" and a source position,
 * without identifying the namespace. Therefore, unused directives are matched
 * by their own trimmed text, such as `using System.Linq;`.
 */
internal object CsUsingCleanup {

    enum class Kind {
        PLAIN,
        STATIC,
        ALIAS,
    }

    /** A using directive at the beginning of a file. */
    class Directive(
        val text: String,
        val start: Int,
        val end: Int,
        val global: Boolean,
        val kind: Kind,
        val name: String,
    )

    /** A source edit replacing [length] characters at [offset] with [text]. */
    class Edit(
        val offset: Int,
        val length: Int,
        val text: String,
    )

    /** The using directives found at the beginning of a file. */
    class Head(
        val directives: List<Directive>,
        val onlyDirectives: Boolean,
    )
    /**
     * Matches using directives.
     *
     * Groups:
     * 1. global modifier
     * 2. static import type
     * 3. alias name
     * 4. namespace name
     */
    private val DIRECTIVE = Regex(
        """(global\s+)?using\s+(?:static\s+([\w.]+)|([A-Za-z_]\w*)\s*=\s*[^;]+|([A-Za-z_][\w.]*))\s*;.*"""
    )

    /**
     * Finds using directives at the beginning of [source].
     *
     * Only column-zero directives are recognized. [Head.onlyDirectives]
     * indicates whether the section contains anything besides directives
     * and blank lines.
     *
     * Comments and preprocessor directives prevent the entire section
     * from being reorganized.
     */
    fun head(source: String): Head {
        val found = ArrayList<Directive>()
        var onlyDirectives = true
        var inBlockComment = false
        var start = 0

        while (start < source.length) {
            val newlineAt = source.indexOf('\n', start)
            val end = if (newlineAt < 0) source.length else newlineAt + 1
            val text = source.substring(start, end).trim()

            when {
                inBlockComment -> {
                    onlyDirectives = false
                    if ("*/" in text) inBlockComment = false
                }

                text.isEmpty() -> Unit

                text.startsWith("//") || text.startsWith("#") -> {
                    onlyDirectives = false
                }

                text.startsWith("/*") -> {
                    onlyDirectives = false
                    if ("*/" !in text) inBlockComment = true
                }

                else -> {
                    // using var and using (...) are statements, not directives.
                    val match = (
                        if (source[start].isWhitespace()) null
                        else DIRECTIVE.matchEntire(text)
                    ) ?: return Head(found, onlyDirectives)

                    val kind = when {
                        match.groups[2] != null -> Kind.STATIC
                        match.groups[3] != null -> Kind.ALIAS
                        else -> Kind.PLAIN
                    }

                    val name = (
                        match.groups[2]
                            ?: match.groups[3]
                            ?: match.groups[4]
                        )!!.value

                    found += Directive(
                        text = text.substringBefore(';') + ";",
                        start = start,
                        end = end,
                        global = match.groups[1] != null,
                        kind = kind,
                        name = name,
                    )
                }
            }

            start = end
        }

        return Head(found, onlyDirectives)
    }

    /** Returns the directive on the line containing [offset], or null. */
    fun directiveAt(source: String, offset: Int): String? =
        head(source).directives
            .firstOrNull { offset in it.start until maxOf(it.end, it.start + 1) }
            ?.text

    /** Removes the first directive matching [text]. */
    fun remove(source: String, text: String): List<Edit> =
        head(source).directives
            .firstOrNull { it.text == text }
            ?.let {
                listOf(Edit(it.start, it.end - it.start, ""))
            }
            .orEmpty()

    /**
     * Removes unused directives and sorts the remaining ones.
     *
     * Ordering:
     * 1. Global directives before regular directives.
     * 2. Plain, static and alias directives.
     * 3. System namespaces before other namespaces.
     * 4. Alphabetical order within each group.
     *
     * Duplicate directives are kept only once.
     *
     * If the section contains only directives and blank lines, the entire
     * section is replaced with the organized result. Otherwise, only unused
     * directives are removed, preserving comments and preprocessor directives.
     */
    fun organize(source: String, unused: Set<String>): List<Edit> {
        val head = head(source)

        if (head.directives.isEmpty()) return emptyList()

        if (!head.onlyDirectives) {
            return head.directives
                .filter { it.text in unused }
                .map { Edit(it.start, it.end - it.start, "") }
        }

        val newline = if (source.contains("\r\n")) "\r\n" else "\n"

        val kept = head.directives
            .filter { it.text !in unused }
            .distinctBy { it.text }
            .sortedWith(
                compareBy<Directive>(
                    { if (it.global) 0 else 1 },
                    { it.kind.ordinal },
                    { systemFirst(it.name) },
                    { it.name },
                ),
            )

        val start = head.directives.first().start
        val end = head.directives.last().end
        val block = kept.joinToString("") { it.text + newline }

        if (source.substring(start, end) == block) return emptyList()

        return listOf(Edit(start, end - start, block))
    }

    private fun systemFirst(name: String): Int =
        if (name == "System" || name.startsWith("System.")) 0 else 1

    /** Stores unused directives by file path for later quick fixes. */
    class Unused {
        private val byFile = ConcurrentHashMap<String, Set<String>>()

        fun put(path: String, texts: Set<String>) {
            byFile[path] = texts
        }

        fun of(path: String): Set<String> =
            byFile[path].orEmpty()
    }
}
