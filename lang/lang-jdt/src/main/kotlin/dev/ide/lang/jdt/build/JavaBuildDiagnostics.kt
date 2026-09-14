package dev.ide.lang.jdt.build

import dev.ide.build.BuildDiagnostic
import dev.ide.build.BuildSeverity
import dev.ide.build.DiagnosticKind
import dev.ide.build.DiagnosticLocation
import dev.ide.build.TaskContext
import dev.ide.build.engine.reportAll
import dev.ide.build.engine.transcript
import dev.ide.lang.jdt.compile.JdtBatchCompiler

/**
 * Surface an ecj compile's result in the build console: its problems as structured, navigable diagnostics
 * and its printed report as the (DEBUG) transcript behind them.
 *
 * ecj hands every problem to an `ICompilerRequestor` as a `CategorizedProblem` — file, line, column, the
 * problem id, the offending source line. That is what a user sees here. The compiler's *printed* report is
 * a rendering of the same problems with the column and id thrown away, so it is kept only as the record of
 * what the tool said, never as the thing we parse to find out what went wrong.
 *
 * Shared by the two `compileJava` tasks (the plain JVM one and the Android variant), which differ in how
 * they assemble the classpath, not in how a Java error should read.
 */
fun TaskContext.reportJavaProblems(result: JdtBatchCompiler.Result) {
    reportAll(result.diagnostics.map { it.toBuildDiagnostic() })
    transcript(result.messages)
}

/** One line naming what failed, for `TaskResult.Failed`; the problems themselves are already reported. */
fun javaFailureSummary(result: JdtBatchCompiler.Result): String {
    val errors = result.diagnostics.filter { it.isError }
    return when (errors.size) {
        0 -> "Java compilation failed"
        1 -> errors.first().message.trim()
        else -> "Java compilation failed with ${errors.size} errors"
    }
}

private fun JdtBatchCompiler.Diagnostic.toBuildDiagnostic(): BuildDiagnostic = BuildDiagnostic(
    severity = if (isError) BuildSeverity.ERROR else BuildSeverity.WARNING,
    message = message.trim(),
    kind = DiagnosticKind.COMPILER,
    source = "java",
    location = file?.let { DiagnosticLocation(it, line ?: -1, column) },
    code = code,
    detail = snippet,
)
