package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtAnnotatedExpression
import dev.ide.kotlin.syntax.psi.KtAnnotationEntry
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtIsExpression
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtClassLiteralExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtConstructorCalleeExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtEscapeStringTemplateEntry
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtImportDirective
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtLoopExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtNullableType
import dev.ide.kotlin.syntax.psi.KtPackageDirective
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtPostfixExpression
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtSuperExpression
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtTypeAlias
import dev.ide.kotlin.syntax.psi.KtTypeArgumentList
import dev.ide.kotlin.syntax.psi.KtTypeProjection
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtValueArgumentName
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtWhenConditionWithExpression
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker

/**
 * The checkers ported from K2's expression and syntax checkers and from the diagnostics its raw FIR builder and
 * call resolution attach to an expression. Each check names the K2 code it mirrors. Registered after the
 * hand-written ones in [KotlinSemanticChecks.checkers], so their report order is unchanged.
 */
internal fun KotlinSemanticChecks.expressionPortedCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtConstantExpression::class) { psi ->
        report(KotlinLiteralChecks.constant(psi as KtConstantExpression))
    },
    KotlinChecker(KtEscapeStringTemplateEntry::class) { psi ->
        report(KotlinLiteralChecks.escape(psi as KtEscapeStringTemplateEntry))
    },
    KotlinChecker(KtQualifiedExpression::class) { psi ->
        report(KotlinExpressionPortedChecks.illegalSelector(psi as KtQualifiedExpression))
    },
    KotlinChecker(KtSuperExpression::class) { psi ->
        report(KotlinExpressionPortedChecks.superMisuse(psi as KtSuperExpression))
        if (resolveReady) report(KotlinSuperCallChecks.check(this@expressionPortedCheckers, psi, resolver))
        report(KotlinLabeledReceiverChecks.check(psi))
    },
    KotlinChecker(KtThisExpression::class) { psi ->
        report(KotlinLabeledReceiverChecks.check(psi as KtThisExpression))
    },
    KotlinChecker(KtNameReferenceExpression::class) { psi ->
        report(KotlinExpressionPortedChecks.underscoreUsage(psi as KtNameReferenceExpression))
    },
    KotlinChecker(KtExpression::class) { psi ->
        if (psi is KtLoopExpression || psi is KtClassOrObject || psi is KtProperty || psi is KtTypeAlias) {
            report(KotlinExpressionPortedChecks.expressionExpected(psi as KtExpression))
        }
    },
    KotlinChecker(KtBinaryExpression::class) { psi ->
        psi as KtBinaryExpression
        if (resolveReady) report(KotlinOperatorChecks.binary(this@expressionPortedCheckers, psi, resolver))
        // CONDITION_TYPE_MISMATCH on an operand of `&&` / `||`, as `FirLogicExpressionTypeChecker` reports it.
        if (resolveReady && (psi.operationToken == KtTokens.ANDAND || psi.operationToken == KtTokens.OROR)) {
            report(conditionNotBoolean(psi.left, resolver))
            report(conditionNotBoolean(psi.right, resolver))
        }
    },
    KotlinChecker(KtWhenExpression::class) { psi ->
        // A `when` without a subject takes conditions, which must be Booleans (`FirWhenConditionChecker`).
        if (resolveReady && (psi as KtWhenExpression).subjectExpression == null) {
            for (entry in psi.entries) for (c in entry.conditions) {
                report(conditionNotBoolean((c as? KtWhenConditionWithExpression)?.expression, resolver))
            }
        }
    },
    KotlinChecker(KtPrefixExpression::class) { psi ->
        if (resolveReady) report(KotlinOperatorChecks.prefix(this@expressionPortedCheckers, psi as KtPrefixExpression, resolver))
    },
    KotlinChecker(KtPostfixExpression::class) { psi ->
        if (resolveReady) report(KotlinOperatorChecks.postfix(this@expressionPortedCheckers, psi as KtPostfixExpression, resolver))
    },
    KotlinChecker(KtNamedFunction::class) { psi ->
        reportAll(KotlinNonLocalReturnChecks.check(psi as KtNamedFunction))
    },
    KotlinChecker(KtClassLiteralExpression::class) { psi ->
        if (resolveReady) report(KotlinClassLiteralChecks.classLiteral(this@expressionPortedCheckers, psi as KtClassLiteralExpression, resolver))
    },
    KotlinChecker(KtUserType::class) { psi ->
        if (resolveReady) report(KotlinDeprecationChecks.type(this@expressionPortedCheckers, psi as KtUserType, resolver))
    },
    KotlinChecker(KtIsExpression::class) { psi ->
        if (resolveReady) report(KotlinCastChecks.isExpression(this@expressionPortedCheckers, psi as KtIsExpression, resolver))
    },
    KotlinChecker(KtBinaryExpressionWithTypeRHS::class) { psi ->
        if (resolveReady) report(KotlinCastChecks.cast(this@expressionPortedCheckers, psi as KtBinaryExpressionWithTypeRHS, resolver))
    },
    KotlinChecker(KtWhenExpression::class) { psi ->
        if (resolveReady) reportAll(KotlinCastChecks.whenConditions(this@expressionPortedCheckers, psi as KtWhenExpression, resolver))
    },
    KotlinChecker(KtCallExpression::class) { psi ->
        reportAll(KotlinExpressionPortedChecks.manyLambdaArguments(psi as KtCallExpression))
        if (resolveReady) {
            reportAll(KotlinArgumentMappingChecks.check(psi, resolver))
            reportAll(KotlinArgumentMappingChecks.namedArgumentsOnFunctionType(psi, resolver))
            reportAll(KotlinClassLiteralChecks.reifiedTypeArgument(this@expressionPortedCheckers, psi, resolver))
        }
    },
)

/**
 * CANNOT_ALL_UNDER_IMPORT_FROM_SINGLETON: `import a.O.*` where `O` is an `object` (a companion included). Ported
 * from the import resolution K2's `FirImportsChecker` reports through: an object's members are reached through
 * its instance, so they cannot be star-imported. File-level, since imports sit outside every declaration.
 */
internal fun KotlinSemanticChecks.allUnderImportFromSingleton(file: KtFile): List<Diagnostic> {
    // Only an object the model confirms is judged, so a half-built index reports nothing rather than guessing.
    val out = ArrayList<Diagnostic>()
    for (imp in file.importDirectives) {
        if (!imp.isAllUnder) continue
        val fqn = imp.importedFqName?.asString()?.takeIf { '.' in it } ?: continue
        if (!service.isKnownType(fqn) || !service.isSingletonObject(fqn)) continue
        val ref = imp.importedReference ?: continue
        val last = (ref as? KtQualifiedExpression)?.selectorExpression ?: ref
        val r = last.textRange
        out += Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Cannot import-on-demand from object '${fqn.substringAfterLast('.')}'", KotlinDiagnosticCodes.IMPORT_FROM_SINGLETON,
        )
    }
    return out
}

internal object KotlinExpressionPortedChecks {

    /**
     * ILLEGAL_SELECTOR: `1."s"`, `a.{ }`, `Int.(a)`. Ported from the raw FIR builder's `checkSelectorInvariant`:
     * after a dot only a name or a call can stand. A call whose callee is a lambda (`a.{ }()`) is
     * NO_RECEIVER_ALLOWED there, reported on the lambda.
     */
    fun illegalSelector(q: KtQualifiedExpression): Diagnostic? {
        val selector = q.selectorExpression ?: return null
        if (selector is KtNameReferenceExpression) return null
        val callee = (selector as? KtCallExpression)?.calleeExpression?.let(::unwrapLabelsAndAnnotations)
        if (selector is KtCallExpression && callee !is KtLambdaExpression) return null
        val anchor = callee ?: selector
        val r = anchor.textRange
        val message = if (callee == null) "The expression cannot be a selector (cannot occur after a dot)"
        else "No receiver can be passed to this function or property"
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, message, KotlinDiagnosticCodes.ILLEGAL_SELECTOR)
    }

    private fun unwrapLabelsAndAnnotations(e: KtExpression): KtExpression? {
        var x: KtExpression? = e
        while (true) {
            x = when (x) {
                is KtParenthesizedExpression -> x.expression
                is KtLabeledExpression -> x.baseExpression
                is KtAnnotatedExpression -> x.baseExpression
                else -> return x
            }
        }
    }

    /**
     * SUPER_IS_NOT_AN_EXPRESSION and SUPER_NOT_AVAILABLE. `super` only ever stands as the receiver of a member
     * access: alone, called (`super()`), or parenthesized it is not an expression (the raw FIR builder's
     * "Super cannot be a callee", and `transformSuperReceiver` with no containing call). As a receiver it needs a
     * dispatch receiver to be the super of: in a top-level function or extension there is no enclosing class,
     * which `transformSuperReceiver` reports as SUPER_NOT_AVAILABLE.
     */
    fun superMisuse(e: KtSuperExpression): Diagnostic? {
        val parent = e.parent
        // `super::f` is a callable reference, judged by rules this does not port.
        if (parent is KtCallableReferenceExpression) return null
        val r = e.textRange
        if (parent !is KtQualifiedExpression || parent.receiverExpressionOrNull !== e) {
            return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "'super' cannot be a callee", KotlinDiagnosticCodes.SUPER_MISUSE)
        }
        if (enclosingClass(e) != null) return null
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "No supertypes are accessible in this context", KotlinDiagnosticCodes.SUPER_MISUSE)
    }

    private fun enclosingClass(e: KtElement): KtClassOrObject? {
        var p = e.parent
        while (p != null && p !is KtFile) {
            if (p is KtClassOrObject) return p
            p = p.parent
        }
        return null
    }

    /**
     * UNDERSCORE_USAGE_WITHOUT_BACKTICKS: a reference spelled only with underscores, `_ + 1`, `__()`, `Host._`.
     * Ported from `FirUnderscoreChecker` (any resolvable reference whose raw identifier is all underscores) and
     * `checkTypeRefForUnderscore`, which `FirReservedUnderscoreDeclarationChecker` runs over a declaration's
     * parameter, return and receiver types. A backticked name is a different identifier and is fine, as is a
     * type argument of a call (`foo<_>()` asks for the argument to be inferred) and a named argument's name.
     */
    fun underscoreUsage(ref: KtNameReferenceExpression): Diagnostic? {
        val text = ref.text
        if (text.isEmpty() || text.any { it != '_' }) return null
        if (ref.parent is KtValueArgumentName) return null
        // Climb out of the user type the name may sit in, through type arguments and `?`, to what owns the type.
        var outerType: KtTypeReference? = null
        var owner = ref.parent
        while (owner is KtUserType || owner is KtTypeReference || owner is KtTypeProjection ||
            owner is KtTypeArgumentList || owner is KtNullableType
        ) {
            if (owner is KtTypeReference) outerType = owner
            owner = owner.parent
        }
        // A lone `_` type argument is a placeholder projection, not a name (PLACEHOLDER_PROJECTION_IN_TYPEREF).
        if (text == "_" && ref.parent is KtUserType && ref.parent?.parent?.parent is KtTypeProjection) return null
        if (outerType != null) {
            val reported = when (owner) {
                // `@__("")`: an annotation call resolves its type like any other call.
                is KtConstructorCalleeExpression -> owner.parent is KtAnnotationEntry
                is KtParameter -> owner.typeReference === outerType
                is KtNamedFunction, is KtProperty -> true
                else -> false
            }
            if (!reported) return null
        }
        var p = owner
        while (p != null && p !is KtFile) {
            if (p is KtImportDirective || p is KtPackageDirective) return null
            p = p.parent
        }
        val r = ref.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Names _, __, ___, ..., are reserved in Kotlin", KotlinDiagnosticCodes.UNDERSCORE_USAGE,
        )
    }

    /**
     * EXPRESSION_EXPECTED: `val x = while (c) {}`, `fun f() = for (i in l) {}`, `val a = class A`,
     * `(d@ val bar = 2)`. The raw FIR builder converts these positions with `toFirExpression`, which turns a
     * declaration or a loop (a statement, not an expression) into an error expression. Only the positions that
     * conversion plainly covers are judged: a property's initializer, an expression body, a returned value, an
     * argument, a binary operand and a parenthesized expression; a branch or block body holds statements.
     */
    fun expressionExpected(e: KtExpression): Diagnostic? {
        if (e is KtEnumEntry) return null
        var inner: KtElement = e
        var parent = e.parent
        while (parent is KtLabeledExpression || parent is KtAnnotatedExpression) { inner = parent; parent = parent.parent }
        val expected = when (parent) {
            is KtParenthesizedExpression, is KtReturnExpression, is KtValueArgument -> true
            is KtBinaryExpression -> parent.operationReference !== inner
            // A declaration or loop directly inside a property or function is its initializer or body: a local
            // declaration sits in a block, and a member in a class body.
            is KtProperty -> parent.initializer === inner
            is KtNamedFunction -> parent.bodyExpression === inner
            else -> false
        }
        if (!expected) return null
        val r = e.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Only expressions are allowed here", KotlinDiagnosticCodes.EXPRESSION_EXPECTED)
    }

    /**
     * MANY_LAMBDA_EXPRESSION_ARGUMENTS: `f {} {}`. Ported from `mapArguments`, which keeps the first trailing
     * lambda as the call's external argument and reports every further one, whatever the callee is.
     */
    fun manyLambdaArguments(call: KtCallExpression): List<Diagnostic> {
        val lambdas = call.lambdaArguments
        if (lambdas.size < 2) return emptyList()
        return lambdas.drop(1).map { arg ->
            val r = (arg.getArgumentExpression() ?: arg).textRange
            Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "Only one lambda expression is allowed outside a parenthesized argument list", KotlinDiagnosticCodes.ARGUMENT_MAPPING,
            )
        }
    }
}
