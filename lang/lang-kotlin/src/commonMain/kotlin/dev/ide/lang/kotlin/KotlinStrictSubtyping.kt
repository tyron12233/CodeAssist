package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.canonicalTypeForCheck
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.flowProvenTypes
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.TypeRef

/**
 * Subtyping with type arguments, the way `AbstractTypeChecker.isSubtypeOf` decides it: the classifier must be a
 * supertype, nullability must fit, and each type argument must fit by the parameter's effective variance (the
 * use-site projection, else the declaration-site one): covariant, contravariant or equal.
 *
 * Three-valued, because the model is best-effort: `false` only when every type involved is known (project source
 * or a built-in whose supertypes are listed), `null` whenever something is not, so a caller reports only a proven
 * mismatch. A type parameter is only a subtype of itself and of `Any?` when [unboundedTypeParameter] says it has
 * no bound; any other type parameter is not judged.
 */
internal class KotlinStrictSubtyping(
    private val service: KotlinSymbolService,
    /** Captured types by the type-parameter name standing for each (see [capturedTypeParameter]): the lower and the
     *  upper bound of the unknown type a projected receiver's argument is. Only `Nothing` and the captured type
     *  itself are below `CapturedType(out B)`; it is below whatever `B` is below. */
    private val captured: Map<String, Pair<KotlinType, KotlinType?>> = emptyMap(),
    /** The upper bound of a rigid type parameter in scope ([UNBOUNDED_TYPE_PARAMETER] when it has none), or null when unknown. */
    private val bounds: (String) -> KotlinType? = { null },
) {
    fun equalTypes(a: KotlinType, b: KotlinType): Boolean? {
        val ab = subtype(a, b) ?: return null
        val ba = subtype(b, a) ?: return null
        return ab && ba
    }

    /** Whether [a] is a subtype of [b]; null when the model cannot tell. */
    fun subtype(a: KotlinType, b: KotlinType): Boolean? = subtype(a, b, 0)

    private fun subtype(a: KotlinType, b: KotlinType, depth: Int): Boolean? {
        if (depth > 8) return null
        if (a.nullable && !b.nullable) return false
        val an = canonicalTypeForCheck(a).qualifiedName
        val bn = canonicalTypeForCheck(b).qualifiedName
        if (!b.isTypeParameter && bn == "kotlin.Any") return true
        if (!a.isTypeParameter && an == "kotlin.Nothing") return true
        if (b.isTypeParameter) captured[b.qualifiedName]?.let { (lower, _) ->
            if (a.isTypeParameter && a.qualifiedName == b.qualifiedName) return true
            return subtype(a, lower.withNullable(lower.nullable || b.nullable), depth + 1)
        }
        if (a.isTypeParameter) captured[a.qualifiedName]?.let { (_, upper) ->
            // An upper bound not known (a bound that did not resolve) leaves the verdict open.
            return if (upper == null) null else subtype(upper.withNullable(upper.nullable || a.nullable), b, depth + 1)
        }
        if (a.isTypeParameter) {
            if (b.isTypeParameter && a.qualifiedName == b.qualifiedName) return true
            // A type parameter is a subtype of whatever its bound is (`Any?` when it has none).
            val bound = bounds(a.qualifiedName) ?: return null
            return subtype(bound.withNullable(bound.nullable || a.nullable), b, depth + 1)
        }
        if (b.isTypeParameter) {
            // Only `Nothing` and the parameter itself (or one bounded by it) are subtypes of a rigid type parameter.
            return if (bounds(b.qualifiedName) != null) false else null
        }
        if (!service.isKnownType(an) || !service.isKnownType(bn)) return null
        if (an == bn) return arguments(a.typeArguments, b.typeArguments, bn, depth)
        if (!service.supertypeClosureKnown(an)) return null
        val isSuper = service.supertypesOf(an).any { (it as? KotlinType)?.let { s -> canonicalTypeForCheck(s).qualifiedName == bn } == true }
        if (!isSuper) return false
        if (b.typeArguments.isEmpty()) return true
        val mapped = service.receiverSupertypeArgs(an, a.typeArguments, bn) ?: return null
        return arguments(mapped, b.typeArguments, bn, depth)
    }

    private fun arguments(aa: List<TypeRef>, ba: List<TypeRef>, classifier: String, depth: Int): Boolean? {
        if (aa.size != ba.size) return null
        if (aa.isEmpty()) return true
        val variance = service.classTypeParameterVariance(classifier)
        if (variance.size != aa.size) return null
        var unknown = false
        for (i in aa.indices) {
            val x = aa[i] as? KotlinType ?: return null
            val y = ba[i] as? KotlinType ?: return null
            if (isStar(y)) continue
            if (isStar(x)) return null
            // A use-site projection against the opposite declaration-site variance is a conflict, not judged here.
            if (y.projection.isNotEmpty() && variance[i].isNotEmpty() && y.projection != variance[i]) return null
            if (x.projection.isNotEmpty() && variance[i].isNotEmpty() && x.projection != variance[i]) return null
            val v = when {
                y.projection == "out" || variance[i] == "out" -> "out"
                y.projection == "in" || variance[i] == "in" -> "in"
                else -> ""
            }
            // A projected argument only fits a position projected the same way (`MyList<out Int>` is no `MyList<Int>`).
            if (x.projection.isNotEmpty() && x.projection != v) return false
            val r = when (v) {
                "out" -> subtype(x.withProjection(""), y.withProjection(""), depth + 1)
                "in" -> subtype(y.withProjection(""), x.withProjection(""), depth + 1)
                else -> {
                    val xy = subtype(x.withProjection(""), y.withProjection(""), depth + 1)
                    val yx = subtype(y.withProjection(""), x.withProjection(""), depth + 1)
                    if (xy == false || yx == false) false else if (xy == null || yx == null) null else true
                }
            }
            when (r) {
                false -> return false
                null -> unknown = true
                else -> {}
            }
        }
        return if (unknown) null else true
    }
}

/** The name a captured type stands under in a type handed to [KotlinStrictSubtyping], for the receiver class's type
 *  parameter [name]: one no written type parameter can have, so an argument's own `T` is never taken for it. */
internal fun capturedTypeParameter(name: String): String = "<captured>$name"

/** A star projection, however the type reached here spelled it. */
internal fun isStar(t: KotlinType): Boolean = t.projection == "*" || t.qualifiedName == "*"

/**
 * [t] with type parameters replaced per [bindings], keeping what [KotlinSymbolService.substitute] drops: the `?` on
 * a substituted parameter (`T?` with `T = String` is `String?`).
 */
internal fun substituteKeepingNullability(t: KotlinType, bindings: Map<String, KotlinType>): KotlinType {
    if (bindings.isEmpty()) return t
    if (t.isTypeParameter) {
        val b = bindings[t.qualifiedName] ?: return t
        val n = if (t.nullable) b.withNullable(true) else b
        return if (t.projection.isEmpty()) n else n.withProjection(t.projection)
    }
    if (t.typeArguments.isEmpty()) return t
    return KotlinType(
        t.qualifiedName, t.typeArguments.map { (it as? KotlinType)?.let { a -> substituteKeepingNullability(a, bindings) } ?: it },
        t.nullable, null, false, t.isExtensionFunctionType, t.isComposable, t.projection,
    )
}

/** The bound of a type parameter declared without one, for [KotlinStrictSubtyping]'s bound lookup. */
internal val UNBOUNDED_TYPE_PARAMETER = KotlinType("kotlin.Any", nullable = true)

/**
 * The type of [e] exactly as its declaration writes it, when [e] is a plain name of a local or parameter with a
 * written type that no smart cast has narrowed; null otherwise.
 */
internal fun KotlinSemanticChecks.writtenTypeOf(e: KtExpression, resolver: KotlinResolver): KotlinType? {
    val ref = unwrapParen(e) as? KtNameReferenceExpression ?: return null
    val text = explicitTypeTextOf(ref) ?: return null
    // A `var` is smart-cast by what it was last assigned, which its written type does not say.
    val decl = nearestLocalDecl(ref.getReferencedName(), ref.textRange.startOffset, ref)
    if (decl is dev.ide.kotlin.syntax.psi.KtProperty && (decl.isVar || decl.hasDelegate())) return null
    if ('(' in text || '@' in text || '&' in text) return null
    if (resolver.flowProvenTypes(ref, deep = true).isNotEmpty()) return null
    return service.typeFromText(text, resolver.fileContext, enclosingClassFqnOf(ref))
}

/**
 * The result of a call that writes every type argument of its one callee, a function in this file whose result
 * type is written: `getMyList<Int>(x)` for `fun <T> getMyList(t: T): MyList<T>` is `MyList<Int>`, whatever the
 * arguments are. Null for anything else.
 */
private fun KotlinSemanticChecks.explicitCallResultType(e: KtExpression, resolver: KotlinResolver): KotlinType? {
    val call = unwrapParen(e) as? dev.ide.kotlin.syntax.psi.KtCallExpression ?: return null
    val written = call.typeArgumentList?.arguments ?: return null
    if (call.calleeExpression !is KtNameReferenceExpression) return null
    val targets = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
        .distinctBy { t ->
            val at = ((t.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi)?.textRange?.startOffset
            if (t.origin.fromSource && at != null) Pair(t.origin.file?.toString(), at) else t
        }
    val sym = targets.singleOrNull() ?: return null
    if (!sym.origin.fromSource || sym.isExtension) return null
    val fn = (sym.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? dev.ide.kotlin.syntax.psi.KtNamedFunction ?: return null
    if (fn.containingFile !== call.containingFile || fn.parent !is dev.ide.kotlin.syntax.psi.KtFile) return null
    val names = fn.typeParameters.map { it.name ?: return null }
    if (names.size != written.size || names.isEmpty()) return null
    val retText = fn.typeReference?.text ?: return null
    if ('(' in retText || '@' in retText || '*' in retText) return null
    val ret = service.markDeclaredTypeParameters(service.typeFromText(retText, resolver.fileContext, null), names.toSet()) ?: return null
    val bindings = HashMap<String, KotlinType>()
    for ((i, a) in written.withIndex()) {
        if (a.projectionKind != dev.ide.kotlin.syntax.psi.KtProjectionKind.NONE) return null
        val text = a.typeReference?.text ?: return null
        if ('(' in text || '@' in text || '*' in text) return null
        val t = service.typeFromText(text, resolver.fileContext, enclosingClassFqnOf(call)) ?: return null
        if (!service.isKnownType(canonicalTypeForCheck(t).qualifiedName)) return null
        bindings[names[i]] = t
    }
    val result = substituteKeepingNullability(ret, bindings)
    return result.takeIf { r -> !mentionsAnyTypeParameter(r) && service.isKnownType(canonicalTypeForCheck(r).qualifiedName) }
}

private fun mentionsAnyTypeParameter(t: KotlinType): Boolean =
    t.isTypeParameter || t.typeArguments.any { (it as? KotlinType)?.let(::mentionsAnyTypeParameter) == true }

/**
 * A value whose written type differs from [declared] only in its type arguments, which do not fit by variance:
 * `Consumer<Int>` where `Consumer<Any>` is expected (`in T`), `Usual<Int>` for `Usual<Any>` (invariant). The
 * classifier-level mismatch is [KotlinSemanticChecks.isMismatch]'s; this only adds the argument check, and only
 * where both types are read off declarations.
 */
internal fun KotlinSemanticChecks.typeArgumentMismatch(declared: KotlinType, value: KtExpression, resolver: KotlinResolver): KotlinType? {
    if (declared.typeArguments.isEmpty() || declared.isTypeParameter) return null
    val actual = writtenTypeOf(value, resolver) ?: explicitCallResultType(value, resolver) ?: return null
    if (actual.typeArguments.isEmpty()) return null
    if (actual.qualifiedName.substringAfterLast('.') == "Array" || declared.qualifiedName.substringAfterLast('.') == "Array") {
        // `Array` is the one invariant built-in whose JVM form erases its argument: judged only when both are source.
        if (service.sourceClass(actual.qualifiedName) == null) return null
    }
    val verdict = KotlinStrictSubtyping(service).subtype(actual, declared)
    return actual.takeIf { verdict == false }
}
