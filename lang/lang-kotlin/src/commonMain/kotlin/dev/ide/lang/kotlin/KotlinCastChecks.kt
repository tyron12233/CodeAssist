package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtIsExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNullableType
import dev.ide.kotlin.syntax.psi.KtProjectionKind
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtWhenConditionIsPattern
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.flowProvenTypes
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.symbols.KotlinType

/**
 * `is` and `as` against a type the runtime cannot check, or that names a generic class without its arguments:
 *
 *  - CANNOT_CHECK_FOR_ERASED, ported from `FirCastOperatorsChecker`'s `checkCastErased` and
 *    `isCastErased`/`findStaticallyKnownSubtype` (`FirCastDiagnosticsHelpers.kt`): `x is T` for a non-reified
 *    `T`, and `x is List<String>` where nothing in `x`'s type fixes the list's element type.
 *  - NO_TYPE_ARGUMENTS_ON_RHS, ported from the body resolver's `withTypeArgumentsForBareType` and
 *    `computeRepresentativeTypeForBareType`: `x is List` / `x as Map` where `x`'s type does not supply the
 *    arguments a bare type takes from it.
 *
 * Both turn on the operand's type, and K2 judges an erased check against the ORIGINAL type (a smart cast only
 * relaxes it). So the operand must be plainly typed (a literal, or a local or parameter with a written type)
 * and not smart-cast at that point, and the generic-target rules only judge an operand whose type has no type
 * arguments: such a type can never supply the target's arguments.
 */
internal object KotlinCastChecks {

    fun isExpression(checks: KotlinSemanticChecks, e: KtIsExpression, resolver: KotlinResolver): Diagnostic? {
        val typeRef = e.typeReference ?: return null
        val subject = checks.plainSubjectType(e.leftHandSide ?: return null, resolver) ?: return null
        return checks.noTypeArguments(typeRef, subject, resolver) ?: checks.erased(typeRef, subject, resolver)
    }

    fun cast(checks: KotlinSemanticChecks, e: KtBinaryExpressionWithTypeRHS, resolver: KotlinResolver): Diagnostic? {
        val op = e.operationReference.getReferencedNameElementType()
        if (op != KtTokens.AS_KEYWORD && op != KtTokens.AS_SAFE) return null
        val typeRef = e.right ?: return null
        val subject = checks.plainSubjectType(e.left ?: return null, resolver) ?: return null
        // An erased `as` is the UNCHECKED_CAST warning, so only the bare type is an error here.
        return checks.noTypeArguments(typeRef, subject, resolver)
    }

    fun whenConditions(checks: KotlinSemanticChecks, w: KtWhenExpression, resolver: KotlinResolver): List<Diagnostic> {
        val subjectExpr = w.subjectExpression ?: return emptyList()
        if (subjectExpr is KtProperty) return emptyList()
        val subject = checks.plainSubjectType(subjectExpr, resolver) ?: return emptyList()
        val out = ArrayList<Diagnostic>()
        for (entry in w.entries) for (c in entry.conditions) {
            val typeRef = (c as? KtWhenConditionIsPattern)?.typeReference ?: continue
            (checks.noTypeArguments(typeRef, subject, resolver) ?: checks.erased(typeRef, subject, resolver))?.let { out += it }
        }
        return out
    }

    /** The operand's type when it is read off the tree and not smart-cast here; null otherwise. */
    private fun KotlinSemanticChecks.plainSubjectType(left: KtExpression, resolver: KotlinResolver): KotlinType? {
        if (!plainlyTyped(left)) return null
        val u = unwrapParen(left)
        if (u is KtNameReferenceExpression) {
            if (resolver.flowProvenTypes(u, deep = true).isNotEmpty()) return null
            // A value of a type parameter's type: what it is at runtime is not a class this can name.
            val written = explicitTypeTextOf(u)?.substringBefore('<')?.trim() ?: return null
            if (typeParameterInScope(u, written) != null) return null
        }
        val t = resolver.inferType(left) ?: return null
        if (t.isTypeParameter || !service.isKnownType(t.qualifiedName)) return null
        return t
    }

    /** A target written as a bare generic class, which takes its arguments from a subject type that has none. */
    private fun KotlinSemanticChecks.noTypeArguments(typeRef: KtTypeReference, subject: KotlinType, resolver: KotlinResolver): Diagnostic? {
        if (subject.typeArguments.isNotEmpty()) return null
        val user = userTypeOf(typeRef) ?: return null
        if (user.typeArgumentList != null) return null
        val name = user.referencedName ?: return null
        // A typealias may or may not be usable as a bare type (`canBeUsedAsBareType`), which this does not model.
        if (service.isProjectTypeAlias(name)) return null
        if (typeParameterInScope(typeRef, name) != null) return null
        val text = user.text
        val target = service.typeFromText(text, resolver.fileContext, enclosingClassFqnOf(typeRef)) ?: return null
        if (target.isTypeParameter) return null
        val fqn = target.qualifiedName
        val source = service.sourceClass(fqn)
        // A local class's own parameters are taken as declared (`firClass.defaultType()`).
        if (source?.isLocal == true) return null
        val params = service.classTypeParameters(fqn).ifEmpty { service.builtinShapeFor(fqn)?.typeParameters.orEmpty() }
        if (params.isEmpty()) return null
        val r = typeRef.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "${params.size} type argument${if (params.size == 1) "" else "s"} expected for '${name}'", KotlinDiagnosticCodes.CAST_ERASED,
        )
    }

    /** `isCastErased(subject, target)` for the two shapes judged here. */
    private fun KotlinSemanticChecks.erased(typeRef: KtTypeReference, subject: KotlinType, resolver: KotlinResolver): Diagnostic? {
        val user = userTypeOf(typeRef) ?: return null
        val name = user.referencedName ?: return null
        val erased = if (user.typeArgumentList == null) {
            // Downcasting to a non-reified type parameter: always erased, since a class type is never a
            // subtype of a type parameter (so this is no upcast).
            if (user.qualifier != null) return null
            val owner = typeParameterInScope(typeRef, name) ?: return null
            owner.typeParameters.first { it.name == name }.hasModifier(KtTokens.REIFIED_KEYWORD).not()
        } else {
            genericTargetErased(user, subject, typeRef, resolver) ?: return null
        }
        if (!erased) return null
        val r = typeRef.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Cannot check for instance of erased type '${typeRef.text.trim()}'", KotlinDiagnosticCodes.CAST_ERASED,
        )
    }

    /**
     * Whether `subject is C<args>` is erased for a subject type without type arguments. `findStaticallyKnownSubtype`
     * then determines none of `C`'s parameters, leaving `C<T1..Tn>` with `C`'s own parameters, and the check is
     * erased unless that is a subtype of the target: every argument must be a star, or `Any?` at a covariant
     * position (an unbounded `T` is below it), or `Nothing` at a contravariant one. Null when it cannot tell.
     */
    private fun KotlinSemanticChecks.genericTargetErased(user: KtUserType, subject: KotlinType, typeRef: KtTypeReference, resolver: KotlinResolver): Boolean? {
        if (subject.typeArguments.isNotEmpty()) return upcastTargetErased(user, subject, typeRef, resolver)
        if (user.qualifier != null) return null
        if (service.isProjectTypeAlias(user.referencedName ?: return null)) return null
        val base = service.typeFromText(user.referencedName, resolver.fileContext, enclosingClassFqnOf(typeRef)) ?: return null
        if (base.isTypeParameter) return null
        val fqn = base.qualifiedName
        if (service.sourceClass(fqn)?.isLocal == true) return null
        // An upcast (the subject's class already inherits the target's) is never erased.
        if (base.withNullable(false).isAssignableFrom(subject.withNullable(false))) return null
        if (!service.supertypeClosureKnown(subject.qualifiedName)) return null
        val shape = service.builtinShapeFor(fqn)
        val params = service.classTypeParameters(fqn).ifEmpty { shape?.typeParameters.orEmpty() }
        val args = user.typeArguments
        if (params.isEmpty() || params.size != args.size) return null
        val variances = service.classTypeParameterVariance(fqn).takeIf { it.size == params.size }
            ?: shape?.typeParameterVariances?.takeIf { it.size == params.size } ?: return null
        val bounds = service.classTypeParameterBounds(fqn)
        for ((i, arg) in args.withIndex()) {
            if (arg.projectionKind == KtProjectionKind.STAR) continue
            val argText = arg.typeReference?.text?.trim() ?: return null
            val variance = when (arg.projectionKind) {
                KtProjectionKind.OUT -> "out"
                KtProjectionKind.IN -> "in"
                else -> variances[i]
            }
            val holds = when (variance) {
                // `T <: X` for the class's own `T`: only its bound or above, and an unbounded `T` is bounded by `Any?`.
                "out" -> {
                    val bound = bounds.getOrNull(i)
                    if (bound != null && !(bound.qualifiedName == "kotlin.Any" && bound.nullable)) return null
                    argText == "Any?" || argText == "kotlin.Any?"
                }
                // `X <: T`: only `Nothing`.
                "in" -> argText == "Nothing" || argText == "kotlin.Nothing"
                else -> false
            }
            if (!holds) return true
        }
        return false
    }

    /**
     * `subject is C<args>` where the subject's class is `C` or inherits it (`MutableList<String> is
     * MutableList<CharSequence>`): `findStaticallyKnownSubtype` is then the subject's own view of `C`, and the check
     * is erased when that is not a subtype of the target. Null when either cannot be told; a downcast (`C` below
     * the subject's class), whose arguments would have to be solved for, is not judged.
     */
    private fun KotlinSemanticChecks.upcastTargetErased(user: KtUserType, subject: KotlinType, typeRef: KtTypeReference, resolver: KotlinResolver): Boolean? {
        if (user.qualifier != null) return null
        val name = user.referencedName ?: return null
        if (service.isProjectTypeAlias(name) || typeParameterInScope(typeRef, name) != null) return null
        val target = service.typeFromText(user.text, resolver.fileContext, enclosingClassFqnOf(typeRef)) ?: return null
        if (target.isTypeParameter || !service.isKnownType(target.qualifiedName)) return null
        if (service.sourceClass(target.qualifiedName)?.isLocal == true) return null
        // Every argument of the target must name known classes (a type parameter there is the other rule's).
        fun known(t: KotlinType): Boolean = t.qualifiedName == "*" || (!t.isTypeParameter && service.isKnownType(t.qualifiedName) &&
            t.typeArguments.all { (it as? KotlinType)?.let(::known) == true })
        if (!target.typeArguments.all { (it as? KotlinType)?.let(::known) == true }) return null
        if (!subject.typeArguments.all { (it as? KotlinType)?.let(::known) == true }) return null
        val view: KotlinType = if (subject.qualifiedName == target.qualifiedName) subject.withNullable(false) else {
            if (!service.supertypeClosureKnown(subject.qualifiedName)) return null
            val inherits = service.supertypesOf(subject.qualifiedName).any { (it as? KotlinType)?.qualifiedName == target.qualifiedName }
            if (!inherits) return null
            val args = service.receiverSupertypeArgs(subject.qualifiedName, subject.typeArguments, target.qualifiedName) ?: return null
            KotlinType(target.qualifiedName, args)
        }
        return when (KotlinStrictSubtyping(service).subtype(view, target.withNullable(false))) {
            false -> true
            true -> false
            null -> null
        }
    }

    private fun userTypeOf(typeRef: KtTypeReference): KtUserType? = when (val el = typeRef.typeElement) {
        is KtUserType -> el
        is KtNullableType -> el.innerType as? KtUserType
        else -> null
    }

    /** The nearest declaration in whose scope [name] is a type parameter, or null. */
    private fun typeParameterInScope(from: KtElement, name: String): KtTypeParameterListOwner? {
        var p = from.parent
        while (p != null && p !is KtFile) {
            if (p is KtTypeParameterListOwner && p.typeParameters.any { it.name == name }) return p
            // Only an inner class sees its outer class's type parameters.
            if (p is KtClassOrObject && !p.hasModifier(KtTokens.INNER_KEYWORD)) return null
            p = p.parent
        }
        return null
    }
}
