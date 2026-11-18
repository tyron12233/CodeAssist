package dev.codeassist.csharp.completion

/**
 * Reads what the server prints for the navigation requests (see `CsNavigator`): tab-separated lines, one
 * record each. A line of another shape is skipped, so a diagnostic the server printed is not taken for data.
 */
internal object CsNavigationLines {

    /** One source location of a symbol: the file, the offset of its name, and its display text. */
    data class Definition(val path: String, val offset: Int, val label: String)

    /** What a symbol is. [kind] is the server's word for it (`class`, `method`, `localVariable`, ...). */
    data class Hover(
        val kind: String,
        val name: String,
        val container: String,
        val signature: String,
        /** The doc comment's `summary` and `param` elements as XML, or empty. */
        val documentation: String,
    )

    /** One name to colour. [modifiers] are the server's words (`declaration`, `static`, ...). */
    data class Token(val start: Int, val length: Int, val kind: String, val modifiers: List<String>)

    fun definitions(lines: List<String>): List<Definition> = lines.mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 4 || parts[0] != "def") return@mapNotNull null
        val offset = parts[2].toIntOrNull() ?: return@mapNotNull null
        Definition(parts[1], offset, parts[3])
    }

    fun hover(lines: List<String>): Hover? = lines.firstNotNullOfOrNull { line ->
        val parts = line.split('\t')
        if (parts.size < 5 || parts[0] != "hover") return@firstNotNullOfOrNull null
        Hover(parts[1], parts[2], parts[3], parts[4], CsDocText.unescape(parts.getOrElse(5) { "" }))
    }

    fun tokens(lines: List<String>): List<Token> = lines.mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 4 || parts[0] != "tok") return@mapNotNull null
        val start = parts[1].toIntOrNull() ?: return@mapNotNull null
        val length = parts[2].toIntOrNull() ?: return@mapNotNull null
        if (start < 0 || length <= 0) return@mapNotNull null
        Token(start, length, parts[3], parts.getOrElse(4) { "" }.split(',').filter { it.isNotEmpty() })
    }
}
