package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtCatchClause
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtContainerNodeForControlStructureBody
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFinallySection
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtPostfixExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTryExpression
import dev.ide.kotlin.syntax.psi.KtWhenEntry
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.bareNameResolves
import dev.ide.lang.kotlin.resolve.companionMemberInScope
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.enclosingClassMembersContain
import dev.ide.lang.kotlin.resolve.enclosingCompanionMember
import dev.ide.lang.kotlin.resolve.implicitReceiversAt
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.resolve.isTypeParameterInScope
import dev.ide.lang.kotlin.resolve.localFunctionsInScope
import dev.ide.lang.kotlin.resolve.localTypesInScope
import dev.ide.lang.kotlin.resolve.localsAt
import dev.ide.lang.kotlin.resolve.resolveCalleeFunction
import dev.ide.lang.kotlin.resolve.topLevelInScope
import dev.ide.lang.resolve.SymbolKind

/*
 * Callable references resolved with no expected type, after K2's callable-reference resolution
 * (`FirCallableReferenceResolver`, `PostponedArgumentsAnalyzer`) and its synthetic calls for `if`/`when`/`try`/`!!`
 * (`FirSyntheticCallGenerator`):
 *
 *  - `::generic` with no left-hand side, naming a single generic callable (a top-level function with type parameters,
 *    or the constructor of a generic class), in a position no expected type reaches: the initializer of a local with
 *    no written type, a statement whose value is discarded, `!!` of one, or a branch of an `if`/`when`/`try` that sits
 *    in such a position and whose other branches add no function type. Nothing constrains the reference's type
 *    variables, so K2 reports CANNOT_INFER_PARAMETER_TYPE on the referenced name.
 *  - `inv()` where the local `inv` was initialized by an `if`/`when`/`try` all of whose branches are such failed
 *    references, or by a reference (or its `!!`) to a name that resolves to nothing: the synthetic call's result is
 *    an error type, so neither a function `inv` nor an `invoke` on it is found (UNRESOLVED_REFERENCE on `inv`).
 */

internal fun KotlinSemanticChecks.callableReferenceInferenceCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtCallableReferenceExpression::class) { psi ->
        KotlinPerf.span("sem.ported.callableRefInfer") {
            if (resolveReady) report(uninferableReference(psi as KtCallableReferenceExpression, resolver))
        }
    },
    KotlinChecker(KtCallExpression::class) { psi ->
        KotlinPerf.span("sem.ported.errorTypedInvoke") {
            if (resolveReady) report(invokeOnErrorTypedLocal(psi as KtCallExpression, resolver))
        }
    },
)

private fun KotlinSemanticChecks.uninferableReference(ref: KtCallableReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    val callee = ref.callableReference as? KtNameReferenceExpression ?: return null
    if (ambiguousSameFileOverloads(ref, resolver) && noExpectedTypeReaches(ref, resolver)) {
        val r = callee.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Overload resolution ambiguity: nothing gives this reference to '${callee.getReferencedName()}' an expected type to choose an overload by",
            KotlinDiagnosticCodes.OVERLOAD_AMBIGUITY,
        )
    }
    if (!referencesUninferableGeneric(ref, resolver)) return null
    if (!noExpectedTypeReaches(ref, resolver)) return null
    val r = callee.textRange
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Not enough information to infer the type arguments of '${callee.getReferencedName()}': nothing gives this reference an expected type",
        KotlinDiagnosticCodes.CANNOT_INFER_TYPE,
    )
}

/**
 * Whether [ref] is `::name` naming exactly one generic callable: the constructor of a generic, instantiable class
 * (`::Box` for `class Box<T>`), or a top-level function with type parameters that is not an extension. Nothing else
 * of that name may be in scope (a local, a member of an enclosing class or implicit receiver, a type parameter).
 */
private fun KotlinSemanticChecks.referencesUninferableGeneric(ref: KtCallableReferenceExpression, resolver: KotlinResolver): Boolean {
    if (ref.receiverExpression != null) return false
    val callee = ref.callableReference as? KtNameReferenceExpression ?: return false
    val name = callee.getReferencedName()
    if (name.isEmpty()) return false
    val offset = callee.textRange.startOffset
    if (resolver.ktFile.importDirectives.any { it.aliasName == name }) return false
    if (resolver.localsAt(offset, name).isNotEmpty() || resolver.localFunctionsInScope(offset, name).isNotEmpty()) return false
    if (resolver.isTypeParameterInScope(name, offset) || resolver.localTypesInScope(offset).containsKey(name)) return false
    if (resolver.enclosingClassMembersContain(offset, name) || resolver.enclosingCompanionMember(name, offset) != null) return false
    if (resolver.implicitReceiversAt(offset).any { service.membersNamed(it.qualifiedName, it.typeArguments, name).isNotEmpty() }) return false
    val sameFile = resolver.ktFile.declarations.filter { (it as? KtNamedDeclaration)?.name == name }
    val functions = service.topLevelByName(name).filter { it.name == name && topLevelCallable(it.kind) && resolver.topLevelInScope(it, resolver.fileContext) }
        // The same declaration can be listed twice (from the file and from the project index).
        .distinctBy { t ->
            val at = ((t.declaration() as? KotlinDomNode)?.psi)?.textRange?.startOffset
            if (t.origin.fromSource && at != null) Pair(t.origin.file?.toString(), at) else t
        }
    val typeFqn = service.resolveTypeName(name, resolver.fileContext, enclosingClassFqnOf(ref))?.takeIf { service.isKnownType(it) }
    if (typeFqn != null) {
        // `::Box`: the constructor of a generic class, and nothing callable shares the name.
        if (functions.isNotEmpty() || sameFile.any { it !is KtClass }) return false
        if (service.classTypeParameters(typeFqn).isEmpty()) return false
        if (service.isNonInstantiableType(typeFqn) != false) return false
        val cls = sameFile.singleOrNull() as? KtClass
        if (sameFile.isNotEmpty() && (cls == null || cls.isInterface() || cls.isEnum() || cls.isAnnotation() || cls.isInner())) return false
        return sameFile.isNotEmpty() || service.isProjectTypeAlias(name).not()
    }
    if (sameFile.isNotEmpty()) {
        val fn = sameFile.singleOrNull() as? KtNamedFunction ?: return false
        if (fn.receiverTypeReference != null || fn.typeParameters.isEmpty()) return false
        // Another file's overload of the same name makes it an overload resolution, not this.
        return functions.size <= 1
    }
    val sym = functions.singleOrNull() ?: return false
    return sym.kind == SymbolKind.METHOD && sym.typeParameters.isNotEmpty() &&
        !sym.isExtension && sym.receiverTypeFqn == null && sym.receiverTypeParam == null
}

/**
 * Whether [ref] is `::name` for two or more top-level functions of this file (no receiver, no other declaration of
 * the name in this file) and nothing else of that name is in scope or in another file. With no expected type K2's
 * callable-reference resolution has no way to choose (OVERLOAD_RESOLUTION_AMBIGUITY).
 */
private fun KotlinSemanticChecks.ambiguousSameFileOverloads(ref: KtCallableReferenceExpression, resolver: KotlinResolver): Boolean {
    if (ref.receiverExpression != null) return false
    val callee = ref.callableReference as? KtNameReferenceExpression ?: return false
    val name = callee.getReferencedName()
    if (name.isEmpty()) return false
    val sameFile = resolver.ktFile.declarations.filter { (it as? KtNamedDeclaration)?.name == name }
    if (sameFile.size < 2 || sameFile.any { it !is KtNamedFunction || it.receiverTypeReference != null }) return false
    // A hidden-deprecated overload is not a candidate at all.
    if (sameFile.any { d -> (d as KtNamedFunction).annotationEntries.any { it.shortName?.asString() == "Deprecated" } }) return false
    // A most specific candidate wins, as in a call (`bar(Int)` over `bar(Any)`): ambiguous only when none is at least
    // as specific as every other. Numeric parameters have their own preference order (`Double` over `Float`): not
    // judged here.
    val fns = sameFile.map { it as KtNamedFunction }
    // The overloads that differ only in arity, referenced straight from an untyped initializer or a function-body
    // statement, are [ambiguousCallableReference]'s; this check covers the rest without reporting them twice.
    val holder = ref.parent
    val direct = (holder is KtProperty && holder.initializer === ref && holder.typeReference == null) ||
        (holder is KtBlockExpression && holder.parent is KtNamedFunction)
    if (direct && fns.map { it.valueParameters.size }.distinct().size == fns.size &&
        fns.none { f -> f.valueParameters.any { it.isVarArg || it.hasDefaultValue() } }
    ) return false
    if (fns.any { f -> f.typeParameters.isNotEmpty() || f.valueParameters.any { it.typeReference == null || it.isVarArg } }) return false
    val types = fns.map { f ->
        f.valueParameters.map { service.typeFromText(it.typeReference!!.text, resolver.fileContext, enclosingClassFqnOf(f)) ?: return false }
    }
    for (a in types) for (b in types) if (a !== b && a.size == b.size && a.indices.any { i ->
            a[i].qualifiedName != b[i].qualifiedName && a[i].qualifiedName in NUMERIC_PARAMS && b[i].qualifiedName in NUMERIC_PARAMS
        }) return false
    fun atLeastAsSpecific(a: Int, b: Int): Boolean =
        types[a].size == types[b].size && types[a].indices.all { i ->
            val pa = types[a][i]; val pb = types[b][i]
            service.isKnownType(pa.qualifiedName) && service.isKnownType(pb.qualifiedName) &&
                (!pa.nullable || pb.nullable) && pb.withNullable(false).isAssignableFrom(pa.withNullable(false))
        }
    if (types.indices.any { a -> types.indices.all { b -> a == b || atLeastAsSpecific(a, b) } }) return false
    val offset = callee.textRange.startOffset
    if (resolver.ktFile.importDirectives.any { it.aliasName == name || it.importedFqName?.shortName()?.asString() == name }) return false
    if (resolver.localsAt(offset, name).isNotEmpty() || resolver.localFunctionsInScope(offset, name).isNotEmpty()) return false
    if (resolver.isTypeParameterInScope(name, offset) || resolver.localTypesInScope(offset).containsKey(name)) return false
    if (resolver.enclosingClassMembersContain(offset, name) || resolver.enclosingCompanionMember(name, offset) != null) return false
    if (resolver.implicitReceiversAt(offset).any { service.membersNamed(it.qualifiedName, it.typeArguments, name).isNotEmpty() }) return false
    // Another file's top-level of the name (same package) joins the candidates in ways not judged here.
    // The project index lists this file's own functions too (from its disk copy): every listed one must sit, in one
    // file, at the offset of a declaration read here.
    val offsets = sameFile.mapTo(HashSet()) { it.textRange.startOffset }
    val listed = service.topLevelByName(name).filter { it.name == name && resolver.topLevelInScope(it, resolver.fileContext) }
    if (listed.map { it.origin.file?.toString() }.distinct().size > 1) return false
    return listed.all { s -> s.origin.fromSource && ((s.declaration() as? KotlinDomNode)?.psi?.textRange?.startOffset in offsets) }
}

private val NUMERIC_PARAMS = setOf(
    "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Double", "kotlin.Float",
    "kotlin.UInt", "kotlin.ULong", "kotlin.UShort", "kotlin.UByte",
)

private fun topLevelCallable(kind: SymbolKind): Boolean =
    kind == SymbolKind.METHOD || kind == SymbolKind.FIELD || kind == SymbolKind.CONSTRUCTOR

/** Whether `::name` (no left-hand side) names nothing at all, or only extensions (which need their receiver written). */
private fun KotlinSemanticChecks.unresolvableReference(ref: KtCallableReferenceExpression, resolver: KotlinResolver): Boolean {
    if (ref.receiverExpression != null) return false
    val callee = ref.callableReference as? KtNameReferenceExpression ?: return false
    val name = callee.getReferencedName()
    if (name.isEmpty()) return false
    val offset = callee.textRange.startOffset
    if (!resolver.bareNameResolves(name, offset) && !resolver.companionMemberInScope(name, offset)) return true
    return extensionReferenceWithoutReceiver(ref, resolver) != null
}

/**
 * Whether no expected type reaches [ref]: walks out through `!!`, parentheses and the branches of `if`/`when`/`try` to
 * the initializer of a local with no written type or a statement whose value is discarded. A branch joins its
 * siblings in K2's synthetic call, so every other branch must add no function type: it is itself a failed reference,
 * or an expression of a known type that is not a function type.
 */
private fun KotlinSemanticChecks.noExpectedTypeReaches(ref: KtCallableReferenceExpression, resolver: KotlinResolver): Boolean {
    var e: KtExpression = ref
    while (true) {
        val p = e.parent
        when {
            p is KtParenthesizedExpression -> e = p
            p is KtPostfixExpression && p.operationReference.getReferencedNameElementType() == KtTokens.EXCLEXCL -> e = p
            p is KtProperty -> return p.initializer === e && p.typeReference == null && !p.hasDelegate()
            p is KtBlockExpression -> {
                val stmts = p.statements
                if (stmts.lastOrNull() !== e) return stmts.contains(e)
                when (val owner = p.parent) {
                    is KtFinallySection -> return true
                    is KtContainerNodeForControlStructureBody -> {
                        val branch = joinBranches(owner, p) ?: return false
                        if (!siblingsAddNoFunctionType(branch.first, branch.second, resolver)) return false
                        e = branch.first
                    }
                    is KtTryExpression, is KtCatchClause -> {
                        val tryExpr = (owner as? KtTryExpression) ?: (owner.parent as? KtTryExpression) ?: return false
                        if (!siblingsAddNoFunctionType(tryExpr, p, resolver)) return false
                        e = tryExpr
                    }
                    is KtNamedFunction -> return owner.hasBlockBody() && owner.nameIdentifier != null
                    is KtWhenEntry -> {
                        val w = owner.parent as? KtWhenExpression ?: return false
                        if (owner.expression !== p || !siblingsAddNoFunctionType(w, p, resolver)) return false
                        e = w
                    }
                    else -> return false
                }
            }
            p is KtContainerNodeForControlStructureBody -> {
                val branch = joinBranches(p, e) ?: return false
                if (!siblingsAddNoFunctionType(branch.first, branch.second, resolver)) return false
                e = branch.first
            }
            p is KtWhenEntry && p.expression === e -> {
                val w = p.parent as? KtWhenExpression ?: return false
                if (!siblingsAddNoFunctionType(w, e, resolver)) return false
                e = w
            }
            else -> return false
        }
    }
}

/** The `if` whose then/else body is [container], with the branch value [value] stands for. */
private fun joinBranches(container: KtContainerNodeForControlStructureBody, value: KtExpression): Pair<KtExpression, KtExpression>? {
    val ifExpr = container.parent as? KtIfExpression ?: return null
    if (ifExpr.then == null || ifExpr.`else` == null) return null
    return ifExpr to value
}

/** The result expressions of [join]'s branches: an `if`'s then/else, a `when`'s entries, a `try`'s block and catches. */
private fun branchValues(join: KtExpression): List<KtExpression?>? = when (join) {
    is KtIfExpression -> listOf(join.then, join.`else`)
    is KtWhenExpression -> join.entries.map { it.expression }
    is KtTryExpression -> listOf<KtExpression?>(join.tryBlock) + join.catchClauses.map { it.catchBody }
    else -> null
}

private fun resultOf(branch: KtExpression?): KtExpression? {
    var b = branch
    while (true) {
        b = when (b) {
            is KtBlockExpression -> b.statements.lastOrNull() ?: return null
            is KtParenthesizedExpression -> b.expression
            else -> return b
        }
    }
}

private fun KotlinSemanticChecks.siblingsAddNoFunctionType(join: KtExpression, own: KtExpression, resolver: KotlinResolver): Boolean {
    val values = branchValues(join) ?: return false
    if (join is KtWhenExpression && join.entries.none { it.isElse } && join.subjectExpression == null) return false
    for (v in values) {
        if (v == null) return false
        if (v === own || resultOf(v) === resultOf(own)) continue
        val r = resultOf(v) ?: return false
        if (failedReference(r, resolver)) continue
        val t = resolver.inferType(r) ?: return false
        if (t.isTypeParameter || t.qualifiedName == "kotlin.Any" || !service.isKnownType(t.qualifiedName)) return false
        if (service.functionalShape(t) != null || FUNCTION_TYPE_PREFIXES.any { t.qualifiedName.startsWith(it) }) return false
    }
    return true
}

private val FUNCTION_TYPE_PREFIXES = listOf(
    "kotlin.Function", "kotlin.reflect.KFunction", "kotlin.reflect.KProperty", "kotlin.reflect.KMutableProperty",
    "kotlin.coroutines.SuspendFunction", "kotlin.reflect.KSuspendFunction", "kotlin.reflect.KCallable",
)

/** A receiver-less reference that resolves to nothing, or to a generic callable nothing can infer. */
private fun KotlinSemanticChecks.failedReference(e: KtExpression, resolver: KotlinResolver): Boolean {
    val ref = e as? KtCallableReferenceExpression ?: return false
    return unresolvableReference(ref, resolver) || referencesUninferableGeneric(ref, resolver)
}

/** Whether [init] is typed as an error: see the file comment for the shapes. */
private fun KotlinSemanticChecks.errorTypedInitializer(init: KtExpression, resolver: KotlinResolver): Boolean {
    var e: KtExpression = init
    while (e is KtParenthesizedExpression) e = e.expression ?: return false
    when (e) {
        is KtCallableReferenceExpression -> return unresolvableReference(e, resolver)
        is KtPostfixExpression -> {
            if (e.operationReference.getReferencedNameElementType() != KtTokens.EXCLEXCL) return false
            val base = e.baseExpression as? KtCallableReferenceExpression ?: return false
            return unresolvableReference(base, resolver)
        }
        is KtIfExpression, is KtWhenExpression, is KtTryExpression -> {
            if (e is KtWhenExpression && e.entries.none { it.isElse } && e.subjectExpression == null) return false
            val values = branchValues(e) ?: return false
            return values.isNotEmpty() && values.all { v -> resultOf(v)?.let { failedReference(it, resolver) } == true }
        }
        else -> return false
    }
}

private fun KotlinSemanticChecks.invokeOnErrorTypedLocal(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
    if (call.typeArgumentList != null) return null
    val parent = call.parent
    if (parent is KtQualifiedExpression && parent.selectorExpression === call) return null
    val name = callee.getReferencedName()
    if (name.isEmpty()) return null
    // Cheap gates first: no function of that name resolves (the memoized callee lookup every call gets anyway), and
    // a local `val name = <if/when/try/::ref>` is declared earlier in an enclosing block.
    if (resolver.resolveCalleeFunction(call) != null) return null
    val decl = precedingLocal(call, name) ?: return null
    val init = decl.initializer ?: return null
    if (decl.typeReference != null || !init.text.contains("::")) return null
    val offset = callee.textRange.startOffset
    if (resolver.localFunctionsInScope(offset, name).isNotEmpty()) return null
    val local = resolver.localsAt(offset, name).firstOrNull() ?: return null
    if ((local.declaration() as? KotlinDomNode)?.psi !== decl) return null
    if (!errorTypedInitializer(init, resolver)) return null
    val r = callee.textRange
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Unresolved reference: '$name' has an error type, so it cannot be invoked",
        KotlinDiagnosticCodes.UNRESOLVED,
    )
}

/** The nearest local property called [name] declared before [at] in a block enclosing it, by sibling pointer. */
private fun precedingLocal(at: KtElement, name: String): KtProperty? {
    var child: KtElement = at
    var node: KtElement? = at.parent
    while (node != null) {
        if (node is KtBlockExpression) {
            var s: KtElement? = child.prevSibling
            while (s != null) {
                if (s is KtProperty && s.name == name) return s
                s = s.prevSibling
            }
        }
        if (node is KtNamedFunction || node is KtClass) return null
        child = node
        node = node.parent
    }
    return null
}
