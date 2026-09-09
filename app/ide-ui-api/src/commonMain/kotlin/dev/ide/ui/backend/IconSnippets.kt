package dev.ide.ui.backend

/** What an icon insertion should reference. */
sealed interface UiIconRef {

    /** A Compose icon property, referenced as `Icons.<Style>.<property>`. */
    data class ComposeIcon(val property: String, val style: String = "filled") : UiIconRef

    /** A resource, referenced as `@drawable/name` or `R.drawable.name` depending on the language. */
    data class Resource(val resType: String, val name: String) : UiIconRef
}

/**
 * The editor buffer an insertion is going into. [composeContext] and [insideXmlAttributeValue] are properties
 * of the buffer rather than the file, so the host computes them from the live text.
 */
data class UiInsertionTarget(
    val path: String,
    /** The buffer already looks like Compose source, so a resource wants a `painterResource` wrapper. */
    val composeContext: Boolean = false,
    /** The caret sits between the quotes of an XML attribute, so only the bare value belongs there. */
    val insideXmlAttributeValue: Boolean = false,
) {
    val language: String get() = IconSnippets.languageOf(path)
}

/**
 * How an icon is written in each language.
 *
 * XML, Java and Kotlin refer to the same drawable three different ways, and Kotlin refers to it differently
 * again inside a Compose file, so "insert at the cursor" cannot be one string. This lives in the shared API
 * module on purpose: the backend builds the real edits from it and the UI labels its button from it, so the
 * two cannot disagree about what is about to be inserted.
 */
object IconSnippets {

    const val KOTLIN = "kotlin"
    const val JAVA = "java"
    const val XML = "xml"
    const val OTHER = "other"

    /** The language of the file at [path], by extension. */
    fun languageOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> KOTLIN
        "java" -> JAVA
        "xml" -> XML
        else -> OTHER
    }

    /** Whether [ref] can be referenced at all from [language]. A Compose icon is Kotlin-only. */
    fun supports(ref: UiIconRef, language: String): Boolean = when (ref) {
        is UiIconRef.ComposeIcon -> language == KOTLIN
        is UiIconRef.Resource -> true
    }

    /**
     * The short reference form, for a button label: what the user will see in their code. Deliberately not
     * the full snippet, which would be too long to read on a button and would also have to guess at the XML
     * attribute-value case.
     */
    fun reference(ref: UiIconRef, language: String): String = when (ref) {
        is UiIconRef.ComposeIcon -> "Icons.${styleName(ref.style)}.${ref.property}"
        is UiIconRef.Resource -> when (language) {
            KOTLIN, JAVA -> "R.${ref.resType}.${ref.name}"
            else -> "@${ref.resType}/${ref.name}"
        }
    }

    /** The text to insert at the caret, or null when [ref] has no meaning in that language. */
    fun snippet(ref: UiIconRef, target: UiInsertionTarget): String? {
        val language = target.language
        if (!supports(ref, language)) return null
        return when (ref) {
            is UiIconRef.ComposeIcon ->
                "Icon(Icons.${styleName(ref.style)}.${ref.property}, contentDescription = null)"

            is UiIconRef.Resource -> when (language) {
                // A Compose file draws a drawable through a painter; a plain Kotlin/Java file just names it.
                KOTLIN -> if (target.composeContext) {
                    "Icon(painterResource(R.${ref.resType}.${ref.name}), contentDescription = null)"
                } else {
                    "R.${ref.resType}.${ref.name}"
                }

                JAVA -> "R.${ref.resType}.${ref.name}"
                // Between the quotes of an attribute only the value belongs; anywhere else in a tag needs
                // the whole attribute, and `android:src` is the one that takes a drawable on a View.
                XML -> if (target.insideXmlAttributeValue) {
                    "@${ref.resType}/${ref.name}"
                } else {
                    "android:src=\"@${ref.resType}/${ref.name}\""
                }

                else -> "@${ref.resType}/${ref.name}"
            }
        }
    }

    /**
     * The imports the snippet needs, most general first. The `R` class import is not here: whether it is
     * needed depends on the file's package and the module's namespace, which only the host knows.
     */
    fun imports(ref: UiIconRef, target: UiInsertionTarget): List<String> {
        if (target.language != KOTLIN) return emptyList()
        return when (ref) {
            is UiIconRef.ComposeIcon -> listOf(
                "androidx.compose.material3.Icon",
                "androidx.compose.material.icons.Icons",
                "androidx.compose.material.icons.${ref.style.lowercase()}.${ref.property}",
            )

            is UiIconRef.Resource ->
                if (target.composeContext) {
                    listOf("androidx.compose.material3.Icon", "androidx.compose.ui.res.painterResource")
                } else {
                    emptyList()
                }
        }
    }

    /** Whether the snippet for [ref] names the `R` class, so the host knows to consider its import. */
    fun needsRClass(ref: UiIconRef, target: UiInsertionTarget): Boolean =
        ref is UiIconRef.Resource && target.language in setOf(KOTLIN, JAVA)

    /**
     * Whether [text] reads as Compose source. Used to decide between the `painterResource` form and a plain
     * `R.` reference; an import is the reliable marker, since a file may use Compose without any annotation
     * of its own.
     */
    fun looksLikeCompose(text: String): Boolean =
        text.contains("androidx.compose") || text.contains("@Composable")

    /**
     * Whether [caret] in [text] sits inside an XML attribute value. Counts quotes back to the start of the
     * enclosing tag: an odd number means the caret is between a pair of them.
     */
    fun insideXmlAttributeValue(text: String, caret: Int): Boolean {
        val at = caret.coerceIn(0, text.length)
        val tagStart = text.lastIndexOf('<', (at - 1).coerceAtLeast(0))
        if (tagStart < 0) return false
        // A tag that already closed before the caret means the caret is in element content, not a tag.
        if (text.lastIndexOf('>', (at - 1).coerceAtLeast(0)) > tagStart) return false
        var quotes = 0
        for (i in tagStart until at) if (text[i] == '"' || text[i] == '\'') quotes++
        return quotes % 2 == 1
    }

    /** `filled` reads as `Filled` in `Icons.Filled.X`; the library spells the two-tone family `TwoTone`. */
    fun styleName(style: String): String = when (style.lowercase()) {
        "outlined" -> "Outlined"
        "rounded" -> "Rounded"
        "sharp" -> "Sharp"
        "twotone" -> "TwoTone"
        else -> "Filled"
    }
}
