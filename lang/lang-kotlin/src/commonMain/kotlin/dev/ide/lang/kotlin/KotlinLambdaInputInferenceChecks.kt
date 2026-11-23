package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFunctionType
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtNullableType
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeElement
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.discardedOrUntypedPosition
import dev.ide.lang.kotlin.resolve.expectedLambdaShape
import dev.ide.lang.kotlin.resolve.enclosingCallAndParamIndex
import dev.ide.lang.kotlin.resolve.lambdaParamIndex
import dev.ide.lang.kotlin.resolve.constructorCallee
import dev.ide.lang.kotlin.resolve.resolveCalleeFunction
import dev.ide.lang.kotlin.resolve.uninferableTypeParameters
import dev.ide.lang.resolve.SymbolKind

/*
 * A lambda argument whose input type is a bare type variable of the callee that nothing gives a proper constraint,
 * after K2's postponed-argument completion (`PostponedArgumentInputTypesResolver`, `KotlinConstraintSystemCompleter`):
 * the lambda can only be analyzed once its input types are fixed, and a variable with no proper constraint is fixed
 * to an error type. K2 then reports CANNOT_INFER_PARAMETER_TYPE on the callee, and on the lambda (its implicit `it`,
 * or a missing receiver) or on the untyped parameter that declares it.
 *
 * `fun <R, T> bar(f: (R) -> T)`: `bar { true }`, `bar { x -> x + 1 }`. Judged only for a call with one candidate, a
 * function declared in project source with no explicit type arguments and no vararg, and a variable that:
 *  - appears in the function type only as that bare input (`R` or `R?`, never nested as `List<R>`, which leaves the
 *    lambda analyzable), and in no other parameter that receives an argument, nor in the receiver;
 *  - has a bound naming no other type parameter, and, if the result type mentions it, the call sits where no
 *    expected type reaches;
 *  - is not constrained by the lambda's own body: the parameter is only returned, or used as the receiver of a
 *    member access or the left operand of an operator, which K2 can only resolve by fixing it; a receiver variable
 *    only with an empty body.
 */

internal fun KotlinSemanticChecks.lambdaInputInferenceCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtCallExpression::class) { psi ->
        KotlinPerf.span("sem.ported.lambdaInput") {
            if (resolveReady) reportAll(uninferableLambdaInputs(psi as KtCallExpression, resolver))
        }
    },
    KotlinChecker(KtLambdaExpression::class) { psi ->
        KotlinPerf.span("sem.ported.expectedParamType") {
            if (resolveReady) reportAll(expectedParameterTypeMismatches(psi as KtLambdaExpression, resolver))
            if (resolveReady) reportAll(lambdaParametersPastExpectedArity(psi as KtLambdaExpression, resolver))
            if (resolveReady) reportAll(lambdaToNonFunctionParameter(psi as KtLambdaExpression, resolver))
        }
    },
    KotlinChecker(KtCallExpression::class) { psi ->
        if (resolveReady) reportAll(nothingToInferFrom(psi as KtCallExpression, resolver))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtProperty::class) { psi ->
        if (resolveReady) reportAll(genericDelegateNotInferred(psi as dev.ide.kotlin.syntax.psi.KtProperty, resolver))
    },
    KotlinChecker(KtCallExpression::class) { psi ->
        KotlinPerf.span("sem.ported.untypedFunctionParams") {
            if (resolveReady && (psi as KtCallExpression).typeArgumentList != null) reportAll(untypedParametersAgainstNonFunction(psi, resolver))
        }
    },
)

/**
 * A lambda or anonymous function whose expected type is a type argument written at the call, and not a function type
 * (`select<Any?>(fun (x, y) {})`, also through an identity-shaped call `select<Any?>(id { x, y -> })`): nothing gives
 * its untyped parameters a type, so K2 reports CANNOT_INFER_VALUE_PARAMETER_TYPE: on each untyped parameter of an
 * anonymous function, and on the last untyped one of a lambda.
 */
private fun KotlinSemanticChecks.untypedParametersAgainstNonFunction(call: KtCallExpression, resolver: KotlinResolver): List<Diagnostic> {
    val args = call.valueArguments
    fun unwrap(e: KtExpression?): KtExpression? {
        var x = e
        while (x is dev.ide.kotlin.syntax.psi.KtParenthesizedExpression) x = x.expression
        return x
    }
    fun functionLiteral(e: KtExpression?): Boolean =
        e is KtLambdaExpression || (e is KtNamedFunction && e.nameIdentifier == null)
    if (args.none { a -> unwrap(a.getArgumentExpression()).let { functionLiteral(it) || it is KtCallExpression } }) return emptyList()
    val fn = singleSourceFunction(call, resolver) ?: return emptyList()
    val typeParams = fn.typeParameters.mapNotNull { it.name }
    val written = call.typeArgumentList?.arguments ?: return emptyList()
    if (written.size != typeParams.size) return emptyList()
    val params = fn.valueParameters
    val out = ArrayList<Diagnostic>()
    for ((i, arg) in args.withIndex()) {
        if (arg.getArgumentName() != null || arg.getSpreadElement() != null || arg is KtLambdaArgument) return emptyList()
        val p = params.getOrNull(i) ?: params.lastOrNull()?.takeIf { it.isVarArg } ?: return emptyList()
        val tp = bareTypeParameter(p.typeReference, typeParams) ?: continue
        val writtenText = written[typeParams.indexOf(tp)].text.trim()
        // A function type, or anything that might name one through an alias or a type parameter, is not judged.
        if ("->" in writtenText || writtenText.removeSuffix("?") !in NON_FUNCTION_TYPE_ARGUMENTS) continue
        var e = unwrap(arg.getArgumentExpression())
        if (e is KtCallExpression) e = identityArgument(e, resolver)
        if (!functionLiteral(e)) continue
        out += untypedParameterDiagnostics(e!!)
    }
    return out
}

private val NON_FUNCTION_TYPE_ARGUMENTS = setOf("Any", "kotlin.Any", "String", "Int", "Number", "Boolean", "Unit")

/** The argument of `id(x)`, a call with no type arguments to a source `fun <K> id(x: K): K`-shaped function. */
private fun KotlinSemanticChecks.identityArgument(call: KtCallExpression, resolver: KotlinResolver): KtExpression? {
    if (call.typeArgumentList != null) return null
    val arg = call.valueArguments.singleOrNull()?.takeIf { it.getArgumentName() == null && it.getSpreadElement() == null } ?: return null
    val fn = singleSourceFunction(call, resolver) ?: return null
    val k = fn.typeParameters.singleOrNull()?.takeIf { it.extendsBound == null }?.name ?: return null
    val p = fn.valueParameters.singleOrNull()?.takeIf { !it.isVarArg } ?: return null
    if (bareTypeParameter(p.typeReference, listOf(k)) != k || p.typeReference?.typeElement is KtNullableType) return null
    val returnsK = fn.typeReference?.let { bareTypeParameter(it, listOf(k)) == k && it.typeElement !is KtNullableType }
        ?: ((fn.bodyExpression as? KtNameReferenceExpression)?.getReferencedName() == p.name && !fn.hasBlockBody())
    if (!returnsK || fn.receiverTypeReference != null) return null
    var e = arg.getArgumentExpression()
    while (e is dev.ide.kotlin.syntax.psi.KtParenthesizedExpression) e = e.expression
    return e
}

private fun KotlinSemanticChecks.singleSourceFunction(call: KtCallExpression, resolver: KotlinResolver): KtNamedFunction? {
    val sym = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
        .filter { it.kind == SymbolKind.METHOD }
        .distinctBy { t ->
            val at = ((t.declaration() as? KotlinDomNode)?.psi)?.textRange?.startOffset
            if (t.origin.fromSource && at != null) Pair(t.origin.file?.toString(), at) else t
        }.singleOrNull() ?: return null
    if (!sym.origin.fromSource) return null
    val fn = (sym.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction ?: return null
    return fn.takeIf { it.name == (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() }
}

private fun bareTypeParameter(t: KtTypeReference?, names: List<String>): String? {
    var el: KtTypeElement? = t?.typeElement
    if (el is KtNullableType) el = el.innerType
    val u = el as? KtUserType ?: return null
    if (u.qualifier != null || u.typeArgumentList != null) return null
    return u.referencedName?.takeIf { it in names }
}

private fun KotlinSemanticChecks.untypedParameterDiagnostics(e: KtExpression): List<Diagnostic> {
    val untyped = when (e) {
        is KtLambdaExpression -> listOfNotNull(e.valueParameters.lastOrNull()?.takeIf { it.typeReference == null && it.destructuringDeclaration == null })
        is KtNamedFunction -> e.valueParameters.filter { it.typeReference == null }
        else -> emptyList()
    }
    return untyped.map { p ->
        val r = p.textRange
        Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Cannot infer a type for parameter '${p.name}': nothing expects a function here. Specify it explicitly",
            KotlinDiagnosticCodes.CANNOT_INFER_TYPE)
    }
}

private fun KotlinSemanticChecks.uninferableLambdaInputs(call: KtCallExpression, resolver: KotlinResolver): List<Diagnostic> {
    // Cheap gates first: a lambda argument, no explicit type arguments, no spread.
    val args = call.valueArguments
    if (args.none { it is KtLambdaArgument || it.getArgumentExpression() is KtLambdaExpression }) return emptyList()
    if (call.typeArgumentList != null || args.any { it.getSpreadElement() != null }) return emptyList()
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return emptyList()

    val targets = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
        .filter { it.kind == SymbolKind.METHOD }
        .distinctBy { t ->
            val at = ((t.declaration() as? KotlinDomNode)?.psi)?.textRange?.startOffset
            if (t.origin.fromSource && at != null) Pair(t.origin.file?.toString(), at) else t
        }
    val sym = targets.singleOrNull() ?: return emptyList()
    if (!sym.origin.fromSource) return emptyList()
    val fn = (sym.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction ?: return emptyList()
    if (fn.name != callee.getReferencedName()) return emptyList()
    val typeParams = fn.typeParameters.mapNotNull { it.name }
    if (typeParams.isEmpty() || fn.typeConstraints.isNotEmpty()) return emptyList()
    if (fn.typeParameters.any { p -> mentions(p.extendsBound, typeParams.toSet()) }) return emptyList()
    val params = fn.valueParameters
    if (params.any { it.isVarArg || it.typeReference == null }) return emptyList()

    // Each argument's parameter index.
    val argParam = HashMap<Int, KtElement>()
    for ((i, arg) in args.withIndex()) {
        val named = arg.getArgumentName()?.asName?.identifier
        val idx = when {
            named != null -> params.indexOfFirst { it.name == named }
            arg is KtLambdaArgument -> params.lastIndex
            else -> i
        }
        if (idx !in params.indices || idx in argParam) return emptyList()
        argParam[idx] = arg.getArgumentExpression() ?: return emptyList()
    }

    val varNames = typeParams.toSet()
    fun varsIn(t: KtElement?): Set<String> {
        if (t == null) return emptySet()
        val out = HashSet<String>()
        fun rec(e: KtElement) {
            if (e is KtNameReferenceExpression && e.getReferencedName() in varNames) out += e.getReferencedName()
            var c = e.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(t)
        return out
    }
    fun bareVar(t: KtTypeReference?): String? {
        var el: KtTypeElement? = t?.typeElement
        if (el is KtNullableType) el = el.innerType
        val u = el as? KtUserType ?: return null
        if (u.qualifier != null || u.typeArgumentList != null) return null
        return u.referencedName?.takeIf { it in varNames }
    }

    // The lambdas K2 postpones, each with the input positions it leaves untyped. Everything else is a proper
    // constraint source: the receiver, every other argument's parameter, and the lambdas' explicitly typed inputs.
    class Postponed(val lambda: KtLambdaExpression, val ft: KtFunctionType, val untyped: List<Pair<KtTypeReference, KtElement>>)
    val proper = HashSet<String>()
    proper += varsIn(fn.receiverTypeReference)
    val postponed = ArrayList<Postponed>()
    for ((idx, argExpr) in argParam) {
        val lambda = argExpr as? KtLambdaExpression
        val ft = params[idx].typeReference?.typeElement as? KtFunctionType
        if (lambda == null || ft == null) { proper += varsIn(params[idx].typeReference); continue }
        val declared = lambda.valueParameters
        val inputs = ft.parameters
        if (declared.isNotEmpty() && declared.size != inputs.size) return emptyList()
        if (declared.isEmpty() && inputs.size > 1) return emptyList()
        if (declared.any { it.destructuringDeclaration != null }) return emptyList()
        val untyped = ArrayList<Pair<KtTypeReference, KtElement>>()
        ft.receiverTypeReference?.let { untyped += it to lambda }
        for ((i, input) in inputs.withIndex()) {
            val t = input.typeReference ?: return emptyList()
            val decl = declared.getOrNull(i)
            if (decl?.typeReference != null) proper += varsIn(t) else untyped += t to (decl ?: lambda)
        }
        postponed += Postponed(lambda, ft, untyped)
    }
    // Analyze, to a fixpoint, every lambda whose input variables all have proper constraints: its result then
    // constrains the variables it mentions.
    val ready = HashSet<Postponed>()
    var changed = true
    while (changed) {
        changed = false
        for (p in postponed) {
            if (p in ready) continue
            if (p.untyped.all { (t, _) -> proper.containsAll(varsIn(t)) }) {
                ready += p; changed = true
                proper += varsIn(p.ft.returnTypeReference)
            }
        }
    }
    val stuck = postponed.filter { it !in ready }
    if (stuck.isEmpty()) return emptyList()
    val q = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === call }
    val resultFree by lazy { discardedOrUntypedPosition(q ?: call) }
    fun freeOutsideTheCall(v: String): Boolean {
        val ret = fn.typeReference
        if (ret == null && fn.hasBlockBody()) return true
        if (ret != null && v !in varsIn(ret)) return true
        return resultFree
    }

    val reported = ArrayList<Diagnostic>()
    for (p in stuck) {
        val body = p.lambda.bodyExpression ?: return emptyList()
        for ((t, anchor) in p.untyped) {
            val v = bareVar(t)
            // A stuck input nested in another type (`List<R>`) leaves the lambda analyzable: not judged here.
            if (v == null) { if (proper.containsAll(varsIn(t))) continue else return emptyList() }
            if (v in proper) continue
            if (!freeOutsideTheCall(v)) return emptyList()
            val isReceiver = t === p.ft.receiverTypeReference
            if (isReceiver) {
                if (body.statements.isNotEmpty()) return emptyList()
            } else {
                val name = (anchor as? dev.ide.kotlin.syntax.psi.KtParameter)?.name ?: "it"
                if (!onlyFixingUses(body, name)) return emptyList()
            }
            val r = anchor.textRange
            reported += Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "Not enough information to infer type variable $v: nothing gives this lambda's ${if (isReceiver) "receiver" else "parameter"} a type",
                KotlinDiagnosticCodes.CANNOT_INFER_TYPE)
        }
    }
    if (reported.isEmpty()) return emptyList()
    // The callee may already be reported by the classic check (a variable only the result type mentions).
    if (runCatching { resolver.uninferableTypeParameters(call) }.getOrDefault(emptyList()).isNotEmpty()) return reported
    val r = callee.textRange
    reported += Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Not enough information to infer the type arguments of '${callee.getReferencedName()}'", KotlinDiagnosticCodes.CANNOT_INFER_TYPE)
    return reported
}

/** Whether every use of [name] in [body] leaves its type unconstrained: the lambda's result, the receiver of a member
 *  access, or the left operand of an operator. A nested lambda (whose own `it` or parameters may shadow) bails. */
private fun onlyFixingUses(body: KtBlockExpression, name: String): Boolean {
    val result = body.statements.lastOrNull()
    var ok = true
    fun rec(e: KtElement) {
        if (!ok) return
        if (e is KtLambdaExpression || e is KtNamedFunction) { ok = false; return }
        if (e is KtNameReferenceExpression && e.getReferencedName() == name) {
            val p = e.parent
            ok = e === result ||
                (p is KtQualifiedExpression && p.receiverExpression === e) ||
                (p is KtBinaryExpression && p.left === e && p.operationToken in FIXING_OPERATORS)
            return
        }
        var c = e.firstChild
        while (c != null && ok) { rec(c); c = c.nextSibling }
    }
    rec(body)
    return ok
}

private val FIXING_OPERATORS = setOf(
    KtTokens.PLUS, KtTokens.MINUS, KtTokens.MUL, KtTokens.DIV, KtTokens.PERC, KtTokens.RANGE,
    KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ,
)

/** Whether the type text under [t] names any of [names]. */
private fun mentions(t: KtElement?, names: Set<String>): Boolean {
    if (t == null) return false
    var found = false
    fun rec(e: KtElement) {
        if (found) return
        if (e is KtNameReferenceExpression && e.getReferencedName() in names) { found = true; return }
        var c = e.firstChild
        while (c != null && !found) { rec(c); c = c.nextSibling }
    }
    rec(t)
    return found
}

/**
 * EXPECTED_PARAMETER_TYPE_MISMATCH: a lambda parameter written with a type (`{ f: String -> … }`) must accept what
 * the expected function type passes it, so its declared type must be a supertype of the expected parameter type
 * (`((Int) -> String) -> String` hands `f` a function, which a `String` cannot hold). Judged only when the lambda's
 * arity matches the expected shape and both types are concrete.
 */
private fun KotlinSemanticChecks.expectedParameterTypeMismatches(lambda: KtLambdaExpression, resolver: KotlinResolver): List<Diagnostic> {
    val params = lambda.valueParameters
    if (params.none { it.typeReference != null }) return emptyList()
    // A slot whose input types are the callee's own type variables (`select(vararg x: T)`, `id(x: K)`) takes the
    // written parameter type as a constraint on them, not as something to check against.
    resolver.enclosingCallAndParamIndex(lambda)?.let { (call, argIndex) ->
        val sym = resolver.resolveCalleeFunction(call) ?: resolver.constructorCallee(call)
        if (sym != null && sym.typeParameters.isNotEmpty()) {
            val raw = sym.paramTypes.getOrNull(resolver.lambdaParamIndex(call, argIndex, sym)) as? dev.ide.lang.kotlin.symbols.KotlinType
            val inputs = raw?.let { service.functionalShape(it)?.parameterTypes } ?: return emptyList()
            if (raw.isTypeParameter || inputs.any { t -> (t as? dev.ide.lang.kotlin.symbols.KotlinType)?.let { mentionsAny(it, sym.typeParameters.toSet()) } != false }) return emptyList()
        }
    }
    val shape = runCatching { resolver.expectedLambdaShape(lambda) }.getOrNull() ?: return emptyList()
    if (shape.parameterTypes.size != params.size) return emptyList()
    val out = ArrayList<Diagnostic>(1)
    for ((i, p) in params.withIndex()) {
        if (p.destructuringDeclaration != null) continue
        val ref = p.typeReference ?: continue
        val declared = service.typeFromText(ref.text, resolver.fileContext, dev.ide.lang.kotlin.resolve.enclosingClassFqnOf(ref)) ?: continue
        val expected = shape.parameterTypes[i] as? dev.ide.lang.kotlin.symbols.KotlinType ?: continue
        if (declared.isTypeParameter || expected.isTypeParameter || mentionsTypeParameter(expected)) continue
        val mismatch = if (isFunctional(expected) || dev.ide.lang.kotlin.symbols.TypeRendering.isFunctionType(expected.qualifiedName)) {
            !isFunctional(declared) && isConcreteNonFunction(declared) && !declared.isAssignableFrom(expected)
        } else isMismatch(declared, expected)
        if (!mismatch) continue
        val r = p.textRange
        out += Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Expected parameter of type '$expected' but '$declared' was declared",
            KotlinDiagnosticCodes.EXPECTED_PARAMETER_TYPE_MISMATCH,
        )
    }
    return out
}

/** Whether [t] or one of its arguments is a type parameter, which a later binding may still change. */
private fun mentionsTypeParameter(t: dev.ide.lang.kotlin.symbols.KotlinType): Boolean =
    t.isTypeParameter || t.typeArguments.any { (it as? dev.ide.lang.kotlin.symbols.KotlinType)?.let(::mentionsTypeParameter) ?: false }

/** Whether [t] names one of [names] anywhere in it. */
private fun mentionsAny(t: dev.ide.lang.kotlin.symbols.KotlinType, names: Set<String>): Boolean =
    t.qualifiedName in names || t.typeArguments.any { (it as? dev.ide.lang.kotlin.symbols.KotlinType)?.let { a -> mentionsAny(a, names) } ?: false }
