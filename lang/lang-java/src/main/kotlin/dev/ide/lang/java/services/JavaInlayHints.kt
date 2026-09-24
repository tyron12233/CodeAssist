package dev.ide.lang.java.services

import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrefixExpression
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil
import dev.ide.lang.dom.TextRange
import dev.ide.lang.hints.InlayHint
import dev.ide.lang.hints.InlayHintKind
import dev.ide.lang.hints.InlayHintPart
import dev.ide.lang.hints.InlayHintService
import dev.ide.lang.java.resolve.JavaSymbol
import dev.ide.platform.EngineCancellation
import dev.ide.psi.IntellijPsiHost
import dev.ide.vfs.VirtualFile

/**
 * Java inlay hints, matching IntelliJ / the prior JDT service:
 *  - **var types** — the inferred type after a `var` local, `for (var x : …)`, or an untyped lambda parameter
 *    (`var a = "s"` → `a: String`), resolved through PSI (`PsiType.presentableText`);
 *  - **parameter names** — at a call site, the callee's parameter name on literal / negated-literal arguments
 *    (`foo(count: 3)`), IntelliJ's default of hinting only non-self-descriptive arguments;
 *  - **chaining** — the receiver call's result type before the next `.` in a multi-line fluent chain.
 *
 * All types/parameters are resolved through IntelliJ's engine (`resolveMethod`, `PsiType`).
 */
class JavaInlayHints(private val psiFor: (VirtualFile) -> PsiJavaFile) : InlayHintService {

    // Under the parse lock (exclusive): the type/parameter/chaining hints resolve methods and expression types,
    // which can lazily `buildTree` classpath PSI — must not race another parse on ART (as JavaSemanticHighlighter).
    override suspend fun hints(file: VirtualFile, range: TextRange): List<InlayHint> = IntellijPsiHost.withParseLock {
        val psi = psiFor(file)
        val out = ArrayList<InlayHint>()
        // Resolve only what intersects the requested window (the editor asks for the visible lines): each
        // hint is a method or type resolution, and an element outside the window cannot yield a hint in it.
        val inRange = { e: PsiElement ->
            EngineCancellation.checkCanceled()
            val r = e.textRange
            r != null && r.endOffset >= range.start && r.startOffset <= range.end
        }
        collectTypeHints(psi, inRange, out)
        collectParameterHints(psi, inRange, out)
        collectChainingHints(psi, inRange, out)
        out.filter { it.offset in range.start..range.end }.sortedBy { it.offset }
    }

    // --- inferred-type hints (var locals / enhanced-for / untyped lambda params) ---------------------------

    private fun collectTypeHints(psi: PsiJavaFile, inRange: (PsiElement) -> Boolean, out: MutableList<InlayHint>) {
        PsiTreeUtil.collectElementsOfType(psi, PsiLocalVariable::class.java).forEach { v ->
            if (!inRange(v)) return@forEach
            if (v.typeElement?.isInferredType == true) typeHint(v.nameIdentifier?.textRange?.endOffset, v.type, out)
        }
        PsiTreeUtil.collectElementsOfType(psi, PsiForeachStatement::class.java).forEach { fe ->
            if (!inRange(fe)) return@forEach
            val p = fe.iterationParameter
            if (p.typeElement?.isInferredType == true) typeHint(p.nameIdentifier?.textRange?.endOffset, p.type, out)
        }
        PsiTreeUtil.collectElementsOfType(psi, PsiLambdaExpression::class.java).forEach { lambda ->
            if (!inRange(lambda)) return@forEach
            lambda.parameterList.parameters.forEach { p ->
                // An untyped lambda parameter has no type element; show its inferred type.
                if (p.typeElement == null) typeHint(p.nameIdentifier?.textRange?.endOffset, p.type, out)
            }
        }
    }

    private fun typeHint(offset: Int?, type: PsiType?, out: MutableList<InlayHint>) {
        if (offset == null || type == null) return
        val name = type.presentableText.ifEmpty { return }
        out += InlayHint(
            offset = offset,
            parts = listOf(InlayHintPart(": $name")),
            kind = InlayHintKind.TYPE,
            tooltip = type.canonicalText,
        )
    }

    // --- parameter-name hints ------------------------------------------------------------------------------

    private fun collectParameterHints(psi: PsiJavaFile, inRange: (PsiElement) -> Boolean, out: MutableList<InlayHint>) {
        PsiTreeUtil.collectElementsOfType(psi, PsiCallExpression::class.java).forEach { call ->
            if (!inRange(call)) return@forEach
            val args = call.argumentList?.expressions ?: return@forEach
            if (args.isEmpty()) return@forEach
            val params = call.resolveMethod()?.parameterList?.parameters ?: return@forEach
            args.forEachIndexed { i, arg ->
                if (i >= params.size) return@forEachIndexed
                // Only literal / negated-literal args (IntelliJ suppresses hints on self-descriptive args).
                if (arg is PsiLiteralExpression || arg is PsiPrefixExpression) {
                    val p = params[i]
                    out += InlayHint(
                        offset = arg.textRange.startOffset,
                        parts = listOf(InlayHintPart("${p.name}:", JavaSymbol(p))),
                        kind = InlayHintKind.PARAMETER,
                        paddingRight = true,
                    )
                }
            }
        }
    }

    // --- chaining hints ------------------------------------------------------------------------------------

    /** At a fluent chain spanning lines, show the receiver call's result type before the next `.`. */
    private fun collectChainingHints(psi: PsiJavaFile, inRange: (PsiElement) -> Boolean, out: MutableList<InlayHint>) {
        val text = psi.text
        PsiTreeUtil.collectElementsOfType(psi, PsiMethodCallExpression::class.java).forEach { call ->
            if (!inRange(call)) return@forEach
            val recv = call.methodExpression.qualifierExpression as? PsiMethodCallExpression ?: return@forEach
            val recvEnd = recv.textRange.endOffset
            val nameStart = call.methodExpression.referenceNameElement?.textRange?.startOffset ?: return@forEach
            // Only when the next call is on a later line (multi-line chain) — otherwise it's just noise.
            if (!hasLineBreak(text, recvEnd, nameStart)) return@forEach
            val t = recv.type ?: return@forEach
            out += InlayHint(
                offset = recvEnd,
                parts = listOf(InlayHintPart(t.presentableText)),
                kind = InlayHintKind.CHAINING,
                paddingLeft = true,
            )
        }
    }

    /** Whether a line break lies between [from] and [to]: the scan covers the gap, not the file up to it. */
    private fun hasLineBreak(text: CharSequence, from: Int, to: Int): Boolean {
        val end = to.coerceIn(0, text.length)
        for (i in from.coerceIn(0, end) until end) if (text[i] == '\n') return true
        return false
    }
}
