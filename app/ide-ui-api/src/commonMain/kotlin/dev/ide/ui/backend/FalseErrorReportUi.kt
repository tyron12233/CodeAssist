package dev.ide.ui.backend

/**
 * The editor action behind "Report a false error" on a diagnostic. It travels the same way a plugin action
 * does (an action id handed to the editor's host), with the diagnostic it is about packed into the id, so the
 * host needs nothing but the id and the range to build the report.
 */
object FalseErrorReportAction {
    private const val PREFIX = "ca.reportFalseError:"

    fun actionId(d: UiDiagnostic): String = PREFIX + d.severity.name + ":" + d.message

    /** The severity and message packed into [actionId], or null when it is some other action. */
    fun parse(actionId: String): Pair<UiSeverity, String>? {
        if (!actionId.startsWith(PREFIX)) return null
        val rest = actionId.removePrefix(PREFIX)
        val sep = rest.indexOf(':').takeIf { it >= 0 } ?: return null
        val severity = UiSeverity.entries.firstOrNull { it.name == rest.substring(0, sep) } ?: return null
        return severity to rest.substring(sep + 1)
    }

    /** A problem is worth reporting as false when it claims the code is wrong, not when it is a suggestion. */
    fun offeredFor(d: UiDiagnostic): Boolean = d.severity == UiSeverity.Error || d.severity == UiSeverity.Warning
}
