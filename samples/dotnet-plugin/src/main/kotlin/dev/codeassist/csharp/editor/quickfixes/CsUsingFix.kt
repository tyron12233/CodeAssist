package dev.codeassist.csharp.editor.quickfixes

import dev.codeassist.csharp.completion.CsSymbolTable

/**
 * Finds namespaces that can resolve missing names and calculates where their using directives should go.
 * This is pure text processing and can be tested without an IDE.
 *
 * Handles CS0246, CS0103, and CS1061 when its message indicates a missing extension method.
 */
internal object CsUsingFix {

    val CODES = setOf("CS0246", "CS0103", "CS1061")

    private const val LINQ = "System.Linq"
    private const val MAX_STATIC = 5

    private val EXTENSION_RECEIVER = Regex("""first argument of type '([^']+)'""")
    private val EXTENSION_MESSAGE =
        Regex("""does not contain a definition for '(\w+)' and no accessible extension""")

    private val SEQUENCE_LIKE = Regex(
        """^(?:string|[\w.]*\b(?:IEnumerable|IQueryable|IList|ICollection|List|HashSet|ISet|SortedSet|Queue|Stack|""" +
            """LinkedList|Dictionary|IDictionary|IReadOnly\w+|ReadOnly\w+|Array|Span|ReadOnlySpan)\b.*|.*\[])$"""
    )

    private val QUOTED = Regex("""'([^']+)'""")
    private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")
    private val PLAIN_USING = Regex("""using\s+([A-Za-z_][\w.]*)\s*;.*""")
    private val OTHER_USING = Regex("""(?:global\s+)?using\s+(?!var\b|\()[^;]*;.*""")
    private val STATIC_USING = Regex("""(?m)^using\s+static\s+([\w.]+)\s*;""")

    private val STATIC_KINDS = setOf("method", "field", "property")

    /** A line to add: [text] goes in at [offset], replacing nothing. */
    class Insertion(val offset: Int, val text: String)

    /**
     * The name a CS0246 / CS0103 message is about: its first quoted part, without generic arguments.
     * Returns null when it is not a plain identifier.
     */
    fun missingName(message: String): String? {
        val quoted = QUOTED.find(message)?.groupValues?.get(1) ?: return null
        val name = quoted.substringBefore('<')
        return name.takeIf { IDENTIFIER.matches(it) }
    }

    /**
     * The type a CS1061 message says the method was called on, or null when it names none.
     */
    fun extensionReceiver(message: String): String? =
        EXTENSION_RECEIVER.find(message)?.groupValues?.get(1)

    /**
     * The method a CS1061 message wants as an extension method, or null for other CS1061 messages.
     */
    fun extensionName(message: String): String? =
        EXTENSION_MESSAGE.find(message)?.groupValues?.get(1)

    /**
     * Finds namespaces declaring a static method called [name], excluding namespaces already imported.
     *
     * The symbol table does not expose the extension attribute or parameter types, so these candidates
     * are not guaranteed to contain extension methods for [receiver]. For sequence-like receivers,
     * LINQ is preferred when it is among the candidates.
     */
    fun extensionCandidates(
        name: String,
        table: CsSymbolTable,
        source: String,
        receiver: String? = null,
    ): List<String> {
        val imported = scan(source).usings.mapNotNull { it.name }.toSet()
        val staticTypes = table.types
            .filter { it.isStatic && it.namespace != "-" }
            .associateBy { it.fullName }

        val found = table.members
            .filter { it.kind == "method" && it.isStatic && it.paramCount >= 1 && it.name == name }
            .mapNotNull { staticTypes[it.typeFullName]?.namespace }
            .filter { it !in imported }
            .distinct()
            .sortedBy { orderKey(it) }

        val sequence = receiver != null && SEQUENCE_LIKE.matches(receiver)
        return if (sequence && LINQ in found) listOf(LINQ) else found
    }

    /**
     * Finds namespaces containing public types called [name], excluding namespaces already imported.
     * Types without a namespace are not offered.
     */
    fun candidates(name: String, table: CsSymbolTable, source: String): List<String> {
        val imported = scan(source).usings.mapNotNull { it.name }.toSet()
        return table.types
            .filter { it.name == name && it.namespace != "-" && it.namespace !in imported }
            .map { it.namespace }
            .distinct()
            .sortedBy { orderKey(it) }
    }

    /**
     * Finds static classes containing a static member called [name], for a `using static` fix.
     * Nested and generic types are excluded. At most [MAX_STATIC] candidates are returned.
     */
    fun staticCandidates(name: String, table: CsSymbolTable, source: String): List<String> {
        if (table.types.any { it.name == name && it.namespace != "-" }) return emptyList()

        val imported = STATIC_USING.findAll(source).map { it.groupValues[1] }.toSet()
        val staticTypes = table.types
            .filter { it.isStatic && it.namespace != "-" && '+' !in it.fullName && '`' !in it.fullName }
            .associateBy { it.fullName }

        return table.members
            .filter { it.isStatic && it.name == name && it.kind in STATIC_KINDS }
            .mapNotNull { staticTypes[it.typeFullName]?.fullName }
            .filter { it !in imported }
            .distinct()
            .sortedBy { orderKey(it) }
            .take(MAX_STATIC)
    }

    /** The text of a `using static` line for [type]. */
    fun staticLine(type: String): String = "using static $type;"

    /** Places a `using static` line after the last using directive. */
    fun staticInsertion(source: String, type: String): Insertion {
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val line = staticLine(type)
        val scanned = scan(source)

        scanned.usings.lastOrNull()?.let { last ->
            return if (last.end < source.length) {
                Insertion(last.end, line + newline)
            } else {
                Insertion(source.length, newline + line)
            }
        }

        val first = scanned.firstDeclaration ?: return Insertion(source.length, line + newline)
        return Insertion(first, line + newline + newline)
    }

    /** True when [source] already has a plain `using [namespace];` at the head of the file. */
    fun imports(source: String, namespace: String): Boolean =
        scan(source).usings.any { it.name == namespace }

    /** Finds where a `using [namespace];` line should be inserted. */
    fun insertion(source: String, namespace: String): Insertion {
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val line = "using $namespace;"
        val scanned = scan(source)

        val plain = scanned.usings.filter { it.name != null }
        val key = orderKey(namespace)

        // Insert before the first plain using that sorts after this namespace.
        plain.firstOrNull { orderKey(it.name!!) > key }?.let {
            return Insertion(it.start, line + newline)
        }

        // Otherwise, insert after the last using directive.
        scanned.usings.lastOrNull()?.let { last ->
            return if (last.end < source.length) {
                Insertion(last.end, line + newline)
            } else {
                Insertion(source.length, newline + line)
            }
        }

        // No usings: insert before the first declaration.
        val first = scanned.firstDeclaration ?: return Insertion(source.length, line + newline)
        return Insertion(first, line + newline + newline)
    }

    /** `System` names first, then the rest; each ordered by ordinal comparison. */
    private fun orderKey(name: String): String =
        (if (name == "System" || name.startsWith("System.")) "0" else "1") + name

    private class Using(val name: String?, val start: Int, val end: Int)

    private class Scanned(val usings: List<Using>, val firstDeclaration: Int?)

    /**
     * Finds top-level using directives at the head of [source] and the first declaration.
     * The end offset includes the line's newline, or is the source length on the last line.
     */
    private fun scan(source: String): Scanned {
        val usings = ArrayList<Using>()
        var inBlockComment = false
        var start = 0

        while (start < source.length) {
            val newlineAt = source.indexOf('\n', start)
            val end = if (newlineAt < 0) source.length else newlineAt + 1
            val text = source.substring(start, end).trim()

            when {
                inBlockComment -> if ("*/" in text) inBlockComment = false
                text.isEmpty() || text.startsWith("//") || text.startsWith("#") -> Unit
                text.startsWith("/*") -> if ("*/" !in text) inBlockComment = true
                else -> {
                    val atColumnZero = !source[start].isWhitespace()
                    val plain = PLAIN_USING.matchEntire(text)

                    when {
                        atColumnZero && plain != null ->
                            usings += Using(plain.groupValues[1], start, end)

                        atColumnZero && OTHER_USING.matchEntire(text) != null ->
                            usings += Using(null, start, end)

                        else -> return Scanned(usings, start)
                    }
                }
            }

            start = end
        }

        return Scanned(usings, null)
    }
}
