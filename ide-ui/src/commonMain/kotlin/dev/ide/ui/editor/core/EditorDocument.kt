package dev.ide.ui.editor.core

/**
 * The editor's line-indexed text model. The bytes live in a [Rope] ([buffer]) so an edit rebuilds only the
 * O(log N) nodes on the path to the cut instead of copying the whole document (the old `String`+`buildString`
 * approach was O(N) per keystroke); on top of that sits the **line-start index**, which [replace] splices
 * incrementally so every line-keyed consumer (styler, layout cache, renderer) invalidates only the lines that
 * moved. Random access ([charAt]) and line extraction ([lineText]) read straight off the rope and never
 * materialize the whole text; the full [text] `String` is built lazily and cached, paid once per revision and
 * only when a `String`-speaking consumer (the host's `TextFieldValue`, analyze, complete) actually asks.
 *
 * Offsets are absolute char offsets into the text; lines are 0-based; a line's end excludes its `\n`.
 */
class EditorDocument private constructor(
    private val buffer: Rope,
    /** `lineStarts[i]` = offset of the first char of line i. Always non-empty; `lineStarts[0] == 0`. */
    private val lineStarts: IntArray,
    /** Seeded when the text is already in hand (file open); null after an edit so [text] materializes lazily. */
    materialized: String?,
) {
    val length: Int get() = buffer.length
    val lineCount: Int get() = lineStarts.size

    /**
     * The full document as a `String`, materialized lazily from the rope and cached for this revision. The
     * one O(N) step in the pipeline — but paid at most once per edit, and skipped entirely for intermediate
     * documents in a batch (IME composition, completion accept) that no one ever reads.
     */
    val text: String by lazy(LazyThreadSafetyMode.NONE) { materialized ?: buffer.toString() }

    /** The text as a [CharSequence] for near-caret reads (smart edits, word boundaries) — no String copy. */
    val chars: CharSequence get() = buffer

    fun charAt(index: Int): Char = buffer[index]

    /** Materialize `[start, end)` directly from the rope — used for line extraction and selection copies. */
    fun substring(start: Int, end: Int): String = buffer.substring(start, end)

    fun lineStart(line: Int): Int = lineStarts[line]

    /** End of [line] excluding the line break (== [lineStart] of the next line minus 1, or text end). */
    fun lineEnd(line: Int): Int =
        if (line == lineStarts.size - 1) buffer.length else lineStarts[line + 1] - 1

    fun lineLength(line: Int): Int = lineEnd(line) - lineStart(line)

    fun lineText(line: Int): String = buffer.substring(lineStart(line), lineEnd(line))

    /** The line containing [offset] (an offset == text.length maps to the last line). Binary search. */
    fun lineForOffset(offset: Int): Int {
        val off = offset.coerceIn(0, buffer.length)
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStarts[mid] <= off) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun columnOf(offset: Int): Int = offset - lineStart(lineForOffset(offset))

    fun offsetOf(line: Int, column: Int): Int {
        val l = line.coerceIn(0, lineCount - 1)
        return (lineStart(l) + column).coerceIn(lineStart(l), lineEnd(l))
    }

    /**
     * Replace `[start, end)` with [replacement]. The rope edit is O(log N + leaf); the new line index is
     * spliced — starts before the first touched line are reused, breaks inside [replacement] are scanned
     * (O(replacement)), and starts after the edit are shifted by the length delta (a flat arraycopy, not a
     * rescan). The returned document materializes its [text] lazily (this edit copies no full string).
     */
    fun replace(start: Int, end: Int, replacement: String): EditorDocument {
        val s = start.coerceIn(0, buffer.length)
        val e = end.coerceIn(s, buffer.length)
        val newBuffer = buffer.replace(s, e, replacement)

        val firstLine = lineForOffset(s)
        val lastLine = lineForOffset(e)
        val delta = replacement.length - (e - s)

        // breaks introduced by the replacement, as new-text line starts
        var breaks = 0
        for (c in replacement) if (c == '\n') breaks++

        val tailCount = lineStarts.size - 1 - lastLine
        val out = IntArray(firstLine + 1 + breaks + tailCount)
        // unchanged prefix
        lineStarts.copyInto(out, 0, 0, firstLine + 1)
        // starts created inside the replacement
        var w = firstLine + 1
        var i = 0
        while (i < replacement.length) {
            if (replacement[i] == '\n') out[w++] = s + i + 1
            i++
        }
        // shifted suffix
        var r = lastLine + 1
        while (r < lineStarts.size) {
            out[w++] = lineStarts[r] + delta
            r++
        }
        return EditorDocument(newBuffer, out, null)
    }

    companion object {
        fun of(text: String): EditorDocument {
            var breaks = 0
            for (c in text) if (c == '\n') breaks++
            val starts = IntArray(breaks + 1)
            starts[0] = 0
            var w = 1
            for (i in text.indices) if (text[i] == '\n') starts[w++] = i + 1
            // seed the materialized cache: the string is already in hand, no need to re-walk the rope to read it
            return EditorDocument(Rope.of(text), starts, text)
        }
    }
}
