package dev.ide.ui.editor.core

/**
 * One block of merge-conflict markers in a file, as Git and the store's "Update and keep my changes" write them:
 *
 * ```
 * <<<<<<< Your version        (startLine)
 * the current side
 * ||||||| base                (baseLine, optional: Git's diff3 style)
 * the common ancestor
 * =======                     (separatorLine)
 * the incoming side
 * >>>>>>> New version (1.0.2) (endLine)
 * ```
 *
 * Offsets are into the text it was found in: [start] is the `<<<<<<<` line's first character and [end] is just
 * past the `>>>>>>>` line's newline (or the end of the text), so `[start, end)` is the whole block. Each side's
 * range holds its lines, newlines included.
 */
data class MergeConflict(
    val start: Int,
    val end: Int,
    val currentStart: Int,
    val currentEnd: Int,
    val incomingStart: Int,
    val incomingEnd: Int,
    val startLine: Int,
    val baseLine: Int?,
    val separatorLine: Int,
    val endLine: Int,
    /** What follows `<<<<<<<`: the current side's name (`HEAD`, `Your version`). */
    val currentLabel: String,
    /** What follows `>>>>>>>`: the incoming side's name. */
    val incomingLabel: String,
) {
    /** The text that replaces the whole block when it is resolved with [choice]. */
    fun resolution(text: CharSequence, choice: ConflictChoice): String = when (choice) {
        ConflictChoice.CURRENT -> text.substring(currentStart, currentEnd)
        ConflictChoice.INCOMING -> text.substring(incomingStart, incomingEnd)
        ConflictChoice.BOTH -> text.substring(currentStart, currentEnd) + text.substring(incomingStart, incomingEnd)
    }

    operator fun contains(offset: Int): Boolean = offset in start until end
}

/** Which side a conflict keeps: Git's "ours" (the current side), "theirs" (the incoming one), or both in order. */
enum class ConflictChoice { CURRENT, INCOMING, BOTH }

private const val MARKER_LENGTH = 7

/**
 * Every complete conflict block in [text], in order. A block missing its `=======` or `>>>>>>>` (half-deleted by
 * hand) is not one, so its markers are left as plain text rather than offered for a resolution that would cut the
 * wrong lines.
 */
fun findMergeConflicts(text: CharSequence): List<MergeConflict> {
    if (text.indexOf("<<<<<<<") < 0) return emptyList() // the common case: one scan, no line walk
    val out = ArrayList<MergeConflict>()
    // The block being read: where it and its sections start, and which section the walk is in.
    var start = -1
    var startLine = -1
    var currentLabel = ""
    var currentStart = -1
    var currentEnd = -1
    var baseLine: Int? = null
    var separatorLine = -1
    var incomingStart = -1

    var lineStart = 0
    var line = 0
    while (lineStart <= text.length) {
        val nl = text.indexOf('\n', lineStart)
        val lineEnd = if (nl < 0) text.length else nl // without the newline
        val next = if (nl < 0) text.length else nl + 1
        when (markerAt(text, lineStart, lineEnd)) {
            '<' -> { // a new block; an unfinished one before it is dropped
                start = lineStart; startLine = line
                currentLabel = labelAt(text, lineStart, lineEnd)
                currentStart = next; currentEnd = -1
                baseLine = null; separatorLine = -1; incomingStart = -1
            }
            '|' -> if (start >= 0 && separatorLine < 0 && baseLine == null) {
                baseLine = line; currentEnd = lineStart
            }
            '=' -> if (start >= 0 && separatorLine < 0) {
                if (currentEnd < 0) currentEnd = lineStart
                separatorLine = line; incomingStart = next
            }
            '>' -> if (start >= 0 && separatorLine >= 0) {
                out += MergeConflict(
                    start = start, end = next,
                    currentStart = currentStart, currentEnd = currentEnd,
                    incomingStart = incomingStart, incomingEnd = lineStart,
                    startLine = startLine, baseLine = baseLine, separatorLine = separatorLine, endLine = line,
                    currentLabel = currentLabel, incomingLabel = labelAt(text, lineStart, lineEnd),
                )
                start = -1
            }
        }
        if (nl < 0) break
        lineStart = next
        line++
    }
    return out
}

/** The marker kind ('<', '|', '=', '>') when the line `[from, to)` is a conflict marker, else null. */
private fun markerAt(text: CharSequence, from: Int, to: Int): Char? {
    if (to - from < MARKER_LENGTH) return null
    val c = text[from]
    if (c != '<' && c != '|' && c != '=' && c != '>') return null
    for (i in 1 until MARKER_LENGTH) if (text[from + i] != c) return null
    val rest = from + MARKER_LENGTH
    // `=======` stands alone on its line; the others may carry a label after a space.
    return when {
        rest == to || (rest == to - 1 && text[rest] == '\r') -> c
        c != '=' && text[rest] == ' ' -> c
        else -> null
    }
}

private fun labelAt(text: CharSequence, from: Int, to: Int): String =
    text.subSequence((from + MARKER_LENGTH).coerceAtMost(to), to).toString().trim()
