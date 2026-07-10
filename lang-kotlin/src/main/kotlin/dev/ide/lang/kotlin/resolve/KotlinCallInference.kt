package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.TypeRef
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression

/**
 * Bidirectional, constraint-based inference of a single call's type arguments — the resolver's stand-in for
 * the compiler's argument-analysis + completion. Explicit type arguments pin verbatim; otherwise every value
 * argument contributes a subtyping constraint on the parameter it fills, a lambda argument is typed TOP-DOWN
 * (its parameters from the callee, via [withLambdaShape]) so its body's result constrains the parameter's
 * functional return BOTTOM-UP, and [expected] (the type the context wants) bounds any return-position variable
 * the arguments underdetermine. The constraint system ([KotlinConstraintSystem]) decomposes and incorporates
 * these, then fixes each variable. Returns only the variables it solved; the caller merges them additively.
 */
internal fun KotlinResolver.inferCallBindings(sym: KotlinSymbol, call: KtCallExpression, expected: KotlinType?): Map<String, TypeRef> {
    if (sym.typeParameters.isEmpty()) return emptyMap()
    val erasure = methodTypeParamErasure(sym)
    val cs = newConstraintSystem(sym)

    call.typeArgumentList?.arguments?.takeIf { it.isNotEmpty() }?.let { targs ->
        sym.typeParameters.forEachIndexed { i, name ->
            (targs.getOrNull(i)?.typeReference?.text?.let { service.typeFromText(it, fileContext) } as? KotlinType)?.let { cs.fix(name, it) }
        }
        return cs.solve(erasure)
    }

    // Expected return type (top-down): `emptyList<T>() : List<String>` ⇒ T = String. The return must be
    // assignable to the expected type, so `returnType <: expected` bounds the return-position variables.
    val ret = sym.type as? KotlinType
    if (expected != null && !expected.isTypeParameter && ret != null) cs.addSubtypeConstraint(ret, expected)

    call.valueArguments.forEachIndexed { i, arg ->
        val expr = arg.getArgumentExpression() ?: return@forEachIndexed
        val pt = (sym.paramTypes.getOrNull(lambdaParamIndex(call, i, sym)) ?: sym.paramTypes.lastOrNull()) as? KotlinType
            ?: return@forEachIndexed
        if (expr is KtLambdaExpression) {
            val shape = service.functionalShape(pt) ?: return@forEachIndexed
            val partial = cs.solve() // earlier args' bindings inform this lambda's parameter types
            val inputs = shape.parameterTypes.map { p -> (p as? KotlinType)?.let { service.substitute(it, partial) } }
            val bodyResult = withLambdaShape(expr, KotlinSymbolService.FunctionalShape(inputs, shape.returnType, shape.isExtension)) {
                inferLambdaResult(expr) as? KotlinType
            }
            cs.addSubtypeConstraint(bodyResult, shape.returnType) // body result ≤ functional return
            // Builder inference (@BuilderInference): when the lambda's parameter is an extension function type
            // whose RECEIVER mentions an unbound type variable (`buildList`'s `MutableList<E>.() -> Unit`),
            // infer that variable from the body's calls on the receiver (`add(1)` ⇒ E = Int).
            if (pt.isExtensionFunctionType) {
                (pt.typeArguments.firstOrNull() as? KotlinType)
                    ?.takeIf { r -> r.typeArguments.any { (it as? KotlinType)?.isTypeParameter == true } }
                    ?.let { inferBuilderVars(it, expr, cs) }
            }
        } else {
            inferType(expr)?.let { cs.addSubtypeConstraint(it, pt) } // argument ≤ parameter
        }
    }
    // No erasure fallback: return ONLY variables a constraint solved, so the caller's additive merge touches
    // nothing the ad-hoc path already covered (which keeps its own erased-bound fallback).
    return cs.solve()
}

/**
 * Builder inference: constrain the builder [receiverType]'s variable type arguments (`MutableList<E>`,
 * `MutableMap<K, V>`) from the lambda body's calls ON that receiver. Each bare call `member(args)` in the body
 * that names a member of [receiverType] contributes `argType <: memberParamType`, and since the member's
 * parameters are the receiver's type variables (`add(E)`, `put(K, V)`) that binds them — so `buildList { add(1) }`
 * infers `E = Int`. Bounded to the body's top-level bare/`this.` calls (the idiomatic builder shape); a builder
 * whose element type is only observable through more elaborate flow degrades to unbound, never wrong.
 */
private fun KotlinResolver.inferBuilderVars(receiverType: KotlinType, lambda: KtLambdaExpression, cs: KotlinConstraintSystem) {
    val body = lambda.bodyExpression ?: return
    // Every bare (or `this.`) call ON the builder receiver anywhere in the body constrains its variables — not
    // just top-level statements, so `for (x in xs) add(x)`, `if (c) add(1)`, and inline `repeat(n) { add(it) }`
    // all count. A call with a non-`this` explicit receiver (`other.add(x)`) is NOT on the builder, so skipped.
    fun visit(e: PsiElement) {
        if (e is KtCallExpression && isBuilderReceiverCall(e)) {
            (e.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()?.let { name ->
                service.membersNamed(receiverType.qualifiedName, receiverType.typeArguments, name)
                    .firstOrNull { it.kind == SymbolKind.METHOD && it.paramTypes.size == e.valueArguments.size }
                    ?.let { member ->
                        e.valueArguments.forEachIndexed { i, arg ->
                            val pt = member.paramTypes.getOrNull(i) as? KotlinType ?: return@forEachIndexed
                            arg.getArgumentExpression()?.let { inferType(it) }?.takeIf { !it.isTypeParameter }?.let { cs.addSubtypeConstraint(it, pt) }
                        }
                    }
            }
        }
        var c = e.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(body)
}

/** A call reached on the IMPLICIT builder receiver: a bare `member(args)` (no qualifier) or an explicit
 *  `this.member(args)` — not `other.member(args)` (a call on some other value). */
private fun isBuilderReceiverCall(call: KtCallExpression): Boolean {
    val q = call.parent as? KtQualifiedExpression ?: return true // bare call → implicit receiver
    return q.selectorExpression === call && q.receiverExpression is KtThisExpression
}

/** A constraint system seeded with [sym]'s type parameters and their declared (erased) upper bounds. */
internal fun KotlinResolver.newConstraintSystem(sym: KotlinSymbol): KotlinConstraintSystem {
    val cs = KotlinConstraintSystem(service)
    val bounds = methodTypeParamErasure(sym)
    sym.typeParameters.forEach { cs.registerVariable(it, bounds[it] as? KotlinType) }
    return cs
}
