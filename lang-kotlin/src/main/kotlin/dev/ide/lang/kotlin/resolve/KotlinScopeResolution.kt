package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.FileContext
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.ide.lang.completion.PrefixMatcher
import dev.ide.lang.kotlin.symbols.DefaultImports
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtWhenExpression

/** Scope and name resolution: locals, implicit receivers, same-file symbols, enclosing class/companion context, and type-receiver classification. */

/**
 * The chain of implicit `this` receivers in scope at [offset] (innermost first): the receiver of each
 * enclosing receiver-lambda (`apply`/`with`/`run`/DSL builders — `T.() -> R`), the enclosing extension
 * function's receiver, and the enclosing class. Their members are visible without an explicit receiver.
 */
fun KotlinResolver.implicitReceiversAt(offset: Int): List<KotlinType> =
    // Suspend the cache during overload scoring: a scope-function lambda's receiver is resolved via the
    // (mid-resolution) enclosing call, so a scoring-time value can be provisional and must not be cached.
    if (scoringActive) computeImplicitReceiversAt(offset)
    else implicitReceiversCache.getOrPut(offset) { computeImplicitReceiversAt(offset) }

internal fun KotlinResolver.computeImplicitReceiversAt(offset: Int): List<KotlinType> {
    val out = ArrayList<KotlinType>()
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        when (node) {
            is KtLambdaExpression -> expectedFunctionTypeFor(node)
                ?.takeIf { it.isExtensionFunctionType }
                ?.let { (it.typeArguments.firstOrNull() as? KotlinType)?.let(out::add) }

            is KtNamedFunction -> node.receiverTypeReference?.text
                ?.let { service.typeFromText(it, fileContext) }?.let(out::add)
            // An enclosing class/object receiver. An ANONYMOUS object (`object : Foo { }`) or a LOCAL
            // class/object has no reachable `fqName`, so use the synthetic classifier the source model
            // registered it under — its own + supertype members then become the implicit `this` in the body.
            // An enum entry with a body isn't a local type (it's excluded from that model bucket), so it keeps
            // to its own `fqName` and never borrows a synthetic key (which could collide with a real local type).
            is KtClassOrObject -> {
                val fqn = node.fqName?.asString()
                    ?: (node as? org.jetbrains.kotlin.psi.KtObjectDeclaration)?.let {
                        dev.ide.lang.kotlin.symbols.SourceIndexBuilder.localTypeFqn(
                            it
                        )
                    }
                    ?: (node as? KtClass)?.takeIf { it !is org.jetbrains.kotlin.psi.KtEnumEntry }
                        ?.let { dev.ide.lang.kotlin.symbols.SourceIndexBuilder.localTypeFqn(it) }
                if (fqn != null) out += service.typeByFqn(fqn)
            }

            else -> {}
        }
        node = node.parent
    }
    return out
}

/** The member named [name] on the nearest implicit receiver in scope at [offset] — the `this` of an
 *  `apply`/`with`/`run` block, an enclosing extension function's receiver, or the enclosing class — for
 *  highlighting a bare member read (`p.apply { x }`). Innermost receiver first; null when none has it. */
fun KotlinResolver.implicitReceiverMember(name: String, offset: Int): KotlinSymbol? {
    if (name.isEmpty()) return null
    for (recv in implicitReceiversAt(offset)) memberNamed(recv, name)?.let { return it }
    return null
}

/** The simple names of [typeFqn]'s enum constants — for highlighting `Color.RED` as an enum constant. */
fun KotlinResolver.enumConstantNames(typeFqn: String): Set<String> =
    service.enumConstantsOf(typeFqn).mapTo(HashSet()) { it.name }

/** A static/companion member named [name] on type [typeFqn] (`MaterialTheme.colorScheme`, `Type.CONST`),
 *  for member-read highlighting. */
fun KotlinResolver.staticMemberNamed(typeFqn: String, name: String): KotlinSymbol? =
    service.companionMembersFor(typeFqn, name).firstOrNull { it.name == name }
        ?: service.membersNamed(typeFqn, emptyList(), name).firstOrNull()

/** An instance member named [name] on [receiverType] (`obj.prop`), for member-read highlighting. */
fun KotlinResolver.instanceMemberNamed(receiverType: KotlinType, name: String): KotlinSymbol? =
    service.membersNamed(receiverType.qualifiedName, receiverType.typeArguments, name).firstOrNull()

/**
 * The callable a `::` reference points at — `Person::age` (a member property/function of the receiver type),
 * `instance::foo` (a member of the receiver value's type), or `::topFun` (a top-level / local / enclosing
 * callable). Resolves against the receiver's TYPE denotation first (`Type::member`), then its instance type,
 * then a same-file (live-buffer) member the disk model may lag, and finally the top-level scope for a
 * receiver-less reference. Null when the parse-only model can't resolve it (conservative — the caller then
 * neither colors nor flags it). Used by semantic highlighting and to suppress the bare-name unresolved check
 * on a callable-reference selector.
 */
fun KotlinResolver.callableReferenceTarget(e: org.jetbrains.kotlin.psi.KtCallableReferenceExpression): KotlinSymbol? {
    val name = e.callableReference.getReferencedName()
    if (name.isEmpty()) return null
    val receiver = e.receiverExpression
    if (receiver != null) {
        // `Type::member` — a class-name receiver denotes a type: an instance member (an unbound reference
        // `Person::age`), else a static/companion member, else a same-file (unsaved) member.
        typeDenotationFqn(receiver)?.let { fqn ->
            instanceMemberNamed(service.typeByFqn(fqn), name)?.let { return it }
            staticMemberNamed(fqn, name)?.let { return it }
            return sameFileTypeMember(fqn, name)
        }
        // `value::member` — an instance receiver: resolve the member against its (bound) type.
        val recvType = inferType(receiver) ?: return null
        val rt = receiverForMembers(recvType, receiver.textRange.startOffset) ?: return null
        if (rt.isTypeParameter) return null
        return instanceMemberNamed(rt, name) ?: sameFileTypeMember(rt.qualifiedName, name)
    }
    // `::top` — a receiver-less reference to a top-level / local / enclosing-class callable.
    scopeSymbolsAt(e.textRange.startOffset, name, exactName = true).firstOrNull { it.name == name }
        ?.let { return it }
    return service.topLevelByName(name).firstOrNull { it.name == name }
}

/** A member named [name] of the class [typeFqn] read straight from the LIVE buffer's PSI — for a same-file
 *  class the disk symbol model hasn't caught up to yet. Covers a primary-constructor `val`/`var` property and
 *  a class-body property/function. Null when the file has no such class or member. */
internal fun KotlinResolver.sameFileTypeMember(typeFqn: String, name: String): KotlinSymbol? {
    val cls = ktFile.declarations.filterIsInstance<KtClassOrObject>()
        .firstOrNull { it.fqName?.asString() == typeFqn } ?: return null
    cls.primaryConstructorParameters.firstOrNull { it.hasValOrVar() && it.name == name }?.let { p ->
        return KotlinSymbol(
            name, SymbolKind.FIELD, type = paramType(p), origin = SOURCE,
            declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
        )
    }
    for (d in cls.declarations) when (d) {
        is KtProperty -> if (d.name == name && d.receiverTypeReference == null) return sameFileProperty(
            d,
            typeFqn
        )

        is KtNamedFunction -> if (d.name == name && d.receiverTypeReference == null) return sameFileFunction(
            d,
            typeFqn
        )

        else -> {}
    }
    return null
}

/** Locals + parameters in scope at [offset] (declared before it). */
fun KotlinResolver.localsAt(offset: Int): List<KotlinSymbol> {
    val out = ArrayList<KotlinSymbol>()
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        when (node) {
            is KtBlockExpression -> node.statements.forEach { st ->
                // A local FUNCTION is in scope from its declaration on AND inside its own body (self-recursion:
                // `fun fact(n: Int): Int = … fact(n - 1)`); everything else only once its declaration closed.
                // An EXTENSION local (`fun String.twice()`) is not callable by bare name — it surfaces on a
                // matching receiver through [scopeMemberExtensions], mirroring [sameFileScopeSymbols].
                if (st is KtNamedFunction) {
                    if (st.receiverTypeReference == null && localFunctionVisibleAt(st, offset)) {
                        out += localFunction(st)
                    }
                    return@forEach
                }
                if (st.textRange.endOffset > offset) return@forEach
                when (st) {
                    is KtProperty -> out += localVar(st)
                    is KtDestructuringDeclaration -> out += destructuringLocals(
                        st,
                        inferType(st.initializer)
                    )

                    else -> {}
                }
            }
            // A lambda's value parameters are handled by the KtLambdaExpression branch below (which types
            // `it`/named params from the functional parameter the lambda fills). Skip the KtFunctionLiteral
            // here — it IS a KtFunction, and adding its params via param() (type-from-text only) would
            // shadow the typed copy with an untyped one (`{ inner -> }`'s `inner` becomes null-typed first).
            is KtFunctionLiteral -> {}
            is KtFunction -> node.valueParameters.forEach { out += param(it) }
            is KtForExpression -> node.loopParameter?.let { lp ->
                // The loop variable is in scope only in the BODY (not the `in` clause). Gating on that both
                // matches Kotlin scoping AND breaks a recursion: inferring the loop range's type queries
                // locals at the range's own position (in the `in` clause), which then must NOT re-enter this
                // branch — else `for (x in m)` would infer `m` → look up locals → re-infer `m` forever.
                val body = node.body
                if (body != null && offset >= body.textRange.startOffset) {
                    // The loop variable's type is the iteration element type (`for (x in list)` → x is the
                    // element); a destructuring loop var (`for ((k, v) in map)`) splits it by componentN.
                    val element = iterationElementType(inferType(node.loopRange))
                    val dd = lp.destructuringDeclaration
                    if (dd != null) out += destructuringLocals(dd, element)
                    else out += loopParam(lp, element)
                }
            }

            is KtCatchClause -> node.catchParameter?.let { out += param(it) }
            is KtWhenExpression -> node.subjectVariable?.let { out += localVar(it) }
            is KtLambdaExpression -> {
                // Type the lambda's `it` / named params from the functional parameter it fills, so
                // completion inside `"".let { it.<caret> }` knows `it` is String and `stream().map { it }`
                // knows `it` is the element type. A RECEIVER lambda (`T.() -> R`) has its receiver dropped
                // (an implicit `this`, handled by implicitReceiversAt), so it contributes no value param.
                val inputs = expectedLambdaShape(node)?.parameterTypes.orEmpty()
                val params = node.valueParameters
                if (params.isEmpty()) {
                    // The implicit `it` exists only for a single-value-parameter functional type.
                    if (inputs.size == 1) {
                        out += KotlinSymbol(
                            "it",
                            SymbolKind.PARAMETER,
                            type = inputs.first(),
                            origin = SOURCE
                        )
                    }
                } else {
                    params.forEachIndexed { i, p ->
                        val t = service.typeFromText(p.typeReference?.text, fileContext, enclosingClassFqnOf(p))
                            ?: inputs.getOrNull(i)
                        // A destructuring lambda parameter (`forEach { (k, v) -> }`) binds its entries by
                        // componentN of the parameter's type (the Map.Entry / Pair / data class it receives),
                        // not a single `_`.
                        val dd = p.destructuringDeclaration
                        if (dd != null) {
                            out += destructuringLocals(dd, t as? KotlinType)
                        } else {
                            out += KotlinSymbol(
                                p.name ?: "_", SymbolKind.PARAMETER, type = t, origin = SOURCE,
                                declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
                            )
                        }
                    }
                }
            }

            else -> {}
        }
        node = node.parent
    }
    // Property-accessor scope: `field` (the backing field) and a setter's value parameter, both typed to the
    // enclosing property's type — Kotlin's `field`/`value` soft keywords, available in getter AND setter bodies.
    out += accessorScopeLocals(offset)
    // Non-property primary-constructor parameters, visible only in constructor scope (an `init { }` block, a
    // member property's initializer/delegate, or the superclass delegation call) — see [constructorScopeParams].
    constructorScopeParams(offset).forEach { out += param(it) }
    return out
}

/**
 * Whether the local function [fn] is in scope at [offset]: at or after its declaration, or anywhere inside its
 * own body. The second case is what makes SELF-RECURSION resolve (`fun fact(n: Int): Int = … fact(n - 1)`) —
 * without it the callee sat outside the "declared before the caret" window and read as unresolved. A local
 * function declared LATER in the block stays out of scope, matching Kotlin (no forward reference between
 * sibling local functions).
 */
internal fun localFunctionVisibleAt(fn: KtNamedFunction, offset: Int): Boolean {
    val r = fn.textRange
    return r.endOffset <= offset || (offset >= r.startOffset && offset <= r.endOffset)
}

/**
 * The local functions in scope at [offset] — declared in any block enclosing [from] and visible there (see
 * [localFunctionVisibleAt]), optionally narrowed to one [name]. Both plain and EXTENSION locals; callers filter.
 * Allocation-free until something matches (almost no file declares a local function, and this sits on the
 * per-call resolution path): the enclosing blocks are walked by sibling pointer rather than through
 * `KtBlockExpression.statements`, which materializes a list per block per query.
 */
internal fun localFunctionsInScope(
    from: PsiElement?,
    offset: Int,
    name: String? = null
): List<KtNamedFunction> {
    var out: MutableList<KtNamedFunction>? = null
    var node: PsiElement? = from
    while (node != null) {
        if (node is KtBlockExpression) forEachLocalFunction(node) { fn ->
            if ((name == null || fn.name == name) && localFunctionVisibleAt(fn, offset)) {
                (out ?: ArrayList<KtNamedFunction>(2).also { out = it }) += fn
            }
        }
        node = node.parent
    }
    return out ?: emptyList()
}

/** Apply [action] to each local function declared directly in [block], walking children by sibling pointer so
 *  the common "no local functions here" case costs nothing. */
internal inline fun forEachLocalFunction(block: KtBlockExpression, action: (KtNamedFunction) -> Unit) {
    var child: PsiElement? = block.firstChild
    while (child != null) {
        if (child is KtNamedFunction) action(child)
        child = child.nextSibling
    }
}

internal fun KotlinResolver.localFunctionsInScope(offset: Int, name: String? = null): List<KtNamedFunction> =
    localFunctionsInScope(elementAt(offset), offset, name)

/**
 * A symbol for a LOCAL function declaration (`fun helper(x: Int) = …` inside a body). Carries the same shape as
 * [sameFileFunction] — value-parameter types/names, per-parameter defaults, the vararg index, type parameters,
 * `@Composable`/`inline`/`suspend` — minus the owner/package (a local has neither). Previously locals got a
 * name-and-kind-only symbol, so every call-applicability check saw a ZERO-parameter function: `helper(1)` was
 * reported "Too many arguments (expected 0)", a named argument "Cannot find a parameter with this name", and a
 * genuinely missing argument went unreported. The return type stays the DECLARED one; an expression body's
 * inferred type is resolved per call site by [inferredReturnTypeForCall] through [declarationNode].
 */
internal fun KotlinResolver.localFunction(fn: KtNamedFunction): KotlinSymbol {
    val params = fn.valueParameters.map { (it.name ?: "_") to it.typeReference?.text }
    val retText = fn.typeReference?.text
    val owner = enclosingClassFqnOf(fn) // the same for every parameter, so resolved once
    return KotlinSymbol(
        name = fn.name ?: "_", kind = SymbolKind.METHOD,
        type = retText?.let { service.typeFromText(it, fileContext, owner) },
        origin = SOURCE,
        signature = "(" + params.joinToString(", ") { (n, t) -> "$n: ${t ?: "?"}" } + ")" + (retText?.let { ": $it" }
            ?: ""),
        paramTypes = params.map { (_, t) -> service.typeFromText(t, fileContext, owner) },
        paramNames = params.map { (n, _) -> n },
        paramHasDefault = fn.valueParameters.map { it.hasDefaultValue() },
        varargParamIndex = fn.valueParameters.indexOfFirst { it.isVarArg },
        typeParameters = fn.typeParameters.mapNotNull { it.name },
        isComposable = fn.annotationEntries.any { it.shortName?.asString() == "Composable" },
        isInline = fn.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INLINE_KEYWORD),
        isSuspend = fn.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SUSPEND_KEYWORD),
        declarationNode = runCatching { parsed.adapt(fn) }.getOrNull(),
    )
}

/**
 * The property-accessor-local bindings in scope at [offset] when it sits inside a getter/setter body: `field`
 * (the backing field, typed to the enclosing property's type) and, in a setter, the accessor's value
 * parameter (`value` by default, or the custom name in `set(v)`), also of the property's type. Empty when
 * [offset] is not inside a property accessor. The property's type comes from its explicit type or, absent
 * one, its initializer (a `field`-referencing property without either doesn't compile, so that gap is moot).
 */
internal fun KotlinResolver.accessorScopeLocals(offset: Int): List<KotlinSymbol> {
    val accessor = PsiTreeUtil.getParentOfType(elementAt(offset), KtPropertyAccessor::class.java)
        ?: return emptyList()
    val prop = accessor.parent as? KtProperty ?: return emptyList()
    val propType =
        service.typeFromText(prop.typeReference?.text, fileContext, enclosingClassFqnOf(prop))
            ?: inferType(prop.initializer)
    val out = ArrayList<KotlinSymbol>(2)
    out += KotlinSymbol(
        "field", SymbolKind.FIELD, type = propType, origin = SOURCE,
        declarationNode = runCatching { parsed.adapt(prop) }.getOrNull(),
    )
    accessor.valueParameters.firstOrNull()?.let { p ->
        out += KotlinSymbol(
            p.name ?: "value",
            SymbolKind.PARAMETER,
            type = service.typeFromText(p.typeReference?.text, fileContext, enclosingClassFqnOf(p)) ?: propType,
            origin = SOURCE,
            declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
        )
    }
    return out
}

/**
 * The primary-constructor parameters visible in *constructor scope* at [offset]: an `init { }` block, a member
 * property's initializer/delegate expression, or the superclass delegation call `: Base(arg)` — the positions
 * that run as part of the primary constructor. Only the NON-property parameters are returned (a `val`/`var`
 * parameter is already a member, surfaced elsewhere); a plain parameter is in scope ONLY here, not in a member
 * function or accessor body. Empty at any other position, including a member function body and a bare
 * declaration slot. The first body boundary above the caret decides: an accessor / secondary constructor /
 * member (or top-level) function body means "not constructor scope"; a lambda is transparent (kept walking).
 */
internal fun KotlinResolver.constructorScopeParams(offset: Int): List<KtParameter> {
    var prev: PsiElement? = null
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        when (node) {
            // Bodies that do NOT run in the primary constructor — only `val`/`var` params are visible there.
            is KtPropertyAccessor, is KtSecondaryConstructor -> return emptyList()
            is KtNamedFunction -> if (node.parent is KtClassBody || node.parent is KtFile) return emptyList()
            // `init { }` — runs in the primary constructor.
            is KtAnonymousInitializer -> return classCtorParams(node)
            // A member property's initializer / delegate expression (its accessors are excluded above).
            is KtProperty -> if (node.parent is KtClassBody && (prev === node.initializer || prev === node.delegate))
                return classCtorParams(node)
            // The superclass delegation call `: Base(arg)` — its arguments run in the primary constructor.
            is KtSuperTypeCallEntry -> return classCtorParams(node)
            // Reached the class without passing through a constructor-scope context (a bare declaration slot).
            is KtClassOrObject -> return emptyList()
        }
        prev = node
        node = node.parent
    }
    return emptyList()
}

/** The non-property primary-constructor parameters of the class enclosing [e]. */
private fun classCtorParams(e: PsiElement): List<KtParameter> =
    PsiTreeUtil.getParentOfType(e, KtClassOrObject::class.java)
        ?.primaryConstructorParameters?.filter { !it.hasValOrVar() }.orEmpty()

/**
 * Whether the receiver [expr] is a type/static reference (`String.`, `Locale.`, a qualified type)
 * rather than a value/instance (`listOf("").`, `someVar.`, a call, a literal). Drives instance-vs-static
 * member filtering at the `.`: a value is shadowing if a local/param of that name is in scope.
 */
fun KotlinResolver.isTypeReceiver(expr: KtExpression): Boolean = typeDenotationFqn(expr) != null

/**
 * If [expr] denotes a TYPE / static reference (`String`, `java.util.Locale`, the Android `R.layout`, an
 * `Outer.Inner`) rather than a value/instance, the resolved FQN; else null. A simple capitalized name
 * resolves through imports/classpath (unless a local of that name shadows it); a qualified expression
 * resolves either as a fully-qualified type by its own text or as a nested type through a resolved outer
 * — so `R.layout.<caret>` (where `layout` is lower-case) is still recognized as static navigation.
 *
 * A singleton is excluded via [KotlinSymbolService.isSingletonObject], NOT [KotlinSymbolService.isObject]:
 * the latter deliberately reports false for a COMPANION, because a bare `Foo.` denotes the class. But an
 * EXPLICITLY spelled `Foo.Companion` / `Duration.Companion` / a named `Foo.Factory` denotes the singleton
 * INSTANCE, whose members are instance members — classifying it as a type receiver filtered completion down
 * to statics and left `import kotlin.time.Duration.Companion.<caret>` (and `Duration.Companion.<caret>`
 * anywhere else) with nothing to offer.
 */
fun KotlinResolver.typeDenotationFqn(expr: KtExpression): String? = when (expr) {
    is KtParenthesizedExpression -> expr.expression?.let { typeDenotationFqn(it) }
    is KtNameReferenceExpression -> {
        val name = expr.getReferencedName()
        when {
            name.firstOrNull()?.isUpperCase() != true -> null // type names are capitalized
            localsAt(expr.textRange.startOffset).any { it.name == name } -> null // a value shadows the type
            // An `object` singleton (`CardDefaults`, `MaterialTheme`, a local `object`) is an INSTANCE, not a
            // type — its members are reached like an instance's, so it is NOT a type/static receiver. A local
            // `class Foo` in scope resolves by its synthetic FQN first (it isn't a resolvable type name).
            // The ENCLOSING class is passed so a NESTED type named by its simple name from inside the
            // enclosing body denotes a type (`Lvl` inside `object Api { enum class Lvl { A, B } }`) — without
            // it `Lvl.A` had no type denotation, so `unresolvedMember`'s enum-constant escape never fired and
            // the constant read "Unresolved reference: A".
            else -> (localTypesInScope(expr.textRange.startOffset)[name] ?: service.resolveTypeName(
                name,
                fileContext,
                enclosingClassFqn(expr.textRange.startOffset)
            ))
                ?.takeIf { service.isKnownType(it) && !service.isSingletonObject(it) }
        }
    }

    is KtQualifiedExpression -> {
        val sel = (expr.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
        when {
            sel == null -> null
            // (a) fully-qualified type by its own text: `java.util.Locale` (but not a qualified singleton)
            sel.firstOrNull()?.isUpperCase() == true && service.isKnownType(expr.text) &&
                !service.isSingletonObject(expr.text) -> expr.text
            // (b) nested type through a resolved outer: `R.layout`, `Outer.Inner` — but a nested SINGLETON
            // (`Foo.Companion`, `object Outer { object Inner }`) is an instance, same as (a).
            else -> {
                // A nested type can also hang off an `object` QUALIFIER (`Api.Lvl` where `Api` is an object),
                // which [typeDenotationFqn] itself rejects since a bare `Api` is the instance. Only probed for
                // a CAPITALIZED selector — one that could name a type at all — so the common member selector
                // (`MaterialTheme.colorScheme`) costs exactly what it did before.
                val container = typeDenotationFqn(expr.receiverExpression)
                    ?: if (sel.firstOrNull()?.isUpperCase() == true) objectQualifierFqn(expr.receiverExpression) else null
                container?.let { "$it.$sel".takeIf { f -> service.isKnownType(f) && !service.isSingletonObject(f) } }
            }
        }
    }

    else -> null // calls, literals, `this`, `super` → instances
}

/** The `object` singleton an expression names when it stands in the QUALIFIER half of a nested-type lookup:
 *  `Api.Lvl` names the nested enum even though `Api` is an object. A BARE `Api` is the INSTANCE, which is why
 *  [typeDenotationFqn] excludes singletons — but here the name is a qualifier, not a value. The singleton
 *  filter still applies to the RESULT of the nested lookup, so `Foo.Companion` is never a type denotation. */
private fun KotlinResolver.objectQualifierFqn(expr: KtExpression): String? = when (expr) {
    is KtParenthesizedExpression -> expr.expression?.let { objectQualifierFqn(it) }
    is KtNameReferenceExpression -> {
        val name = expr.getReferencedName()
        val offset = expr.textRange.startOffset
        if (localsAt(offset).any { it.name == name }) null // a value of that name shadows the object
        else (localTypesInScope(offset)[name]
            ?: service.resolveTypeName(name, fileContext, enclosingClassFqn(offset)))
            ?.takeIf { service.isSingletonObject(it) }
    }
    is KtQualifiedExpression -> (expr.selectorExpression as? KtNameReferenceExpression)?.let { sel ->
        (typeDenotationFqn(expr.receiverExpression) ?: objectQualifierFqn(expr.receiverExpression))
            ?.let { "$it.${sel.getReferencedName()}" }
            ?.takeIf { service.isSingletonObject(it) }
    }
    else -> null
}

/**
 * The FQN of the `object` singleton a BARE [name] denotes at [offset] — a local `object`, a same-file or
 * nested one reached by simple name, an imported one (`import nav.NavKeys.shoppingListScreen`) — or null when
 * [name] isn't one. A value in scope of that name shadows it, as does anything the name resolves to that
 * isn't a singleton.
 *
 * The counterpart of [typeDenotationFqn], which deliberately EXCLUDES objects: a bare object reference is the
 * INSTANCE (a value), not a type/static receiver. Case-blind — an object may be named in any case
 * (`data object shoppingListScreen`), so the capitalized-name shortcut that guards the type paths can't be
 * used here.
 */
fun KotlinResolver.objectDenotationFqn(name: String, offset: Int): String? {
    if (name.isEmpty()) return null
    if (localsAt(offset).any { it.name == name }) return null // a value of that name shadows the object
    localTypesInScope(offset)[name]?.let { return it.takeIf { fqn -> service.isObject(fqn) } }
    return service.resolveTypeName(name, fileContext, enclosingClassFqn(offset))
        ?.takeIf { service.isObject(it) }
}

/** Whether [name] is a type parameter declared by an enclosing function / class / property at [offset]
 *  (`fun <T>`, `class C<T>`, `val <T> List<T>.x`) — so a generic type reference isn't flagged unresolved. */
fun KotlinResolver.isTypeParameterInScope(name: String, offset: Int): Boolean {
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        if (node is org.jetbrains.kotlin.psi.KtTypeParameterListOwner && node.typeParameters.any { it.name == name }) return true
        node = node.parent
    }
    return false
}

/**
 * The declared upper bound of the type parameter [name] in scope at [offset], resolved to a type — so a value
 * whose type is a bare `T` (`<T : Bound>`) enumerates the members of `Bound` (Kotlin resolves `t.member`
 * against a type parameter's upper bound). Reads the parameter's `: Bound` clause and any matching `where T :
 * Bound` constraint on the nearest enclosing type-parameter-list owner (function / class / property) that
 * declares [name]; a bound that is itself another type parameter (`<R, T : R>`) is followed to its own bound.
 * Returns null when [name] is not a type parameter in scope, has no explicit bound, or the bound is itself
 * unresolvable — the caller then enumerates no members (a bare unbounded `T` has only `Any?`'s, as before).
 */
fun KotlinResolver.typeParameterUpperBound(
    name: String,
    offset: Int,
    seen: MutableSet<String> = HashSet()
): KotlinType? {
    if (!seen.add(name)) return null // guard a cyclic `<T : R, R : T>`
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        if (node is org.jetbrains.kotlin.psi.KtTypeParameterListOwner) {
            val tp = node.typeParameters.firstOrNull { it.name == name }
            if (tp != null) {
                val boundTexts = buildList {
                    tp.extendsBound?.text?.let { add(it) }
                    node.typeConstraints.forEach { c ->
                        if (c.subjectTypeParameterName?.getReferencedName() == name) c.boundTypeReference?.text?.let {
                            add(
                                it
                            )
                        }
                    }
                }
                for (text in boundTexts) {
                    val t = service.typeFromText(text, fileContext) ?: continue
                    if (service.isKnownType(t.qualifiedName)) return t
                    // The bound is itself a type parameter (`<R, T : R>`) → resolve THAT one's bound.
                    if (isTypeParameterInScope(
                            t.qualifiedName,
                            offset
                        )
                    ) typeParameterUpperBound(t.qualifiedName, offset, seen)?.let { return it }
                }
                return null // declared here (an inner owner shadows an outer one) but no resolvable bound
            }
        }
        node = node.parent
    }
    return null
}

/**
 * [type] as a receiver for MEMBER access: a bare type-parameter classifier in scope at [offset] is replaced by
 * its resolved upper bound ([typeParameterUpperBound]) so `t.member` sees the bound's members; any other type
 * is returned unchanged. Null only when [type] IS a type parameter in scope with no resolvable bound (the
 * caller then enumerates nothing, matching the pre-existing back-off). [type]'s nullability is preserved.
 */
fun KotlinResolver.receiverForMembers(type: KotlinType, offset: Int): KotlinType? {
    if (!isTypeParameterInScope(type.qualifiedName, offset)) return type
    return typeParameterUpperBound(type.qualifiedName, offset)?.withNullable(type.nullable)
}

internal fun KotlinResolver.enclosingClassOrObject(offset: Int): KtClassOrObject? {
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        if (node is KtClassOrObject) return node; node = node.parent
    }
    return null
}

/** Whether a bare [name] resolves to anything in scope (local, implicit-receiver member, top-level,
 *  import, or a known type). Used by the unresolved-reference diagnostic. */
fun KotlinResolver.bareNameResolves(name: String, offset: Int): Boolean {
    if (localsAt(offset).any { it.name == name }) return true
    // A reified type parameter used in expression position (`T::class`) is in scope, not unresolved.
    if (isTypeParameterInScope(name, offset)) return true
    // A named LOCAL class/object in scope (`class Foo` / `object O` declared in this or an enclosing block):
    // referenced as `Foo()` / `O.member`, resolvable by simple name only within its scope.
    if (localTypesInScope(offset).containsKey(name)) return true
    // Top-level declarations in THIS live file (the module index is disk-based and may lag the buffer):
    // functions/properties AND classes/objects/typealiases (a same-file `object Foo` / `class Foo` is a
    // resolvable bare reference — `Foo()` / `Foo.bar` — before the index has caught up to the buffer).
    if (ktFile.declarations.any { it is org.jetbrains.kotlin.psi.KtNamedDeclaration && it.name == name }) return true
    // Members of an ENCLOSING class of the live buffer — the symbol service indexes disk, not the file
    // being edited, so a same-file member (`field`, `helper()`) won't appear in membersOf() below.
    if (enclosingClassMembersContain(offset, name)) return true
    // A member (or inherited member) of an implicit `this` receiver resolves bare; an EXTENSION on that
    // receiver only resolves when it is actually in scope (imported / same-package / default) — Kotlin
    // requires a top-level extension to be imported even when its receiver type IS the implicit `this`
    // (`ComponentActivity.setContent` inside a `ComponentActivity` still needs `import
    // androidx.activity.compose.setContent`). Without this gate an un-imported extension was treated as
    // resolved, so no `kt.unresolved` diagnostic (and thus no "Import" quick-fix) fired, yet the code didn't
    // compile. Mirrors the member-access rule in [KotlinSemanticChecks.unresolvedMember] (`16.dp`).
    if (implicitReceiversAt(offset).any { recv ->
            service.membersNamed(recv.qualifiedName, recv.typeArguments, name)
                .any { !it.isExtension || extensionInScope(it) }
        }
    ) return true
    // A LOCAL extension function called bare on an implicit `this` of its receiver type — in practice its own
    // recursive self-call (`fun String.twice(): String = if (n == 0) this else twice()`). Locals of that shape
    // are deliberately kept out of [localsAt] (an extension is not bare-callable in general), so without this
    // the recursion read as unresolved. Needs no import check: a local is always in its own scope.
    if (localFunctionsInScope(offset, name).any { it.receiverTypeReference != null } &&
        implicitReceiversAt(offset).any { recv -> scopeMemberExtensions(offset, recv, name).any { it.name == name } }
    ) return true
    // A top-level callable (`remember`, `mutableStateOf`) resolves bare only when it is actually in
    // scope — explicitly imported, star-imported, same-package, or default-imported. A classpath
    // callable that was never imported stays unresolved, as Kotlin reports (mirrors the extension rule).
    // A `private` top-level from ANOTHER file is out of scope (this file's own privates already resolved above
    // via `ktFile.declarations`), so a cross-file private reference stays unresolved — as the compiler reports.
    if (service.topLevelByName(name).any { Modifier.PRIVATE !in it.modifiers && topLevelInScope(it, fileContext) }) return true
    // An explicit import brings [name] into scope, but ONLY as what it NAMES. A TYPE/object import, an
    // object/enum-entry MEMBER import (its owner is a type), or a non-extension top-level callable (line 501
    // usually resolved that) is usable bare. But an import of a pure EXTENSION
    // (`import androidx.activity.compose.setContent`) does NOT make it usable without its receiver — and no
    // implicit receiver of the right type is in scope here (checked above) — so it stays unresolved, exactly
    // as Kotlin reports. Only a POSITIVELY-confirmed pure extension falls through; anything unrecognized
    // (a typealias, an unresolved category) resolves, so this never false-positives.
    val matchingImports = fileContext.imports.filter { !it.isStar && it.simpleName == name }
    if (matchingImports.isNotEmpty()) {
        val extPackages = service.extensionPackages(name)
        val hasNonExtensionTopLevel = service.topLevelByName(name).any { it.receiverTypeFqn == null }
        val allPureExtension = matchingImports.all { imp ->
            val owner = imp.fqn.substringBeforeLast('.', "")
            owner in extPackages && !hasNonExtensionTopLevel &&
                !service.typeFqnKnown(imp.fqn) &&                              // not a type/object import
                !(owner.isNotEmpty() && service.typeFqnKnown(owner))           // not an object/enum member import
        }
        if (!allPureExtension) return true
        // else: every matching import is a confirmed pure extension with no applicable receiver → not resolvable.
    }
    // A different-package project type isn't in scope until imported (resolveTypeName resolves a project type
    // bare only same-package), so an unimported same-module type is flagged by the bare-reference diagnostic. The
    // ENCLOSING class is passed so a NESTED-class constructor (`Plan(1)` inside `class Game { private class Plan }`)
    // resolves by simple name.
    return service.resolveTypeName(name, fileContext, enclosingClassFqn(offset))?.let { service.isKnownType(it) } == true
}

/**
 * Whether a top-level callable [sym] is in scope at the use site: Kotlin resolves a top-level
 * function/property only when its package is imported (explicitly or via a star/default import) or is the
 * file's own package. No package info → don't guess a rejection (treat as in scope). Mirrors the
 * extension-visibility rule in [KotlinSourceAnalyzer.extensionInScope].
 */
internal fun KotlinResolver.topLevelInScope(sym: KotlinSymbol, ctx: FileContext): Boolean {
    val pkg =
        sym.packageName ?: sym.declaringClassFqn?.substringBeforeLast('.', "")?.ifEmpty { null }
        ?: return true
    if (pkg == ctx.packageName || DefaultImports.isDefaultImported(pkg)) return true
    return ctx.imports.any { imp -> if (imp.isStar) imp.packageName == pkg else imp.fqn == "$pkg.${sym.name}" }
}

/** Whether [name] is a member (function/property or a constructor `val/var` param) of any class enclosing
 *  [offset] in the live buffer — including an enclosing enum's own CONSTANTS, which are bare-accessible within
 *  its body (`fun opp() = LEFT`, `when (this) { LEFT -> … }`). Resolves same-file members the disk-based symbol
 *  service can't see yet. */
internal fun KotlinResolver.enclosingClassMembersContain(offset: Int, name: String): Boolean {
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        if (node is KtClassOrObject) {
            if (node.declarations.any {
                    (it is KtNamedFunction && it.name == name) || (it is KtProperty && it.name == name) ||
                        (it is org.jetbrains.kotlin.psi.KtEnumEntry && it.name == name)
                }) return true
            if (node.primaryConstructorParameters.any { it.hasValOrVar() && it.name == name }) return true
        }
        node = node.parent
    }
    return false
}

/** The KIND of an enclosing-class member named [name] in the LIVE buffer (the disk model can lag an edit):
 *  `true` = a member FUNCTION (a candidate un-invoked call), `false` = a VALUE (a property, a `val`/`var`
 *  constructor parameter, or an enum constant → a legitimate bare read), `null` = no such member. A value
 *  anywhere on the enclosing chain wins (never mis-flag a value read); otherwise a function if one was seen. */
internal fun KotlinResolver.enclosingClassMemberKind(offset: Int, name: String): Boolean? {
    var node: PsiElement? = elementAt(offset)
    var sawFunction = false
    while (node != null) {
        if (node is KtClassOrObject) {
            for (d in node.declarations) {
                if ((d is KtProperty && d.name == name) ||
                    (d is org.jetbrains.kotlin.psi.KtEnumEntry && d.name == name)
                ) return false
                if (d is KtNamedFunction && d.name == name) sawFunction = true
            }
            if (node.primaryConstructorParameters.any { it.hasValOrVar() && it.name == name }) return false
        }
        node = node.parent
    }
    return if (sawFunction) true else null
}

/**
 * Whether [name] resolves through a COMPANION object in scope at [offset] — a companion's members (and the
 * companion's own name, `companion object Factory`) are bare-accessible throughout the enclosing class's body.
 * Three sources, so the unresolved-reference diagnostic doesn't need the old blanket "any enclosing class has
 * a companion → back off" rule (which silently suppressed EVERY unresolved bare name in such a class — the
 * common `class MyViewModel { … companion object { … } }` shape, where a missing `viewModelScope`/`withContext`
 * import was never reported):
 *  - the LIVE buffer's own companion declarations (an edit is seen before the disk model catches up);
 *  - the symbol model's view of the enclosing class's companion ([KotlinSymbolService.companionMembersFor] —
 *    the companion's inherited members plus compiler-plugin synthetics like `serializer()`);
 *  - a SUPERTYPE's companion, whose members are bare-accessible in a subclass body too.
 *
 * Backs off (returns true) when an enclosing class's supertype can't be resolved: that supertype may declare a
 * companion contributing [name], and erring toward "resolved" keeps the check false-positive-free.
 */
fun KotlinResolver.companionMemberInScope(name: String, offset: Int): Boolean {
    if (name.isEmpty()) return false
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        if (node is KtClassOrObject) {
            // `companion object Factory` referenced by its own name (`Factory.create()`).
            if (node.companionObjects.any { it.name == name }) return true
            for (comp in node.companionObjects) {
                if (comp.declarations.any { it is KtNamedDeclaration && it.name == name }) return true
                // A companion with a supertype we can't enumerate could inherit `name` → back off.
                if (comp.superTypeListEntries.isNotEmpty() && !supertypesResolvable(comp)) return true
            }
            val fqn = node.fqName?.asString()
            if (fqn != null) {
                if (service.companionMembersFor(fqn, name).any { it.name == name }) return true
                if (service.supertypesOf(fqn).any { sup ->
                        service.companionMembersFor(sup.qualifiedName, name).any { it.name == name }
                    }
                ) return true
            }
            if (node.superTypeListEntries.isNotEmpty() && !supertypesResolvable(node)) return true
        }
        node = node.parent
    }
    return false
}

/** Whether every supertype named in [cls]'s supertype list resolves to a known type (or is a type parameter) —
 *  i.e. the inherited scope is fully enumerable. False when any entry's name can't be resolved. */
private fun KotlinResolver.supertypesResolvable(cls: KtClassOrObject): Boolean =
    cls.superTypeListEntries.all { entry ->
        val userType = entry.typeReference?.typeElement as? KtUserType ?: return@all true
        val name = userType.referenceExpression?.getReferencedName() ?: return@all true
        if (isTypeParameterInScope(name, entry.textRange.startOffset)) return@all true
        service.resolveTypeName(name, fileContext)?.let { service.isKnownType(it) } == true
    }

/** Named LOCAL types in scope at [offset] (simple name → the synthetic FQN they were registered under): a
 *  `class`/`object` declared as a statement in this block or an enclosing one. So a `LocalClass()` / a local
 *  `object`'s member reference resolves + isn't false-flagged. Anonymous objects (nameless) aren't included —
 *  they're referenced by the `object … { }` expression, not by name. */
fun KotlinResolver.localTypesInScope(offset: Int): Map<String, String> {
    val out = HashMap<String, String>()
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        if (node is KtBlockExpression) {
            for (st in node.statements) {
                if (st is KtClassOrObject && st !is org.jetbrains.kotlin.psi.KtEnumEntry &&
                    st.fqName == null && st.name != null
                ) out.putIfAbsent(
                    st.name!!,
                    dev.ide.lang.kotlin.symbols.SourceIndexBuilder.localTypeFqn(st)
                )
            }
        }
        node = node.parent
    }
    return out
}

/** A bare-accessible member of an enclosing class's companion object named [name] (`fun f() = CONST` inside the
 *  class), resolved through the companion's distinct classifier. Null when no enclosing companion has it. */
fun KotlinResolver.enclosingCompanionMember(name: String, offset: Int): KotlinSymbol? {
    if (name.isEmpty()) return null
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        if (node is KtClassOrObject && node.companionObjects.isNotEmpty()) {
            node.fqName?.asString()?.let { fqn ->
                service.companionMembersFor(fqn, name).firstOrNull { it.name == name }
                    ?.let { return it }
            }
        }
        node = node.parent
    }
    return null
}

/** All bare-accessible members of every enclosing class's companion object — for completion of an unqualified
 *  reference inside the class body (`CONST`, a companion `factory()`). */
internal fun KotlinResolver.enclosingCompanionMembers(offset: Int): List<KotlinSymbol> {
    val out = ArrayList<KotlinSymbol>()
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        if (node is KtClassOrObject && node.companionObjects.isNotEmpty())
            node.fqName?.asString()?.let { out += service.companionMembersFor(it) }
        node = node.parent
    }
    return out
}

/** A [name] used as a constructor call (a simple `Foo` or a qualified `pkg.Foo`/`Outer.Inner`, its last
 *  segment capitalized) → the resolved type FQN (a known type not shadowed by a local), else null. Drives
 *  constructor-argument validation. */
fun KotlinResolver.constructorTypeFqn(name: String, offset: Int): String? {
    if (name.substringAfterLast('.').firstOrNull()?.isUpperCase() != true) return null
    if ('.' !in name && localsAt(offset).any { it.name == name }) return null
    if ('.' !in name) localTypesInScope(offset)[name]?.let { return it } // a local `class Foo` in scope
    // A different-package project type isn't a resolvable bare constructor call until imported, so `Foo()` for
    // such a type is flagged unresolved (and arg-validation backs off) rather than silently resolving.
    // The ENCLOSING class is passed so a NESTED type constructed by its simple name from inside the enclosing
    // body resolves (`Level(21, "x")` inside `object Api { data class Level(…) }`). Without it the name named no
    // constructor here, while the bare-name path (`typeOfName`) DID resolve it through the enclosing chain — so
    // the call fell through to the classifier-as-value type and [notCallable] reported "Expression 'Level' of
    // type Level cannot be invoked as a function".
    return service.resolveTypeName(name, fileContext, enclosingClassFqn(offset))?.takeIf { service.isKnownType(it) }
}

fun KotlinResolver.enclosingClassFqn(offset: Int): String? = elementAt(offset)?.let { enclosingClassFqnOf(it) }

/** The FQN of the innermost `KtClassOrObject` lexically containing [element] — the enclosing-class half of
 *  [dev.ide.lang.kotlin.symbols.KotlinSymbolService.resolveTypeName]'s nested-type scoping, walked straight
 *  from the PSI parent chain. Preferred over [enclosingClassFqn] wherever the element is already in hand: the
 *  declaration-typing paths run per parameter/local on every diagnostics and completion pass, and this skips
 *  the `elementAt` offset lookup. */
internal fun enclosingClassFqnOf(element: PsiElement): String? {
    var node: PsiElement? = element
    while (node != null) {
        if (node is KtClassOrObject) return node.fqName?.asString()
        node = node.parent
    }
    return null
}

/** The candidate set for a bare name-reference completion: locals, enclosing members, top-level.
 *  [exactName] is the RESOLUTION-probe mode (a call target's known name): equality filtering plus the
 *  exact top-level lookup, so the probe never materializes the graded matcher's wider candidate net. */
fun KotlinResolver.scopeSymbolsAt(
    offset: Int,
    namePrefix: String = "",
    exactName: Boolean = false
): List<KotlinSymbol> {
    val out = ArrayList<KotlinSymbol>()
    out += localsAt(offset)
    // Same-file declarations from the LIVE buffer — a just-typed `fun helper()` / `val x` / `class Foo`.
    // The symbol service indexes DISK and is not refreshed mid-session, so these would otherwise not
    // complete until the file is saved AND the analyzer rebuilt. Mirrors the live-buffer resolution in
    // [bareNameResolves] so a reference that ISN'T flagged unresolved also COMPLETES.
    out += sameFileScopeSymbols(offset)
    // Members of every implicit `this` (apply/with/run block, extension fn, enclosing class).
    implicitReceiversAt(offset).forEach { recv ->
        out += service.membersOf(recv.qualifiedName, recv.typeArguments, null)
            .filterIsInstance<KotlinSymbol>()
    }
    // Bare-accessible members of an enclosing class's companion object (`CONST`, a companion `factory()`).
    out += enclosingCompanionMembers(offset)
    // Named local types in scope (`class Foo` / `object O` in a body) — offered by simple name.
    localTypesInScope(offset).forEach { (simple, fqn) ->
        out += KotlinSymbol(
            simple,
            SymbolKind.CLASS,
            type = service.typeByFqn(fqn),
            origin = SOURCE
        )
    }
    // Locals/implicit members are small, so filter them here; the classpath top-level universe is large, so
    // [topLevelCallables] filters by prefix itself rather than materializing all of it (empty = all).
    val m = PrefixMatcher(namePrefix)
    val scoped = when {
        namePrefix.isEmpty() -> out
        exactName -> out.filter { it.name == namePrefix }
        else -> out.filter { m.matches(it.name) }
    }
    // A `private` top-level declaration is file-scoped: from ANOTHER file it must not be offered. Same-file
    // privates are already in `out`/`scoped` (added by [sameFileScopeSymbols] from the live buffer), so dropping
    // private from the project-wide top-level results here removes exactly the cross-file ones without losing the
    // current file's. (The symbol service stays unfiltered so RESOLUTION of a same-file private still works.)
    val topLevel = (if (exactName) service.topLevelByName(namePrefix) else service.topLevelCallables(namePrefix))
        .filterNot { Modifier.PRIVATE in it.modifiers }
    return scoped + topLevel
}

/**
 * Symbols for declarations visible by simple name in the LIVE buffer that the disk-based symbol model may
 * not carry yet: this file's top-level functions/properties/types plus the members of every enclosing
 * class. Extensions are excluded (not callable by bare name). Signatures match [KotlinSymbolService]'s
 * `toSymbol` so the completion de-dup folds a live symbol together with its already-indexed disk twin.
 */
internal fun KotlinResolver.sameFileScopeSymbols(offset: Int): List<KotlinSymbol> {
    val out = ArrayList<KotlinSymbol>()
    for (d in ktFile.declarations) when (d) {
        is KtNamedFunction -> if (d.receiverTypeReference == null) out += sameFileFunction(d, null)
        is KtProperty -> if (d.receiverTypeReference == null) out += sameFileProperty(d, null)
        is KtClassOrObject -> out += sameFileType(d)
        else -> {}
    }
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        if (node is KtClassOrObject) {
            val ownerFqn = node.fqName?.asString()
            for (d in node.declarations) when (d) {
                is KtNamedFunction -> if (d.receiverTypeReference == null) out += sameFileFunction(
                    d,
                    ownerFqn
                )

                is KtProperty -> if (d.receiverTypeReference == null) out += sameFileProperty(
                    d,
                    ownerFqn
                )

                else -> {}
            }
            node.primaryConstructorParameters.filter { it.hasValOrVar() }.forEach { p ->
                out += KotlinSymbol(
                    p.name ?: "_", SymbolKind.FIELD,
                    type = paramType(p), origin = SOURCE,
                    owner = ownerFqn?.let {
                        KotlinSymbol(
                            it.substringAfterLast('.'),
                            SymbolKind.CLASS,
                            origin = SOURCE
                        )
                    },
                    declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
                )
            }
        }
        node = node.parent
    }
    return out
}

internal fun KotlinResolver.sameFileFunction(fn: KtNamedFunction, ownerFqn: String?): KotlinSymbol {
    val params = fn.valueParameters.map { (it.name ?: "_") to it.typeReference?.text }
    val retText = fn.typeReference?.text
    val sig =
        "(" + params.joinToString(", ") { (n, t) -> "$n: ${t ?: "?"}" } + ")" + (retText?.let { ": $it" }
            ?: "")
    return KotlinSymbol(
        name = fn.name ?: "_", kind = SymbolKind.METHOD,
        // Resolved with [ownerFqn] as the enclosing class, so a signature naming a NESTED class of the owner
        // (`fun f(t: T): T` inside `object Api { class T }`) types by simple name — as [sameFileProperty] does.
        type = retText?.let { service.typeFromText(it, fileContext, ownerFqn) },
        owner = ownerFqn?.let {
            KotlinSymbol(
                it.substringAfterLast('.'),
                SymbolKind.CLASS,
                origin = SOURCE
            )
        },
        origin = SOURCE, signature = sig,
        paramTypes = params.map { (_, t) -> service.typeFromText(t, fileContext, ownerFqn) },
        paramNames = params.map { (n, _) -> n },
        paramHasDefault = fn.valueParameters.map { it.hasDefaultValue() },
        varargParamIndex = fn.valueParameters.indexOfFirst { it.isVarArg },
        isComposable = fn.annotationEntries.any { it.shortName?.asString() == "Composable" },
        isInline = fn.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INLINE_KEYWORD),
        isDeprecated = fn.annotationEntries.any { it.shortName?.asString() == "Deprecated" },
        // Type parameters (`fun <reified T> foo()` → ["T"]) — mirror toSymbol so a generic same-file call keeps
        // its type parameters for explicit-type-argument resolution (reified-generics lowering reads them).
        typeParameters = fn.typeParameters.mapNotNull { it.name },
        // Top-level callables carry their package for import-visibility; members don't (mirror toSymbol).
        packageName = if (ownerFqn == null) fileContext.packageName.ifEmpty { null } else null,
        declarationNode = runCatching { parsed.adapt(fn) }.getOrNull(),
    )
}

internal fun KotlinResolver.sameFileProperty(p: KtProperty, ownerFqn: String?): KotlinSymbol {
    val retText = p.typeReference?.text
    return KotlinSymbol(
        name = p.name ?: "_", kind = SymbolKind.FIELD,
        // A `by`-delegated member/top-level property types as its delegate's `value` (the State/Lazy
        // convention), the same as [localVar] — the value lives in the delegate, not the initializer. The type is
        // resolved with [ownerFqn] as the enclosing class, so a member typed as a NESTED class of the owner
        // (`var pending: Plan?` inside `class Game { private class Plan }`) resolves by simple name.
        type = retText?.let { service.typeFromText(it, fileContext, ownerFqn) }
            ?: inferType(p.initializer)
            ?: p.delegateExpression?.let(::delegatedValueType),
        owner = ownerFqn?.let {
            KotlinSymbol(
                it.substringAfterLast('.'),
                SymbolKind.CLASS,
                origin = SOURCE
            )
        },
        origin = SOURCE, signature = retText?.let { ": $it" } ?: "",
        isDeprecated = p.annotationEntries.any { it.shortName?.asString() == "Deprecated" },
        packageName = if (ownerFqn == null) fileContext.packageName.ifEmpty { null } else null,
        declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
    )
}

internal fun KotlinResolver.sameFileType(c: KtClassOrObject): KotlinSymbol {
    val cls = c as? KtClass
    val kind = when {
        cls?.isInterface() == true -> SymbolKind.INTERFACE
        cls?.isEnum() == true -> SymbolKind.ENUM
        cls?.isAnnotation() == true -> SymbolKind.ANNOTATION_TYPE
        else -> SymbolKind.CLASS
    }
    return KotlinSymbol(
        name = c.name ?: "_", kind = kind,
        type = c.fqName?.asString()?.let { service.typeByFqn(it) },
        origin = SOURCE,
        declarationNode = runCatching { parsed.adapt(c) }.getOrNull(),
    )
}


/** A value parameter's name + (resolved) type — for named-argument completion and arg expected-type. */
data class ParamInfo(val name: String, val type: KotlinType?)
