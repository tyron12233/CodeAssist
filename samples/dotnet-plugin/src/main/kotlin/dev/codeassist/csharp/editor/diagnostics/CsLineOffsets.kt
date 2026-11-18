package dev.codeassist.csharp.editor.diagnostics


import dev.ide.lang.dom.TextRange

/**
 * Converts Roslyn's 1-based line and column positions into buffer offsets.
 *
 * Line starts are indexed once so multiple diagnostics can be mapped without rescanning the text.
 */
internal class CsLineOffsets(private val text: CharSequence) {

    /** Buffer offset of the first character on each line. */
    private val lineStarts = buildList {
        add(0)
        text.forEachIndexed { index, char ->
            if (char == '\n') add(index + 1)
        }
    }.toIntArray()

    /** Returns the offset at [line]:[column], clamped to the corresponding line. */
    fun offsetOf(line: Int, column: Int): Int {
        val lineIndex = (line - 1).coerceIn(0, lineStarts.lastIndex)
        val start = lineStarts[lineIndex]
        val end = if (lineIndex < lineStarts.lastIndex) {
            lineStarts[lineIndex + 1] - 1
        } else {
            text.length
        }

        return (start + (column - 1).coerceAtLeast(0))
            .coerceIn(start, maxOf(start, end))
    }

    /**
     * Returns the identifier at [line]:[column], or a one-character range if no identifier
     * is found, so the diagnostic remains visible.
     */
    fun tokenAt(line: Int, column: Int): TextRange {
        val start = offsetOf(line, column)
        var end = start

        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) {
            end++
        }

        val tokenEnd = if (end > start) end else (start + 1).coerceAtMost(text.length)
        return TextRange(start, tokenEnd)
    }
}
