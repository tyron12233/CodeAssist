package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtAnnotationEntry
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtCollectionLiteralExpression
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.canonicalTypeForCheck
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.symbols.KotlinType

/*
 * An annotation's arguments against its constructor's parameters (ARGUMENT_TYPE_MISMATCH): `@BadAnnotation(1)` for
 * `annotation class BadAnnotation(val s: String)`, `@Deprecated("", level = MyLevel.ERROR)` with a project enum
 * standing where `DeprecationLevel` is expected, `@Special(Nested)` for a `KClass<*>` parameter. K2 resolves an
 * annotation call like any constructor call (FirAnnotationCallResolution through the call resolver), so its
 * applicability check is the one ordinary calls get.
 *
 * Judged by the same assignability rule as other arguments ([KotlinSemanticChecks.isMismatch]): both types known
 * and concrete, numbers left to [annotationNumericArguments]. An argument this cannot type (a collection literal,
 * a spread) is skipped, and so is a call whose arguments cannot all be placed.
 */

internal fun KotlinSemanticChecks.annotationArgumentTypeCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtAnnotationEntry::class) { psi ->
        KotlinPerf.span("sem.annotationArgTypes") {
            if (resolveReady) reportAll(annotationArgumentTypes(psi as KtAnnotationEntry, resolver))
        }
    },
)

private class AnnotationParam(val name: String?, val type: KotlinType, val vararg: Boolean)

private fun KotlinSemanticChecks.annotationArgumentTypes(entry: KtAnnotationEntry, resolver: KotlinResolver): List<Diagnostic> {
    val args = entry.valueArguments
    if (args.isEmpty() || args.any { it.isSpread }) return emptyList()
    val typeRef = entry.typeReference ?: return emptyList()
    val fqn = classifierFqnOf(typeRef, resolver) ?: return emptyList()
    val params = annotationParameters(fqn, resolver) ?: return emptyList()
    val out = ArrayList<Diagnostic>()
    fun tooMany(arg: dev.ide.kotlin.syntax.psi.KtValueArgument) {
        val r = (arg.getArgumentExpression() ?: arg).textRange
        out += Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Too many arguments for '${typeRef.text.trim()}'", KotlinDiagnosticCodes.ARGUMENT_COUNT,
        )
    }
    // An annotation with no parameters takes no arguments (TOO_MANY_ARGUMENTS on each, as on any call).
    if (params.isEmpty()) {
        args.filter { it.getArgumentName() == null }.forEach(::tooMany)
        return out
    }
    var sawNamed = false
    for ((i, arg) in args.withIndex()) {
        val named = arg.getArgumentName()?.asName?.identifier
        if (named != null) sawNamed = true else if (sawNamed) return out
        val varargAt = params.indexOfFirst { it.vararg }
        val p = when {
            named != null -> params.firstOrNull { it.name == named } ?: return out
            varargAt in 0..i -> params[varargAt]
            else -> params.getOrNull(i) ?: run {
                // A positional argument past the last parameter, with no vararg to take it.
                args.drop(i).filter { it.getArgumentName() == null }.forEach(::tooMany)
                return out
            }
        }
        val value = arg.getArgumentExpression()?.let { unwrapParen(it) } ?: continue
        // A vararg named argument takes the whole array, not an element: left alone.
        if (p.vararg && named != null) continue
        if (value is KtCollectionLiteralExpression) continue
        if (value is KtConstantExpression && value.text == "null") continue
        val expected = p.type
        if (expected.isTypeParameter) continue
        val actual = resolver.inferType(value) ?: continue
        if (actual.isTypeParameter || isFunctional(actual)) continue
        val en = canonicalTypeForCheck(expected).qualifiedName
        val an = canonicalTypeForCheck(actual).qualifiedName
        if (en in NUMERIC && an in NUMERIC) continue
        if (!service.isKnownType(en) || !service.isKnownType(an)) continue
        if (!isMismatch(expected, actual)) continue
        val r = value.textRange
        out += mismatchDiagnostic(r.startOffset, r.endOffset, actual, expected)
    }
    return out
}

/** The value parameters of the annotation class [fqn]: read from its declaration in project source, else from its
 *  one constructor on the classpath. Null when they cannot be told. */
private fun KotlinSemanticChecks.annotationParameters(fqn: String, resolver: KotlinResolver): List<AnnotationParam>? {
    val src = service.sourceClass(fqn)
    if (src != null) {
        val cls = (src.node as? KotlinDomNode)?.psi as? KtClass ?: return null
        if (!cls.isAnnotation()) return null
        val ps = cls.primaryConstructor?.valueParameters ?: return emptyList()
        return ps.map { p ->
            val text = p.typeReference?.text ?: return null
            val t = service.typeFromText(text, resolver.fileContext, enclosingClassFqnOf(cls)) ?: return null
            AnnotationParam(p.name ?: return null, t, p.isVarArg)
        }
    }
    val ctor = service.constructorsOf(fqn).singleOrNull() ?: return null
    if (ctor.paramNames.size != ctor.paramTypes.size) return null
    return ctor.paramTypes.mapIndexed { i, t ->
        var type = t as? KotlinType ?: return null
        val vararg = i == ctor.varargParamIndex
        // A vararg read from a binary class may come as its array type; the element is what one argument fills.
        if (vararg && type.qualifiedName == "kotlin.Array") type = (type.typeArguments.singleOrNull() as? KotlinType)?.withProjection("") ?: return null
        AnnotationParam(ctor.paramNames[i], type, vararg)
    }
}
