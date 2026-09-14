package dev.ide.buildcli

import dev.ide.core.headless.HeadlessBuildResult
import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.UiSeverity
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The GitHub Actions side of the launcher: workflow commands on stdout, and the files the runner hands a
 * step through the environment.
 *
 * Annotations are the point of it. A compiler or aapt2 error carries a file, a line and a column, and a
 * `::error file=…,line=…::` line turns that into a marker on the pull request's diff — so a failed build
 * reads like a review comment rather than a wall of log. Everything here is inert when [enabled] is false
 * (a plain terminal run), and each sink is skipped when the runner did not provide it.
 *
 * See https://docs.github.com/actions/reference/workflow-commands-for-github-actions.
 */
internal class GitHubActions(private val enabled: Boolean, private val projectRoot: Path) {

    /** `$GITHUB_WORKSPACE` — annotation paths must be relative to the checkout, not to the project. */
    private val workspace: Path? = System.getenv("GITHUB_WORKSPACE")
        ?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }

    private var openGroup = false

    fun beginLogGroup(title: String) {
        if (!enabled) return
        println("::group::$title")
        openGroup = true
    }

    fun endLogGroup() {
        if (!enabled || !openGroup) return
        println("::endgroup::")
        openGroup = false
    }

    /**
     * Turn the build's structured diagnostics into annotations. GitHub renders at most 10 of each level per
     * step, so the ones with a file position go first: an annotation that lands on a line is worth more
     * than one that lands on the job. Warnings follow errors for the same reason.
     */
    fun annotate(diagnostics: List<BuildDiagnosticUi>) {
        if (!enabled) return
        val ranked = diagnostics
            .filter { it.severity == UiSeverity.Error || it.severity == UiSeverity.Warning }
            .sortedWith(compareBy({ it.severity != UiSeverity.Error }, { it.file == null }))
        for (d in ranked.take(MAX_ANNOTATIONS)) {
            val command = if (d.severity == UiSeverity.Error) "error" else "warning"
            val properties = buildList {
                annotationPath(d.file)?.let { add("file=${escapeProperty(it)}") }
                if (d.line > 0) {
                    add("line=${d.line}")
                    if (d.column > 0) add("col=${d.column}")
                }
                d.source.takeIf { it.isNotBlank() }?.let { add("title=${escapeProperty(it)}") }
            }
            val head = if (properties.isEmpty()) "::$command::" else "::$command ${properties.joinToString(",")}::"
            println(head + escapeData(d.message))
        }
    }

    /** A job-level error (no file position) — used when the build never got as far as producing one. */
    fun error(message: String) {
        if (!enabled) return
        println("::error::${escapeData(message)}")
    }

    /** One diagnostic as a single terminal line: `path:line:col: message`. */
    fun describe(d: BuildDiagnosticUi): String {
        val where = annotationPath(d.file) ?: d.file
        val position = listOfNotNull(where, d.line.takeIf { it > 0 }, d.column.takeIf { it > 0 })
            .joinToString(":")
        return if (position.isEmpty()) d.message else "$position: ${d.message}"
    }

    /** Append a `key=value` to `$GITHUB_OUTPUT`, so later steps can read `steps.<id>.outputs.<key>`. */
    fun output(key: String, value: String) {
        if (value.isEmpty()) return
        appendTo("GITHUB_OUTPUT", "$key=${value.lineSequence().first()}\n")
    }

    /** The multi-line form of [output], which needs a delimiter the value cannot contain. */
    fun outputList(key: String, values: List<String>) {
        if (values.isEmpty()) return
        val delimiter = "ghadelimiter_${java.util.UUID.randomUUID()}"
        appendTo("GITHUB_OUTPUT", "$key<<$delimiter\n${values.joinToString("\n")}\n$delimiter\n")
    }

    /** Markdown appended to the job summary shown at the top of the run page. */
    fun summary(markdown: String) {
        appendTo("GITHUB_STEP_SUMMARY", markdown.trimEnd() + "\n\n")
    }

    /** The job summary for a finished build: the verdict, what ran, and the errors that stopped it. */
    fun summarize(result: HeadlessBuildResult, errors: Int, warnings: Int) = summary(buildString {
        val verdict = when {
            result.succeeded -> "\u2705 Build succeeded"
            result.timedOut -> "\u23f1\ufe0f Build timed out"
            else -> "\u274c Build failed"
        }
        appendLine("## $verdict")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Task | `${result.taskId}` |")
        if (result.moduleName.isNotBlank()) appendLine("| Module | `${result.moduleName}` |")
        appendLine("| Time | ${"%.1f".format(result.elapsedMs / 1000.0)}s |")
        appendLine("| Diagnostics | $errors error(s), $warnings warning(s) |")
        result.outputs.forEach { appendLine("| Output | `${it.fileName}` |") }
        val shown = result.diagnostics.filter { it.severity == UiSeverity.Error }.take(MAX_SUMMARY_ERRORS)
        if (shown.isNotEmpty()) {
            appendLine()
            appendLine("### Errors")
            appendLine()
            shown.forEach { appendLine("- ${describe(it)}") }
            if (errors > shown.size) appendLine("- \u2026 and ${errors - shown.size} more")
        }
    })

    /**
     * A diagnostic's file as the runner needs to see it: relative to the checkout. A path outside it (a
     * cached dependency, a generated source under another root) keeps its absolute form, which GitHub shows
     * in the log without trying to anchor it in the diff.
     */
    private fun annotationPath(file: String?): String? {
        if (file.isNullOrBlank()) return null
        val path = runCatching { real(Path.of(file)) }.getOrNull() ?: return file
        val relative = runCatching { real(workspace ?: projectRoot).relativize(path).toString() }.getOrNull()
            ?: return file
        return if (relative.isEmpty() || relative.startsWith("..")) file else relative
    }

    /**
     * A path with every symlink resolved, so the two sides of a [relativize] are comparable. The build
     * tools do not agree on this: ecj reports the real path while the Kotlin backend reports the one it was
     * handed, and on a Mac `/tmp` is a link to `/private/tmp` — enough for a relativize to walk out of the
     * workspace and lose the annotation. Falls back to the plain absolute form for a path that is gone.
     */
    private fun real(path: Path): Path =
        runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }

    private fun appendTo(envVar: String, text: String) {
        if (!enabled) return
        val target = System.getenv(envVar)?.let { runCatching { Path.of(it) }.getOrNull() } ?: return
        runCatching {
            Files.writeString(target, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    private companion object {
        /** GitHub renders 10 annotations per level per step; a few spare cover the mix of levels. */
        const val MAX_ANNOTATIONS = 24

        /** The summary is a digest, not the log: enough errors to see the shape of the failure. */
        const val MAX_SUMMARY_ERRORS = 20

        fun escapeData(value: String): String =
            value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")

        fun escapeProperty(value: String): String =
            escapeData(value).replace(":", "%3A").replace(",", "%2C")
    }
}

/** The `--report` file: the build's outcome as JSON, for a step that wants to act on it. */
internal object JsonReport {
    fun of(result: HeadlessBuildResult, projectRoot: Path): String = buildString {
        appendLine("{")
        appendLine("""  "status": "${if (result.succeeded) "success" else "failed"}",""")
        appendLine("""  "timedOut": ${result.timedOut},""")
        appendLine("""  "task": ${quote(result.taskId)},""")
        appendLine("""  "module": ${quote(result.moduleName)},""")
        appendLine("""  "project": ${quote(projectRoot.toString())},""")
        appendLine("""  "elapsedMs": ${result.elapsedMs},""")
        appendLine("""  "outputs": [${result.outputs.joinToString(", ") { quote(it.toString()) }}],""")
        appendLine("""  "diagnostics": [""")
        val diagnostics = result.diagnostics.filter { it.severity == UiSeverity.Error || it.severity == UiSeverity.Warning }
        diagnostics.forEachIndexed { index, d ->
            val comma = if (index == diagnostics.lastIndex) "" else ","
            append("    {")
            append(""""severity": ${quote(d.severity.name.lowercase())}, """)
            append(""""source": ${quote(d.source)}, """)
            append(""""file": ${quote(d.file ?: "")}, """)
            append(""""line": ${d.line}, """)
            append(""""column": ${d.column}, """)
            append(""""message": ${quote(d.message)}""")
            appendLine("}$comma")
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
