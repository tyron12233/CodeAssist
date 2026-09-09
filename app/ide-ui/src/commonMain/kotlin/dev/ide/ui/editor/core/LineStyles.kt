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
 *
 * **Lazy tokenization (windowed):** tokenizing is DEFERRED, not done eagerly at [reset]. `[0, highWater]` is
 * the contiguous, up-to-date tokenized prefix; lines above it hold placeholders until first requested by
 * [spansFor]/[revOf] (or reached by a [splice] ripple). Because per-line lexing carries state across lines
 * (block comments, raw strings), a line can only be tokenized once every line before it is — so on demand we
 * extend the prefix from the high-water mark up to the requested line. Opening a very large file is therefore
 * O(1) (nothing is lexed until viewed) and scrolling lexes only up to the viewport instead of the whole file.
 */
class LineStyles(private val language: CodeLanguage) {
    private val spans = ArrayList<List<LineSpan>>()
    private val exits = ArrayList<Int>()
    private val revs = ArrayList<Int>()
    private var stamp = 0

    /** The current buffer, needed to lex a line on demand; set by [reset]/[splice]. */
    private var doc: EditorDocument? = null
    /** Highest line index of the contiguous tokenized prefix `[0, highWater]`; -1 when nothing is tokenized. */
    private var highWater = -1

    val lineCount: Int get() = spans.size

    fun spansFor(line: Int): List<LineSpan> {
        if (line !in spans.indices) return emptyList()
        ensureTokenized(line)
        return spans[line]
    }

    /** Unique-per-content revision of [line]; bumped whenever the line is (re)tokenized. */
    fun revOf(line: Int): Int {
        if (line !in revs.indices) return -1
        ensureTokenized(line)
        return revs[line]
    }

    /**
     * Rebuild from scratch for [doc] (file open / external replace). Tokenization is DEFERRED: the parallel
     * arrays are sized with placeholders and each line is lexed lazily on first [spansFor]/[revOf] (or when a
     * [splice] reaches it), so opening a very large file no longer lexes every line up front.
     */
    fun reset(doc: EditorDocument) {
        this.doc = doc
        val n = doc.lineCount
        spans.clear(); exits.clear(); revs.clear()
        spans.ensureCapacity(n); exits.ensureCapacity(n); revs.ensureCapacity(n)
        repeat(n) { spans.add(emptyList()); exits.add(LexState.CODE); revs.add(0) }
        highWater = -1
    }

    /** Extend the tokenized prefix up to and including [line], carrying lexer state forward from the high-water
     *  mark. O(lines newly tokenized); a no-op once [line] is already covered. */
    private fun ensureTokenized(line: Int) {
        if (line <= highWater) return
        val d = doc ?: return
        val target = line.coerceAtMost(spans.size - 1)
        if (target <= highWater) return
        var entry = if (highWater < 0) LexState.CODE else exits[highWater]
        var i = highWater + 1
        while (i <= target) {
            val res = styleLine(d.lineText(i), entry, language)
            spans[i] = res.spans; exits[i] = res.exitState; revs[i] = ++stamp
            entry = res.exitState
            i++
        }
        highWater = target
    }

    /**
     * Apply an edit that replaced [removed] lines starting at [firstLine] with [inserted] lines (counts
     * include the partially-edited first/last lines). [doc] is the post-edit document. Returns the index
     * one past the last re-tokenized line (callers can use it to know how far styling actually rippled).
     */
    fun splice(doc: EditorDocument, firstLine: Int, removed: Int, inserted: Int): Int {
        this.doc = doc
        // The entry state for re-tokenizing comes from the line before the edit, so make sure the prefix up to
        // there is tokenized first (this also advances the high-water mark past the predecessor).
        if (firstLine > 0) ensureTokenized(firstLine - 1)
        val oldHigh = highWater
        val lastRemovedOld = firstLine + removed - 1 // old index of the last removed line

        // Resize the parallel arrays at the edit point, shifting the tail exactly ONCE. The old code did a
        // single add(firstLine)/removeAt(firstLine) per line, each of which moves the whole tail — so a bulk
        // insert/delete of K lines cost O(K·N) (a large multi-line paste or delete stalled for seconds).
        // subList(...).clear() and addAll(index, …) each do a single arraycopy → O(N + K). The placeholder
        // entries are overwritten by the re-tokenize walk below (every inserted line is `fresh`).
        when {
            removed > inserted -> {
                val drop = removed - inserted
                spans.subList(firstLine, firstLine + drop).clear()
                exits.subList(firstLine, firstLine + drop).clear()
                revs.subList(firstLine, firstLine + drop).clear()
            }
            inserted > removed -> {
                val addN = inserted - removed
                spans.addAll(firstLine, List(addN) { emptyList() })
                exits.addAll(firstLine, List(addN) { LexState.CODE })
                revs.addAll(firstLine, List(addN) { 0 })
            }
        }

        // Recompute the tokenized-prefix high water in NEW indices. If the whole removed region was inside the
        // tokenized prefix (lastRemovedOld <= oldHigh), the shifted prefix stays valid up to oldHigh + delta;
        // otherwise the removal reached into never-tokenized lines, so only the freshly re-lexed inserted lines
        // are valid and everything past them tokenizes lazily again.
        val delta = inserted - removed
        highWater = (if (oldHigh >= lastRemovedOld) oldHigh + delta else firstLine + inserted - 1)
            .coerceIn(firstLine - 1, spans.size - 1)

        // Re-tokenize from firstLine down through the valid prefix, stopping when a not-directly-edited line's
        // exit state is unchanged (the incremental stop rule). Lines beyond [highWater] tokenize lazily later.
        var entry = if (firstLine == 0) LexState.CODE else exits[firstLine - 1]
        var i = firstLine
        while (i <= highWater) {
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
