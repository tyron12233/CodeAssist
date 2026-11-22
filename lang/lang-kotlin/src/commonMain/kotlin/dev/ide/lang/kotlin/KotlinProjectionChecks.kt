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
import dev.ide.lang.kotlin.resolve.inferType
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
internal fun KotlinSemanticChecks.memberProjectedOut(call: KtCallExpression, resolver: KotlinResolver): List<Diagnostic> {
    val pm = projectedMember(call, resolver) ?: return emptyList()
    val (name, typeRef, projections, _, tps, variances, member) = pm
    if (projections.none { it.projectionKind == KtProjectionKind.OUT || it.projectionKind == KtProjectionKind.STAR }) return emptyList()
    val args = call.valueArguments
    val out = ArrayList<Diagnostic>()
    for ((i, arg) in args.withIndex()) {
        // A spread hands an array to the vararg: no single value of the parameter's type ([projectedArgumentMismatch]).
        if (arg.isSpread) continue
        val p = member.paramTypes.getOrNull(parameterIndex(member, i)) as? KotlinType ?: continue
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
        out += Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "'$name' cannot be called with this argument: the receiver type '${typeRef.text}' has $kind projection, " +
                "and the parameter takes its '${p.qualifiedName}'",
            KotlinDiagnosticCodes.MEMBER_PROJECTED_OUT,
        )
    }
    return out
}

/** The parameter argument [i] lands on: a trailing vararg takes every argument from its position on. */
private fun parameterIndex(member: dev.ide.lang.kotlin.symbols.KotlinSymbol, i: Int): Int =
    if (member.varargParamIndex in 0..i) member.varargParamIndex else i

/**
 * ARGUMENT_TYPE_MISMATCH where a projected receiver's type argument sits INSIDE a member's parameter type:
 * `x.addAll(x)` on `x: MC<out Open>` for `fun addAll(c: C<T>)`. K2 substitutes a captured type for the projected
 * `T` (`CapturedType(out Open)`, an unknown subtype of `Open`), and only `Nothing` fits below it: the parameter is
 * `C<CapturedType(out Open)>`, which no `C<Open>` is. An `in` projection's captured type is above its type and
 * below the parameter's bound. A parameter that IS the projected `T` is [memberProjectedOut]'s.
 *
 * Judged by [KotlinStrictSubtyping] with the captured types standing in, so only a proven mismatch is reported,
 * and only for an argument whose type is read rather than inferred (a literal, a name with a written type, a call
 * whose type arguments are all written): an inferred one (`x.addAll(c())`) takes its type from the parameter.
 */
internal fun KotlinSemanticChecks.projectedArgumentMismatch(call: KtCallExpression, resolver: KotlinResolver): List<Diagnostic> =
    projectedArgumentMismatchOrNull(call, resolver).orEmpty()

private fun KotlinSemanticChecks.projectedArgumentMismatchOrNull(call: KtCallExpression, resolver: KotlinResolver): List<Diagnostic>? {
    val (_, typeRef, projections, fqn, tps, variances, member) = projectedMember(call, resolver) ?: return null
    val decl = (service.sourceClass(fqn)?.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClassOrObject
    // A `where` clause bounds a parameter where the declared bound list does not say.
    val boundsKnown = decl == null || decl.typeConstraintList == null
    val declaredBounds = service.classTypeParameterBounds(fqn).takeIf { it.size == tps.size }
    val captured = HashMap<String, Pair<KotlinType, KotlinType?>>()
    val bindings = HashMap<String, KotlinType>()
    val nothing = KotlinType("kotlin.Nothing")
    for ((k, tp) in tps.withIndex()) {
        val proj = projections[k]
        val written = proj.typeReference?.let { r ->
            service.typeFromText(r.text, resolver.fileContext, enclosingClassFqnOf(typeRef)) ?: return null
        }
        if (proj.projectionKind == KtProjectionKind.NONE) {
            bindings[tp] = written ?: return null
            continue
        }
        // A projection that repeats or contradicts the declared variance is no capture this models.
        if (variances[k].isNotEmpty()) return null
        val bound = if (!boundsKnown || declaredBounds == null) null
            else declaredBounds[k] ?: if (service.sourceClass(fqn) != null) UNBOUNDED_TYPE_PARAMETER else null
        val name = capturedTypeParameter(tp)
        captured[name] = when (proj.projectionKind) {
            KtProjectionKind.OUT -> nothing to (written ?: return null)
            KtProjectionKind.IN -> (written ?: return null) to bound
            else -> nothing to bound
        }
        bindings[tp] = KotlinType(name, isTypeParameter = true)
    }
    if (captured.isEmpty()) return null
    val subtyping = KotlinStrictSubtyping(service, captured = captured)
    val out = ArrayList<Diagnostic>()
    for ((i, arg) in call.valueArguments.withIndex()) {
        val element = member.paramTypes.getOrNull(parameterIndex(member, i)) as? KotlinType ?: continue
        // A spread hands the vararg an array of its elements: `Array<out T>`.
        val p = if (arg.isSpread) KotlinType("kotlin.Array", listOf(element.withProjection("out"))) else element
        if (p.isTypeParameter) continue
        val substituted = substituteKeepingNullability(p, bindings)
        if (!mentionsCaptured(substituted)) continue
        val value = arg.getArgumentExpression() ?: continue
        val actual = readArgumentType(value, resolver) ?: continue
        if (subtyping.subtype(actual, substituted) != false) continue
        val r = value.textRange
        out += Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Argument type mismatch: actual type is '${renderType(actual)}', but '${renderCaptured(substituted, captured)}' was expected",
            KotlinDiagnosticCodes.TYPE_MISMATCH,
        )
    }
    return out
}

private fun mentionsCaptured(t: KotlinType): Boolean =
    (t.isTypeParameter && t.qualifiedName.startsWith("<captured>")) ||
        t.typeArguments.any { (it as? KotlinType)?.let(::mentionsCaptured) == true }

private fun KotlinSemanticChecks.renderCaptured(t: KotlinType, captured: Map<String, Pair<KotlinType, KotlinType?>>): String {
    val rendered = renderType(t)
    return captured.keys.fold(rendered) { acc, name ->
        val (lower, upper) = captured.getValue(name)
        val shown = if (lower.qualifiedName == "kotlin.Nothing") "CapturedType(out ${upper?.let(::renderType) ?: "*"})" else "CapturedType(in ${renderType(lower)})"
        acc.replace(name.substringAfterLast('.'), shown)
    }
}

/** The type of an argument as it is written, not as inference would make it fit: a literal, a local or parameter
 *  with a written type no smart cast narrows, or a call whose type arguments are all written. */
private fun KotlinSemanticChecks.readArgumentType(e: KtExpression, resolver: KotlinResolver): KotlinType? {
    val u = unwrapParenthesized(e)
    val t = when {
        u is KtConstantExpression -> if (u.text == "null") null else resolver.inferType(u)
        u is KtStringTemplateExpression -> resolver.inferType(u)
        u is KtNameReferenceExpression -> writtenTypeOf(u, resolver)
        u is KtCallExpression && u.typeArgumentList != null -> resolver.inferType(u)
        else -> null
    } ?: return null
    if (mentionsTypeParameter(t) || isFunctional(t)) return null
    return t
}

private fun mentionsTypeParameter(t: KotlinType): Boolean =
    t.isTypeParameter || t.typeArguments.any { (it as? KotlinType)?.let(::mentionsTypeParameter) == true }

/** A member call on a receiver whose written type projects some of its class's type arguments, with the one
 *  member it can be (see [memberProjectedOut] for which calls qualify). */
private data class ProjectedMember(
    val name: String,
    val typeRef: KtTypeReference,
    val projections: List<dev.ide.kotlin.syntax.psi.KtTypeProjection>,
    val fqn: String,
    val tps: List<String>,
    val variances: List<String>,
    val member: dev.ide.lang.kotlin.symbols.KotlinSymbol,
)

private fun KotlinSemanticChecks.projectedMember(call: KtCallExpression, resolver: KotlinResolver): ProjectedMember? {
    val dot = call.parent as? KtDotQualifiedExpression ?: return null
    if (dot.selectorExpression !== call) return null
    val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
    if (call.typeArgumentList != null) return null
    val args = call.valueArguments
    if (args.isEmpty() || args.any { it.getArgumentName() != null }) return null
    val typeRef = declaredReceiverType(dot.receiverExpression, resolver) ?: return null
    val user = typeRef.typeElement as? KtUserType ?: return null
    val projections = user.typeArguments
    if (projections.isEmpty() || projections.all { it.projectionKind == KtProjectionKind.NONE }) return null
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
        if (m.varargParamIndex >= 0) {
            // A trailing vararg takes every argument from its position on.
            m.varargParamIndex == m.paramTypes.size - 1 && args.size >= m.varargParamIndex
        } else m.paramTypes.size >= args.size && args.none { it.isSpread } &&
            (args.size until m.paramTypes.size).all { i -> m.paramHasDefault.getOrNull(i) == true }
    }
    // An override and the member it overrides both list (`MutableList.add` and `MutableCollection.add`): one signature.
    val member = fits.distinctBy { m -> m.paramTypes.map { (it as? KotlinType)?.qualifiedName } }.singleOrNull() ?: return null
    if (member.typeParameters.isNotEmpty()) return null
    // What the call resolved to must be that member, not an extension in scope.
    val targets = resolver.callTargets(call)
    if (targets.any { it.isExtension }) return null
    return ProjectedMember(name, typeRef, projections, fqn, tps, variances, member)
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
