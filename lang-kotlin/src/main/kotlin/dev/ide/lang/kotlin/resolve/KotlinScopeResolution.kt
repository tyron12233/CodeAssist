package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.FileContext
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtWhenExpression

/** Scope and name resolution: locals, implicit receivers, same-file symbols, enclosing class/companion context, and type-receiver classification. */

/**
 * The chain of implicit `this` receivers in scope at [offset] (innermost first): the receiver of each
 * enclosing receiver-lambda (`apply`/`with`/`run`/DSL builders — `T.() -> R`), the enclosing extension
 * function's receiver, and the enclosing class. Their members are visible without an explicit receiver.
 */
fun KotlinResolver.implicitReceiversAt(offset: Int): List<KotlinType> =
    implicitReceiversCache.getOrPut(offset) { computeImplicitReceiversAt(offset) }

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
            is KtClassOrObject -> node.fqName?.asString()?.let { out += service.typeByFqn(it) }
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
fun KotlinResolver.enumConstantNames(typeFqn: String): Set<String> = service.enumConstantsOf(typeFqn).mapTo(HashSet()) { it.name }

/** A static/companion member named [name] on type [typeFqn] (`MaterialTheme.colorScheme`, `Type.CONST`),
 *  for member-read highlighting. */
fun KotlinResolver.staticMemberNamed(typeFqn: String, name: String): KotlinSymbol? =
    service.companionMembersFor(typeFqn, name).firstOrNull { it.name == name }
        ?: service.membersNamed(typeFqn, emptyList(), name).firstOrNull()

/** An instance member named [name] on [receiverType] (`obj.prop`), for member-read highlighting. */
fun KotlinResolver.instanceMemberNamed(receiverType: KotlinType, name: String): KotlinSymbol? =
    service.membersNamed(receiverType.qualifiedName, receiverType.typeArguments, name).firstOrNull()

/** Locals + parameters in scope at [offset] (declared before it). */
fun KotlinResolver.localsAt(offset: Int): List<KotlinSymbol> {
    val out = ArrayList<KotlinSymbol>()
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        when (node) {
            is KtBlockExpression -> node.statements.filter { it.textRange.endOffset <= offset }.forEach { st ->
                when (st) {
                    is KtProperty -> out += localVar(st)
                    is KtDestructuringDeclaration -> out += destructuringLocals(st, inferType(st.initializer))
                    is KtNamedFunction -> out += KotlinSymbol(
                        st.name ?: "_", SymbolKind.METHOD,
                        type = service.typeFromText(st.typeReference?.text, fileContext), origin = SOURCE,
                        declarationNode = runCatching { parsed.adapt(st) }.getOrNull(),
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
                        out += KotlinSymbol("it", SymbolKind.PARAMETER, type = inputs.first(), origin = SOURCE)
                    }
                } else {
                    params.forEachIndexed { i, p ->
                        val t = service.typeFromText(p.typeReference?.text, fileContext) ?: inputs.getOrNull(i)
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
    return out
}

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
 */
fun KotlinResolver.typeDenotationFqn(expr: KtExpression): String? = when (expr) {
    is KtParenthesizedExpression -> expr.expression?.let { typeDenotationFqn(it) }
    is KtNameReferenceExpression -> {
        val name = expr.getReferencedName()
        when {
            name.firstOrNull()?.isUpperCase() != true -> null // type names are capitalized
            localsAt(expr.textRange.startOffset).any { it.name == name } -> null // a value shadows the type
            // An `object` singleton (`CardDefaults`, `MaterialTheme`) is an INSTANCE, not a type — its
            // members are reached like an instance's, so it is NOT a type/static receiver.
            else -> service.resolveTypeName(name, fileContext)?.takeIf { service.isKnownType(it) && !service.isObject(it) }
        }
    }
    is KtQualifiedExpression -> {
        val sel = (expr.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
        when {
            sel == null -> null
            // (a) fully-qualified type by its own text: `java.util.Locale` (but not a qualified `object`)
            sel.firstOrNull()?.isUpperCase() == true && service.isKnownType(expr.text) && !service.isObject(expr.text) -> expr.text
            // (b) nested type through a resolved outer: `R.layout`, `Outer.Inner`
            else -> typeDenotationFqn(expr.receiverExpression)?.let { "$it.$sel".takeIf { f -> service.isKnownType(f) } }
        }
    }
    else -> null // calls, literals, `this`, `super` → instances
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

internal fun KotlinResolver.enclosingClassOrObject(offset: Int): KtClassOrObject? {
    var node: PsiElement? = elementAt(offset)
    while (node != null) { if (node is KtClassOrObject) return node; node = node.parent }
    return null
}

/** Whether a bare [name] resolves to anything in scope (local, implicit-receiver member, top-level,
 *  import, or a known type). Used by the unresolved-reference diagnostic. */
fun KotlinResolver.bareNameResolves(name: String, offset: Int): Boolean {
    if (localsAt(offset).any { it.name == name }) return true
    // A reified type parameter used in expression position (`T::class`) is in scope, not unresolved.
    if (isTypeParameterInScope(name, offset)) return true
    // Top-level declarations in THIS live file (the module index is disk-based and may lag the buffer):
    // functions/properties AND classes/objects/typealiases (a same-file `object Foo` / `class Foo` is a
    // resolvable bare reference — `Foo()` / `Foo.bar` — before the index has caught up to the buffer).
    if (ktFile.declarations.any { it is org.jetbrains.kotlin.psi.KtNamedDeclaration && it.name == name }) return true
    // Members of an ENCLOSING class of the live buffer — the symbol service indexes disk, not the file
    // being edited, so a same-file member (`field`, `helper()`) won't appear in membersOf() below.
    if (enclosingClassMembersContain(offset, name)) return true
    if (implicitReceiversAt(offset).any { recv ->
            service.membersNamed(recv.qualifiedName, recv.typeArguments, name).isNotEmpty()
        }
    ) return true
    // A top-level callable (`remember`, `mutableStateOf`) resolves bare only when it is actually in
    // scope — explicitly imported, star-imported, same-package, or default-imported. A classpath
    // callable that was never imported stays unresolved, as Kotlin reports (mirrors the extension rule).
    if (service.topLevelByName(name).any { topLevelInScope(it, fileContext) }) return true
    if (fileContext.imports.any { !it.isStar && it.simpleName == name }) return true
    return service.resolveTypeName(name, fileContext)?.let { service.isKnownType(it) } == true
}

/**
 * Whether a top-level callable [sym] is in scope at the use site: Kotlin resolves a top-level
 * function/property only when its package is imported (explicitly or via a star/default import) or is the
 * file's own package. No package info → don't guess a rejection (treat as in scope). Mirrors the
 * extension-visibility rule in [KotlinSourceAnalyzer.extensionInScope].
 */
internal fun KotlinResolver.topLevelInScope(sym: KotlinSymbol, ctx: FileContext): Boolean {
    val pkg = sym.packageName ?: sym.declaringClassFqn?.substringBeforeLast('.', "")?.ifEmpty { null } ?: return true
    if (pkg == ctx.packageName || dev.ide.lang.kotlin.symbols.DefaultImports.isDefaultImported(pkg)) return true
    return ctx.imports.any { imp -> if (imp.isStar) imp.packageName == pkg else imp.fqn == "$pkg.${sym.name}" }
}

/** Whether [name] is a member (function/property or a constructor `val/var` param) of any class enclosing
 *  [offset] in the live buffer. Resolves same-file members the disk-based symbol service can't see yet. */
internal fun KotlinResolver.enclosingClassMembersContain(offset: Int, name: String): Boolean {
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        if (node is KtClassOrObject) {
            if (node.declarations.any { (it is KtNamedFunction && it.name == name) || (it is KtProperty && it.name == name) }) return true
            if (node.primaryConstructorParameters.any { it.hasValOrVar() && it.name == name }) return true
        }
        node = node.parent
    }
    return false
}

/** True if any enclosing class has a companion object, whose members are bare-accessible but not
 *  modeled, so the unresolved-reference diagnostic backs off to avoid false positives. */
fun KotlinResolver.companionInScope(offset: Int): Boolean {
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        if (node is KtClassOrObject && node.companionObjects.isNotEmpty()) return true
        node = node.parent
    }
    return false
}

/** A [name] used as a constructor call (a simple `Foo` or a qualified `pkg.Foo`/`Outer.Inner`, its last
 *  segment capitalized) → the resolved type FQN (a known type not shadowed by a local), else null. Drives
 *  constructor-argument validation. */
fun KotlinResolver.constructorTypeFqn(name: String, offset: Int): String? {
    if (name.substringAfterLast('.').firstOrNull()?.isUpperCase() != true) return null
    if ('.' !in name && localsAt(offset).any { it.name == name }) return null
    return service.resolveTypeName(name, fileContext)?.takeIf { service.isKnownType(it) }
}

fun KotlinResolver.enclosingClassFqn(offset: Int): String? {
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        if (node is KtClassOrObject) return node.fqName?.asString()
        node = node.parent
    }
    return null
}

/** The candidate set for a bare name-reference completion: locals, enclosing members, top-level.
 *  [exactName] is the RESOLUTION-probe mode (a call target's known name): equality filtering plus the
 *  exact top-level lookup, so the probe never materializes the graded matcher's wider candidate net. */
fun KotlinResolver.scopeSymbolsAt(offset: Int, namePrefix: String = "", exactName: Boolean = false): List<KotlinSymbol> {
    val out = ArrayList<KotlinSymbol>()
    out += localsAt(offset)
    // Same-file declarations from the LIVE buffer — a just-typed `fun helper()` / `val x` / `class Foo`.
    // The symbol service indexes DISK and is not refreshed mid-session, so these would otherwise not
    // complete until the file is saved AND the analyzer rebuilt. Mirrors the live-buffer resolution in
    // [bareNameResolves] so a reference that ISN'T flagged unresolved also COMPLETES.
    out += sameFileScopeSymbols(offset)
    // Members of every implicit `this` (apply/with/run block, extension fn, enclosing class).
    implicitReceiversAt(offset).forEach { recv ->
        out += service.membersOf(recv.qualifiedName, recv.typeArguments, null).filterIsInstance<KotlinSymbol>()
    }
    // Locals/implicit members are small, so filter them here; the classpath top-level universe is large, so
    // [topLevelCallables] filters by prefix itself rather than materializing all of it (empty = all).
    val m = dev.ide.lang.completion.PrefixMatcher(namePrefix)
    val scoped = when {
        namePrefix.isEmpty() -> out
        exactName -> out.filter { it.name == namePrefix }
        else -> out.filter { m.matches(it.name) }
    }
    return scoped + (if (exactName) service.topLevelByName(namePrefix) else service.topLevelCallables(namePrefix))
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
                is KtNamedFunction -> if (d.receiverTypeReference == null) out += sameFileFunction(d, ownerFqn)
                is KtProperty -> if (d.receiverTypeReference == null) out += sameFileProperty(d, ownerFqn)
                else -> {}
            }
            node.primaryConstructorParameters.filter { it.hasValOrVar() }.forEach { p ->
                out += KotlinSymbol(
                    p.name ?: "_", SymbolKind.FIELD,
                    type = paramType(p), origin = SOURCE,
                    owner = ownerFqn?.let { KotlinSymbol(it.substringAfterLast('.'), SymbolKind.CLASS, origin = SOURCE) },
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
    val sig = "(" + params.joinToString(", ") { (n, t) -> "$n: ${t ?: "?"}" } + ")" + (retText?.let { ": $it" } ?: "")
    return KotlinSymbol(
        name = fn.name ?: "_", kind = SymbolKind.METHOD,
        type = retText?.let { service.typeFromText(it, fileContext) },
        owner = ownerFqn?.let { KotlinSymbol(it.substringAfterLast('.'), SymbolKind.CLASS, origin = SOURCE) },
        origin = SOURCE, signature = sig,
        paramTypes = params.map { (_, t) -> service.typeFromText(t, fileContext) },
        paramNames = params.map { (n, _) -> n },
        paramHasDefault = fn.valueParameters.map { it.hasDefaultValue() },
        varargParamIndex = fn.valueParameters.indexOfFirst { it.isVarArg },
        isComposable = fn.annotationEntries.any { it.shortName?.asString() == "Composable" },
        isDeprecated = fn.annotationEntries.any { it.shortName?.asString() == "Deprecated" },
        // Top-level callables carry their package for import-visibility; members don't (mirror toSymbol).
        packageName = if (ownerFqn == null) fileContext.packageName.ifEmpty { null } else null,
        declarationNode = runCatching { parsed.adapt(fn) }.getOrNull(),
    )
}

internal fun KotlinResolver.sameFileProperty(p: KtProperty, ownerFqn: String?): KotlinSymbol {
    val retText = p.typeReference?.text
    return KotlinSymbol(
        name = p.name ?: "_", kind = SymbolKind.FIELD,
        type = retText?.let { service.typeFromText(it, fileContext) } ?: inferType(p.initializer),
        owner = ownerFqn?.let { KotlinSymbol(it.substringAfterLast('.'), SymbolKind.CLASS, origin = SOURCE) },
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
