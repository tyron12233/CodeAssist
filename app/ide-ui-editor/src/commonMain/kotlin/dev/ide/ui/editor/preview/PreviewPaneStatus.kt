package dev.ide.ui.editor.preview

/**
 * Where a preview pane has got to, for a caller that renders one with nobody watching it (the agent's headless
 * preview): whether it has [settled] on what it will show, what that is ([label]), and what stops it rendering
 * or is wrong with the render ([problems], empty when it rendered cleanly). [failed] means there is nothing to
 * capture: the problems say why.
 */
data class PreviewPaneStatus(
    val settled: Boolean,
    val label: String? = null,
    val problems: List<String> = emptyList(),
    val failed: Boolean = false,
)

/** A pane's issue as one line of text for a status report; a long detail (a stack trace) is cut. */
internal fun PreviewIssue.describe(): String {
    val detail = message.trim().take(MAX_ISSUE_DETAIL)
    return if (detail.isEmpty()) title else "$title: $detail"
}

private const val MAX_ISSUE_DETAIL = 800
