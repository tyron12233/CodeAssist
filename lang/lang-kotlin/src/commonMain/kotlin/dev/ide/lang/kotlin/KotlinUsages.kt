package dev.ide.lang.kotlin

import dev.ide.lang.dom.TextRange

/**
 * What Find Usages and Rename act on: a symbol identified by WHERE IT IS DECLARED.
 *
 * A reference is a usage when go-to-declaration on it lands on one of [identities] (a declaration's file and
 * the offset of its name). That one notion covers a Kotlin declaration, a Java one and a library one alike,
 * and it never mistakes a same-named symbol elsewhere for this one, which a text search would.
 *
 * Several declarations can make up one target: the overloads declared beside it (the symbol model does not
 * choose between overloads, so their calls are only told apart by name), and for a member, the members it
 * overrides and that override it. Renaming one of those alone would leave the code broken, so they move
 * together.
 */
class KotlinUsageTarget internal constructor(
    val name: String,
    /** `class`, `function`, `property`, `variable`, `parameter`, or `symbol` for one not declared in Kotlin. */
    val kind: String,
    internal val identities: Set<String>,
    /** The declaration names to rename, as (file path, name range). Empty when not declared in project Kotlin. */
    val declarations: List<Pair<String, TextRange>>,
    /** A local or private symbol's file and the only range its references can be in; null when project-wide. */
    val scope: Pair<String, TextRange>?,
    internal val importFqns: Set<String>,
    /** The file that is named after this symbol (a top-level class in its own `Name.kt`), renamed with it. */
    val fileNamedAfter: String?,
) {
    /** Rename edits Kotlin declarations; a Java or library symbol is renamed (if at all) from its own side. */
    val renameable: Boolean get() = declarations.isNotEmpty()

    /** Whether the declaration at [path]:[offset] (its name) is this target: how a reference resolved by
     *  another language (Java, through a light class) is matched. */
    fun isDeclaredAt(path: String, offset: Int): Boolean = usageIdentity(path, offset) in identities
}

internal fun usageIdentity(path: String, offset: Int): String = "$path#$offset"

/** Whether [name] occurs in [text] as a whole identifier: the cheap filter before a file is parsed. */
internal fun containsIdentifier(text: CharSequence, name: String): Boolean {
    if (name.isEmpty()) return false
    var i = text.indexOf(name, 0)
    while (i >= 0) {
        val before = if (i > 0) text[i - 1] else ' '
        val after = if (i + name.length < text.length) text[i + name.length] else ' '
        if (!before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_') return true
        i = text.indexOf(name, i + 1)
    }
    return false
}
