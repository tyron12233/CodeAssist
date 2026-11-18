package dev.codeassist.csharp.editor.diagnostics


/** One diagnostic parsed from Roslyn compiler output. */
internal data class CsDiagnostic(
    val path: String,
    val line: Int,
    val column: Int,
    val severity: Severity,
    val code: String,
    val message: String,
) {
    enum class Severity {
        ERROR,
        WARNING,
    }
}

internal object CsDiagnostics {

    private val diagnosticLine = Regex(
        """^(.+)\((\d+),(\d+)\): (error|warning) (CS\d{4}): (.*)$""",
    )

    private val unusedUsingLine = Regex(
        """^(.+)\((\d+),(\d+)\): hidden CS8019: .*$""",
    )

    const val UNUSED_USING_CODE = "CS8019"

    /**
     * Parses a compiler error or warning.
     *
     * Lines without a source position, such as `error CS2007: Unrecognized option`,
     * do not match.
     */
    fun parse(line: String): CsDiagnostic? {
        val match = diagnosticLine.matchEntire(line) ?: return null
        val (path, lineNumber, columnNumber, severity, code, message) = match.destructured

        return CsDiagnostic(
            path = path,
            line = lineNumber.toInt(),
            column = columnNumber.toInt(),
            severity = if (severity == "error") {
                CsDiagnostic.Severity.ERROR
            } else {
                CsDiagnostic.Severity.WARNING
            },
            code = code,
            message = message,
        )
    }

    /**
     * Parses Roslyn's `hidden CS8019` output for unnecessary using directives.
     *
     * This format is handled separately because [parse] only accepts errors and warnings.
     * Its exact output format still needs device verification.
     */
    fun parseUnusedUsing(line: String): CsDiagnostic? {
        val match = unusedUsingLine.matchEntire(line) ?: return null
        val (path, lineNumber, columnNumber) = match.destructured

        return CsDiagnostic(
            path = path,
            line = lineNumber.toInt(),
            column = columnNumber.toInt(),
            severity = CsDiagnostic.Severity.WARNING,
            code = UNUSED_USING_CODE,
            message = "Unnecessary using directive",
        )
    }
}
