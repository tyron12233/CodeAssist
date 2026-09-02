package dev.ide.core.actions

import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId
import dev.ide.plugin.action.ActionContext
import dev.ide.plugin.action.ActionEffect
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.CaretContext
import dev.ide.plugin.action.SimpleAction
import dev.ide.plugin.action.TextEdit
import dev.ide.plugin.action.UI_ACTION_EP

/**
 * Move the top-level declaration at the caret into its own file, named after it and placed beside the
 * original. The declaration is cut from the source file and written to the new one under the same package
 * with the same imports, and the new file is opened.
 *
 * This is the editor action that spans files, so it is the one that needs [ActionEffect.CreateFile]
 * alongside an edit to the file it came from. It is offered only when the declaration is not the one the
 * file is named after, since moving that one would leave the original file named after nothing.
 *
 * Unused imports are carried over rather than pruned. Removing them needs resolution, which the portable
 * action tier does not have; the unused-import inspection flags them in the new file, with its own fix.
 */
object ExtractFileAction {
    val PLUGIN = PluginId("editor-extract-file")

    fun register(extensions: ExtensionRegistry) {
        extensions.register(
            UI_ACTION_EP,
            SimpleAction(
                id = "editor.moveToNewFile",
                text = "Move to a New File",
                places = setOf(ActionPlaces.EDITOR, ActionPlaces.COMMAND_PALETTE),
                iconId = "file",
                order = 40,
                visible = { plan(it) != null },
            ) { ctx -> perform(ctx) },
            PLUGIN,
        )
    }

    private fun perform(ctx: ActionContext): ActionResult {
        val plan = plan(ctx) ?: return ActionResult.NONE
        return ActionResult(
            message = "Moved ${plan.name} to ${plan.newFileName}",
            effects = listOf(
                // Write the new file first: if creating it fails (the name is taken), the declaration is
                // still in its original file rather than deleted with nowhere to go.
                ActionEffect.CreateFile(plan.newPath, plan.newText, open = false),
                ActionEffect.ApplyEdits(listOf(TextEdit(plan.cutStart, plan.cutEnd - plan.cutStart, ""))),
                ActionEffect.OpenFile(plan.newPath),
            ),
        )
    }
}

/** Everything the action needs, computed once so listing and performing cannot disagree. */
private class Plan(
    val name: String,
    val newPath: String,
    val newFileName: String,
    val newText: String,
    val cutStart: Int,
    val cutEnd: Int,
)

private fun plan(ctx: ActionContext): Plan? {
    val caret = ctx.caret ?: return null
    val text = ctx.documentText ?: return null
    val path = ctx.activeFilePath ?: return null
    val extension = path.substringAfterLast('.', "")
    if (extension != "kt" && extension != "java") return null

    // The declaration must be top level: its only ancestor is the file itself.
    val declaration = topLevelDeclaration(caret) ?: return null
    val start = declaration.first.coerceIn(0, text.length)
    val end = declaration.second.coerceIn(start, text.length)
    val name = declaredName(text.substring(start, end)) ?: return null

    val fileName = path.substringAfterLast('/').substringBeforeLast('.')
    if (name == fileName) return null // this file is already named after it

    val dir = path.substringBeforeLast('/', "")
    if (dir.isEmpty()) return null
    val newFileName = "$name.$extension"
    val header = header(text, start, extension)
    val body = text.substring(start, end).trimEnd()
    return Plan(
        name = name,
        newPath = "$dir/$newFileName",
        newFileName = newFileName,
        newText = if (header.isEmpty()) "$body\n" else "$header\n$body\n",
        // Swallow the blank lines that separated it, so the source file does not keep a gap.
        cutStart = backOverBlankLines(text, start),
        cutEnd = forwardOverBlankLines(text, end),
    )
}

/**
 * `[start, end)` of the top-level declaration the caret is in, or null when it is not in one.
 *
 * A top-level declaration is one whose only ancestor is the compilation unit. The caret may be on the
 * declaration itself or anywhere inside it, so both the caret node and its ancestors are considered, and
 * the outermost declaration wins: with the caret in a nested class, the whole outer class moves, since
 * moving an inner class out on its own would not compile.
 */
private fun topLevelDeclaration(caret: CaretContext): Pair<Int, Int>? {
    val chain = caret.ancestors
    // The caret node itself, when the file is its only ancestor.
    if (isDeclaration(caret.nodeKind) && chain.size == 1 && isFileRoot(chain[0].kind)) {
        return caret.nodeStart to caret.nodeEnd
    }
    // Otherwise the ancestor that is a declaration and sits directly under the file root.
    for ((i, a) in chain.withIndex()) {
        if (!isDeclaration(a.kind)) continue
        val next = chain.getOrNull(i + 1) ?: continue
        if (isFileRoot(next.kind)) return a.start to a.end
    }
    return null
}

private fun isDeclaration(kind: String): Boolean =
    kind == "class_decl" || kind == "kt.object" || kind == "method_decl"

private fun isFileRoot(kind: String): Boolean = kind == "compilation_unit"

/** The declared name in [source]: the identifier after `class`, `interface`, `object`, `enum` or `fun`. */
private fun declaredName(source: String): String? {
    val match = Regex(
        """\b(?:class|interface|object|enum\s+class|enum|annotation\s+class|fun)\s+([A-Za-z_][\w]*)""",
    ).find(source) ?: return null
    return match.groupValues[1]
}

/** The package declaration and imports of [text], which the extracted declaration needs to compile. */
private fun header(text: String, before: Int, extension: String): String {
    val terminator = if (extension == "java") ";" else ""
    val lines = text.substring(0, before).lines()
    val header = lines.filter {
        val t = it.trim()
        t.startsWith("package ") || t.startsWith("import ")
    }
    if (header.isEmpty()) return ""
    return header.joinToString("\n") { it.trim().removeSuffix(";") + terminator } + "\n"
}

private fun backOverBlankLines(text: String, start: Int): Int {
    var i = start
    while (i > 0 && (text[i - 1] == '\n' || text[i - 1] == ' ' || text[i - 1] == '\t')) i--
    // Keep one newline so the previous declaration is not glued to what follows.
    return if (i > 0) i + 1 else i
}

private fun forwardOverBlankLines(text: String, end: Int): Int {
    var i = end
    while (i < text.length && (text[i] == '\n' || text[i] == ' ' || text[i] == '\t')) i++
    return i
}
