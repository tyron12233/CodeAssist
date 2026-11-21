package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtWhenConditionWithExpression
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.canonicalTypeForCheck
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.symbols.KotlinType

/**
 * `==`/`!=`/`===`/`!==` between types that can never hold the same value, ported from K2's
 * `FirEqualityCompatibilityChecker` (with the `TypeInfo` and RULES1 helpers of `FirTypeCompatibilityHelpers`):
 * INCOMPATIBLE_ENUM_COMPARISON_ERROR when an enum is involved (`e1 == e2` of two enums, an enum against an
 * interface it does not implement), EQUALITY_NOT_APPLICABLE otherwise (`"" == 1`), and INCOMPATIBLE_TYPES for a
 * `when` branch condition the subject is compared to.
 *
 * K2 judges the operands by their ORIGINAL types: a smart cast only ever adds a warning. So an operand is
 * typed only where that type is plain: a literal, a local or parameter with a written type, an enum entry, a
 * constructor call, `this` in an enum. A type must also be one whose kind is certain: a built-in value type
 * or a project-source class; anything else (a classpath class whose modality, or value-class-ness, the index
 * does not record) backs off. The verdicts that K2 relaxes to a warning in the current language version
 * (two nullable enums, two types that may both have subtypes) are left to the warning checks.
 */
internal object KotlinEqualityChecks {

    private class TypeInfo(
        val type: KotlinType,
        val nullable: Boolean,
        val isEnum: Boolean,
        val isPrimitive: Boolean,
        val isBuiltin: Boolean,
        /** RULES1's "final class": a class (not an interface) that cannot be extended. */
        val isFinalClass: Boolean,
        val isClass: Boolean,
    ) {
        /** K1's `canHaveSubtypes` on the non-null type: an enum or a non-final class may. */
        val canHaveSubtypesK1: Boolean get() = isEnum || !isFinalClass
    }

    private enum class Applicability { APPLICABLE, GENERALLY_INAPPLICABLE, INAPPLICABLE_AS_ENUMS }

    private val PRIMITIVES = setOf(
        "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Double", "kotlin.Float",
        "kotlin.Boolean", "kotlin.Char",
    )
    private val UNSIGNED = setOf("kotlin.UInt", "kotlin.ULong", "kotlin.UShort", "kotlin.UByte")
    private val NUMERIC = setOf("kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Double", "kotlin.Float")

    fun binary(checks: KotlinSemanticChecks, e: KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
        val identity = when (e.operationToken) {
            KtTokens.EQEQ, KtTokens.EXCLEQ -> false
            KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ -> true
            else -> return null
        }
        val l = checks.operandInfo(e.left ?: return null, resolver) ?: return null
        val r = checks.operandInfo(e.right ?: return null, resolver) ?: return null
        val applicability = if (identity) identityApplicability(l, r) else equalityApplicability(l, r)
        return report(e, applicability, l, r, whenCondition = false)
    }

    /** `when (s) { x -> }` compares the subject to each branch value; K2 checks that comparison like an `==`
     *  whose source is the condition, so a generally inapplicable one is INCOMPATIBLE_TYPES. */
    fun whenConditions(checks: KotlinSemanticChecks, w: KtWhenExpression, resolver: KotlinResolver): List<Diagnostic> {
        val subject = w.subjectExpression ?: return emptyList()
        if (subject is KtProperty) return emptyList()
        val s = checks.operandInfo(subject, resolver) ?: return emptyList()
        val out = ArrayList<Diagnostic>()
        for (entry in w.entries) for (c in entry.conditions) {
            val ce = (c as? KtWhenConditionWithExpression)?.expression ?: continue
            val ci = checks.operandInfo(ce, resolver) ?: continue
            // Two built-in value types are what `incompatibleWhenConditions` already reports.
            if (s.isBuiltin && !s.isEnum && ci.isBuiltin && !ci.isEnum) continue
            report(ce, equalityApplicability(s, ci), s, ci, whenCondition = true)?.let { out += it }
        }
        return out
    }

    private fun report(at: KtElement, applicability: Applicability, l: TypeInfo, r: TypeInfo, whenCondition: Boolean): Diagnostic? {
        if (applicability == Applicability.APPLICABLE) return null
        // `isCaseMissedByK1Intersector && isCaseMissedByAdditionalK1IncompatibleEnumsCheck`, with the K1
        // behavior replicated (ReportErrorsForComparisonOperators is off): both types may have subtypes, and
        // either neither is an enum or both are nullable.
        val forceWarning = l.canHaveSubtypesK1 && r.canHaveSubtypesK1 &&
            ((!l.isEnum && !r.isEnum) || (l.nullable && r.nullable))
        val range = at.textRange
        fun error(message: String) =
            Diagnostic(TextRange(range.startOffset, range.endOffset), Severity.ERROR, message, KotlinDiagnosticCodes.INCOMPARABLE_EQUALITY)
        return when (applicability) {
            Applicability.INAPPLICABLE_AS_ENUMS -> {
                val bothNullableEnums = l.isEnum && l.nullable && r.isEnum && r.nullable
                if (forceWarning || bothNullableEnums) null
                else error("Comparison of incompatible enums '${l.type}' and '${r.type}' is always unsuccessful")
            }
            else -> when {
                forceWarning -> null
                whenCondition -> error("Incompatible types '${r.type}' and '${l.type}'")
                else -> error("Operator '${(at as? KtBinaryExpression)?.operationReference?.text ?: "=="}' cannot be applied to '${l.type}' and '${r.type}'")
            }
        }
    }

    /** `checkEqualityApplicability`: only a comparison involving a built-in is checked (StrictEquals is off). */
    private fun equalityApplicability(l: TypeInfo, r: TypeInfo): Applicability {
        // Integer literals adapt to the other side's numeric type, which this does not model.
        if (l.type.qualifiedName in NUMERIC && r.type.qualifiedName in NUMERIC) return Applicability.APPLICABLE
        return if ((l.isBuiltin || r.isBuiltin) && shouldReportAsPerRules1(l, r)) inapplicability(l, r)
        else Applicability.APPLICABLE
    }

    /** `checkIdentityApplicability`, for types with identity: a primitive or unsigned value has none, which is
     *  FORBIDDEN_IDENTITY_EQUALS and reported elsewhere. */
    private fun identityApplicability(l: TypeInfo, r: TypeInfo): Applicability {
        if (l.isPrimitive || r.isPrimitive || l.type.qualifiedName in UNSIGNED || r.type.qualifiedName in UNSIGNED) return Applicability.APPLICABLE
        val oneIsNotNull = !l.nullable || !r.nullable
        return if (oneIsNotNull && shouldReportAsPerRules1(l, r)) inapplicability(l, r) else Applicability.APPLICABLE
    }

    /** `getInapplicabilityFor`: K1 called a pair involving an enum "incompatible enums" when its intersector
     *  saw a possibly non-empty intersection (both sides can have subtypes). */
    private fun inapplicability(l: TypeInfo, r: TypeInfo): Applicability =
        if (l.canHaveSubtypesK1 && r.canHaveSubtypesK1 && (l.isEnum || r.isEnum)) Applicability.INAPPLICABLE_AS_ENUMS
        else Applicability.GENERALLY_INAPPLICABLE

    /** RULES1 (KT-57779): with a final class on either side, or two classes, the types must be related. */
    private fun shouldReportAsPerRules1(l: TypeInfo, r: TypeInfo): Boolean {
        val oneIsFinal = l.isFinalClass || r.isFinalClass
        val bothAreClasses = l.isClass && r.isClass
        if (!oneIsFinal && !bothAreClasses) return false
        val a = l.type.withNullable(false)
        val b = r.type.withNullable(false)
        return !a.isAssignableFrom(b) && !b.isAssignableFrom(a)
    }

    /** The original (not smart-cast) type of [e] with what RULES1 needs to know of it, or null when it is not
     *  plainly known. */
    private fun KotlinSemanticChecks.operandInfo(e: KtExpression, resolver: KotlinResolver): TypeInfo? {
        val t = originalType(unwrapParen(e), resolver) ?: return null
        if (t.isTypeParameter) return null
        val type = canonicalTypeForCheck(t)
        val fqn = type.qualifiedName
        if (fqn == "kotlin.Any" || fqn == "kotlin.Nothing") return null
        val primitive = fqn in PRIMITIVES
        if (primitive || fqn in UNSIGNED || fqn == "kotlin.String") {
            return TypeInfo(type, t.nullable, isEnum = false, isPrimitive = primitive, isBuiltin = true, isFinalClass = true, isClass = true)
        }
        // Only a project-source class is known well enough: its kind, its modality, whether it is a value class.
        val rc = service.sourceClass(fqn) ?: return null
        if (rc.isLocal || rc.isAnnotation || !service.supertypeClosureKnown(fqn)) return null
        val psi = (rc.node as? KotlinDomNode)?.psi as? KtClass
        if (psi != null && (psi.hasModifier(KtTokens.VALUE_KEYWORD) || psi.hasModifier(KtTokens.INLINE_KEYWORD))) return null
        if (psi == null && !rc.isEnum && !rc.isInterface) return null
        // A final generic class's subtyping in K1 also looked at its arguments; that is not modelled.
        if (rc.typeParameterNames.isNotEmpty() && !rc.isEnum) return null
        val finalClass = rc.isEnum || rc.isObject || rc.isFinalClass
        return TypeInfo(
            type, t.nullable, isEnum = rc.isEnum, isPrimitive = false, isBuiltin = rc.isEnum,
            isFinalClass = finalClass && !rc.isInterface, isClass = !rc.isInterface,
        )
    }

    private fun KotlinSemanticChecks.originalType(u: KtExpression, resolver: KotlinResolver): KotlinType? = when (u) {
        is KtNameReferenceExpression -> declaredType(u, resolver)
        // `E.A`: an enum entry read through its class.
        is KtDotQualifiedExpression -> {
            val entry = (u.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
            val t = entry?.let { resolver.inferType(u) }
            t?.takeIf { !it.isTypeParameter && service.enumConstantsOf(it.qualifiedName).any { c -> c.name == entry } &&
                (u.receiverExpression as? KtNameReferenceExpression)?.getReferencedName() == it.qualifiedName.substringAfterLast('.') }
        }
        // A constructor call: the class it constructs.
        is KtCallExpression -> {
            val name = (u.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            resolver.inferType(u)?.takeIf { name != null && it.qualifiedName.substringAfterLast('.') == name && service.sourceClass(it.qualifiedName) != null }
        }
        // `this` in an enum class's own body is that enum: a smart cast cannot narrow a final type. Inside an
        // entry's body it is the entry's own anonymous class instead, which this does not model.
        is KtThisExpression -> if (u.getLabelName() == null && nearestClass(u) !is KtEnumEntry) {
            enclosingClassFqnOf(u)?.takeIf { service.sourceClass(it)?.isEnum == true }?.let { service.typeByFqn(it) }
        } else null
        else -> if (isLiteral(u) && !isNullLiteral(u)) resolver.inferType(u) else null
    }

    private fun nearestClass(e: KtElement): KtClassOrObject? {
        var p = e.parent
        while (p != null && p !is KtClassOrObject) p = p.parent
        return p as? KtClassOrObject
    }

    /** The type written on the local or parameter [ref] names, nullability included. */
    private fun KotlinSemanticChecks.declaredType(ref: KtNameReferenceExpression, resolver: KotlinResolver): KotlinType? {
        val decl = nearestLocalDecl(ref.getReferencedName(), ref.textRange.startOffset, ref) ?: return null
        val typeRef = when (decl) {
            is KtProperty -> decl.typeReference
            is KtParameter -> decl.typeReference?.takeIf { !decl.isVarArg }
            else -> null
        } ?: return null
        val text = typeRef.text.trim()
        if ('<' in text || service.isProjectTypeAlias(text.removeSuffix("?").substringAfterLast('.'))) return null
        return service.typeFromText(text, resolver.fileContext, enclosingClassFqnOf(typeRef))
    }
}
