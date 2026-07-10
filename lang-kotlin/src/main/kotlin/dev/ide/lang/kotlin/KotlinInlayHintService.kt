package dev.ide.lang.kotlin

import dev.ide.lang.dom.TextRange
import dev.ide.lang.hints.InlayHint
import dev.ide.lang.hints.InlayHintKind
import dev.ide.lang.hints.InlayHintPart
import dev.ide.lang.hints.InlayHintService
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.resolve.*
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.vfs.VirtualFile
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Inlay hints for Kotlin, computed over the live PSI + the backend's own inference (no FIR). Mirrors the
 * Java/JDT hints but for the cases that matter in Kotlin:
 *  - **local `val`/`var` inferred types** — `val x = foo()` → `x: Bar` (only when there's no explicit type),
 *  - **lambda parameter types** — `list.map { x -> … }` → `x: String`, and the implicit `it` → `it: String`,
 *  - **lambda scope receivers** — `Column { … }` → `this: ColumnScope`, for any receiver-typed lambda
 *    (`RowScope.() -> Unit`, a DSL builder block, `with(x) { … }`).
 *
 * Every hint comes from a type the resolver could infer; an unknown type simply yields no hint (so a
 * half-typed buffer never shows a wrong or `Unknown` annotation).
 */
class KotlinInlayHintService(
    private val parsedFor: (VirtualFile) -> KotlinParsedFile?,
    private val resolverFor: (KotlinParsedFile) -> KotlinResolver,
) : InlayHintService {

    override suspend fun hints(file: VirtualFile, range: TextRange): List<InlayHint> {
        val parsed = parsedFor(file) ?: return emptyList()
        val resolver = resolverFor(parsed)
        val out = ArrayList<InlayHint>()

        // Only descend into nodes whose text range intersects the requested window. Type inference per
        // val/lambda is the cost here, so skipping the off-screen subtrees (the editor asks for the visible
        // range) keeps the pass proportional to what's shown, not to file size. A node fully outside the
        // window can't contain an in-window hint, so pruning its subtree is exact, not a heuristic.
        fun walk(psi: PsiElement) {
            val r = psi.textRange
            if (r.endOffset < range.start || r.startOffset > range.end) return
            when (psi) {
                is KtProperty -> localTypeHint(psi, resolver)?.let { out += it }
                is KtLambdaExpression -> lambdaHints(psi, resolver, out)
                else -> {}
            }
            var c = psi.firstChild
            while (c != null) { walk(c); c = c.nextSibling }
        }
        walk(parsed.ktFile)
        return out.filter { it.offset in range.start..range.end }.sortedBy { it.offset }
    }

    /** A LOCAL `val`/`var` with an initializer but NO explicit type → the inferred type after its name. */
    private fun localTypeHint(prop: KtProperty, resolver: KotlinResolver): InlayHint? {
        if (prop.parent !is KtBlockExpression) return null // locals only (members are usually explicitly typed)
        if (prop.typeReference != null) return null // already has a written type
        val init = prop.initializer ?: return null
        val nameEnd = prop.nameIdentifier?.textRange?.endOffset ?: return null
        val type = resolver.inferType(init) ?: return null
        return typeHint(nameEnd, type)
    }

    /** Untyped lambda parameters (`{ x -> }`), the implicit `it`, and the implicit `this` receiver of a
     *  receiver-typed lambda, all typed from the function-type the lambda fills. */
    private fun lambdaHints(lambda: KtLambdaExpression, resolver: KotlinResolver, out: MutableList<InlayHint>) {
        // Added first so it sorts ahead of any same-offset `it:` hint (`R.(A) -> Unit` shows `this: R it: A`).
        receiverScopeHint(lambda, resolver)?.let { out += it }

        val inputs = resolver.lambdaParameterTypes(lambda)
        if (inputs.isEmpty()) return
        val params = lambda.valueParameters
        if (params.isEmpty()) {
            // Implicit `it`: only when the lambda takes one value and the body actually references `it`.
            val it = inputs.singleOrNull() as? KotlinType ?: return
            if (!referencesIt(lambda)) return
            val lBrace = lambda.functionLiteral.lBrace // always present on a parsed lambda
            out += InlayHint(
                lBrace.textRange.endOffset,
                listOf(InlayHintPart("it: " + it), InlayHintPart(" ->")),
                InlayHintKind.TYPE, tooltip = it.qualifiedName, paddingLeft = true, paddingRight = true,
            )
        } else {
            params.forEachIndexed { i, p ->
                if (p.typeReference != null) return@forEachIndexed // already typed
                val t = inputs.getOrNull(i) as? KotlinType ?: return@forEachIndexed
                val nameEnd = p.nameIdentifier?.textRange?.endOffset ?: return@forEachIndexed
                out += typeHint(nameEnd, t)
            }
        }
    }

    /** A receiver-typed lambda (`RowScope.() -> Unit` — a Compose content lambda, a DSL builder block,
     *  `with`/`buildString`) → `this: RowScope` right after the `{`. Suppressed when the receiver is an
     *  unbound type parameter (e.g. `apply`/`run`, whose `T` is only known from the call's receiver, which the
     *  resolver doesn't bind) so we never render a bare `this: T`. */
    private fun receiverScopeHint(lambda: KtLambdaExpression, resolver: KotlinResolver): InlayHint? {
        val receiver = resolver.lambdaReceiverType(lambda) ?: return null
        if (receiver.isTypeParameter) return null
        val lBrace = lambda.functionLiteral.lBrace // always present on a parsed lambda
        return InlayHint(
            lBrace.textRange.endOffset,
            listOf(InlayHintPart("this: " + receiver)),
            InlayHintKind.TYPE, tooltip = receiver.qualifiedName, paddingLeft = true, paddingRight = true,
        )
    }

    private fun referencesIt(lambda: KtLambdaExpression): Boolean {
        var found = false
        fun rec(p: PsiElement) {
            if (found) return
            // A nested lambda shadows `it`, so don't look inside one.
            if (p is KtLambdaExpression && p !== lambda) return
            if (p is KtNameReferenceExpression && p.getReferencedName() == "it") { found = true; return }
            var c = p.firstChild
            while (c != null && !found) { rec(c); c = c.nextSibling }
        }
        lambda.bodyExpression?.let { rec(it) }
        return found
    }

    private fun typeHint(offset: Int, type: KotlinType): InlayHint =
        InlayHint(offset, listOf(InlayHintPart(": " + type)), InlayHintKind.TYPE, tooltip = type.qualifiedName, paddingLeft = false)
}
