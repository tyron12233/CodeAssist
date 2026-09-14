package dev.ide.lang.kotlin.build

import dev.ide.build.BuildDiagnostic
import dev.ide.build.BuildSeverity
import dev.ide.build.DiagnosticKind
import dev.ide.build.DiagnosticLocation
import dev.ide.build.TaskContext
import dev.ide.build.engine.reportAll
import dev.ide.build.engine.toolOutput
import dev.ide.build.engine.transcript
import dev.ide.lang.kotlin.compile.IncrementalKotlinCompiler
import dev.ide.lang.kotlin.compile.KotlinCompileResult
import dev.ide.lang.kotlin.compile.KotlinDiagnostic
import dev.ide.lang.kotlin.compile.KotlinDiagnosticSeverity

/**
 * Surface a kotlinc compile's result in the build console: its problems as structured, navigable
 * diagnostics and its printed output as the (DEBUG) transcript behind them.
 *
 * kotlinc reports through a `MessageCollector` that already carries the severity, the path/line/column and
 * the offending source line, so nothing here is recovered by parsing — the flat `path:line:col: error: …`
 * lines the collector also records exist for the transcript, not for us to read back.
 *
 * The one exception is [toolOutput]'s text path, kept as a fallback for a result that carries messages but
 * no diagnostics: a forked compiler worker from an older build speaks only the flat lines (see
 * `KotlincWire`), and an error must still reach the user when the two halves are out of step.
 *
 * Shared by the two `compileKotlin` tasks (the plain JVM one and the Android variant).
 */
fun TaskContext.reportKotlinProblems(result: KotlinCompileResult) =
    reportKotlinProblems(result.diagnostics, result.messages)

fun TaskContext.reportKotlinProblems(result: IncrementalKotlinCompiler.Result) =
    reportKotlinProblems(result.diagnostics, result.messages)

private fun TaskContext.reportKotlinProblems(diagnostics: List<KotlinDiagnostic>, messages: List<String>) {
    if (diagnostics.isEmpty() && messages.isNotEmpty()) {
        toolOutput("kotlin", messages)
        return
    }
    reportAll(diagnostics.map { it.toBuildDiagnostic() })
    transcript(messages)
}

/** One line naming what failed, for `TaskResult.Failed`; the problems themselves are already reported. */
fun kotlinFailureSummary(result: KotlinCompileResult): String =
    kotlinFailureSummary(result.diagnostics, result.messages)

fun kotlinFailureSummary(result: IncrementalKotlinCompiler.Result): String =
    kotlinFailureSummary(result.diagnostics, result.messages)

private fun kotlinFailureSummary(diagnostics: List<KotlinDiagnostic>, messages: List<String>): String {
    val errors = diagnostics.filter { it.severity == KotlinDiagnosticSeverity.ERROR }
    return when {
        errors.size == 1 -> errors.first().message.trim()
        errors.size > 1 -> "Kotlin compilation failed with ${errors.size} errors"
        // No structured problems: fall back to the first error-looking message line (the older-worker case).
        else -> messages.firstOrNull { it.trimStart().startsWith("e", ignoreCase = true) }?.trim()
            ?: "Kotlin compilation failed"
    }
}

private fun KotlinDiagnostic.toBuildDiagnostic(): BuildDiagnostic = BuildDiagnostic(
    severity = when (severity) {
        KotlinDiagnosticSeverity.ERROR -> BuildSeverity.ERROR
        KotlinDiagnosticSeverity.WARNING -> BuildSeverity.WARNING
    },
    message = message.trim(),
    kind = DiagnosticKind.COMPILER,
    source = "kotlin",
    location = path?.let { DiagnosticLocation(it, line, column) },
    detail = snippet,
)
