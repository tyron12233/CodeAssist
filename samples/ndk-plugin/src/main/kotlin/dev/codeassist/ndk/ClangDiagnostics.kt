package dev.codeassist.ndk

import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticSource
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange

/**
 * Turns what clang printed into editor diagnostics.
 *
 * Kept free of the toolchain, the file system and the plugin SPI's moving parts so it can be tested as what
 * it is: a string-to-data function. Everything awkward about clang's output is here rather than spread
 * through the analyzer.
 *
 * The shape being parsed, with `-fno-caret-diagnostics -fno-color-diagnostics
 * -fdiagnostics-print-source-range-info`:
 *
 * ```
 * <stdin>:7:12: error: use of undeclared identifier 'qux'
 * <stdin>:9:5:{9:5-9:18}: warning: unused variable 'x' [-Wunused-variable]
 * <stdin>:3:6: note: candidate function not viable
 * ```
 */
object ClangDiagnostics {

    /** `file:line:col:` then optional `{l:c-l:c}` ranges, then the severity and the message. */
    private val LINE = Regex(
        """^(?<file>[^:]*):(?<line>\d+):(?<col>\d+):(?<ranges>(?:\{\d+:\d+-\d+:\d+\})*):?\s*""" +
            """(?<severity>fatal error|error|warning|note|remark):\s*(?<message>.*)$"""
    )

    /** A trailing `[-Wunused-variable]`, which is the closest clang gives to a diagnostic code. */
    private val CHECK = Regex("""\s*\[(-[^\]]+)\]\s*$""")

    /**
     * One `{line:col-line:col}` range out of clang's range list.
     *
     * Compiled once, not per diagnostic: a file full of errors would otherwise pay for a regex compile each
     * time. And every `}` and `]` here is escaped even though a bare one is legal on the desktop JVM,
     * because **Android's ICU regex engine rejects them** — an unescaped `}` throws
     * `PatternSyntaxException` at class-init time on a device, where a JVM unit test sees nothing wrong.
     */
    private val RANGE = Regex("""\{(\d+):(\d+)-(\d+):(\d+)\}""")

    /**
     * Parse [output] into diagnostics against [text].
     *
     * Only diagnostics clang attributed to the buffer itself are kept. One in an `#include`d header is real,
     * but it belongs to that file, and reporting it at a meaningless offset in this one is worse than not
     * reporting it: [bufferName] is the name clang used for the buffer, and everything else is dropped.
     */
    fun parse(output: String, text: CharSequence, bufferName: String = "<stdin>"): List<Diagnostic> {
        if (output.isBlank()) return emptyList()
        val lines = LineOffsets(text)
        val result = ArrayList<Diagnostic>()

        for (raw in output.lineSequence()) {
            val match = LINE.matchEntire(raw.trim()) ?: continue
            val severityText = match.groups["severity"]!!.value
            val message = match.groups["message"]!!.value.trim()
            val fromBuffer = match.groups["file"]!!.value == bufferName

            // A note explains the diagnostic above it ("candidate function not viable", "declared here").
            // Folded into that one rather than surfaced separately: alone it underlines a position with no
            // problem at it, and the explanation is the useful half of the pair.
            if (severityText == "note") {
                val previous = result.removeLastOrNull() ?: continue
                result.add(previous.copy(message = "${previous.message}\n$message"))
                continue
            }
            if (!fromBuffer) continue

            val severity = when (severityText) {
                "fatal error", "error" -> Severity.ERROR
                "warning" -> Severity.WARNING
                else -> Severity.INFO
            }
            val line = match.groups["line"]!!.value.toIntOrNull() ?: continue
            val column = match.groups["col"]!!.value.toIntOrNull() ?: continue
            val range = rangeOf(match.groups["ranges"]?.value, lines)
                ?: lines.tokenAt(line, column)

            result.add(
                Diagnostic(
                    range = range,
                    severity = severity,
                    message = CHECK.replace(message, ""),
                    // The compiler, not an analyzer of ours. The engine keys quick fixes off this and
                    // pins compiler errors at ERROR regardless of any profile override.
                    source = DiagnosticSource.Compiler,
                    // The warning flag, when clang named one, so a fix or a suppression can key on it.
                    code = CHECK.find(message)?.groupValues?.get(1),
                )
            )
        }
        return result
    }

    /** The first `{l:c-l:c}` of clang's range list, which is the span the diagnostic is really about. */
    private fun rangeOf(ranges: String?, lines: LineOffsets): TextRange? {
        if (ranges.isNullOrEmpty()) return null
        val m = RANGE.find(ranges) ?: return null
        val (sl, sc, el, ec) = m.destructured
        val start = lines.offsetOf(sl.toInt(), sc.toInt())
        val end = lines.offsetOf(el.toInt(), ec.toInt())
        return if (end > start) TextRange(start, end) else null
    }
}

/**
 * Line and column (both 1-based, as every compiler reports them) to an offset in the buffer.
 *
 * Built once per parse: a diagnostic list is walked in file order but not necessarily monotonically, and
 * re-scanning the text for each one is what makes a naive version quadratic on a file full of errors.
 */
class LineOffsets(private val text: CharSequence) {

    /** Offset of the first character of each line, by zero-based line number. */
    private val starts: IntArray = buildList {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }.toIntArray()

    /**
     * The 1-based line and column of [offset], which is how every compiler wants a position named.
     *
     * A binary search rather than a scan: completion asks for this on the caret offset of a file that may be
     * thousands of lines long, and it is on the path between a keystroke and a popup.
     */
    fun lineColOf(offset: Int): Pair<Int, Int> {
        val clamped = offset.coerceIn(0, text.length)
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= clamped) low = mid else high = mid - 1
        }
        return (low + 1) to (clamped - starts[low] + 1)
    }

    fun offsetOf(line: Int, column: Int): Int {
        val index = (line - 1).coerceIn(0, starts.size - 1)
        val lineStart = starts[index]
        val lineEnd = if (index + 1 < starts.size) starts[index + 1] - 1 else text.length
        return (lineStart + (column - 1).coerceAtLeast(0)).coerceIn(lineStart, lineEnd)
    }

    /**
     * The identifier at [line]:[column], or a single character when there is none.
     *
     * clang points at a position; an editor needs something to underline. Covering the token gives the
     * squiggle the shape of the thing that is wrong, and falling back to one character keeps a diagnostic on
     * a bracket or an operator visible rather than empty.
     */
    fun tokenAt(line: Int, column: Int): TextRange {
        val start = offsetOf(line, column)
        var end = start
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
        return if (end > start) TextRange(start, end)
        else TextRange(start, (start + 1).coerceAtMost(text.length))
    }
}
