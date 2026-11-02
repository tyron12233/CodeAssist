package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtArrayAccessExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtImportDirective
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtModifierListOwner
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeProjection
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.findElementAt
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.kotlin.symbols.KotlinSymbolService

/**
 * The quick fixes keyed off the editor's own diagnostics, offered from the lightbulb (Alt+Enter) on the
 * flagged range. Each one is purely syntactic: it finds the element the diagnostic is anchored on (every check
 * reports a known element's range, see [KotlinSemanticChecks]) and rewrites only that, so a fix can never
 * reach beyond the code the diagnostic named. Where the element at the range is not the expected one (a stale
 * diagnostic over an edited buffer) the fix is simply not offered.
 *
 * The one fix that needs resolution, adding a `when`'s missing branches, lives in [KotlinEditorFeatures]
 * (it has to enumerate the subject's enum constants or sealed subclasses).
 */
internal object KotlinQuickFixes {

    /** The diagnostic codes this object (plus the `when` branches fix) can fix. */
    val CODES: Set<String> = setOf(
        KotlinDiagnosticCodes.UNUSED_IMPORT,
        KotlinDiagnosticCodes.UNUSED_LOCAL,
        KotlinDiagnosticCodes.UNUSED_PRIVATE,
        KotlinDiagnosticCodes.UNUSED_PARAMETER,
        KotlinDiagnosticCodes.VAR_COULD_BE_VAL,
        KotlinDiagnosticCodes.VAL_REASSIGN,
        KotlinDiagnosticCodes.REDUNDANT_SAFE_CALL,
        KotlinDiagnosticCodes.REDUNDANT_NOT_NULL,
        KotlinDiagnosticCodes.USELESS_CAST,
        KotlinDiagnosticCodes.USELESS_ELVIS,
        KotlinDiagnosticCodes.REDUNDANT_STRING_TEMPLATE,
        KotlinDiagnosticCodes.REDUNDANT_PROJECTION,
        KotlinDiagnosticCodes.USE_PROPERTY_ACCESS,
        KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        KotlinDiagnosticCodes.OVERRIDE_REQUIRED,
        KotlinDiagnosticCodes.NOTHING_TO_OVERRIDE,
        KotlinDiagnosticCodes.WHEN_EXHAUSTIVE,
    )

    /** The fixes for a diagnostic with [code] and [message] over `[start, end)` in [ktFile]. */
    fun fixes(code: String, ktFile: KtFile, start: Int, end: Int, message: String): List<KotlinImportFix> {
        val text = ktFile.text
        if (start < 0 || end > text.length || start > end) return emptyList()
        val f = Fixer(ktFile, text, start, end)
        return when (code) {
            KotlinDiagnosticCodes.UNUSED_IMPORT -> listOfNotNull(f.removeImport())
            KotlinDiagnosticCodes.UNUSED_LOCAL -> listOfNotNull(f.removeLocal())
            KotlinDiagnosticCodes.UNUSED_PRIVATE -> listOfNotNull(f.removePrivate())
            KotlinDiagnosticCodes.UNUSED_PARAMETER -> listOfNotNull(f.renameLambdaParameter())
            KotlinDiagnosticCodes.VAR_COULD_BE_VAL -> listOfNotNull(f.varToVal())
            KotlinDiagnosticCodes.VAL_REASSIGN -> listOfNotNull(f.valToVar())
            KotlinDiagnosticCodes.REDUNDANT_SAFE_CALL ->
                listOfNotNull(f.replaceRangeIf("?.", ".", "Replace '?.' with '.'"))
            KotlinDiagnosticCodes.REDUNDANT_NOT_NULL ->
                listOfNotNull(f.replaceRangeIf("!!", "", "Remove '!!'"))
            KotlinDiagnosticCodes.USELESS_CAST -> listOfNotNull(f.removeCast())
            KotlinDiagnosticCodes.USELESS_ELVIS -> listOfNotNull(f.removeElvis())
            KotlinDiagnosticCodes.REDUNDANT_STRING_TEMPLATE -> listOfNotNull(f.removeTemplateBraces())
            KotlinDiagnosticCodes.REDUNDANT_PROJECTION -> listOfNotNull(f.removeProjection())
            KotlinDiagnosticCodes.USE_PROPERTY_ACCESS -> listOfNotNull(f.usePropertyAccess(message))
            KotlinDiagnosticCodes.UNSAFE_NULLABLE -> f.nullSafety()
            KotlinDiagnosticCodes.OVERRIDE_REQUIRED -> listOfNotNull(f.addOverride())
            KotlinDiagnosticCodes.NOTHING_TO_OVERRIDE -> listOfNotNull(f.removeOverride())
            KotlinDiagnosticCodes.WHEN_EXHAUSTIVE -> listOfNotNull(f.addElseBranch())
            else -> emptyList()
        }
    }

    /**
     * `when` branches for the cases [subjectFqn] has that [whenExpr] does not cover yet: `is Circle -> TODO()`
     * for a sealed type, `Color.RED -> TODO()` for an enum. Empty when the subject is neither, or every case is
     * covered. Shared by the completion item and the `kt.whenExhaustive` quick fix. A branch condition
     * containing [ignore] (the completion's placeholder identifier) is not counted as covering anything.
     */
    fun missingWhenBranches(
        whenExpr: KtWhenExpression,
        subjectFqn: String,
        service: KotlinSymbolService,
        ignore: String? = null,
    ): List<String> {
        val covered = HashSet<String>()
        for (entry in whenExpr.entries) for (cond in entry.conditions) when (cond) {
            is dev.ide.kotlin.syntax.psi.KtWhenConditionIsPattern ->
                cond.typeReference?.text?.substringBefore('<')?.substringAfterLast('.')?.trim()?.let { covered += it }
            is dev.ide.kotlin.syntax.psi.KtWhenConditionWithExpression ->
                cond.expression?.text?.substringAfterLast('.')?.trim()
                    ?.let { if (ignore == null || ignore !in it) covered += it }
        }
        service.sealedSubclassesOf(subjectFqn)?.let { subs ->
            return subs.map { it.substringAfterLast('.') }.filter { it !in covered }.map { "is $it -> TODO()" }
        }
        val simple = subjectFqn.substringAfterLast('.')
        return service.enumConstantsOf(subjectFqn).map { it.name }.filter { it !in covered }
            .map { "$simple.$it -> TODO()" }
    }

    /** Insert [branches] as new entries of [whenExpr], one per line, before its closing brace. */
    fun insertBranches(text: String, whenExpr: KtWhenExpression, branches: List<String>): DocumentEdit? {
        val close = whenExpr.closeBrace ?: return null
        val closeAt = close.textRange.startOffset
        val closeLineStart = lineStart(text, closeAt)
        val braceOnOwnLine = text.substring(closeLineStart, closeAt).isBlank()
        if (!braceOnOwnLine) {
            // A one-line `when (x) { A -> 1 }`: entries on one line are separated by `;`.
            val before = text.substring(0, closeAt).trimEnd()
            val sep = if (before.endsWith("{") || before.endsWith(";")) " " else "; "
            val trimmedStart = before.length
            return DocumentEdit(trimmedStart, closeAt - trimmedStart, sep + branches.joinToString("; ") + " ")
        }
        val entryIndent = whenExpr.entries.firstOrNull()?.let { indentOf(text, it.textRange.startOffset) }
            ?: (indentOf(text, closeAt) + "    ")
        val block = branches.joinToString("") { "$entryIndent$it\n" }
        return DocumentEdit(closeLineStart, 0, block)
    }

    // --- the per-diagnostic fixes, over one diagnostic's range ---

    private class Fixer(val kt: KtFile, val text: String, val start: Int, val end: Int) {
        val leaf: KtElement? = kt.findElementAt(start.coerceIn(0, (text.length - 1).coerceAtLeast(0)))
        val rangeText: String get() = text.substring(start, end)

        /** The innermost [T] enclosing the range's start that also starts there. */
        inline fun <reified T : KtElement> startingAt(): T? {
            var n = leaf
            while (n != null) {
                if (n is T && n.textRange.startOffset == start) return n
                n = n.parent
            }
            return null
        }

        /** The innermost [T] enclosing the range's start. */
        inline fun <reified T : KtElement> enclosing(): T? {
            var n = leaf
            while (n != null) {
                if (n is T) return n
                n = n.parent
            }
            return null
        }

        /** The declaration whose NAME the range is (the unused / override checks anchor on the name). */
        inline fun <reified T : KtNamedDeclaration> declNamedHere(): T? {
            var n = leaf
            while (n != null) {
                if (n is T && n.nameIdentifier?.textRange?.startOffset == start) return n
                n = n.parent
            }
            return null
        }

        fun edit(title: String, vararg edits: DocumentEdit) = KotlinImportFix(title, edits.toList())

        fun replaceRangeIf(expected: String, with: String, title: String): KotlinImportFix? {
            if (rangeText != expected) return null
            return edit(title, DocumentEdit(start, end - start, with))
        }

        fun removeImport(): KotlinImportFix? {
            val imp = enclosing<KtImportDirective>() ?: return null
            val r = imp.textRange
            // The only import goes with one of the blank lines around it, not leaving two in a row.
            return edit("Remove unused import", deleteLines(text, r.startOffset, r.endOffset, collapseBlank = true))
        }

        fun removeLocal(): KotlinImportFix? {
            val prop = declNamedHere<KtProperty>() ?: return null
            if (prop.parent !is KtBlockExpression) return null
            val r = prop.textRange
            val init = prop.initializer
            val name = prop.name ?: return null
            return if (init == null || prop.hasDelegate() || isSideEffectFree(init)) {
                edit("Remove variable '$name'", deleteLines(text, r.startOffset, r.endOffset))
            } else {
                // The initializer may do something (a call): keep it as a statement, drop only the variable.
                edit("Remove variable '$name' (keep its initializer)",
                    DocumentEdit(r.startOffset, r.endOffset - r.startOffset, init.text))
            }
        }

        fun removePrivate(): KotlinImportFix? {
            val decl = declNamedHere<KtNamedFunction>() ?: declNamedHere<KtProperty>() ?: return null
            if (decl.parent !is KtFile && decl.parent !is KtClassBody) return null
            val kind = if (decl is KtNamedFunction) "function" else "property"
            var s = decl.textRange.startOffset
            // A doc comment is trivia to this parser, not part of the declaration: take it along.
            val before = text.substring(0, s).trimEnd()
            if (before.endsWith("*/")) {
                val docStart = before.lastIndexOf("/**")
                if (docStart >= 0 && text.substring(lineStart(text, docStart), docStart).isBlank()) s = docStart
            }
            return edit("Remove unused $kind '${decl.name}'", deleteLines(text, s, decl.textRange.endOffset, collapseBlank = true))
        }

        fun renameLambdaParameter(): KotlinImportFix? {
            val p = declNamedHere<KtParameter>() ?: return null
            if (p.parent?.parent !is KtFunctionLiteral) return null // only a lambda's parameter can be `_`
            val id = p.nameIdentifier ?: return null
            return edit("Rename to _", DocumentEdit(id.textRange.startOffset, id.textLength, "_"))
        }

        fun varToVal(): KotlinImportFix? {
            val prop = declNamedHere<KtProperty>() ?: return null
            val kw = prop.valOrVarKeyword.takeIf { it !== prop && it.text == "var" } ?: return null
            return edit("Change to 'val'", DocumentEdit(kw.textRange.startOffset, kw.textLength, "val"))
        }

        /** `x = 1` on a local `val x`: make the declaration a `var` (a parameter can't be, so no fix there). */
        fun valToVar(): KotlinImportFix? {
            val name = rangeText.takeIf { it.isNotBlank() } ?: return null
            var n: KtElement? = leaf
            var child: KtElement? = null
            while (n != null) {
                if (n is KtNamedFunction || n is KtClassBody || n is KtFile) return null
                if (n is KtBlockExpression) {
                    val stop = child?.textRange?.startOffset ?: start
                    val decl = n.children.filterIsInstance<KtProperty>()
                        .lastOrNull { it.name == name && it.textRange.startOffset < stop }
                    if (decl != null) {
                        val kw = decl.valOrVarKeyword.takeIf { it !== decl && it.text == "val" } ?: return null
                        return edit("Change '$name' to 'var'", DocumentEdit(kw.textRange.startOffset, kw.textLength, "var"))
                    }
                }
                child = n
                n = n.parent
            }
            return null
        }

        fun removeCast(): KotlinImportFix? {
            val cast = startingAt<KtBinaryExpressionWithTypeRHS>()?.takeIf { it.textRange.endOffset == end } ?: return null
            val left = cast.left ?: return null
            return edit("Remove useless cast", DocumentEdit(start, end - start, left.text))
        }

        fun removeElvis(): KotlinImportFix? {
            if (!rangeText.startsWith("?:")) return null
            var s = start
            while (s > 0 && (text[s - 1] == ' ' || text[s - 1] == '\t')) s--
            return edit("Remove useless elvis operator", DocumentEdit(s, end - s, ""))
        }

        fun removeTemplateBraces(): KotlinImportFix? {
            val t = rangeText
            if (!t.startsWith("\${") || !t.endsWith("}")) return null
            val name = t.substring(2, t.length - 1).trim()
            if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' }) return null
            return edit("Remove redundant braces", DocumentEdit(start, end - start, "\$$name"))
        }

        fun removeProjection(): KotlinImportFix? {
            val proj = startingAt<KtTypeProjection>() ?: return null
            val mod = proj.modifierList?.getModifier(KtTokens.OUT_KEYWORD)
                ?: proj.modifierList?.getModifier(KtTokens.IN_KEYWORD) ?: return null
            var e = mod.textRange.endOffset
            while (e < text.length && text[e] == ' ') e++
            return edit("Remove redundant '${mod.text}' projection",
                DocumentEdit(mod.textRange.startOffset, e - mod.textRange.startOffset, ""))
        }

        fun usePropertyAccess(message: String): KotlinImportFix? {
            val marker = "syntax: '"
            val at = message.indexOf(marker).takeIf { it >= 0 } ?: return null
            val suggestion = message.substring(at + marker.length).removeSuffix("'")
            if (suggestion.isBlank()) return null
            return edit("Use property access syntax", DocumentEdit(start, end - start, suggestion))
        }

        /** An unsafe call on a nullable receiver: make it a safe call, or assert the receiver non-null. */
        fun nullSafety(): List<KotlinImportFix> {
            val out = ArrayList<KotlinImportFix>()
            val qualified = enclosing<KtQualifiedExpression>()
                ?.takeIf { it.receiverExpressionOrNull?.textRange?.let { r -> r.startOffset == start && r.endOffset == end } == true }
            if (qualified != null) {
                val selectorAt = qualified.selectorExpression?.textRange?.startOffset ?: qualified.textRange.endOffset
                val dot = text.indexOf('.', end).takeIf { it in end until selectorAt && text[it - 1] != '?' }
                if (dot != null) out += edit("Replace with safe call '?.'", DocumentEdit(dot, 1, "?."))
            }
            val receiver = rangeText
            val simple = leaf?.let { isPostfixSafe(it, start, end) } ?: false
            out += if (simple) {
                edit("Add non-null assertion '!!'", DocumentEdit(end, 0, "!!"))
            } else {
                edit("Add non-null assertion '!!'", DocumentEdit(start, end - start, "($receiver)!!"))
            }
            return out
        }

        fun addOverride(): KotlinImportFix? {
            val decl = declNamedHere<KtNamedFunction>() ?: declNamedHere<KtProperty>() ?: declNamedHere<KtParameter>()
                ?: return null
            val owner = decl as KtModifierListOwner
            owner.modifierList?.getModifier(KtTokens.OPEN_KEYWORD)?.let { open ->
                // An override is already open: `open` becomes `override` rather than sitting beside it.
                return edit("Add 'override' modifier", DocumentEdit(open.textRange.startOffset, open.textLength, "override"))
            }
            val keyword = when (decl) {
                is KtNamedFunction -> decl.funKeyword
                is KtProperty -> decl.valOrVarKeyword.takeIf { it !== decl }
                is KtParameter -> decl.valOrVarKeyword
                else -> null
            } ?: return null
            // Before the modifiers that conventionally follow `override` (suspend, operator, ...), else the keyword.
            val trailing = listOf(
                KtTokens.SUSPEND_KEYWORD, KtTokens.INLINE_KEYWORD, KtTokens.OPERATOR_KEYWORD, KtTokens.INFIX_KEYWORD,
                KtTokens.TAILREC_KEYWORD, KtTokens.EXTERNAL_KEYWORD, KtTokens.LATEINIT_KEYWORD, KtTokens.CONST_KEYWORD,
            ).mapNotNull { owner.modifierList?.getModifier(it)?.textRange?.startOffset }
            val at = (trailing + keyword.textRange.startOffset).min()
            return edit("Add 'override' modifier", DocumentEdit(at, 0, "override "))
        }

        fun removeOverride(): KotlinImportFix? {
            val decl = declNamedHere<KtNamedFunction>() ?: declNamedHere<KtProperty>() ?: declNamedHere<KtParameter>()
                ?: return null
            val tok = (decl as KtModifierListOwner).modifierList?.getModifier(KtTokens.OVERRIDE_KEYWORD) ?: return null
            var e = tok.textRange.endOffset
            while (e < text.length && text[e] == ' ') e++
            return edit("Remove 'override' modifier", DocumentEdit(tok.textRange.startOffset, e - tok.textRange.startOffset, ""))
        }

        fun addElseBranch(): KotlinImportFix? {
            val w = enclosing<KtWhenExpression>()?.takeIf { it.whenKeyword.textRange.startOffset == start } ?: return null
            if (w.entries.any { it.isElse }) return null
            val e = insertBranches(text, w, listOf("else -> TODO()")) ?: return null
            return edit("Add 'else' branch", e)
        }
    }

    /** No observable effect in evaluating [e], so removing it with its variable changes nothing. Conservative. */
    private fun isSideEffectFree(e: KtElement): Boolean = when (e) {
        is KtConstantExpression, is KtNameReferenceExpression, is KtLambdaExpression, is KtThisExpression -> true
        is KtStringTemplateExpression -> '(' !in e.text // `"$a"` yes, `"${f()}"` no
        is KtParenthesizedExpression -> e.children.all { isSideEffectFree(it) }
        is KtQualifiedExpression -> false // a property read can run a getter
        else -> false
    }

    /** Whether `!!` can follow `[start, end)` without parentheses: a name, call, qualified access, `this`, ... */
    private fun isPostfixSafe(leaf: KtElement, start: Int, end: Int): Boolean {
        // Every element spanning exactly the range counts (the identifier token and the name expression around it
        // share one), so climb through all of them before deciding.
        var n: KtElement? = leaf
        while (n != null) {
            val r = n.textRange
            if (r.startOffset < start || r.endOffset > end) return false
            if (r.startOffset == start && r.endOffset == end && (
                    n is KtNameReferenceExpression || n is KtCallExpression || n is KtQualifiedExpression ||
                        n is KtThisExpression || n is KtParenthesizedExpression || n is KtArrayAccessExpression)
            ) return true
            n = n.parent
        }
        return false
    }

    /**
     * Delete `[start, end)`. When that is all there is on its lines, the whole lines go (indent and newline),
     * so no blank line is left behind; with [collapseBlank] a blank line left doubled up is removed too.
     * Otherwise only the range goes, with a `;` that separated it from the next statement.
     */
    fun deleteLines(text: String, start: Int, end: Int, collapseBlank: Boolean = false): DocumentEdit {
        val ls = lineStart(text, start)
        var le = end
        while (le < text.length && (text[le] == ' ' || text[le] == '\t')) le++
        val wholeLines = text.substring(ls, start).isBlank() && (le == text.length || text[le] == '\n')
        if (!wholeLines) {
            var e = end
            while (e < text.length && text[e] == ' ') e++
            if (e < text.length && text[e] == ';') { e++; while (e < text.length && text[e] == ' ') e++ }
            return DocumentEdit(start, e - start, "")
        }
        if (le < text.length) le++ // the newline
        if (collapseBlank) {
            val prevBlank = ls == 0 || lineBlankBefore(text, ls)
            val nextLineEnd = text.indexOf('\n', le).let { if (it < 0) text.length else it }
            if (prevBlank && le < text.length && text.substring(le, nextLineEnd).isBlank()) {
                le = if (nextLineEnd < text.length) nextLineEnd + 1 else nextLineEnd
            }
        }
        return DocumentEdit(ls, le - ls, "")
    }

    /** Whether the line ending just before [lineStartOffset] is blank. */
    private fun lineBlankBefore(text: String, lineStartOffset: Int): Boolean {
        if (lineStartOffset < 1) return true
        val prevStart = lineStart(text, lineStartOffset - 1)
        return text.substring(prevStart, lineStartOffset - 1).isBlank()
    }

    private fun lineStart(text: String, offset: Int): Int {
        var s = offset.coerceIn(0, text.length)
        while (s > 0 && text[s - 1] != '\n') s--
        return s
    }

    private fun indentOf(text: String, offset: Int): String {
        val s = lineStart(text, offset)
        var i = s
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return text.substring(s, i)
    }
}
