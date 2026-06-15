package dev.ide.ui.editor.core

import dev.ide.ui.editor.CodeLanguage

/**
 * Per-file cache of tokenized lines, kept in sync with [EditorDocument] edits incrementally.
 *
 * Each line stores its spans, its exit lexer state, and a globally-unique revision stamp. [splice]
 * mirrors a document edit: the touched line entries are replaced and re-tokenized, then tokenization
 * walks forward only while a following line's exit state changes (the sora-editor
 * `AsyncIncrementalAnalyzeManager` stop rule — typing inside a line re-tokenizes exactly that line;
 * opening a block comment ripples until the state stabilizes). Revision stamps never repeat, so any
 * consumer caching per-line data (the render layout cache) can validate an entry with one int compare.
 */
class LineStyles(private val language: CodeLanguage) {
    private val spans = ArrayList<List<LineSpan>>()
    private val exits = ArrayList<Int>()
    private val revs = ArrayList<Int>()
    private var stamp = 0

    val lineCount: Int get() = spans.size

    fun spansFor(line: Int): List<LineSpan> = if (line in spans.indices) spans[line] else emptyList()

    /** Unique-per-content revision of [line]; bumped whenever the line is (re)tokenized. */
    fun revOf(line: Int): Int = if (line in revs.indices) revs[line] else -1

    /** Rebuild from scratch for [doc] (file open / external replace). */
    fun reset(doc: EditorDocument) {
        spans.clear(); exits.clear(); revs.clear()
        spans.ensureCapacity(doc.lineCount); exits.ensureCapacity(doc.lineCount); revs.ensureCapacity(doc.lineCount)
        var entry = LexState.CODE
        for (i in 0 until doc.lineCount) {
            val res = styleLine(doc.lineText(i), entry, language)
            spans.add(res.spans); exits.add(res.exitState); revs.add(++stamp)
            entry = res.exitState
        }
    }

    /**
     * Apply an edit that replaced [removed] lines starting at [firstLine] with [inserted] lines (counts
     * include the partially-edited first/last lines). [doc] is the post-edit document. Returns the index
     * one past the last re-tokenized line (callers can use it to know how far styling actually rippled).
     */
    fun splice(doc: EditorDocument, firstLine: Int, removed: Int, inserted: Int): Int {
        // resize the arrays at the edit point
        if (removed > inserted) {
            repeat(removed - inserted) { spans.removeAt(firstLine); exits.removeAt(firstLine); revs.removeAt(firstLine) }
        } else if (inserted > removed) {
            repeat(inserted - removed) {
                spans.add(firstLine, emptyList()); exits.add(firstLine, LexState.CODE); revs.add(firstLine, 0)
            }
        }

        var entry = if (firstLine == 0) LexState.CODE else exits[firstLine - 1]
        var i = firstLine
        while (i < doc.lineCount) {
            val fresh = i < firstLine + inserted // directly edited lines: always re-tokenize
            val oldExit = if (fresh) -1 else exits[i]
            val res = styleLine(doc.lineText(i), entry, language)
            spans[i] = res.spans; exits[i] = res.exitState; revs[i] = ++stamp
            entry = res.exitState
            i++
            if (!fresh && res.exitState == oldExit) break // state stabilized; lines below are still valid
        }
        return i
    }
}
