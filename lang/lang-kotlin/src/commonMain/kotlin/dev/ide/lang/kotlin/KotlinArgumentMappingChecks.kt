package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.resolve.nearerValueInvokeTargets
import dev.ide.lang.kotlin.resolve.typeOfName
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.TypeRendering

/**
 * The argument-to-parameter mapping errors, ported from K2's `FirCallArgumentsProcessor`
 * (`FirArgumentsToParametersMapper.kt`): MIXING_NAMED_AND_POSITIONAL_ARGUMENTS (a positional argument after a
 * named one that is not in its own position), ARGUMENT_PASSED_TWICE, NON_VARARG_SPREAD (`*a` bound to a plain
 * parameter) and VARARG_OUTSIDE_PARENTHESES (a trailing lambda bound to a vararg), plus NAMED_ARGUMENTS_NOT_ALLOWED
 * for a function type's `invoke`.
 *
 * K2 maps the arguments against every candidate and reports a mapping error only when no candidate is
 * applicable. The same holds here: the mapping runs against each of the call's candidates, and an error is
 * reported at an argument only when EVERY candidate's mapping reports that same error there. A candidate
 * whose parameter names are unknown (a Java method read from bytecode) cannot be mapped, so its call is left
 * alone.
 */
internal object KotlinArgumentMappingChecks {

    private enum class Kind(val message: String) {
        MIXING("Mixing named and positional arguments is not allowed unless the order of the arguments matches the order of the parameters"),
        PASSED_TWICE("Argument already passed for this parameter"),
        NON_VARARG_SPREAD("The spread operator (*foo) can only be applied in a vararg position"),
        VARARG_OUTSIDE_PARENTHESES("Passing value as a vararg is allowed only inside a parenthesized argument list"),
    }

    private data class Problem(val arg: KtValueArgument, val kind: Kind)

    fun check(call: KtCallExpression, resolver: KotlinResolver): List<Diagnostic> {
        if (call.calleeExpression !is KtNameReferenceExpression) return emptyList()
        val inParens = call.valueArgumentList?.arguments.orEmpty()
        val lambda = call.lambdaArguments.firstOrNull()
        // Without a named or spread argument, or a trailing lambda, none of these errors can arise.
        if (inParens.none { it.getArgumentName() != null || it.isSpread } && lambda == null) return emptyList()
        // A member call on a receiver whose type is unknown has candidates nobody can list.
        val q = call.parent as? KtQualifiedExpression
        if (q != null && q.selectorExpression === call && resolver.inferType(q.receiverExpression) == null) return emptyList()
        val targets = resolver.callTargets(call)
        if (targets.isEmpty() || targets.any { !mappable(it) }) return emptyList()
        var common: Set<Problem>? = null
        for (t in targets) {
            val found = map(t, inParens, lambda).toSet()
            common = common?.intersect(found) ?: found
            if (common.isEmpty()) return emptyList()
        }
        return common.orEmpty().map { (arg, kind) ->
            val anchor: KtElement = when (kind) {
                Kind.PASSED_TWICE -> arg.getArgumentName()?.referenceExpression ?: arg
                Kind.NON_VARARG_SPREAD -> arg.getSpreadElement() ?: arg
                else -> arg.getArgumentExpression() ?: arg
            }
            val r = anchor.textRange
            Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, kind.message, KotlinDiagnosticCodes.ARGUMENT_MAPPING)
        }
    }

    /**
     * NAMED_ARGUMENTS_NOT_ALLOWED on a call of a function-typed value, `f(p1 = "")` where `f: (String) -> Unit`.
     * K2's `forbiddenNamedArgumentsTargetOrNull` forbids names for a function type's `invoke`: its parameter
     * names are not part of the type. A value of a class with its own `operator fun invoke` keeps its names, so
     * only a value whose type is a plain function type is judged.
     */
    fun namedArgumentsOnFunctionType(call: KtCallExpression, resolver: KotlinResolver): List<Diagnostic> {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return emptyList()
        val named = call.valueArgumentList?.arguments.orEmpty().mapNotNull { it.getArgumentName() }
        if (named.isEmpty()) return emptyList()
        val q = call.parent as? KtQualifiedExpression
        if (q != null && q.selectorExpression === call) return emptyList()
        val name = callee.getReferencedName()
        // Null unless the nearest declaration of the name is a value, not a function.
        resolver.nearerValueInvokeTargets(call, name) ?: return emptyList()
        val type = resolver.typeOfName(name, call.textRange.startOffset) ?: return emptyList()
        if (!TypeRendering.isFunctionType(type.qualifiedName)) return emptyList()
        return named.map { n ->
            val r = n.referenceExpression.textRange
            Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "Named arguments are prohibited for function types", KotlinDiagnosticCodes.ARGUMENT_MAPPING,
            )
        }
    }

    /** Whether the candidate's parameters are known well enough to map named arguments onto them. */
    private fun mappable(t: KotlinSymbol): Boolean {
        val n = t.paramTypes.size
        if (t.paramNames.size != n) return n == 0 && t.paramNames.isEmpty()
        return t.paramNames.none { it.isEmpty() || (it.length >= 2 && it[0] == 'p' && it.drop(1).all(Char::isDigit)) }
    }

    /** `FirCallArgumentsProcessor` over one candidate: the mapping errors it would record, by argument. */
    private fun map(t: KotlinSymbol, inParens: List<KtValueArgument>, lambda: KtLambdaArgument?): List<Problem> {
        val names = t.paramNames
        val count = t.paramTypes.size
        val vararg = t.varargParamIndex
        val out = ArrayList<Problem>()
        // The argument bound to each parameter, or the marker for a vararg's positional run.
        val bound = HashMap<Int, KtValueArgument?>()
        var named = false // State.NAMED_ONLY_ARGUMENTS
        var inVararg = false // State.VARARG_POSITION
        var position = 0
        for (arg in inParens) {
            val name = arg.getArgumentName()?.asName?.identifier
            if (name == null) {
                if (named) { out += Problem(arg, Kind.MIXING); continue }
                // Too many arguments is another check's error, and the mapping goes on without this one.
                if (position >= count) continue
                if (position != vararg) {
                    bound[position] = arg
                    position++
                    inVararg = false
                } else {
                    bound[position] = null
                    inVararg = true
                }
                continue
            }
            // A named argument ends a vararg's positional run, whose arguments are then bound.
            inVararg = false
            val allowsMixed = !named
            named = true
            val index = names.indexOf(name)
            // An unknown name is NAMED_PARAMETER_NOT_FOUND, reported elsewhere.
            if (index < 0) continue
            if (index in bound) { out += Problem(arg, Kind.PASSED_TWICE); continue }
            bound[index] = arg
            if (allowsMixed && position == index) {
                named = false
                position++
            }
        }
        if (lambda != null && count > 0) {
            val last = count - 1
            when {
                last == vararg -> out += Problem(lambda, Kind.VARARG_OUTSIDE_PARENTHESES)
                last !in bound -> bound[last] = lambda
            }
        }
        // `processDefaultsAndRunChecks`: a spread bound to a parameter that is not the vararg.
        for ((index, arg) in bound) {
            if (arg != null && index != vararg && arg.isSpread) out += Problem(arg, Kind.NON_VARARG_SPREAD)
        }
        return out
    }
}
