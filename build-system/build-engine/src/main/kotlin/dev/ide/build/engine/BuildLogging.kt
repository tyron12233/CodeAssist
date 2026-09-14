package dev.ide.build.engine

import dev.ide.build.BuildDiagnostic
import dev.ide.build.BuildLogEntry
import dev.ide.build.BuildLogLevel
import dev.ide.build.BuildSeverity
import dev.ide.build.DiagnosticKind
import dev.ide.build.TaskContext

/**
 * Level-aware logging for build tasks, and the one place a tool's raw output is made fit to read.
 *
 * The transcript a user sees is a *product*, not a dump. Two rules make it one:
 *
 *  1. **A task logs at the level the line deserves.** `ctx.logger()` is the legacy INFO-only channel; the
 *     [debug]/[info]/[warn]/[error] helpers below are how a task says what a line is worth. Engine
 *     bookkeeping (cache hits, bucket counts, per-file accounting) is [debug] — real, but nobody's business
 *     unless they asked. The console hides `DEBUG` until Verbose is switched on, so the default view is the
 *     build's shape plus its problems, nothing else.
 *  2. **A tool's output is humanized before anyone sees it.** [toolOutput] strips the Java stack frames a
 *     crashed tool prints around its one-line cause, collapses the duplicate copies tools echo, levels each
 *     surviving line by its `error:`/`warning:` prefix, and hands the cleaned lines to the structured
 *     diagnostics parser. A stack trace is never user-facing output; it is `DEBUG` detail for a bug report.
 *
 * D8/R8 already humanize at the tool boundary ([dev.ide.android.support.tools.DexDiagnostics], which adds
 * dex-specific rewrites on top); this is the generic layer every other tool gets for free.
 */

/** Engine bookkeeping: true, uninteresting, hidden unless the user asks for Verbose. */
fun TaskContext.debug(message: String) = buildLog.log(BuildLogEntry(message, BuildLogLevel.DEBUG))

/** Something the user would want to see in a normal build transcript. */
fun TaskContext.info(message: String) = buildLog.log(BuildLogEntry(message, BuildLogLevel.INFO))

/** A problem the build recovered from — the user should know, the build still succeeds. */
fun TaskContext.warn(message: String) = buildLog.log(BuildLogEntry(message, BuildLogLevel.WARN))

/** A problem that fails the build or a part of it. */
fun TaskContext.error(message: String) = buildLog.log(BuildLogEntry(message, BuildLogLevel.ERROR))

/**
 * Report a problem a tool handed us *structurally* — the preferred path, and the reason a task should reach
 * for its tool's diagnostic API before its printed output.
 *
 * Every in-process tool in the pipeline has one: kotlinc's `MessageCollector` (severity + path/line/column +
 * the offending source line), ecj's `ICompilerRequestor` (`CategorizedProblem`, carrying the problem id that
 * keys a quick fix), D8/R8's `DiagnosticsHandler` (origin + position), the manifest merger's typed records.
 * Printing those to text and regex-parsing them back loses the column, the code, the snippet, and the
 * severity — and lets any line containing the word "error" masquerade as a problem. So: report the
 * diagnostic, and let [toLogLine] render the transcript's copy of it, never the other way round.
 */
fun TaskContext.report(diagnostic: BuildDiagnostic) {
    diagnostics.report(diagnostic)
    buildLog.log(BuildLogEntry(diagnostic.toLogLine(), diagnostic.severity.asLogLevel()))
}

/** Report a batch of structured problems (see [report]). */
fun TaskContext.reportAll(diagnostics: List<BuildDiagnostic>) = diagnostics.forEach { report(it) }

/**
 * A tool's raw output, kept as the build's transcript but nothing more: humanized, then logged at `DEBUG`.
 *
 * Use this for the tool whose *problems* already arrived through [report] — the text is then only a record
 * of what the tool printed, available under Verbose and in a copied build report, and out of the way of the
 * user who just wants to know what is wrong with their code.
 */
fun TaskContext.transcript(lines: List<String>) {
    for (line in ToolLog.humanize(lines)) debug(line)
}

/** The console log level matching a diagnostic's severity. */
fun BuildSeverity.asLogLevel(): BuildLogLevel = when (this) {
    BuildSeverity.ERROR -> BuildLogLevel.ERROR
    BuildSeverity.WARNING -> BuildLogLevel.WARN
    BuildSeverity.INFO -> BuildLogLevel.INFO
}

/**
 * A diagnostic as one transcript line, in the compiler shape everyone can read at a glance:
 * `MainActivity.kt:42:9: error: Unresolved reference 'viewModle'`.
 *
 * Deliberately the file's *name*, not its path: the Problems list already groups by full path and is where
 * you go to navigate, while the log is read on a phone where an absolute path buries the message.
 */
fun BuildDiagnostic.toLogLine(): String {
    val severity = when (severity) {
        BuildSeverity.ERROR -> "error"
        BuildSeverity.WARNING -> "warning"
        BuildSeverity.INFO -> "note"
    }
    val where = location?.let { loc ->
        val name = loc.path.substringAfterLast('/').substringAfterLast('\\')
        when {
            loc.line > 0 && loc.column > 0 -> "$name:${loc.line}:${loc.column}: "
            loc.line > 0 -> "$name:${loc.line}: "
            else -> "$name: "
        }
    } ?: ""
    return "$where$severity: $message"
}

/**
 * Log a tool's captured output *and* parse it into structured diagnostics, in one pass over the humanized
 * lines — for the text-only tools, the ones that have no diagnostic API to ask: aapt2, apksigner,
 * bundletool, the AIDL compiler, and any tool run in a forked VM that reaches us as merged stderr. A tool
 * that *does* expose its problems should use [report]/[transcript] instead.
 *
 * Replaces the `r.log.forEach(ctx.logger()); ctx.reportToolDiagnostics(...)` pair, which logged every line
 * of a tool's output at INFO (stack frames included) and then parsed the *raw* text a second time. Here the
 * output is cleaned once ([ToolLog.humanize]), each surviving line is logged at the level its own prefix
 * declares ([ToolLog.levelOf] — chatter lands at `DEBUG`), and the same cleaned lines feed the parser, so
 * the Problems list and the log never disagree about what the tool said.
 */
fun TaskContext.toolOutput(
    source: String,
    lines: List<String>,
    kind: DiagnosticKind = DiagnosticKind.COMPILER,
) {
    if (lines.isEmpty()) return
    val clean = ToolLog.humanize(lines)
    for (line in clean) buildLog.log(BuildLogEntry(line, ToolLog.levelOf(line)))
    reportToolDiagnostics(source, clean, kind)
}

/** Text rules shared by every tool adapter: what to drop, what a line's severity is, how a failure reads. */
object ToolLog {

    /**
     * Turn a tool's raw output into lines worth showing.
     *
     * Drops the stack frames (`at …`, `… N more`) that surround a crashed tool's actual message; rewrites
     * the frames' headers (`Exception in thread "…" com.foo.Bar: message`, `Caused by: com.foo.Bar: message`)
     * into a plain `error: message`, keeping the cause and discarding the JVM ceremony; and collapses the
     * duplicate copies tools print when they report a failure both as a diagnostic and as a thrown exception.
     *
     * Idempotent, so a tool that already humanized its own output (the dexers) loses nothing by passing
     * through again.
     */
    fun humanize(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        val seenErrors = HashSet<String>()
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty()) { if (out.lastOrNull()?.isNotEmpty() == true) out.add(raw); continue }
            if (STACK_FRAME.matches(t) || MORE_FRAMES.matches(t)) continue
            val rewritten = rewriteThrowableHeader(t) ?: raw
            // A failure echoed twice (once as the tool's diagnostic, once as the exception it threw) is one
            // problem; keep the first copy of each distinct error line and drop the rest.
            if (levelOf(rewritten) == BuildLogLevel.ERROR && !seenErrors.add(rewritten.trim())) continue
            out.add(rewritten)
        }
        return out
    }

    /**
     * The severity a tool line declares about itself. Recognizes the prefixes the native pipeline actually
     * emits: kotlinc's `e:`/`w:`, the GNU/javac/aapt2 `error:`/`warning:` (bare or after a `file:line:col:`),
     * and ecj's `N. ERROR in …` block headers. Everything else is `DEBUG` — a tool's progress chatter is
     * not news, and the lines that *are* news all label themselves.
     */
    fun levelOf(line: String): BuildLogLevel {
        val t = line.trim()
        return when {
            ERROR_PREFIX.containsMatchIn(t) || ECJ_ERROR.containsMatchIn(t) -> BuildLogLevel.ERROR
            WARNING_PREFIX.containsMatchIn(t) || ECJ_WARNING.containsMatchIn(t) -> BuildLogLevel.WARN
            else -> BuildLogLevel.DEBUG
        }
    }

    /**
     * A one-line `TaskResult.Failed` summary for a tool whose problems already went out as diagnostics.
     *
     * The failure message is a *summary*, never the transcript: a task that hands back its whole compiler
     * output makes the engine print every error a second time as a wall of text, on top of the navigable
     * Problems the same output already produced. One error reads as itself; several read as a count, and
     * the Problems list holds the detail.
     */
    fun failureSummary(tool: String, lines: List<String>): String {
        val errors = humanize(lines).filter { levelOf(it) == BuildLogLevel.ERROR }
        return when (errors.size) {
            0 -> "$tool failed"
            1 -> errors.first().trim()
            else -> "$tool failed with ${errors.size} errors"
        }
    }

    /**
     * `Exception in thread "main" com.foo.Bar: boom` / `Caused by: com.foo.Bar: boom` → `error: boom`.
     *
     * The wrapped message often already carries the tool's own `error:` prefix (a `CompilationFailedException`
     * built from the diagnostic it printed a moment ago). Strip it before re-prefixing, or the rewrite yields
     * `error: error: …` — a string that no longer matches the earlier copy, so the de-duplication in
     * [humanize] misses it and the user reads the same failure twice.
     */
    private fun rewriteThrowableHeader(line: String): String? {
        val m = THROWABLE_HEADER.matchEntire(line) ?: return null
        val type = m.groupValues[1].substringAfterLast('.')
        val message = m.groupValues[2].trim()
            .removePrefix("error:").removePrefix("Error:").removePrefix("e:").trim()
        return if (message.isEmpty()) "error: $type" else "error: $message"
    }

    private val STACK_FRAME = Regex("""^at\s+\S.*""")
    private val MORE_FRAMES = Regex("""^\.\.\.\s+\d+\s+more$""")

    // "Exception in thread "main" java.lang.IllegalStateException: boom" / "Caused by: java.io.IOException: boom"
    private val THROWABLE_HEADER = Regex(
        """^(?:Exception in thread\s+"[^"]*"\s+|Caused by:\s+)([\w.$]+(?:Exception|Error|Throwable))(?::\s*(.*))?$"""
    )

    // A bare "error:" prefix, or one after a "file:line:col:" location; kotlinc's "e:" shorthand.
    private val ERROR_PREFIX = Regex("""^(e:|error:|.+?:\d+(?::\d+)?:\s*error:)""", RegexOption.IGNORE_CASE)
    private val WARNING_PREFIX = Regex("""^(w:|warning:|.+?:\d+(?::\d+)?:\s*warning:)""", RegexOption.IGNORE_CASE)
    private val ECJ_ERROR = Regex("""^\d+\.\s*ERROR\s+in\s+""")
    private val ECJ_WARNING = Regex("""^\d+\.\s*WARNING\s+in\s+""")
}
