package dev.codeassist.csharp.project.csproj

 /**
 * Provides completion suggestions for project files:
 * child tag names, item attributes, and valid property or attribute values.
 *
 * All completion logic operates on [CsprojText.positionAt] and [CsprojSchema].
 * The editor's completion contributor is responsible only for converting suggestions
 * into completion items.
 */
internal object CsprojCompleter {

    enum class Kind { TAG, ATTRIBUTE, VALUE }

    /**
     * [label] is displayed to the user.
     * [insert] replaces the typed text.
     * [caret] specifies the caret position inside [insert], or null to place it at the end.
     */
    class Suggestion(
        val label: String,
        val insert: String,
        val detail: String,
        val kind: Kind,
        val caret: Int? = null,
    )

    fun suggest(text: String, caret: Int): List<Suggestion> =
        when (val position = CsprojText.positionAt(text, caret)) {
            null -> emptyList()
            is CsprojText.Position.TagName -> tags(position)
            is CsprojText.Position.ClosingName ->
                position.open?.let {
                    listOf(Suggestion(it, it, "close <$it>", Kind.TAG))
                }.orEmpty()
            is CsprojText.Position.AttributeName -> attributes(text, caret, position)
            is CsprojText.Position.AttributeValue -> attributeValues(position)
            is CsprojText.Position.Value -> values(position)
        }

    private fun tags(position: CsprojText.Position.TagName): List<Suggestion> =
        when (position.parent) {
            null -> listOf(
                Suggestion("Project", "Project Sdk=\"Microsoft.NET.Sdk\"", "the root element", Kind.TAG),
            )

            "Project" -> CsprojSchema.GROUPS.map {
                Suggestion(it, it, "<Project> child", Kind.TAG)
            }

            "PropertyGroup" -> CsprojSchema.PROPERTIES.map {
                Suggestion(it.name, "${it.name}></${it.name}>", it.doc, Kind.TAG, it.name.length + 1)
            }

            "ItemGroup" -> CsprojSchema.ITEMS.map {
                val head = "${it.name} Include=\""
                Suggestion(it.name, "$head\" />", it.doc, Kind.TAG, head.length)
            }

            else -> emptyList()
        }.filter { it.label.startsWith(position.typed, ignoreCase = true) }

    private fun attributes(
        text: String,
        caret: Int,
        position: CsprojText.Position.AttributeName,
    ): List<Suggestion> {
        val item = CsprojSchema.item(position.element) ?: return emptyList()

        // Exclude attributes already present in the current start tag.
        val tagStart = text.lastIndexOf('<', (caret - 1).coerceAtLeast(0))
        val written = if (tagStart >= 0) {
            text.substring(tagStart, caret.coerceIn(0, text.length))
        } else {
            ""
        }

        return item.attributes
            .filter { !Regex("""\s${it}\s*=""").containsMatchIn(written) }
            .filter { it.startsWith(position.typed, ignoreCase = true) }
            .map {
                Suggestion(it, "$it=\"\"", "${item.name} attribute", Kind.ATTRIBUTE, it.length + 2)
            }
    }

    private fun attributeValues(position: CsprojText.Position.AttributeValue): List<Suggestion> {
        val words = when (position.element to position.attribute) {
            "Reference" to "Private", "Using" to "Static" -> listOf("true", "false")
            else -> emptyList()
        }

        return words
            .filter { it.startsWith(position.typed, ignoreCase = true) }
            .map { Suggestion(it, it, "${position.attribute} value", Kind.VALUE) }
    }

    private fun values(position: CsprojText.Position.Value): List<Suggestion> {
        if (position.parent != "PropertyGroup") return emptyList()

        val property = CsprojSchema.property(position.element) ?: return emptyList()

        val words = when (property.kind) {
            CsprojSchema.Kind.FRAMEWORK ->
                listOf("net10.0", "net9.0", "net8.0", "net7.0", "net6.0", "netstandard2.1")

            CsprojSchema.Kind.LANG_VERSION ->
                listOf("latest", "preview", "default", "13", "12", "11", "10")

            else -> property.values
        }

        return words
            .filter { it.startsWith(position.typed, ignoreCase = true) }
            .map { Suggestion(it, it, property.name, Kind.VALUE) }
    }
}