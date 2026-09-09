package dev.ide.lang.kotlin.analysis

import dev.ide.analysis.ActionProvider
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.EditorActionContext
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.kotlin.KotlinLanguageBackend
import dev.ide.lang.kotlin.parse.KotlinDomNode
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import com.intellij.psi.PsiElement

/**
 * "Extract function": move the selected statements into a new private function and call it in their place.
 *
 * Locals the selection reads but does not declare become parameters, so the extracted body compiles on its
 * own. A local the selection declares and the code after it still uses cannot be handed back without a
 * return value and a declaration at the call site, so that case is not offered rather than producing code
 * that does not compile.
 *
 * The generated name is `extracted`, deduplicated against the file. Nothing prompts for a name: the call
 * site and the declaration are both written, and renaming is a rename away.
 */
class KotlinExtractFunctionActionProvider : ActionProvider {
    override val languages = setOf(KotlinLanguageBackend.LANGUAGE_ID)

    override fun actions(ctx: EditorActionContext): List<QuickFix> {
        if (ctx.range.start == ctx.range.end) return emptyList() // needs a selection
        val selection = selectedStatements(ctx) ?: return emptyList()
        val owner = selection.owner
        val text = ctx.target.parsed.text()

        val declaredInside = selection.statements
            .flatMap { it.collectDescendantsOfType<KtProperty>() }
            .mapNotNull { it.name }
            .toSet()
        if (declaredInside.any { usedAfter(owner, selection.end, it) }) return emptyList()

        // Names the selection reads that it does not declare, in first-use order, restricted to locals and
        // parameters of the enclosing function: anything else (a member, a top-level function, an import)
        // is already in scope inside the new function.
        val visible = localsAndParametersBefore(owner, selection.start)
        val captured = LinkedHashSet<String>()
        for (statement in selection.statements) {
            for (ref in statement.collectDescendantsOfType<KtNameReferenceExpression>()) {
                val name = ref.getReferencedName()
                if (name in declaredInside) continue
                if (name in visible) captured.add(name)
            }
        }

        val fnName = freshFunctionName(text)
        val body = text.subSequence(selection.start, selection.end).toString()
        val callIndent = lineIndentOfText(text, selection.start)
        val declIndent = lineIndentOfText(text, owner.textRange.startOffset)
        val params = captured.joinToString(", ") { "$it: ${visible.getValue(it)}" }
        val args = captured.joinToString(", ")

        return listOf(object : QuickFix {
            override val title = "Extract function '$fnName'"
            override val kind = CodeActionKind.REFACTOR
            override suspend fun computeEdits(fixCtx: FixContext): WorkspaceEdit {
                val reindented = body.lines().joinToString("\n") { line ->
                    if (line.isBlank()) line else "$declIndent    " + line.removePrefix(callIndent)
                }
                val declaration = buildString {
                    append("\n\n")
                    append(declIndent).append("private fun $fnName($params) {\n")
                    append(reindented).append('\n')
                    append(declIndent).append('}')
                }
                return WorkspaceEdit(
                    mapOf(
                        fixCtx.target.file to listOf(
                            // The declaration goes after the enclosing function, at a HIGHER offset than
                            // the call it replaces, so applying descending by offset keeps both correct.
                            DocumentEdit(owner.textRange.endOffset, 0, declaration),
                            DocumentEdit(selection.start, selection.end - selection.start, "$fnName($args)"),
                        ),
                    ),
                )
            }
        })
    }

    /** The statements the selection covers, and the function that holds them. */
    private class Selection(
        val owner: KtDeclarationWithBody,
        val statements: List<KtExpression>,
        val start: Int,
        val end: Int,
    )

    /**
     * The whole statements the selection touches, widened to their boundaries so a partial selection still
     * extracts something that compiles. Null when the selection is not inside one function's block body.
     */
    private fun selectedStatements(ctx: EditorActionContext): Selection? {
        val psi = (ctx.node as? KotlinDomNode)?.psi ?: return null
        var n: PsiElement? = psi
        var block: KtBlockExpression? = null
        while (n != null) {
            if (n is KtBlockExpression) { block = n; break }
            n = n.parent
        }
        val body = block ?: return null
        val owner = body.parent as? KtDeclarationWithBody ?: return null
        if (owner !is KtNamedFunction) return null // a lambda or accessor body: the call site differs

        val covered = body.statements.filter {
            it.textRange.endOffset > ctx.range.start && it.textRange.startOffset < ctx.range.end
        }
        if (covered.isEmpty()) return null
        return Selection(
            owner = owner,
            statements = covered,
            start = covered.first().textRange.startOffset,
            end = covered.last().textRange.endOffset,
        )
    }

    /** Locals declared and parameters visible before [offset] in [owner], mapped to a written type. */
    private fun localsAndParametersBefore(
        owner: KtDeclarationWithBody,
        offset: Int,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (parameter in owner.valueParameters) {
            val name = parameter.name ?: continue
            out[name] = parameter.typeReference?.text ?: continue
        }
        val body = owner.bodyExpression as? KtBlockExpression ?: return out
        for (property in body.collectDescendantsOfType<KtProperty>()) {
            if (property.textRange.endOffset > offset) continue
            val name = property.name ?: continue
            // Without a written type there is nothing to put in the parameter list, and inferring it here
            // would need the resolver on the listing path. Such a local is simply not offered as a capture.
            val type = property.typeReference?.text ?: continue
            out[name] = type
        }
        return out
    }

    /** True when [name] is referenced after [offset] inside [owner], so extracting its declaration breaks it. */
    private fun usedAfter(owner: KtDeclarationWithBody, offset: Int, name: String): Boolean =
        owner.collectDescendantsOfType<KtNameReferenceExpression>()
            .any { it.textRange.startOffset >= offset && it.getReferencedName() == name }

    private fun freshFunctionName(text: CharSequence): String {
        var name = "extracted"
        var i = 1
        while (Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(text)) {
            name = "extracted$i"
            i++
            if (i > 99) break
        }
        return name
    }
}

/** The leading whitespace of the line containing [offset]. */
private fun lineIndentOfText(text: CharSequence, offset: Int): String {
    var start = offset.coerceIn(0, text.length)
    while (start > 0 && text[start - 1] != '\n') start--
    val sb = StringBuilder()
    var i = start
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
        sb.append(text[i])
        i++
    }
    return sb.toString()
}
