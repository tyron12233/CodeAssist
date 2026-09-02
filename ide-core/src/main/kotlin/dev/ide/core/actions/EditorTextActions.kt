package dev.ide.core.actions

import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId
import dev.ide.plugin.action.ActionContext
import dev.ide.plugin.action.ActionEffect
import dev.ide.plugin.action.ActionGroup
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.SimpleAction
import dev.ide.plugin.action.SimpleGroup
import dev.ide.plugin.action.TextEdit
import dev.ide.plugin.action.ACTION_GROUP_EP
import dev.ide.plugin.action.UI_ACTION_EP

/**
 * Editor actions that work on lines rather than on syntax: comment toggling, moving a statement, sorting a
 * selection. They are contributed to [ActionPlaces.EDITOR] through the same extension point a plugin uses,
 * so they are also the reference for what that tier can do.
 *
 * Nothing here parses. Each action reads [ActionContext.documentText] and the selection, computes the
 * replacement, and returns it as edits, which is why one implementation covers every language. The one
 * language-specific detail is the line-comment token, taken from [ActionContext.caret]'s language id.
 */
object EditorTextActions {
    val PLUGIN = PluginId("ide-core-editor-actions")

    fun register(extensions: ExtensionRegistry) {
        val actions = listOf(
            SimpleAction(
                id = "editor.toggleComment",
                text = "Comment / Uncomment Lines",
                places = setOf(ActionPlaces.EDITOR, ActionPlaces.COMMAND_PALETTE),
                iconId = "code",
                order = 10,
                visible = { it.lineCommentToken() != null },
            ) { ctx -> toggleComment(ctx) },
            SimpleAction(
                id = "editor.moveStatementUp",
                text = "Move Line Up",
                places = setOf(ActionPlaces.EDITOR, ActionPlaces.COMMAND_PALETTE),
                iconId = "chevronUp",
                order = 20,
                visible = { it.hasEditorText() },
            ) { ctx -> moveLines(ctx, up = true) },
            SimpleAction(
                id = "editor.moveStatementDown",
                text = "Move Line Down",
                places = setOf(ActionPlaces.EDITOR, ActionPlaces.COMMAND_PALETTE),
                iconId = "chevronDown",
                order = 21,
                visible = { it.hasEditorText() },
            ) { ctx -> moveLines(ctx, up = false) },
            SimpleAction(
                id = "editor.sortLines",
                text = "Sort Lines",
                places = setOf(ActionPlaces.EDITOR, ActionPlaces.COMMAND_PALETTE),
                iconId = "braces",
                order = 30,
                // Only for a selection spanning more than one line: sorting one line does nothing.
                visible = { it.hasEditorText() && it.selectedLineCount() > 1 },
            ) { ctx -> sortLines(ctx) },
        )
        for (action in actions) extensions.register(UI_ACTION_EP, action, PLUGIN)

        // Nest them under one submenu so the editor's overflow menu stays short.
        extensions.register(
            ACTION_GROUP_EP,
            SimpleGroup(
                id = "editor.linesGroup",
                text = "Lines",
                places = setOf(ActionPlaces.EDITOR),
                iconId = "code",
                order = 10,
                children = listOf(
                    "editor.toggleComment",
                    ActionGroup.SEPARATOR,
                    "editor.moveStatementUp",
                    "editor.moveStatementDown",
                    "editor.sortLines",
                ),
            ),
            PLUGIN,
        )
    }
}

// ---------------------------------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------------------------------

/**
 * Prefix every selected line with the language's line-comment token, or strip it when all of them already
 * carry one (the usual toggle). The token goes at each line's own indent rather than at column zero, so a
 * commented block keeps its shape.
 */
private fun toggleComment(ctx: ActionContext): ActionResult {
    val text = ctx.documentText ?: return ActionResult.NONE
    val token = ctx.lineCommentToken() ?: return ActionResult.NONE
    val (first, last) = ctx.selectedLineBounds(text) ?: return ActionResult.NONE

    val lines = (first..last).map { lineRange(text, it) }
    val content = lines.map { text.substring(it.first, it.second) }
    // Blank lines do not decide the direction: a selection of code plus a blank line still uncomments.
    val meaningful = content.filter { it.isNotBlank() }
    if (meaningful.isEmpty()) return ActionResult.NONE
    val allCommented = meaningful.all { it.trimStart().startsWith(token) }

    val edits = ArrayList<TextEdit>()
    if (allCommented) {
        for ((i, line) in content.withIndex()) {
            if (line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            val at = lines[i].first + indent
            // Drop the token and one following space, which is what the commenting branch inserts.
            val len = token.length + if (line.startsWith("$token ", indent)) 1 else 0
            edits.add(TextEdit(at, len, ""))
        }
    } else {
        // Comment at the shallowest indent in the block so the tokens line up in a column.
        val column = meaningful.minOf { it.length - it.trimStart().length }
        for ((i, line) in content.withIndex()) {
            if (line.isBlank()) continue
            edits.add(TextEdit.insert(lines[i].first + column, "$token "))
        }
    }
    if (edits.isEmpty()) return ActionResult.NONE
    return ActionResult.effect(ActionEffect.ApplyEdits(edits))
}

/**
 * Swap the selected lines with the line above or below, keeping the selection on the moved text. Both
 * lines are rewritten as one edit, so the move is a single undo step.
 */
private fun moveLines(ctx: ActionContext, up: Boolean): ActionResult {
    val text = ctx.documentText ?: return ActionResult.NONE
    val (first, last) = ctx.selectedLineBounds(text) ?: return ActionResult.NONE
    if (up && first == 0) return ActionResult.NONE
    if (!up && last >= movableLineCount(text) - 1) return ActionResult.NONE

    val blockStart = lineRange(text, first).first
    val blockEnd = lineRange(text, last).second
    val block = text.substring(blockStart, blockEnd)
    return if (up) {
        val prev = lineRange(text, first - 1)
        val prevText = text.substring(prev.first, prev.second)
        val replacement = "$block\n$prevText"
        ActionResult.effect(
            ActionEffect.ApplyEdits(listOf(TextEdit.replace(prev.first, blockEnd, replacement))),
            ActionEffect.Select(prev.first, prev.first + block.length),
        )
    } else {
        val next = lineRange(text, last + 1)
        val nextText = text.substring(next.first, next.second)
        val replacement = "$nextText\n$block"
        val movedStart = blockStart + nextText.length + 1
        ActionResult.effect(
            ActionEffect.ApplyEdits(listOf(TextEdit.replace(blockStart, next.second, replacement))),
            ActionEffect.Select(movedStart, movedStart + block.length),
        )
    }
}

/** Sort the selected lines alphabetically, case-insensitively, leaving each line's own text untouched. */
private fun sortLines(ctx: ActionContext): ActionResult {
    val text = ctx.documentText ?: return ActionResult.NONE
    val (first, last) = ctx.selectedLineBounds(text) ?: return ActionResult.NONE
    if (last <= first) return ActionResult.NONE
    val start = lineRange(text, first).first
    val end = lineRange(text, last).second
    val lines = text.substring(start, end).split('\n')
    val sorted = lines.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.trim() })
    if (sorted == lines) return ActionResult.message("Lines are already sorted")
    val joined = sorted.joinToString("\n")
    return ActionResult.effect(
        ActionEffect.ApplyEdits(listOf(TextEdit.replace(start, end, joined))),
        ActionEffect.Select(start, start + joined.length),
    )
}

// ---------------------------------------------------------------------------------------------------
// Line helpers. Offsets are UTF-16 into the buffer; lines are 0-based and exclude their terminator.
// ---------------------------------------------------------------------------------------------------

private fun ActionContext.hasEditorText(): Boolean = !documentText.isNullOrEmpty() && selectionStart != null

/** The line-comment token for the caret's language, or null for a language that has none. */
internal fun ActionContext.lineCommentToken(): String? {
    if (!hasEditorText()) return null
    return when (caret?.languageId ?: activeFilePath?.substringAfterLast('.')) {
        "java", "kotlin", "kt", "groovy", "gradle", "aidl", "json5" -> "//"
        "properties", "pro", "toml", "sh", "gitignore" -> "#"
        else -> null
    }
}

/** The first and last line the selection touches, or null without a selection. */
private fun ActionContext.selectedLineBounds(text: String): Pair<Int, Int>? {
    val start = selectionStart ?: return null
    val end = selectionEnd ?: start
    val lo = lineForOffset(text, minOf(start, end))
    val hi = lineForOffset(text, maxOf(start, end))
    // A selection ending exactly at a line start belongs to the line above it, not the empty one after.
    val adjusted = if (hi > lo && maxOf(start, end) == lineRange(text, hi).first) hi - 1 else hi
    return lo to adjusted
}

private fun ActionContext.selectedLineCount(): Int {
    val text = documentText ?: return 0
    val (first, last) = selectedLineBounds(text) ?: return 0
    return last - first + 1
}

private fun lineForOffset(text: String, offset: Int): Int {
    val at = offset.coerceIn(0, text.length)
    var line = 0
    for (i in 0 until at) if (text[i] == '\n') line++
    return line
}

/**
 * How many lines can be moved. A file ending in a newline has an empty final line that is a terminator,
 * not content, so moving the last real line "down" past it has to be a no-op rather than inserting a blank.
 */
private fun movableLineCount(text: String): Int {
    val lines = text.count { it == '\n' } + 1
    return if (text.endsWith("\n")) lines - 1 else lines
}

/** `[start, end)` of [line]'s content, excluding its newline. */
private fun lineRange(text: String, line: Int): Pair<Int, Int> {
    var start = 0
    var seen = 0
    while (seen < line && start < text.length) {
        if (text[start] == '\n') seen++
        start++
    }
    var end = start
    while (end < text.length && text[end] != '\n') end++
    return start to end
}
