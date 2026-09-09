package dev.ide.build.engine

import dev.ide.build.BuildDiagnostic
import dev.ide.build.BuildSeverity
import dev.ide.build.DiagnosticKind
import dev.ide.build.DiagnosticLocation
import dev.ide.build.TaskContext

/**
 * Parse [messages] from a tool labelled [source] into structured diagnostics and stream them to
 * [TaskContext.diagnostics] in order — the standard way a task surfaces compiler/tool output as more than
 * a flat log. Each message string may itself be multi-line (it's split first).
 */
fun TaskContext.reportToolDiagnostics(
    source: String,
    messages: List<String>,
    kind: DiagnosticKind = DiagnosticKind.COMPILER,
) {
    if (messages.isEmpty()) return
    val parser = CompilerOutputParser(source, kind)
    val sink = diagnostics
    for (msg in messages) for (line in msg.lineSequence()) parser.accept(line) { sink.report(it) }
    parser.flush { sink.report(it) }
}

/**
 * Turns a tool's text output into structured [BuildDiagnostic]s, fed line by line so a task can stream
 * them as the tool prints them rather than parsing one blob at the end.
 *
 * Two shapes cover the native pipeline:
 *  - **GNU/javac/kotlinc/aapt2** single-line: `path:line[:col]: error|warning|note: message` — emitted
 *    the moment the line arrives.
 *  - **ecj batch** block (the desktop Java path): a `N. ERROR in <file> (at line L)` header, source +
 *    caret context lines, then the message text, closed by a `----------` rule — buffered until the
 *    block completes, then emitted.
 *
 * Anything it can't classify but that looks like an error/warning (a bare `error:`/`exception`) becomes a
 * located-less diagnostic so nothing is silently dropped; pure chatter is ignored (it still rides the raw
 * text log). [flush] emits any block left open when the stream ends.
 */
class CompilerOutputParser(
    private val source: String,
    private val kind: DiagnosticKind = DiagnosticKind.COMPILER,
) {
    private var block: EcjBlock? = null

    /** Feed one output line; [emit] is called for each completed diagnostic (zero, one, or — at a block
     *  boundary that also opens the next — handled across calls). */
    fun accept(line: String, emit: (BuildDiagnostic) -> Unit) {
        // Inside an ecj block: the rule line closes it; a new header closes-then-reopens.
        if (block != null) {
            when {
                line.trim() == RULE -> { block!!.toDiagnostic()?.let(emit); block = null }
                ECJ_HEADER.matches(line) -> { block!!.toDiagnostic()?.let(emit); block = EcjBlock.parse(line) }
                else -> block!!.body.append(line).append('\n')
            }
            return
        }
        ECJ_HEADER.matchEntire(line)?.let { block = EcjBlock.parse(line); return }

        val gnu = GNU.matchEntire(line)
        if (gnu != null) { emit(fromGnu(gnu, line)); return }

        // Un-located but clearly a problem (e.g. a kotlinc "error: ..." with no file, a tool summary).
        BARE.find(line)?.let { m ->
            emit(BuildDiagnostic(severityOf(m.groupValues[1]), line.trim(), kind, source, detail = line))
        }
    }

    /** Emit a block left open when the output ends without a trailing rule line. */
    fun flush(emit: (BuildDiagnostic) -> Unit) { block?.toDiagnostic()?.let(emit); block = null }

    private fun fromGnu(m: MatchResult, raw: String): BuildDiagnostic {
        val (path, lineStr, colStr, sevStr, msg) = m.destructured
        val line = lineStr.toIntOrNull() ?: -1
        val col = colStr.toIntOrNull() ?: -1
        return BuildDiagnostic(
            severity = severityOf(sevStr),
            message = msg.trim(),
            kind = kind,
            source = source,
            location = DiagnosticLocation(path, line, col),
            detail = raw,
        )
    }

    private fun severityOf(s: String): BuildSeverity = when (s.lowercase()) {
        "error" -> BuildSeverity.ERROR
        "warning" -> BuildSeverity.WARNING
        else -> BuildSeverity.INFO
    }

    /** A partially-collected ecj block: its header location + the body lines (source/caret/message). */
    private class EcjBlock(val severity: BuildSeverity, val path: String, val line: Int) {
        val body = StringBuilder()

        fun toDiagnostic(): BuildDiagnostic? {
            // The message is the body with the source-snippet + caret rows stripped (caret rows are only
            // '^' and whitespace; the row just above the caret is the snippet). Keep the rest as the message.
            val lines = body.toString().lines().map { it.trimEnd() }
            val caretIdx = lines.indexOfFirst { it.isNotBlank() && it.trim().all { c -> c == '^' } }
            val msgLines = (if (caretIdx >= 0) lines.drop(caretIdx + 1) else lines).filter { it.isNotBlank() }
            val message = msgLines.joinToString(" ").trim().ifEmpty { return null }
            val snippet = if (caretIdx >= 1) lines[caretIdx - 1].trim() else null
            return BuildDiagnostic(
                severity = severity,
                message = message,
                kind = DiagnosticKind.COMPILER,
                source = "ecj",
                location = DiagnosticLocation(path, line),
                detail = snippet,
            )
        }

        companion object {
            fun parse(header: String): EcjBlock? {
                val m = ECJ_HEADER.matchEntire(header) ?: return null
                val sev = if (m.groupValues[1].equals("ERROR", true)) BuildSeverity.ERROR else BuildSeverity.WARNING
                return EcjBlock(sev, m.groupValues[2].trim(), m.groupValues[3].toIntOrNull() ?: -1)
            }
        }
    }

    companion object {
        private const val RULE = "----------"

        // "/abs/Foo.java:12:7: error: message"  /  "/abs/Foo.kt:12: warning: message" (col optional)
        private val GNU = Regex("""^(.+?):(\d+)(?::(\d+))?:\s*(error|warning|note|info)s?:\s*(.*)$""", RegexOption.IGNORE_CASE)

        // "1. ERROR in /abs/Foo.java (at line 12)"
        private val ECJ_HEADER = Regex("""^\s*\d+\.\s*(ERROR|WARNING)\s+in\s+(.+?)\s+\(at line (\d+)\).*$""")

        // A located-less error/warning line we still want to surface.
        private val BARE = Regex("""\b(error|warning)\b\s*:""", RegexOption.IGNORE_CASE)
    }
}
