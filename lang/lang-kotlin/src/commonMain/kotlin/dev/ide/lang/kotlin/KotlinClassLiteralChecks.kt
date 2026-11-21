package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClassLiteralExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtNullableType
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeParameter
import dev.ide.kotlin.syntax.psi.KtTypeArgumentList
import dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.calleeFunctionOf
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf

/**
 * `X::class` with a left side that names no class, ported from `FirClassLiteralChecker`:
 *
 *  - NULLABLE_TYPE_IN_CLASS_LITERAL_LHS / EXPRESSION_OF_NULLABLE_TYPE_IN_CLASS_LITERAL_LHS: a `?` written
 *    before the `::` (`A?::class`), which the checker finds as a QUEST token directly under the literal;
 *  - CLASS_LITERAL_LHS_NOT_A_CLASS: type arguments on the left (`A<String>::class`), since a class literal
 *    denotes a class, not a type. `Array<…>` (allowed for some element types) and typealiases back off;
 *  - TYPE_PARAMETER_AS_REIFIED: `T::class` for a type parameter that is not `reified`.
 *
 * Also TYPE_PARAMETER_AS_REIFIED from `FirReifiedChecker`: a non-reified type parameter given as the explicit
 * type argument of a `reified` one (`foo<T>()`), judged for project functions and the standard library's
 * array and enum factories, whose parameters are known to be reified.
 */
internal object KotlinClassLiteralChecks {

    fun classLiteral(checks: KotlinSemanticChecks, e: KtClassLiteralExpression, resolver: KotlinResolver): Diagnostic? {
        val lhs = e.receiverExpression ?: return null
        val r = e.textRange
        fun error(message: String) = Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, message, KotlinDiagnosticCodes.CLASS_LITERAL)
        if (e.children.any { it.elementType == KtTokens.QUEST }) return error("Type in a class literal must not be nullable")
        // A lone name: a non-reified type parameter has no class at runtime.
        if (lhs is KtNameReferenceExpression) {
            val tp = typeParameterNamed(e, lhs.getReferencedName()) ?: return null
            return if (tp.hasModifier(KtTokens.REIFIED_KEYWORD)) null
            else error("Cannot use '${lhs.getReferencedName()}' as reified type parameter. Use a class instead")
        }
        val segments = segments(lhs) ?: return null
        if (segments.none { it.second != null }) return null
        val path = segments.joinToString(".") { it.first }
        if (checks.service.isProjectTypeAlias(segments.last().first)) return null
        val fqn = checks.service.resolveTypeName(path, resolver.fileContext, enclosingClassFqnOf(e)) ?: return null
        if (fqn == "kotlin.Array") return null
        // A wrong count is WRONG_NUMBER_OF_TYPE_ARGUMENTS instead.
        val own = checks.service.classTypeParameters(fqn).ifEmpty { checks.service.builtinShapeFor(fqn)?.typeParameters.orEmpty() }
        if ((segments.last().second?.arguments?.size ?: 0) != own.size) return null
        if (!checks.service.isKnownType(fqn)) return null
        return error("Only classes are allowed on the left hand side of a class literal")
    }

    /** `A<…>`, `A.B<…>`, `A<…>.B<…>`: each segment's name and type arguments (the parser reads a generic type
     *  before `::` as a call without an argument list), or null when the left side is not a dotted type. */
    private fun segments(lhs: KtExpression): List<Pair<String, KtTypeArgumentList?>>? = when (lhs) {
        is KtNameReferenceExpression -> listOf(lhs.getReferencedName() to null)
        is KtCallExpression -> {
            val name = (lhs.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            if (name != null && lhs.valueArgumentList == null && lhs.lambdaArguments.isEmpty()) listOf(name to lhs.typeArgumentList) else null
        }
        is KtDotQualifiedExpression -> {
            val head = lhs.receiverExpressionOrNull?.let(::segments)
            val tail = lhs.selectorExpression?.let(::segments)
            if (head == null || tail == null) null else head + tail
        }
        else -> null
    }

    fun reifiedTypeArgument(checks: KotlinSemanticChecks, call: KtCallExpression, resolver: KotlinResolver): List<Diagnostic> {
        val args = call.typeArguments
        if (args.isEmpty() || call.valueArgumentList == null && call.lambdaArguments.isEmpty()) return emptyList()
        val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return emptyList()
        val reified: List<Boolean> = STDLIB_REIFIED[name]?.takeIf { !declaredInFile(call, name) }
            ?: run {
                val fn = (resolver.calleeFunctionOf(call)?.takeIf { it.origin.fromSource }?.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction
                    ?: return emptyList()
                if (fn.name != name) return emptyList()
                fn.typeParameters.map { it.hasModifier(KtTokens.REIFIED_KEYWORD) }
            }
        if (reified.size != args.size) return emptyList()
        val out = ArrayList<Diagnostic>()
        for ((i, arg) in args.withIndex()) {
            if (!reified[i]) continue
            val typeRef = arg.typeReference ?: continue
            val user = when (val el = typeRef.typeElement) {
                is KtUserType -> el
                is KtNullableType -> el.innerType as? KtUserType
                else -> null
            } ?: continue
            if (user.qualifier != null || user.typeArgumentList != null) continue
            val tpName = user.referencedName ?: continue
            val tp = typeParameterNamed(call, tpName) ?: continue
            if (tp.hasModifier(KtTokens.REIFIED_KEYWORD)) continue
            val r = typeRef.textRange
            out += Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "Cannot use '$tpName' as reified type parameter. Use a class instead", KotlinDiagnosticCodes.CLASS_LITERAL,
            )
        }
        return out
    }

    /** The standard library's functions whose type parameter is `reified`, by name. */
    private val STDLIB_REIFIED = mapOf(
        "arrayOf" to listOf(true), "arrayOfNulls" to listOf(true), "emptyArray" to listOf(true),
        "enumValues" to listOf(true), "enumValueOf" to listOf(true), "enumEntries" to listOf(true), "typeOf" to listOf(true),
    )

    private fun declaredInFile(e: KtElement, name: String): Boolean =
        (e.containingKtFile.declarations).any { it is KtNamedFunction && it.name == name }

    /** The type parameter [name] in scope at [from], or null when [name] is no type parameter there. */
    private fun typeParameterNamed(from: KtElement, name: String): KtTypeParameter? {
        var p = from.parent
        while (p != null && p !is KtFile) {
            if (p is KtTypeParameterListOwner) p.typeParameters.firstOrNull { it.name == name }?.let { return it }
            if (p is KtClassOrObject && !p.hasModifier(KtTokens.INNER_KEYWORD)) return null
            p = p.parent
        }
        return null
    }
}
