package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtFunction
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNullableType
import dev.ide.kotlin.syntax.psi.KtObjectLiteralExpression
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtProjectionKind
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.smartCastTypeAt
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind

/**
 * MEMBER_PROJECTED_OUT: `list.add(42)` on a `MutableList<out Number>`, `inv.f(1)` on an `Inv<*>`. A use-site
 * `out` (or star) projection makes the receiver's `T` an unknown subtype of its bound, so a member parameter of
 * type `T` accepts nothing but `Nothing`: every real argument is rejected. (`in T` declared with a star reads as
 * `in Nothing`, the same.)
 *
 * The receiver's projections are read from SOURCE, where they are written: a parameter's or local's declared type,
 * or a cast (`(x as Inv<out Int>).f(1)`); the type model drops use-site projections when it parses type text.
 * The argument must be one whose type plainly is not that unknown type: a literal, a string, a lambda, an object
 * literal, a constructor call. A value read from the same projected receiver (`a.set(0, a.get(0))`) has the very
 * captured type the parameter wants, so anything else is left alone. Only a member that is the one candidate of
 * its name and arity is judged; overloads (`In<*>.f("1")` against `f(T)` and `f(Int)`) are the compiler's.
 */
internal fun KotlinSemanticChecks.memberProjectedOut(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
    val dot = call.parent as? KtDotQualifiedExpression ?: return null
    if (dot.selectorExpression !== call) return null
    val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
    if (call.typeArgumentList != null) return null
    val args = call.valueArguments
    if (args.isEmpty() || args.any { it.getArgumentName() != null || it.isSpread }) return null
    val typeRef = declaredReceiverType(dot.receiverExpression, resolver) ?: return null
    val user = typeRef.typeElement as? KtUserType ?: return null
    val projections = user.typeArguments
    if (projections.isEmpty() || projections.none { it.projectionKind == KtProjectionKind.OUT || it.projectionKind == KtProjectionKind.STAR }) return null
    val fqn = service.typeFromText(typeRef.text.substringBefore('<'), resolver.fileContext, enclosingClassFqnOf(typeRef))
        ?.qualifiedName ?: return null
    // A typealias names another class's parameters in its own order.
    if (service.isProjectTypeAlias(user.referencedName ?: return null)) return null
    // A built-in (`MutableList`, `Array`) keeps its type parameters in its own shape.
    val tps = service.classTypeParameters(fqn).ifEmpty { service.builtinShapeFor(fqn)?.typeParameters.orEmpty() }
    if (tps.size != projections.size) return null
    val variances = service.classTypeParameterVariance(fqn).takeIf { it.size == tps.size }
        ?: service.builtinShapeFor(fqn)?.typeParameterVariances.orEmpty()
    // An unknown variance may be a declared `out T`, whose `@UnsafeVariance` parameters a projection does not close.
    if (variances.size != tps.size) return null
    // The member must be declared by the receiver's class itself: inherited through a covariant supertype
    // (`List.indexOf(@UnsafeVariance E)` seen from `MutableList<*>`) it is judged by rules this does not model.
    val declaredHere = service.sourceClass(fqn)
        ?.let { (it.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClassOrObject }
        ?.declarations?.any { it is dev.ide.kotlin.syntax.psi.KtNamedFunction && it.name == name }
        ?: service.builtinShapeFor(fqn)?.members?.any { it.name == name && it.kind == SymbolKind.METHOD }
        ?: false
    if (!declaredHere) return null
    // The members read against the class's own type parameters, so an inherited member's parameter names the
    // receiver class's parameter.
    val generic = tps.map { KotlinType(it, isTypeParameter = true) }
    val all = service.membersNamed(fqn, generic, name).filter { it.name == name && it.kind == SymbolKind.METHOD }
    if (all.isEmpty() || all.any { it.isExtension }) return null
    val fits = all.filter { m ->
        m.varargParamIndex < 0 && m.paramTypes.size >= args.size &&
            (args.size until m.paramTypes.size).all { i -> m.paramHasDefault.getOrNull(i) == true }
    }
    // An override and the member it overrides both list (`MutableList.add` and `MutableCollection.add`): one signature.
    val member = fits.distinctBy { m -> m.paramTypes.map { (it as? KotlinType)?.qualifiedName } }.singleOrNull() ?: return null
    if (member.typeParameters.isNotEmpty()) return null
    // What the call resolved to must be that member, not an extension in scope.
    val targets = resolver.callTargets(call)
    if (targets.any { it.isExtension }) return null
    for ((i, arg) in args.withIndex()) {
        val p = member.paramTypes.getOrNull(i) as? KotlinType ?: continue
        if (!p.isTypeParameter) continue
        val k = tps.indexOf(p.qualifiedName)
        if (k < 0) continue
        val declaredVariance = variances.getOrNull(k).orEmpty()
        val projected = when (projections[k].projectionKind) {
            KtProjectionKind.STAR -> declaredVariance != "out"
            KtProjectionKind.OUT -> declaredVariance.isEmpty()
            else -> false
        }
        if (!projected) continue
        val value = arg.getArgumentExpression() ?: continue
        if (!plainlyTyped(value, resolver)) continue
        val r = value.textRange
        val kind = if (projections[k].projectionKind == KtProjectionKind.STAR) "a star" else "an 'out'"
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "'$name' cannot be called with this argument: the receiver type '${typeRef.text}' has $kind projection, " +
                "and the parameter takes its '${p.qualifiedName}'",
            KotlinDiagnosticCodes.MEMBER_PROJECTED_OUT,
        )
    }
    return null
}

/** An argument whose type is plain to see and cannot be a captured type: a literal (not `null`), a string, a
 *  lambda, an object literal, or a call of a constructor or SAM constructor. */
private fun KotlinSemanticChecks.plainlyTyped(e: KtExpression, resolver: KotlinResolver): Boolean = when (val x = unwrapParenthesized(e)) {
    is KtConstantExpression -> x.text != "null"
    is KtStringTemplateExpression, is KtLambdaExpression, is KtObjectLiteralExpression -> true
    is KtCallExpression -> {
        val callee = (x.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        val type = callee?.takeIf { it.first().isUpperCase() && x.typeArgumentList == null }
            ?.let { service.resolveTypeName(it, resolver.fileContext, enclosingClassFqnOf(x)) }
        type != null && service.isKnownType(type) && service.classTypeParameters(type).isEmpty()
    }
    else -> false
}

private fun unwrapParenthesized(e: KtExpression?): KtExpression? {
    var x = e
    while (x is KtParenthesizedExpression) x = x.expression
    return x
}

/**
 * The type reference a receiver expression is declared with in source: `(x as T)` gives `T`; a bare name gives
 * the explicit type of the parameter or local it reads, when nothing narrows it there. Null otherwise.
 */
private fun declaredReceiverType(receiver: KtExpression?, resolver: KotlinResolver): KtTypeReference? {
    when (val r = unwrapParenthesized(receiver)) {
        is KtBinaryExpressionWithTypeRHS ->
            return r.right.takeIf { r.operationReference.getReferencedNameElementType() == KtTokens.AS_KEYWORD }
        is KtNameReferenceExpression -> {
            val name = r.getReferencedName()
            val offset = r.textRange.startOffset
            if (resolver.smartCastTypeAt(name, offset) != null) return null
            var child: KtElement = r
            var node: KtElement? = r.parent
            while (node != null) {
                when (node) {
                    is KtBlockExpression -> {
                        val local = node.statements.takeWhile { it !== child && it.textRange.endOffset <= offset }
                            .lastOrNull { it is KtProperty && it.name == name } as? KtProperty
                        if (local != null) return local.typeReference?.takeIf { it.typeElement !is KtNullableType }
                    }
                    is KtFunction -> node.valueParameters.firstOrNull { it.name == name }?.let { p ->
                        return p.typeReference?.takeIf { it.typeElement !is KtNullableType }
                    }
                    is KtClassOrObject, is KtFile -> return null
                }
                child = node
                node = node.parent
            }
            return null
        }
        else -> return null
    }
}
