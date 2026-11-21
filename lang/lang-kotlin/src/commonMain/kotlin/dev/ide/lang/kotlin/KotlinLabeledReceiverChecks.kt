package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtAnnotatedExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtInstanceExpressionWithLabel
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtSuperExpression
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtValueArgumentList
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange

/**
 * UNRESOLVED_LABEL on `this@L` / `super@L`, ported from the body resolver's labeled receiver lookup
 * (`transformThisReceiverExpression` and `transformSuperReceiver`, which find an implicit receiver by label).
 * A label names a receiver only when it names an enclosing class, an enclosing extension function or
 * property (its receiver), or a lambda with a receiver. `this@f` inside a plain member function `f` names
 * nothing (`f` has no receiver), and `super@L` needs an enclosing class `L`.
 *
 * Whether a lambda has a receiver depends on what it is passed to, so any enclosing lambda or anonymous
 * function that carries the label, written (`L@{ }`) or taken from its call (`L { }`), backs off.
 */
internal object KotlinLabeledReceiverChecks {

    fun check(e: KtInstanceExpressionWithLabel): Diagnostic? {
        val label = e.getLabelName()?.takeIf { it.isNotEmpty() } ?: return null
        val isSuper = e is KtSuperExpression
        var p = e.parent
        while (p != null && p !is KtFile) {
            // A context receiver is named by its type (`this@Logger`), which is not modelled here.
            if ((p is KtNamedFunction || p is KtProperty || p is KtClassOrObject) && p.text.trimStart().startsWith("context")) return null
            when (p) {
                // An unnamed companion object is labeled `Companion`.
                is KtClassOrObject -> if (p.name == label || (p.name == null && label == "Companion")) return null
                is KtNamedFunction -> {
                    if (p.name == label && !isSuper && p.receiverTypeReference != null) return null
                    if (p.name == null && carriesLabel(p, label)) return null
                }
                is KtProperty -> if (p.name == label && !isSuper && p.receiverTypeReference != null) return null
                is KtLambdaExpression -> if (carriesLabel(p, label)) return null
            }
            p = p.parent
        }
        val q = e.labelQualifier ?: return null
        val r = q.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Unresolved label: $label", KotlinDiagnosticCodes.UNRESOLVED_LABEL)
    }

    /** `L@{ }`, or `{ }` passed to a call of `L` (the implicit label). */
    private fun carriesLabel(fn: KtElement, label: String): Boolean {
        var holder: KtElement = fn
        while (true) {
            val parent = holder.parent ?: return false
            if (parent is KtLabeledExpression) {
                if (parent.getLabelName() == label) return true
                holder = parent
                continue
            }
            if (parent is KtParenthesizedExpression || parent is KtAnnotatedExpression) { holder = parent; continue }
            val call = when (parent) {
                is KtLambdaArgument -> parent.parent as? KtCallExpression
                is KtValueArgument -> (parent.parent as? KtValueArgumentList)?.parent as? KtCallExpression
                else -> null
            } ?: return false
            return (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == label
        }
    }
}
