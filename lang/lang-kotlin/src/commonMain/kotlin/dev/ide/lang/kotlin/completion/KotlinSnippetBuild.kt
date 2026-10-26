package dev.ide.lang.kotlin.completion

import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.template.ExpandedStop
import dev.ide.lang.template.SnippetExpansion

/** Build a [SnippetExpansion] programmatically; offsets are relative to the inserted text. Shared by the
 *  Kotlin postfix templates and the statement-position live templates (mirror of lang-jdt's SnippetBuild). */
internal fun snippet(block: SnippetBuilder.() -> Unit): SnippetExpansion = SnippetBuilder().apply(block).build()

internal class SnippetBuilder {
    private val sb = StringBuilder()
    private val stops = LinkedHashMap<Int, MutableList<TextRange>>()
    private var finalOffset = -1

    fun text(s: String) { sb.append(s) }

    /** A tab stop [index] with optional [default] placeholder text; repeat an index for linked cursors. */
    fun stop(index: Int, default: String = "") {
        val start = sb.length
        sb.append(default)
        stops.getOrPut(index) { ArrayList() }.add(TextRange(start, sb.length))
    }

    /** Mark the final caret position (`$0`). */
    fun finalHere() { finalOffset = sb.length }

    fun build(): SnippetExpansion {
        val expanded = stops.entries.sortedBy { it.key }.map { (i, ranges) -> ExpandedStop(i, ranges) }
        val finalAt = if (finalOffset >= 0) finalOffset else sb.length
        return SnippetExpansion(sb.toString(), expanded, finalAt)
    }
}

/**
 * An `import fqn` edit for a Kotlin file, placed after its last import (or its `package` line), or null when the
 * file already imports [fqn] or its package with `*`. Read off the text: the templates that use it import fixed
 * library classes (`android.util.Log`, Compose's `Composable`), so there is no name to resolve.
 */
internal fun kotlinImportEdit(text: CharSequence, fqn: String): TextEdit? {
    val pkg = fqn.substringBeforeLast('.')
    var lastImportEnd = -1
    var packageEnd = -1
    var lineStart = 0
    while (lineStart <= text.length) {
        var lineEnd = lineStart
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        val line = text.subSequence(lineStart, lineEnd).trim().toString()
        if (line.startsWith("import ")) {
            val imported = line.removePrefix("import ").trim()
            if (imported == fqn || imported == "$pkg.*" || imported.startsWith("$fqn as ")) return null
            lastImportEnd = lineEnd
        } else if (line.startsWith("package ")) {
            packageEnd = lineEnd
        } else if (line.isNotEmpty() && !line.startsWith("//") && !line.startsWith("@file:") && lastImportEnd >= 0) {
            break // past the import list
        }
        lineStart = lineEnd + 1
    }
    return when {
        lastImportEnd >= 0 -> TextEdit(TextRange(lastImportEnd, lastImportEnd), "\nimport $fqn")
        packageEnd >= 0 -> TextEdit(TextRange(packageEnd, packageEnd), "\n\nimport $fqn")
        else -> TextEdit(TextRange(0, 0), "import $fqn\n\n")
    }
}
