package dev.codeassist.csharp.editor.diagnostics

/**
 * Helpers for filtering false missing-type errors from the fallback semantic check.
 *
 * The compiler may report only syntax errors while any source file has syntax errors.
 * The fallback check excludes those broken files, but this can make types declared in
 * them appear missing. [mentionsType] filters out errors referring to those types.
 */
private val typeDeclaration = Regex(
    """\b(?:class|struct|interface|enum|record)\s+([A-Za-z_]\w*)""",
)

private val quotedName = Regex("""'([^']+)'""")

/** Finds type names declared in [source]. This is a text-based check, not a C# parser. */
internal fun declaredTypeNames(source: String): Set<String> =
    typeDeclaration.findAll(source)
        .map { it.groupValues[1] }
        .toSet()

/**
 * Checks whether [message] refers to a type in [typeNames].
 *
 * Handles qualified and generic names, such as `'MyApp.Greeter'` and `'Box<int>'`.
 */
internal fun mentionsType(message: String, typeNames: Set<String>): Boolean {
    if (typeNames.isEmpty()) return false

    return quotedName.findAll(message).any { match ->
        val name = match.groupValues[1]
            .substringBefore('<')
            .substringAfterLast('.')

        name in typeNames
    }
}
