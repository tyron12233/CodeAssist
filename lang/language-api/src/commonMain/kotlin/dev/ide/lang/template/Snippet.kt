package dev.ide.lang.template

import kotlin.jvm.JvmInline

import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentSnapshot

/**
 * Snippet / template expressions, modelled on the LSP / TextMate snippet syntax. A snippet is a string
 * with **tab stops** (`$1`, `$2`, … and the final `$0`), **placeholders** with defaults
 * (`${1:name}`), **choices** (`${1|a,b,c|}`), and **variables** (`$TM_FILENAME`, `${TM_SELECTED_TEXT}`).
 *
 * The design follows the same layering as completion's [dev.ide.lang.completion.CaretAction]: the parse
 * + expansion live here in language-api and produce an **editor-agnostic, offset-relative** result
 * ([SnippetExpansion]); the editor knows nothing about Java, tab stops, or variables — it just inserts
 * the text and drives the carets the expansion describes. A tab stop that expands to more than one
 * [ExpandedStop.ranges] entry is the **linked-edit / multiple-cursors** contract (the same index used
 * twice mirrors edits across all its occurrences).
 *
 * These are the contracts the editor and the language backends build against.
 */
@JvmInline
value class SnippetTemplate(val raw: String)

/** A snippet parsed into ordered segments; defaults/choices are not yet resolved against a context. */
data class ParsedSnippet(val segments: List<SnippetSegment>)

/** One piece of a [ParsedSnippet]. */
sealed interface SnippetSegment {
    /** Literal text, emitted verbatim. */
    data class Literal(val text: String) : SnippetSegment

    /**
     * A tab stop. [index] `0` is the final caret. The same [index] appearing more than once means those
     * positions are linked (edited together → multiple cursors). [default] is the initial/selected text
     * (may itself contain nested stops); [choices] is a non-empty pick list for a `${n|a,b|}` stop.
     */
    data class TabStop(
        val index: Int,
        val default: ParsedSnippet? = null,
        val choices: List<String> = emptyList(),
    ) : SnippetSegment

    /** A named variable (`$TM_FILENAME`); falls back to [default] (then to the empty string) if unresolved. */
    data class Variable(
        val name: String,
        val default: ParsedSnippet? = null,
    ) : SnippetSegment
}

/** Parses [SnippetTemplate] text into a [ParsedSnippet]. Implementations must be tolerant: a malformed
 *  snippet degrades to literal text rather than throwing. */
interface SnippetParser {
    fun parse(template: SnippetTemplate): ParsedSnippet
}

/**
 * The situation a snippet expands in, supplied by the host. Keeps the snippet API host-agnostic the same
 * way [dev.ide.lang.completion.CompletionContext] does — the engine asks for variable values through
 * [SnippetVariableResolver], the host decides what they mean.
 */
data class SnippetContext(
    val document: DocumentSnapshot,
    /** Caret offset where the snippet's text begins. */
    val offset: Int,
    /** The selection the snippet replaces, if any (drives `$TM_SELECTED_TEXT`). */
    val selection: TextRange? = null,
    /** The leading whitespace of the snippet's line, prepended to inner newlines for re-indentation. */
    val indent: String = "",
)

/** Resolves snippet variable names (`TM_FILENAME`, `TM_SELECTED_TEXT`, `CURRENT_YEAR`, …) to text.
 *  Returns null for an unknown variable, letting the engine fall back to its default. Host-supplied. */
interface SnippetVariableResolver {
    fun resolve(name: String, ctx: SnippetContext): String?
}

/**
 * Expands a parsed/raw snippet against a context into the final text plus the carets to drive. The result
 * is offset-relative to the insertion start, so the editor applies it without any language knowledge.
 */
interface SnippetEngine {
    fun expand(
        template: SnippetTemplate,
        ctx: SnippetContext,
        resolver: SnippetVariableResolver,
    ): SnippetExpansion
}

/**
 * The editor-agnostic result of expanding a snippet: [text] to insert, the tab [stops] to step through,
 * and where the caret lands when the user finishes ([finalCaretOffset], i.e. the `$0` position). All
 * offsets/ranges are relative to the start of the inserted [text]; the editor clamps them into range.
 */
data class SnippetExpansion(
    val text: String,
    val stops: List<ExpandedStop>,
    val finalCaretOffset: Int,
)

/**
 * One tab stop after expansion. [ranges] holds every occurrence of this stop's [index] in order — a
 * single range is a plain stop/placeholder; **more than one range is the linked-edit / multiple-cursors
 * contract** (typing in one mirrors into the rest). [choices] is non-empty for a choice stop, to be
 * offered as a popup when the editor reaches it.
 */
data class ExpandedStop(
    val index: Int,
    val ranges: List<TextRange>,
    val choices: List<String> = emptyList(),
)

/**
 * This expansion with [indent] after every line break, its stops and final caret moved to match. A built-in
 * template is written as if at column 0, and the line it lands on is usually indented: without this, the body
 * and closing brace of an `if` typed inside a method would fall back to column 0.
 */
fun SnippetExpansion.indented(indent: String): SnippetExpansion {
    if (indent.isEmpty() || '\n' !in text) return this
    fun map(offset: Int): Int {
        var breaks = 0
        for (i in 0 until offset.coerceIn(0, text.length)) if (text[i] == '\n') breaks++
        return offset + breaks * indent.length
    }
    return SnippetExpansion(
        text = text.replace("\n", "\n$indent"),
        stops = stops.map { s -> s.copy(ranges = s.ranges.map { TextRange(map(it.start), map(it.end)) }) },
        finalCaretOffset = map(finalCaretOffset),
    )
}

/** The leading spaces and tabs of the line holding [offset]: the indent a template inserted there takes on. */
fun lineIndentAt(text: CharSequence, offset: Int): String {
    var start = offset.coerceIn(0, text.length)
    while (start > 0 && text[start - 1] != '\n') start--
    var end = start
    while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
    return text.subSequence(start, end).toString()
}
