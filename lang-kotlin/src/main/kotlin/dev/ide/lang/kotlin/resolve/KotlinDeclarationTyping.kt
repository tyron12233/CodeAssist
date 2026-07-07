package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.varargArrayText
import dev.ide.lang.resolve.SymbolKind
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParameterList
import org.jetbrains.kotlin.psi.KtProperty

/** Declaration typing: delegated properties, destructuring components, loop variables, and parameter/local symbols. */

/**
 * The delegate-accessor operators a `by`-delegated [property] needs but that are NOT in scope: `getValue`
 * always, plus `setValue` for a `var`. Kotlin desugars `val x by d` to `d.getValue(thisRef, prop)` and a
 * `var` write to `d.setValue(…)`, requiring an applicable `operator fun` on the delegate's type. For
 * Compose's `MutableState` these are EXTENSIONS in `androidx.compose.runtime`, so
 * `val text by remember { mutableStateOf(0) }` does NOT compile without `import
 * androidx.compose.runtime.getValue` — exactly the reported gap. A plain MEMBER operator, or an in-scope
 * (imported / same-package / default-imported, e.g. `kotlin.Lazy.getValue`) extension operator on the
 * delegate type or a supertype, satisfies it. Empty when the delegate type is unknown OR the operator
 * isn't modeled at all on the classpath (conservative — never invents a rejection).
 */
fun KotlinResolver.missingDelegateOperators(property: KtProperty): List<String> {
    val delegate = property.delegateExpression ?: return emptyList()
    val dt = inferType(delegate) ?: return emptyList()
    if (dt.isTypeParameter) return emptyList()
    // When the delegate's type can't be inferred (`remember { mutableStateOf() }`), the root error is the
    // inference failure, not the operator — its type (an erased `MutableState<Any?>`) is fictitious, so a
    // getValue check against it is misleading. Defer to the `cannotInferType` diagnostic.
    if (delegateContainsUninferableCall(delegate)) return emptyList()
    val needed = if (property.isVar) listOf("getValue", "setValue") else listOf("getValue")
    return needed.filter { op -> !delegateOperatorInScope(dt, op) }
}

/**
 * Fully-qualified names to import to bring a `by`-delegate's missing `getValue`/`setValue` operator into
 * scope — the quick-fix for `val text by remember { mutableStateOf(0) }` →
 * `import androidx.compose.runtime.getValue`. Only out-of-scope EXTENSION operators that actually apply to
 * the delegate's type are offered (so an unrelated `getValue` isn't suggested); empty when the operator is
 * already in scope, unmodeled, or the delegate type is unknown/uninferable.
 */
fun KotlinResolver.delegateOperatorImportCandidates(property: KtProperty): List<String> {
    val delegate = property.delegateExpression ?: return emptyList()
    val dt = inferType(delegate)?.takeIf { !it.isTypeParameter } ?: return emptyList()
    if (delegateContainsUninferableCall(delegate)) return emptyList()
    val ops = if (property.isVar) listOf("getValue", "setValue") else listOf("getValue")
    val out = LinkedHashSet<String>()
    for (op in ops) {
        val candidates = service.membersNamed(dt.qualifiedName, dt.typeArguments, op)
            .filter { it.kind == SymbolKind.METHOD }
        if (candidates.any { !it.isExtension || extensionInScope(it) }) continue // already satisfied
        candidates.filter { it.isExtension && !extensionInScope(it) }.forEach { ext ->
            val pkg = ext.packageName ?: ext.declaringClassFqn?.substringBeforeLast('.', "")?.ifEmpty { null }
            if (pkg != null) out += "$pkg.$op"
        }
    }
    return out.toList()
}

/** Whether the delegate expression contains a call whose type arguments can't be inferred — so the
 *  delegate-operator check should stand down in favor of the type-inference diagnostic. */
internal fun KotlinResolver.delegateContainsUninferableCall(delegate: KtExpression): Boolean {
    var found = false
    fun rec(e: PsiElement) {
        if (found) return
        if (e is KtCallExpression && uninferableTypeParameters(e).isNotEmpty()) { found = true; return }
        var c = e.firstChild
        while (c != null && !found) { rec(c); c = c.nextSibling }
    }
    rec(delegate)
    return found
}

internal fun KotlinResolver.localVar(p: KtProperty) = KotlinSymbol(
    name = p.name ?: "_",
    kind = SymbolKind.LOCAL_VARIABLE,
    type = service.typeFromText(p.typeReference?.text, fileContext)
        ?: inferType(p.initializer)
        ?: p.delegateExpression?.let(::delegatedValueType),
    origin = SOURCE,
    declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
)

/** A `by`-delegated property's value type: the type of the delegate's `value` member (the State/Lazy
 *  `.value` convention — `by remember { mutableStateOf("") }` types as `String`). Null for a delegate that
 *  exposes no `value`, so we never fabricate a type for an unmodeled getValue convention. */
internal fun KotlinResolver.delegatedValueType(delegate: KtExpression): KotlinType? =
    inferType(delegate)?.let { memberNamed(it, "value")?.type as? KotlinType }

/**
 * Names bound by a destructuring declaration (`val (a, b) = …`, `for ((k, v) in …)`, `{ (k, v) -> }`),
 * each typed by the corresponding `componentN()` of [sourceType] (the destructured value's type): a data
 * class's Nth property, a `Pair`/`Triple`, a `Map.Entry`'s key/value, etc. An entry with an explicit type
 * (`(a: Int, b) = …`) keeps it; an entry whose component can't be typed stays untyped (never invents one).
 */
internal fun KotlinResolver.destructuringLocals(d: KtDestructuringDeclaration, sourceType: KotlinType?): List<KotlinSymbol> =
    d.entries.mapIndexedNotNull { i, e ->
        e.name?.let { name ->
            val t = service.typeFromText(e.typeReference?.text, fileContext) ?: componentType(sourceType, i)
            KotlinSymbol(
                name, SymbolKind.LOCAL_VARIABLE, type = t, origin = SOURCE,
                declarationNode = runCatching { parsed.adapt(e) }.getOrNull(),
            )
        }
    }

/**
 * The type of the value a destructuring declaration destructures: its initializer (`val (a, b) = e`), the
 * iteration element (`for ((k, v) in xs)`), or the lambda parameter it binds (`{ (k, v) -> }`). Null when
 * it can't be inferred. Powers the destructuring diagnostics (the resolver itself uses the more specific
 * source already known at each call site in [localsAt]).
 */
fun KotlinResolver.destructuringSourceType(d: KtDestructuringDeclaration): KotlinType? {
    d.initializer?.let { return inferType(it) }
    val param = d.parent as? KtParameter ?: return null
    (param.parent as? KtForExpression)?.let { f -> if (f.loopParameter === param) return iterationElementType(inferType(f.loopRange)) }
    val literal = (param.parent as? KtParameterList)?.parent as? KtFunctionLiteral
    val lambda = literal?.parent as? KtLambdaExpression ?: return null
    val idx = literal.valueParameters.indexOf(param)
    return expectedLambdaShape(lambda)?.parameterTypes?.getOrNull(idx) as? KotlinType
}

/** The destructuring component type for entry [index] (0-based) of [sourceType] — the `componentN()` return
 *  type, or null when that operator isn't modeled. Public counterpart of [componentType] for diagnostics. */
fun KotlinResolver.componentTypeFor(sourceType: KotlinType, index: Int): KotlinType? = componentType(sourceType, index)

/** The type of the [index]-th (0-based) destructuring component of [sourceType]: the return type of its
 *  `componentN()` operator (member or extension, receiver type-args already bound). Null when the component
 *  operator isn't modeled for the type — so an unknown destructuring degrades to untyped, never wrong. */
internal fun KotlinResolver.componentType(sourceType: KotlinType?, index: Int): KotlinType? {
    val t = sourceType?.takeIf { !it.isTypeParameter } ?: return null
    return service.membersNamed(t.qualifiedName, t.typeArguments, "component${index + 1}")
        .firstOrNull { it.kind == SymbolKind.METHOD }?.type as? KotlinType
}

/** The element type produced by iterating [iterableType] (`for (x in xs)` → x's type): the return type of
 *  `iterator().next()` (member or extension — a `Map`'s `iterator()` is a stdlib extension yielding
 *  `Iterator<Map.Entry<K, V>>`). Null when the iterator convention isn't modeled. */
internal fun KotlinResolver.iterationElementType(iterableType: KotlinType?): KotlinType? {
    val t = iterableType?.takeIf { !it.isTypeParameter } ?: return null
    val iterator = service.membersNamed(t.qualifiedName, t.typeArguments, "iterator")
        .firstOrNull { it.kind == SymbolKind.METHOD }?.type as? KotlinType ?: return null
    return service.membersNamed(iterator.qualifiedName, iterator.typeArguments, "next")
        .firstOrNull { it.kind == SymbolKind.METHOD }?.type as? KotlinType
}

/** A `for` loop variable: its declared type if written (`for (x: T in …)`), else the iteration [element] type. */
internal fun KotlinResolver.loopParam(p: KtParameter, element: KotlinType?) = KotlinSymbol(
    name = p.name ?: "_",
    kind = SymbolKind.PARAMETER,
    type = service.typeFromText(p.typeReference?.text, fileContext) ?: element,
    origin = SOURCE,
    declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
)

internal fun KotlinResolver.param(p: KtParameter) = KotlinSymbol(
    name = p.name ?: "_",
    kind = SymbolKind.PARAMETER,
    type = paramType(p),
    origin = SOURCE,
    declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
)

/** A value parameter's type as SEEN inside the body. A `vararg x: E` is an array there, not the element `E`
 *  (`vararg x: Int` -> `IntArray`; a non-primitive -> `Array<E>`), so `fun f(vararg x: Int) { x.sum() }`
 *  resolves `sum` on `IntArray`. See [varargArrayText]. Non-vararg params type verbatim. */
internal fun KotlinResolver.paramType(p: KtParameter): KotlinType? {
    val text = p.typeReference?.text?.trim() ?: return null
    return service.typeFromText(if (p.isVarArg) varargArrayText(text) else text, fileContext)
}
