package dev.codeassist.ndk

/**
 * One candidate clang offered, in neutral terms.
 *
 * Kept free of the completion SPI so the parsing is testable as what it is — a string-to-data function — and
 * so the awkward parts of clang's chunk format live in one place rather than in the contributor.
 */
data class ClangCompletion(
    /** What gets typed and inserted: the `TypedText` chunk. */
    val name: String,
    /** A readable signature for the second line of the popup, or null when clang gave only a name. */
    val signature: String?,
    /** The return or declared type, from the `[#…#]` chunk. */
    val resultType: String?,
    /** True when this is a code pattern (`for`, `if`, a constructor skeleton) rather than a declaration. */
    val isPattern: Boolean,
    /** True when the candidate takes a parameter list, which is what distinguishes a call from a value. */
    val isCallable: Boolean,
    /**
     * libclang's cursor kind (`FieldDecl`, `CXXMethod`, `macrodefinition`, ...) when the answer came from the
     * resident daemon; null from the command line, whose format carries no kind.
     */
    val kind: String? = null,
)

/**
 * Reads the candidate list clang prints for `-code-completion-at`.
 *
 * The format is one line per candidate:
 *
 * ```
 * COMPLETION: size : [#size_type#]size()
 * COMPLETION: push_back : [#void#]push_back(<#const value_type &__x#>)
 * COMPLETION: Pattern : for(<#init#>; <#cond#>; <#inc#>) {<#body#>}
 * COMPLETION: value_type
 * ```
 *
 * The resident daemon prints the same lines with the cursor kind after the prefix,
 * `COMPLETION(CXXMethod): size : [#size_type#]size()`, which the command line never could.
 *
 * Four chunk markers carry the structure: `[#type#]` is the result type, `<#placeholder#>` a
 * parameter, `{#text#}` informative text the user does not type, and `(#…#)` an optional parameter group.
 * Everything else is literal. The markers have to be stripped rather than shown, because a popup row reading
 * `[#void#]push_back(<#const value_type &__x#>)` is worse than no detail at all.
 */
object ClangCompletions {

    private val LINE = Regex("""^COMPLETION(?:\((?<kind>[A-Za-z]+)\))?:\s+(?<name>[^:]+?)(?:\s+:\s+(?<chunks>.*))?$""")

    // Every `}` and `]` below is escaped even though a bare one is legal on the desktop JVM. **Android's
    // ICU regex engine rejects them**: an unescaped `}` throws PatternSyntaxException, and because these are
    // object properties it throws at class-init, so the whole parser dies and completion silently returns
    // nothing. A JVM unit test cannot catch it — the desktop engine is the more permissive of the two.

    /** `[#type#]` — the result type, which clang always puts first when there is one. */
    private val RESULT_TYPE = Regex("""\[#([^#]*)#\]""")

    /** The other three marker pairs, which carry text the user sees but does not type. */
    private val PLACEHOLDER = Regex("""<#([^#]*)#>""")
    private val INFORMATIVE = Regex("""\{#([^#]*)#\}""")
    private val OPTIONAL = Regex("""\(#([^#]*)#\)""")

    fun parse(output: String): List<ClangCompletion> {
        if (output.isBlank()) return emptyList()
        val seen = LinkedHashMap<String, ClangCompletion>()

        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("COMPLETION")) continue
            val match = LINE.matchEntire(line) ?: continue
            val name = match.groups["name"]!!.value.trim()
            if (name.isEmpty()) continue
            val chunks = match.groups["chunks"]?.value?.trim()

            val isPattern = name == "Pattern"
            val resultType = chunks?.let { RESULT_TYPE.find(it)?.groupValues?.get(1)?.trim() }?.takeIf { it.isNotEmpty() }
            val signature = chunks?.let { readable(it) }?.takeIf { it.isNotEmpty() && it != name }

            // A pattern's typed text is the literal word "Pattern", which is a label, not something to
            // insert. The first word of its body is what the user is actually typing.
            val insertName = if (isPattern) signature?.takeWhile { it.isLetterOrDigit() || it == '_' }.orEmpty()
            else name
            if (insertName.isEmpty()) continue

            // clang lists every overload separately, and a popup with `push_back` three times is noise. The
            // first is kept, which is the one clang ranked highest.
            seen.putIfAbsent(
                insertName,
                ClangCompletion(
                    name = insertName,
                    signature = signature,
                    resultType = resultType,
                    isPattern = isPattern,
                    isCallable = chunks?.contains('(') == true,
                    kind = match.groups["kind"]?.value,
                ),
            )
        }
        return seen.values.toList()
    }

    /**
     * Strip the chunk markers, keeping the text inside them: what a person would read as the signature.
     *
     * Repeated until nothing changes, because optional groups nest (`substr({#<#pos#>{#, <#n#>#}#})`): one
     * pass only unwraps the innermost, and the popup used to show `substr({#size_type pos = 0, ...`.
     */
    private fun readable(chunks: String): String {
        var s = RESULT_TYPE.replace(chunks, "")
        while (true) {
            var next = PLACEHOLDER.replace(s) { it.groupValues[1] }
            next = INFORMATIVE.replace(next) { it.groupValues[1] }
            next = OPTIONAL.replace(next) { it.groupValues[1] }
            if (next == s) break
            s = next
        }
        return s.trim()
    }
}
