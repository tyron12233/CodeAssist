package dev.ide.lang.kotlin.analysis

import dev.ide.analysis.ActionProvider
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.EditorActionContext
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.dom.DomNode
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.kotlin.KotlinLanguageBackend
import dev.ide.lang.kotlin.KotlinSourceAnalyzer
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.resolve.TypeRef
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import com.intellij.psi.PsiElement

/**
 * The Kotlin caret intentions: the refactorings and rewrites offered at a position with no diagnostic.
 * Registered on `platform.actionProvider` beside [KotlinImportActionProvider], which covers the
 * diagnostic-driven half.
 *
 * These are Kotlin-specific by nature, so they read the backend's own PSI through [KotlinDomNode.psi]
 * rather than only the neutral DOM: the neutral kinds collapse `if`, `for`, `while`, `return` and the rest
 * into one `kt.element`, which is not enough to tell an `if` body from a lambda. Everything a
 * cross-language feature consumes still travels as neutral [QuickFix]es and [DocumentEdit]s.
 *
 * Applicability is decided on the structure alone, so listing stays off the resolver. The two intentions
 * that need a type ([KotlinIntroduceVariableActionProvider], [KotlinExplicitTypeActionProvider]) resolve it
 * inside `computeEdits`, which runs only for the intention the user picked.
 */
private val KOTLIN = KotlinLanguageBackend.LANGUAGE_ID

// ---------------------------------------------------------------------------------------------------
// Surround with
// ---------------------------------------------------------------------------------------------------

/**
 * Wrap the selection (or the statement at the caret) in `if`, `try/catch`, `run` or `let`. The wrapped
 * text keeps its relative indentation and gains one level, and the caret lands where the user has to type:
 * the `if` condition, or the `catch` body.
 */
class KotlinSurroundActionProvider : ActionProvider {
    override val languages = setOf(KOTLIN)

    override fun actions(ctx: EditorActionContext): List<QuickFix> {
        val target = surroundTarget(ctx) ?: return emptyList()
        val text = ctx.target.parsed.text()
        val indent = lineIndentAt(text, target.first)
        val body = text.subSequence(target.first, target.second).toString()
        if (body.isBlank()) return emptyList()
        val inner = "$indent    "
        val reindented = body.replace("\n", "\n    ")

        fun wrap(title: String, render: () -> String) = object : QuickFix {
            override val title = title
            override val kind = CodeActionKind.REFACTOR
            override suspend fun computeEdits(ctx2: FixContext): WorkspaceEdit =
                WorkspaceEdit.of(
                    ctx2.target.file,
                    DocumentEdit(target.first, target.second - target.first, render()),
                )
        }

        return listOf(
            wrap("Surround with 'if'") {
                buildString {
                    append("if (true) {\n")
                    append(inner).append(reindented).append('\n')
                    append(indent).append('}')
                }
            },
            wrap("Surround with 'try/catch'") {
                buildString {
                    append("try {\n")
                    append(inner).append(reindented).append('\n')
                    append(indent).append("} catch (e: Exception) {\n")
                    append(inner).append("throw e\n")
                    append(indent).append('}')
                }
            },
            wrap("Surround with 'run'") {
                buildString {
                    append("run {\n")
                    append(inner).append(reindented).append('\n')
                    append(indent).append('}')
                }
            },
        )
    }

    /** `[start, end)` of the text to wrap: the selection, widened to whole statements, else the statement. */
    private fun surroundTarget(ctx: EditorActionContext): Pair<Int, Int>? {
        if (ctx.range.start != ctx.range.end) {
            val stmt = ctx.enclosingStatement() ?: return null
            // A selection inside one statement wraps that statement, not a fragment of it.
            return if (stmt.range.start <= ctx.range.start && stmt.range.end >= ctx.range.end) {
                stmt.range.start to stmt.range.end
            } else {
                ctx.range.start to ctx.range.end
            }
        }
        val stmt = ctx.enclosingStatement() ?: return null
        return stmt.range.start to stmt.range.end
    }
}

// ---------------------------------------------------------------------------------------------------
// Introduce local variable
// ---------------------------------------------------------------------------------------------------

/**
 * Extract the expression at the caret (or the selected expression) into a `val <name> = <expr>` declared
 * above the enclosing statement, replacing the expression with the name. The name is derived from the
 * expression and deduplicated against the file; the type is left inferred, which is idiomatic Kotlin.
 */
class KotlinIntroduceVariableActionProvider : ActionProvider {
    override val languages = setOf(KOTLIN)

    override fun actions(ctx: EditorActionContext): List<QuickFix> {
        val expr = expressionToExtract(ctx) ?: return emptyList()
        val stmt = ctx.enclosingStatement() ?: return emptyList()
        if (expr.range == stmt.range) return emptyList() // the statement IS the expression; nothing to name
        val text = ctx.target.parsed.text()
        val exprText = text.subSequence(expr.range.start, expr.range.end).toString()
        if (exprText.isBlank()) return emptyList()
        val indent = lineIndentAt(text, stmt.range.start)
        val name = freshName(exprText, text)
        return listOf(object : QuickFix {
            override val title = "Introduce local variable '$name'"
            override val kind = CodeActionKind.REFACTOR
            override suspend fun computeEdits(ctx2: FixContext): WorkspaceEdit = WorkspaceEdit(
                mapOf(
                    ctx2.target.file to listOf(
                        DocumentEdit(expr.range.start, expr.range.end - expr.range.start, name),
                        DocumentEdit(stmt.range.start, 0, "val $name = $exprText\n$indent"),
                    ),
                ),
            )
        })
    }

    private fun expressionToExtract(ctx: EditorActionContext): DomNode? {
        val psi = ctx.node.psiOrNull() ?: return null
        if (ctx.range.start != ctx.range.end) {
            // With a selection: the smallest expression covering it.
            var n: PsiElement? = psi
            while (n != null) {
                if (n is KtExpression && n.textRange.startOffset <= ctx.range.start &&
                    n.textRange.endOffset >= ctx.range.end && isExtractable(n)
                ) return ctx.nodeFor(n)
                n = n.parent
            }
            return null
        }
        var n: PsiElement? = psi
        while (n != null) {
            // Part of a qualified chain (`"a".trim()`): climb to the whole chain, since extracting
            // either the receiver or the selector alone leaves a fragment that does not compile.
            if (n is KtExpression && isExtractable(n) && n.parent !is KtQualifiedExpression) return ctx.nodeFor(n)
            if (n is KtBlockExpression) return null
            n = n.parent
        }
        return null
    }

    /** Expressions worth naming: calls and operators, not a bare name, literal, or block-ish shape. */
    private fun isExtractable(e: KtExpression): Boolean = when (e) {
        is KtBlockExpression, is KtIfExpression, is KtForExpression, is KtWhileExpression,
        is KtDoWhileExpression, is KtReturnExpression,
        -> false
        else -> e !is org.jetbrains.kotlin.psi.KtNameReferenceExpression &&
            e !is org.jetbrains.kotlin.psi.KtConstantExpression &&
            e.textRange.length > 0
    }

}

// ---------------------------------------------------------------------------------------------------
// Implement members
// ---------------------------------------------------------------------------------------------------

/**
 * Generate `override` stubs for the abstract members a class has not implemented, offered anywhere inside
 * the class rather than only on the "not implemented" error. [KotlinImplementMembersFixProvider] covers the
 * diagnostic; this covers the case where the user goes looking for it.
 */
class KotlinImplementMembersActionProvider : ActionProvider {
    override val languages = setOf(KOTLIN)

    override fun actions(ctx: EditorActionContext): List<QuickFix> {
        // Structural gate first: only inside a class body, so the resolver is not consulted per caret move.
        if (ctx.nearestClass() == null) return emptyList()
        val analyzer = ctx.target.resolver as? KotlinSourceAnalyzer ?: return emptyList()
        val fix = analyzer.implementMembersFix(ctx.target.file, ctx.range.start) ?: return emptyList()
        return listOf(object : QuickFix {
            override val title = fix.title
            override val kind = CodeActionKind.REFACTOR
            override suspend fun computeEdits(ctx2: FixContext): WorkspaceEdit =
                WorkspaceEdit.of(ctx2.target.file, *fix.edits.toTypedArray())
        })
    }
}

// ---------------------------------------------------------------------------------------------------
// Function body form
// ---------------------------------------------------------------------------------------------------

/**
 * Convert a function between an expression body and a block body: `fun f() = expr` to
 * `fun f() { return expr }` and back. Only offered on the form that can convert, and only when the block
 * body is a single `return` (anything else has no expression-body equivalent).
 */
class KotlinFunctionBodyActionProvider : ActionProvider {
    override val languages = setOf(KOTLIN)

    override fun actions(ctx: EditorActionContext): List<QuickFix> {
        val fn = ctx.nearestFunction() ?: return emptyList()
        // Only from the signature, not from deep inside the body: an intention offered on every line of
        // every function is noise.
        val bodyStart = fn.bodyExpression?.textRange?.startOffset ?: return emptyList()
        if (ctx.range.start > bodyStart) return emptyList()
        val text = ctx.target.parsed.text()

        val body = fn.bodyExpression
        if (body is KtBlockExpression) {
            val ret = body.statements.singleOrNull() as? KtReturnExpression ?: return emptyList()
            val value = ret.returnedExpression ?: return emptyList()
            val valueText = text.subSequence(value.textRange.startOffset, value.textRange.endOffset).toString()
            return listOf(object : QuickFix {
                override val title = "Convert to expression body"
                override val kind = CodeActionKind.REFACTOR
                override suspend fun computeEdits(ctx2: FixContext): WorkspaceEdit = WorkspaceEdit.of(
                    ctx2.target.file,
                    DocumentEdit(
                        body.textRange.startOffset,
                        body.textRange.length,
                        "= $valueText",
                    ),
                )
            })
        }
        if (body != null) {
            val indent = lineIndentAt(text, fn.textRange.startOffset)
            val exprText = text.subSequence(body.textRange.startOffset, body.textRange.endOffset).toString()
            // Replace from the `=` so the assignment token goes away with the expression form.
            val eq = fn.equalsToken?.textRange?.startOffset ?: return emptyList()
            return listOf(object : QuickFix {
                override val title = "Convert to block body"
                override val kind = CodeActionKind.REFACTOR
                override suspend fun computeEdits(ctx2: FixContext): WorkspaceEdit = WorkspaceEdit.of(
                    ctx2.target.file,
                    DocumentEdit(
                        eq,
                        body.textRange.endOffset - eq,
                        "{\n$indent    return $exprText\n$indent}",
                    ),
                )
            })
        }
        return emptyList()
    }
}

// ---------------------------------------------------------------------------------------------------
// Braces
// ---------------------------------------------------------------------------------------------------

/**
 * Add braces around a single-statement `if`, `else`, `for`, `while` or `do` body, or remove them again
 * when the block holds exactly one statement. Keyed on the branch the caret is in, so an `if/else` offers
 * the action for the branch being edited rather than both.
 */
class KotlinBracesActionProvider : ActionProvider {
    override val languages = setOf(KOTLIN)

    override fun actions(ctx: EditorActionContext): List<QuickFix> {
        val branch = enclosingBranchBody(ctx) ?: return emptyList()
        val body = branch.body
        val text = ctx.target.parsed.text()
        val label = branch.label
        if (body is KtBlockExpression) {
            val only = body.statements.singleOrNull() ?: return emptyList()
            val onlyText = text.subSequence(only.textRange.startOffset, only.textRange.endOffset).toString()
            if ('\n' in onlyText) return emptyList() // a multi-line statement reads worse unbraced
            return listOf(object : QuickFix {
                override val title = "Remove braces from '$label'"
                override val kind = CodeActionKind.REFACTOR
                override suspend fun computeEdits(ctx2: FixContext): WorkspaceEdit = WorkspaceEdit.of(
                    ctx2.target.file,
                    DocumentEdit(body.textRange.startOffset, body.textRange.length, onlyText),
                )
            })
        }
        val indent = lineIndentAt(text, statementStartOf(body, text))
        val bodyText = text.subSequence(body.textRange.startOffset, body.textRange.endOffset).toString()
        return listOf(object : QuickFix {
            override val title = "Add braces to '$label'"
            override val kind = CodeActionKind.REFACTOR
            override suspend fun computeEdits(ctx2: FixContext): WorkspaceEdit = WorkspaceEdit.of(
                ctx2.target.file,
                DocumentEdit(
                    body.textRange.startOffset,
                    body.textRange.length,
                    "{\n$indent    $bodyText\n$indent}",
                ),
            )
        })
    }

    /**
     * The `if`/`else`/loop body the caret sits in, with the keyword to name it by, or null when the caret
     * is not in one.
     *
     * Kotlin wraps a control-structure body in a `KtContainerNodeForControlStructureBody`, so the body is
     * never a direct child of the `if` or the loop. The branch is therefore identified by which of the
     * owner's bodies covers the caret, not by matching the caret node's parent.
     */
    private fun enclosingBranchBody(ctx: EditorActionContext): Branch? {
        val offset = ctx.range.start
        fun covers(e: KtExpression?): Boolean =
            e != null && offset >= e.textRange.startOffset && offset <= e.textRange.endOffset

        var n: PsiElement? = ctx.node.psiOrNull() ?: return null
        while (n != null) {
            when (val owner = n) {
                is KtIfExpression -> {
                    // The `else` branch is checked first so a caret in it is not attributed to the `then`
                    // of an outer `if` that also spans it.
                    if (covers(owner.`else`)) return Branch(owner.`else`!!, "else")
                    if (covers(owner.then)) return Branch(owner.then!!, "if")
                }
                is KtForExpression -> if (covers(owner.body)) return Branch(owner.body!!, "for")
                is KtWhileExpression -> if (covers(owner.body)) return Branch(owner.body!!, "while")
                is KtDoWhileExpression -> if (covers(owner.body)) return Branch(owner.body!!, "do")
                else -> {}
            }
            n = n.parent
        }
        return null
    }

    /** A control-structure body and the keyword an action names it by. */
    private class Branch(val body: KtExpression, val label: String)

    /** The offset of the statement that owns [body], for indentation (the `if`, not the body itself). */
    private fun statementStartOf(body: KtExpression, text: CharSequence): Int {
        var n: PsiElement? = body.parent
        while (n != null) {
            if (n.parent is KtBlockExpression) return n.textRange.startOffset
            n = n.parent
        }
        return body.textRange.startOffset
    }
}

// ---------------------------------------------------------------------------------------------------
// Explicit type
// ---------------------------------------------------------------------------------------------------

/**
 * Write the inferred type of a `val`/`var` declaration out explicitly, and take it away again. Offered on
 * the declaration name, so it does not compete with the intentions for the initializer expression.
 */
class KotlinExplicitTypeActionProvider : ActionProvider {
    override val languages = setOf(KOTLIN)

    override fun actions(ctx: EditorActionContext): List<QuickFix> {
        val property = ctx.nearestProperty() ?: return emptyList()
        val nameRange = property.nameIdentifier?.textRange ?: return emptyList()
        // Only with the caret on the name: elsewhere in the declaration the initializer's intentions apply.
        if (ctx.range.start < nameRange.startOffset || ctx.range.start > nameRange.endOffset) return emptyList()

        val declared = property.typeReference
        if (declared != null) {
            val from = nameRange.endOffset
            val to = declared.textRange.endOffset
            return listOf(object : QuickFix {
                override val title = "Remove explicit type"
                override val kind = CodeActionKind.REFACTOR
                override suspend fun computeEdits(ctx2: FixContext): WorkspaceEdit =
                    WorkspaceEdit.of(ctx2.target.file, DocumentEdit(from, to - from, ""))
            })
        }
        // No declared type: it can only be written out when there is an initializer to infer it from.
        val initializer = property.initializer ?: return emptyList()
        val initializerNode = ctx.nodeFor(initializer) ?: return emptyList()
        return listOf(object : QuickFix {
            override val title = "Specify explicit type"
            override val kind = CodeActionKind.REFACTOR
            override suspend fun computeEdits(ctx2: FixContext): WorkspaceEdit {
                val type = ctx2.target.resolver.resolveType(initializerNode)
                    ?.let { renderKotlinType(it) } ?: return WorkspaceEdit.EMPTY
                return WorkspaceEdit.of(
                    ctx2.target.file,
                    DocumentEdit(nameRange.endOffset, 0, ": $type"),
                )
            }
        })
    }
}

/**
 * Render [type] as Kotlin source. Kotlin needs no import to name a type it can already see, and the
 * resolver reports a qualified name, so the simple name is used with its type arguments preserved.
 * Unwritable results (an error type, `Unit` from a statement, a platform capture) return null so the
 * intention makes no edit rather than a wrong one.
 */
private fun renderKotlinType(type: TypeRef): String? {
    val qn = type.qualifiedName
    if (qn.isBlank() || qn == "kotlin.Unit" || qn == "kotlin.Nothing" || '#' in qn || '&' in qn) return null
    if ("error" in qn.lowercase() || "capture" in qn) return null
    // Shorten every qualified name in the string (the type plus any type arguments) to its simple name.
    return Regex("""[A-Za-z_][\w.]*""").replace(qn) { m ->
        val v = m.value
        if ('.' in v) v.substringAfterLast('.') else v
    }
}

// ---------------------------------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------------------------------

private fun DomNode.psiOrNull(): PsiElement? = (this as? KotlinDomNode)?.psi

/** The DOM node wrapping [psi], so a fix computed over PSI can still speak the neutral currency. */
private fun EditorActionContext.nodeFor(psi: PsiElement): DomNode? {
    val owner = (node as? KotlinDomNode)?.owner ?: return null
    // The file element is represented by the parsed file itself, never by a wrapper node.
    return if (psi is KtFile) owner else owner.adapt(psi)
}

private fun EditorActionContext.nearestFunction(): KtNamedFunction? = nearestPsi()

private fun EditorActionContext.nearestProperty(): KtProperty? = nearestPsi()

private fun EditorActionContext.nearestClass(): KtClassOrObject? = nearestPsi()

private inline fun <reified T : PsiElement> EditorActionContext.nearestPsi(): T? {
    var n: PsiElement? = node.psiOrNull()
    while (n != null) {
        if (n is T) return n
        n = n.parent
    }
    return null
}

/** The leading whitespace of the line containing [offset]. */
private fun lineIndentAt(text: CharSequence, offset: Int): String {
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

/** A readable, non-clashing name for a value produced by [exprText]. */
private fun freshName(exprText: String, allText: CharSequence): String {
    val callName = Regex("""([A-Za-z_][\w]*)\s*\(""").find(exprText)?.groupValues?.get(1)
    val ident = callName ?: exprText.trim().substringAfterLast('.').takeWhile { it.isLetterOrDigit() || it == '_' }
    val stripped = when {
        ident.startsWith("get") && ident.length > 3 -> ident.removePrefix("get")
        ident.startsWith("is") && ident.length > 2 && ident[2].isUpperCase() -> ident.removePrefix("is")
        else -> ident
    }
    val base = stripped.replaceFirstChar { it.lowercaseChar() }
        .filter { it.isLetterOrDigit() || it == '_' }
        .ifBlank { "value" }
    val safe = if (base.first().isDigit()) "value$base" else base
    var name = safe
    var i = 1
    while (Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(allText)) {
        name = "$safe${i++}"
        if (i > 99) break
    }
    return name
}
