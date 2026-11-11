package dev.ide.ui.screens

import androidx.compose.runtime.Immutable
import dev.ide.ui.backend.ConsoleChunk
import dev.ide.ui.backend.ConsoleChunkKind

/**
 * One display line of a run transcript: its text in [parts], each part keeping the kind (output, echoed input,
 * system note) it came from, since a prompt and the input typed after it share a line. No line break inside.
 */
@Immutable
class ConsoleLine(val parts: List<ConsoleChunk>) {
    val text: String get() = if (parts.size == 1) parts[0].text else parts.joinToString("") { it.text }
}

/**
 * Cuts [transcript] into display lines so the console can lay out only the lines on screen. A whole transcript
 * runs to a megabyte, and measured as one paragraph it held the main thread for seconds on every update.
 *
 * A line longer than [maxLineChars] is cut into pieces of at most that many characters, so one huge line (a
 * program that never prints a newline) can't become the same single paragraph. A trailing `\r` is dropped.
 * A transcript ending in `\n` has no empty last line.
 */
fun consoleLines(transcript: List<ConsoleChunk>, maxLineChars: Int = MAX_CONSOLE_LINE_CHARS): List<ConsoleLine> {
    val lines = ArrayList<ConsoleLine>()
    var parts = ArrayList<ConsoleChunk>(1)
    var lineLength = 0
    fun add(text: String, from: Int, to: Int, kind: ConsoleChunkKind) {
        if (to <= from) return
        val last = parts.lastOrNull()
        val piece = text.substring(from, to)
        if (last != null && last.kind == kind) parts[parts.lastIndex] = ConsoleChunk(last.text + piece, kind)
        else parts.add(ConsoleChunk(piece, kind))
        lineLength += to - from
    }
    fun endLine() {
        val last = parts.lastOrNull()
        if (last != null && last.text.endsWith('\r')) {
            if (last.text.length == 1) parts.removeAt(parts.lastIndex)
            else parts[parts.lastIndex] = ConsoleChunk(last.text.dropLast(1), last.kind)
        }
        lines.add(ConsoleLine(parts))
        parts = ArrayList(1)
        lineLength = 0
    }
    for (chunk in transcript) {
        val text = chunk.text
        var i = 0
        while (i < text.length) {
            val nl = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            var start = i
            // Hard-wrap a line that outgrows the cap, piece by piece.
            while (nl - start > maxLineChars - lineLength) {
                val cut = start + (maxLineChars - lineLength)
                add(text, start, cut, chunk.kind)
                endLine()
                start = cut
            }
            add(text, start, nl, chunk.kind)
            if (nl < text.length) endLine()
            i = nl + 1
        }
    }
    if (parts.isNotEmpty()) endLine()
    return lines
}

/** Long enough for any real line of output, short enough that one line lays out in well under a frame. */
const val MAX_CONSOLE_LINE_CHARS = 2_000
