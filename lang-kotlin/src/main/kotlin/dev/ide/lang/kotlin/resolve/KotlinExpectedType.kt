package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.KotlinType
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/** Expected type at a position: the target type a return or argument slot demands. */

/**
 * The type the context at [offset] expects an expression to have, or null when unconstrained: a typed
 * property initializer (`val x: T = ‹here›`), a `return ‹here›` / expression body of a typed function, a
 * call-argument slot (positional or named), and a boolean condition (`if`/`while`/`do-while`, `&&`/`||`,
 * `!`). Powers expected-type-aware completion (rank matches first, offer enum constants + booleans).
 */
fun KotlinResolver.expectedTypeAt(offset: Int): KotlinType? {
    var child: PsiElement? = null
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        when (node) {
            is KtProperty ->
                if (child != null && child === node.initializer)
                    return node.typeReference?.text?.let { service.typeFromText(it, fileContext) }

            is KtNamedFunction ->
                if (child != null && child === node.bodyExpression && !node.hasBlockBody())
                    return node.typeReference?.text?.let { service.typeFromText(it, fileContext) }

            is KtReturnExpression ->
                if (child === node.returnedExpression) return enclosingFunctionReturnType(node)

            is KtValueArgument -> return expectedArgType(node)
            // A `when`-entry condition with a SUBJECT (`when (color) { █ }`) expects the subject's type — so
            // value completion offers its enum constants / companion constants. A subjectless `when` has
            // Boolean conditions; left to the generic path (offering true/false there is low value).
            is org.jetbrains.kotlin.psi.KtWhenConditionWithExpression ->
                node.getStrictParentOfType<KtWhenExpression>()?.subjectExpression?.let { s ->
                    inferType(
                        s
                    )?.let { return it }
                }
            // A condition is wrapped in a container node, so match by range rather than child identity.
            is KtIfExpression -> if (node.condition?.textRange?.contains(offset) == true) return service.typeByFqn(
                "kotlin.Boolean"
            )

            is KtWhileExpression -> if (node.condition?.textRange?.contains(offset) == true) return service.typeByFqn(
                "kotlin.Boolean"
            )

            is KtDoWhileExpression -> if (node.condition?.textRange?.contains(offset) == true) return service.typeByFqn(
                "kotlin.Boolean"
            )

            is KtPrefixExpression ->
                if (node.operationToken == KtTokens.EXCL) return service.typeByFqn("kotlin.Boolean")

            is KtBinaryExpression ->
                if (node.operationToken == KtTokens.ANDAND || node.operationToken == KtTokens.OROR)
                    return service.typeByFqn("kotlin.Boolean")
        }
        child = node
        node = node.parent
    }
    return null
}

internal fun KotlinResolver.enclosingFunctionReturnType(from: PsiElement): KotlinType? {
    var node: PsiElement? = from.parent
    while (node != null) {
        if (node is KtNamedFunction) return node.typeReference?.text?.let {
            service.typeFromText(
                it,
                fileContext
            )
        }
        if (node is KtLambdaExpression) return null // a return@label leaves the lambda's type to inference
        node = node.parent
    }
    return null
}

internal fun KotlinResolver.expectedArgType(arg: KtValueArgument): KotlinType? {
    val argList = arg.parent as? KtValueArgumentList ?: return null
    // An ANNOTATION argument (`@Foo(mode = <caret>)`): resolve against the annotation's parameter types so an
    // enum-typed argument offers its constants — the annotation's arg list parent is a KtAnnotationEntry, not a
    // KtCallExpression, so the call path below can't reach it.
    (argList.parent as? KtAnnotationEntry)?.let { entry ->
        val params = annotationParameters(entry)
        val argName = arg.getArgumentName()?.asName?.identifier
        val p = if (argName != null) params.firstOrNull { it.name == argName }
        else params.getOrNull(argList.arguments.indexOf(arg))
        return p?.type
    }
    val call = argList.parent as? KtCallExpression ?: return null
    val targets = callTargets(call)
    if (targets.isEmpty()) return null
    val argName = arg.getArgumentName()?.asName?.identifier
    if (argName != null) {
        targets.forEach { s ->
            val i = s.paramNames.indexOf(argName)
            if (i >= 0) (s.paramTypes.getOrNull(i) as? KotlinType)?.let { return it }
        }
        return null
    }
    val index = argList.arguments.indexOf(arg)
    targets.forEach { s -> (s.paramTypes.getOrNull(index) as? KotlinType)?.let { return it } }
    return null
}
