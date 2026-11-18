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

    /** A declaration in the file's outline. [depth] is its nesting, 0 at the top. */
    data class Item(val kind: String, val depth: Int, val nameOffset: Int, val end: Int, val name: String, val detail: String)

    /** A region that can be collapsed. [kind] is the editor's word for it (`classBody`, `imports`, ...). */
    data class Fold(val start: Int, val end: Int, val kind: String, val placeholder: String)

    class Outline(val items: List<Item>, val folds: List<Fold>)

    /** The name at the caret: its text and range, what it is, and whether the project declares it. */
    data class Named(val name: String, val start: Int, val end: Int, val kind: String, val inSource: Boolean)

    /** One place a symbol appears. [access] is `d` for its declaration, `w` where it is assigned, `r` elsewhere. */
    data class Reference(val path: String, val start: Int, val end: Int, val access: Char)

    class References(val symbol: Named, val places: List<Reference>)

    fun outline(lines: List<String>): Outline {
        val items = ArrayList<Item>()
        val folds = ArrayList<Fold>()
        for (line in lines) {
            val parts = line.split('\t')
            when (parts[0]) {
                "item" -> {
                    if (parts.size < 6) continue
                    val depth = parts[2].toIntOrNull() ?: continue
                    val offset = parts[3].toIntOrNull() ?: continue
                    val end = parts[4].toIntOrNull() ?: continue
                    items += Item(parts[1], depth, offset, end, parts[5], parts.getOrElse(6) { "" })
                }
                "fold" -> {
                    if (parts.size < 4) continue
                    val start = parts[1].toIntOrNull() ?: continue
                    val end = parts[2].toIntOrNull() ?: continue
                    if (end > start) folds += Fold(start, end, parts[3], parts.getOrElse(4) { "..." }.ifEmpty { "..." })
                }
            }
        }
        return Outline(items, folds)
    }

    fun references(lines: List<String>): References? {
        var symbol: Named? = null
        val places = ArrayList<Reference>()
        for (line in lines) {
            val parts = line.split('\t')
            when (parts[0]) {
                "sym" -> {
                    if (parts.size < 6) continue
                    val start = parts[2].toIntOrNull() ?: continue
                    val end = parts[3].toIntOrNull() ?: continue
                    symbol = Named(parts[1], start, end, parts[4], parts[5] == "1")
                }
                "ref" -> {
                    if (parts.size < 5) continue
                    val start = parts[2].toIntOrNull() ?: continue
                    val end = parts[3].toIntOrNull() ?: continue
                    places += Reference(parts[1], start, end, parts[4].firstOrNull() ?: 'r')
                }
            }
        }
        return symbol?.let { References(it, places) }
    }

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
