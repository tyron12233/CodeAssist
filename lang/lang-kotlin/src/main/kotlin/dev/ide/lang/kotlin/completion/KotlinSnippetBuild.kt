package dev.ide.lang.kotlin.completion

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
        val expanded = stops.toSortedMap().map { (i, ranges) -> ExpandedStop(i, ranges) }
        val finalAt = if (finalOffset >= 0) finalOffset else sb.length
        return SnippetExpansion(sb.toString(), expanded, finalAt)
    }
}
