package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.symbols.FileContext
import dev.ide.lang.kotlin.symbols.ImportInfo
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.TypeRendering
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.ValueArgument

/**
 * Resolution and the inference subset, computed over the LIVE [KtFile] (the buffer being edited).
 * No `BindingContext`: scopes are assembled from PSI parents, names resolved against the scope chain, and
 * a small declared-type-driven typer covers literals, locals-from-initializers, member/call return types,
 * and constructor calls, enough for multi-level member completion (`a.b().c`). Anything it can't type
 * degrades to null, yielding scope-only completion, never an error.
 */
class KotlinResolver(
    private val ktFile: KtFile,
    private val parsed: KotlinParsedFile,
    private val service: KotlinSymbolService,
) {
    val fileContext: FileContext = run {
        val imports = ktFile.importDirectives.mapNotNull { imp ->
            imp.importedFqName?.asString()?.let { ImportInfo(it, imp.aliasName, imp.isAllUnder) }
        }
        FileContext(ktFile.name, ktFile.packageFqName.asString(), imports)
    }

    // --- inference ---

    fun inferType(expr: KtExpression?): KotlinType? = when (expr) {
        null -> null
        is KtParenthesizedExpression -> inferType(expr.expression)
        is KtConstantExpression -> constType(expr)
        is KtStringTemplateExpression -> service.typeByFqn("kotlin.String")
        is KtNameReferenceExpression -> typeOfName(expr.getReferencedName(), expr.textRange.startOffset)
        is KtCallExpression -> typeOfCall(expr, null)
        is KtQualifiedExpression -> typeOfQualified(expr)
        is KtThisExpression -> thisType(expr)
        is KtSuperExpression -> superType(expr)
        else -> null
    }

    /** `this` (optionally `this@Label`) → the matching implicit receiver in scope (innermost when unlabeled). */
    private fun thisType(expr: KtThisExpression): KotlinType? {
        val receivers = implicitReceiversAt(expr.textRange.startOffset)
        val label = expr.getLabelName()
        return if (label == null) receivers.firstOrNull()
        else receivers.firstOrNull { it.qualifiedName.substringAfterLast('.') == label }
    }

    /** `super` (optionally `super<Base>`) → the enclosing class's supertype, so `super.member` lists inherited
     *  members. With no explicit supertype, `kotlin.Any` (whose members `toString`/`hashCode`/… are inherited). */
    private fun superType(expr: KtSuperExpression): KotlinType? {
        expr.superTypeQualifier?.text?.let { service.typeFromText(it, fileContext)?.let { t -> return t } }
        val cls = enclosingClassOrObject(expr.textRange.startOffset) ?: return null
        val superText = cls.superTypeListEntries.firstNotNullOfOrNull { it.typeReference?.text }
        return superText?.let { service.typeFromText(it, fileContext) } ?: service.typeByFqn("kotlin.Any")
    }

    private fun constType(e: KtConstantExpression): KotlinType? {
        val t = e.text.trim()
        return service.typeByFqn(
            when {
                t == "true" || t == "false" -> "kotlin.Boolean"
                t == "null" -> return null
                t.startsWith("'") -> "kotlin.Char"
                t.endsWith("L") || t.endsWith("l") -> "kotlin.Long"
                t.endsWith("f") || t.endsWith("F") -> "kotlin.Float"
                '.' in t || 'e' in t || 'E' in t -> "kotlin.Double"
                else -> "kotlin.Int"
            },
        )
    }

    private fun typeOfQualified(q: KtQualifiedExpression): KotlinType? {
        // A fully-qualified type used directly (`android.R`, `java.util.Locale`) — resolve the whole text.
        if (service.isKnownType(q.text)) return service.typeByFqn(q.text)
        val receiverType = inferType(q.receiverExpression)
            ?: typeOfTypeName(q.receiverExpression) // receiver may be a class name (companion/static access)
            ?: return null
        return when (val sel = q.selectorExpression) {
            is KtCallExpression -> typeOfCall(sel, receiverType)
            is KtNameReferenceExpression -> memberNamed(receiverType, sel.getReferencedName())?.type as? KotlinType
            else -> null
        }
    }

    private fun typeOfCall(call: KtCallExpression, receiverType: KotlinType?): KotlinType? {
        val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        // No receiver + Capitalized → a constructor call (the type itself).
        if (receiverType == null && name.firstOrNull()?.isUpperCase() == true) {
            service.resolveTypeName(name, fileContext)?.let { return service.typeByFqn(it) }
        }
        val sym = resolveCalleeFunction(call) ?: return null
        // Bind the function's OWN type parameters from the arguments (listOf("") -> List<String>; a lambda's
        // result binds R in `(…) -> R`), falling back to each parameter's erased bound when an argument can't
        // pin it (a raw `findViewById(): T` → `View`), then substitute into the return type.
        val bindings = methodTypeParamErasure(sym) + inferTypeArguments(sym, call)
        return (sym.type as? KotlinType)?.let { service.substitute(it, bindings) as? KotlinType }
    }

    /** Each of a function's own type parameters mapped to its erased upper bound, so any that argument
     *  inference leaves unbound still resolves to a concrete type rather than a member-less `T`. */
    private fun methodTypeParamErasure(sym: KotlinSymbol): Map<String, TypeRef> {
        if (sym.typeParameters.isEmpty() || sym.typeParameterBounds.isEmpty()) return emptyMap()
        val out = HashMap<String, TypeRef>(sym.typeParameters.size)
        sym.typeParameters.forEachIndexed { i, name -> sym.typeParameterBounds.getOrNull(i)?.let { out[name] = it } }
        return out
    }

    /** Resolve a call's callee to the best-fitting function overload (member/extension via its receiver, or
     *  top-level), with receiver type params already bound by [KotlinSymbolService.membersOf]. */
    private fun resolveCalleeFunction(call: KtCallExpression): KotlinSymbol? {
        val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        val argCount = call.valueArguments.size // already includes the trailing lambda
        val q = call.parent as? KtQualifiedExpression
        val receiverType = if (q != null && q.selectorExpression === call) inferType(q.receiverExpression) else null
        val candidates = if (receiverType != null) {
            service.membersOf(receiverType.qualifiedName, receiverType.typeArguments, null)
                .filterIsInstance<KotlinSymbol>().filter { it.name == name && it.kind == SymbolKind.METHOD }
        } else {
            service.topLevelByName(name).filter { it.kind == SymbolKind.METHOD }
        }
        return candidates.firstOrNull { it.paramTypes.size == argCount }
            ?: candidates.firstOrNull { it.paramTypes.isNotEmpty() && it.paramTypes.size <= argCount }
            ?: candidates.firstOrNull()
    }

    private fun inferTypeArguments(sym: KotlinSymbol, call: KtCallExpression): Map<String, TypeRef> {
        if (sym.typeParameters.isEmpty()) return emptyMap()
        val bindings = HashMap<String, TypeRef>()
        // valueArguments already includes a trailing lambda (its getArgumentExpression() is the lambda).
        call.valueArguments.forEachIndexed { i, arg ->
            val expr = arg.getArgumentExpression() ?: return@forEachIndexed
            val pt = (sym.paramTypes.getOrNull(i) ?: sym.paramTypes.lastOrNull()) as? KotlinType ?: return@forEachIndexed
            if (expr is KtLambdaExpression) bindLambdaReturn(pt, expr, bindings)
            else inferType(expr)?.let { unify(pt, it, bindings) }
        }
        return bindings
    }

    /** A lambda fills a functional parameter (a Kotlin `(…) -> R` or a Java SAM); bind its result type
     *  parameter from the lambda's inferred result (`map { … }`'s `R`, `let`'s `R`). */
    private fun bindLambdaReturn(pt: KotlinType, lambda: KtLambdaExpression, bindings: MutableMap<String, TypeRef>) {
        val r = service.functionalShape(pt)?.returnType as? KotlinType ?: return
        if (r.isTypeParameter) inferLambdaResult(lambda)?.let { bindings.putIfAbsent(r.qualifiedName, it) }
    }

    private fun inferLambdaResult(lambda: KtLambdaExpression): TypeRef? =
        inferType(lambda.bodyExpression?.statements?.lastOrNull() as? KtExpression)

    /** The `(P…) -> R` type a lambda is expected to be (from the parameter it fills), receiver-bound — used
     *  to type the lambda's `it`/named parameters. */
    private fun expectedFunctionTypeFor(lambda: KtLambdaExpression): KotlinType? {
        val (call, paramIndex) = enclosingCallAndParamIndex(lambda) ?: return null
        val sym = resolveCalleeFunction(call) ?: return null
        val raw = sym.paramTypes.getOrNull(paramIndex) as? KotlinType ?: return null
        if (!TypeRendering.isFunctionType(raw.qualifiedName)) return null
        // Bind the function's type params from the NON-lambda value args (with(x){…} binds T from x), so the
        // block's receiver/params are concrete. (Skip lambdas to avoid recursing back into this lambda.)
        return service.substitute(raw, bindingsFromValueArgs(sym, call)) as? KotlinType
    }

    /** The value-parameter types a lambda receives (in order), from the functional parameter it fills — for
     *  inlay hints on `{ x -> … }` / the implicit `it`. Handles both a Kotlin function-type parameter and a
     *  Java SAM (`stream().map { it }`). Empty when the expected type is unknown. */
    fun lambdaParameterTypes(lambda: KtLambdaExpression): List<TypeRef?> =
        expectedLambdaShape(lambda)?.parameterTypes ?: emptyList()

    /** The functional shape (value-param types + result) a lambda is expected to satisfy — its parameter slot
     *  in the enclosing call, resolved to a Kotlin function type or a Java SAM, with the call's non-lambda
     *  arguments already used to bind the function's type parameters. */
    private fun expectedLambdaShape(lambda: KtLambdaExpression): KotlinSymbolService.FunctionalShape? {
        val (call, paramIndex) = enclosingCallAndParamIndex(lambda) ?: return null
        val sym = resolveCalleeFunction(call) ?: return null
        val raw = sym.paramTypes.getOrNull(paramIndex) as? KotlinType ?: return null
        val bound = service.substitute(raw, bindingsFromValueArgs(sym, call)) as? KotlinType ?: return null
        return service.functionalShape(bound)
    }

    private fun bindingsFromValueArgs(sym: KotlinSymbol, call: KtCallExpression): Map<String, TypeRef> {
        if (sym.typeParameters.isEmpty()) return emptyMap()
        val bindings = HashMap<String, TypeRef>()
        call.valueArguments.forEachIndexed { i, arg ->
            val expr = arg.getArgumentExpression() ?: return@forEachIndexed
            if (expr is KtLambdaExpression) return@forEachIndexed
            val pt = (sym.paramTypes.getOrNull(i) ?: sym.paramTypes.lastOrNull()) as? KotlinType ?: return@forEachIndexed
            inferType(expr)?.let { unify(pt, it, bindings) }
        }
        return bindings
    }

    /**
     * The chain of implicit `this` receivers in scope at [offset] (innermost first): the receiver of each
     * enclosing receiver-lambda (`apply`/`with`/`run`/DSL builders — `T.() -> R`), the enclosing extension
     * function's receiver, and the enclosing class. Their members are visible without an explicit receiver.
     */
    fun implicitReceiversAt(offset: Int): List<KotlinType> {
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

    /** The call a lambda argument belongs to + its parameter index. `KtCallExpression.valueArguments`
     *  ALREADY includes the trailing lambda, so the index is its position in that list. */
    private fun enclosingCallAndParamIndex(lambda: KtLambdaExpression): Pair<KtCallExpression, Int>? {
        val valueArg = lambda.parent as? ValueArgument ?: return null
        val call = when (val p = lambda.parent) {
            is KtLambdaArgument -> p.parent as? KtCallExpression
            is KtValueArgument -> (p.parent as? KtValueArgumentList)?.parent as? KtCallExpression
            else -> null
        } ?: return null
        return call to call.valueArguments.indexOf(valueArg).coerceAtLeast(0)
    }

    private fun unify(param: KotlinType, arg: KotlinType, bindings: MutableMap<String, TypeRef>) {
        if (param.isTypeParameter) { bindings.putIfAbsent(param.qualifiedName, arg); return }
        // A vararg parameter (`of(E...)`) is an `Array<E>`; a single scalar argument unifies with the element.
        if (param.qualifiedName == "kotlin.Array" && param.typeArguments.size == 1 &&
            arg.qualifiedName != "kotlin.Array"
        ) {
            (param.typeArguments.first() as? KotlinType)?.let { unify(it, arg, bindings) }
            return
        }
        param.typeArguments.zip(arg.typeArguments).forEach { (p, a) ->
            if (p is KotlinType && a is KotlinType) unify(p, a, bindings)
        }
    }

    private fun typeOfName(name: String, offset: Int): KotlinType? {
        localsAt(offset).firstOrNull { it.name == name }?.let { return it.type as? KotlinType }
        // Members of any implicit `this` (apply/with/run block, extension fn, enclosing class).
        for (recv in implicitReceiversAt(offset)) memberNamed(recv, name)?.let { return it.type as? KotlinType }
        service.topLevelByName(name).firstOrNull { it.kind == SymbolKind.FIELD }?.let { return it.type as? KotlinType }
        // A bare type name used as an expression (e.g. `Foo` in `Foo.CONST`).
        if (name.firstOrNull()?.isUpperCase() == true) {
            service.resolveTypeName(name, fileContext)?.let { return service.typeByFqn(it) }
        }
        return null
    }

    private fun typeOfTypeName(expr: KtExpression): KotlinType? {
        val name = (expr as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        if (name.firstOrNull()?.isUpperCase() != true) return null
        return service.resolveTypeName(name, fileContext)?.let { service.typeByFqn(it) }
    }

    private fun memberNamed(type: KotlinType, name: String): KotlinSymbol? =
        service.membersOf(type.qualifiedName, type.typeArguments, null).filterIsInstance<KotlinSymbol>().firstOrNull { it.name == name }

    // --- scopes ---

    /** Locals + parameters in scope at [offset] (declared before it). */
    fun localsAt(offset: Int): List<KotlinSymbol> {
        val out = ArrayList<KotlinSymbol>()
        var node: PsiElement? = elementAt(offset)
        while (node != null) {
            when (node) {
                is KtBlockExpression -> node.statements.filter { it.textRange.endOffset <= offset }.forEach { st ->
                    when (st) {
                        is KtProperty -> out += localVar(st)
                        is KtDestructuringDeclaration -> out += destructuringLocals(st)
                        is KtNamedFunction -> out += KotlinSymbol(
                            st.name ?: "_", SymbolKind.METHOD,
                            type = service.typeFromText(st.typeReference?.text, fileContext), origin = SOURCE,
                            declarationNode = runCatching { parsed.adapt(st) }.getOrNull(),
                        )
                        else -> {}
                    }
                }
                is KtFunction -> node.valueParameters.forEach { out += param(it) }
                is KtForExpression -> node.loopParameter?.let { lp ->
                    lp.destructuringDeclaration?.let { out += destructuringLocals(it) } ?: run { out += param(lp) }
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
                            out += KotlinSymbol(
                                p.name ?: "_", SymbolKind.PARAMETER, type = t, origin = SOURCE,
                                declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
                            )
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
    fun isTypeReceiver(expr: KtExpression): Boolean = typeDenotationFqn(expr) != null

    /**
     * If [expr] denotes a TYPE / static reference (`String`, `java.util.Locale`, the Android `R.layout`, an
     * `Outer.Inner`) rather than a value/instance, the resolved FQN; else null. A simple capitalized name
     * resolves through imports/classpath (unless a local of that name shadows it); a qualified expression
     * resolves either as a fully-qualified type by its own text or as a nested type through a resolved outer
     * — so `R.layout.<caret>` (where `layout` is lower-case) is still recognized as static navigation.
     */
    fun typeDenotationFqn(expr: KtExpression): String? = when (expr) {
        is KtParenthesizedExpression -> expr.expression?.let { typeDenotationFqn(it) }
        is KtNameReferenceExpression -> {
            val name = expr.getReferencedName()
            when {
                name.firstOrNull()?.isUpperCase() != true -> null // type names are capitalized
                localsAt(expr.textRange.startOffset).any { it.name == name } -> null // a value shadows the type
                else -> service.resolveTypeName(name, fileContext)?.takeIf { service.isKnownType(it) }
            }
        }
        is KtQualifiedExpression -> {
            val sel = (expr.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
            when {
                sel == null -> null
                // (a) fully-qualified type by its own text: `java.util.Locale`
                sel.firstOrNull()?.isUpperCase() == true && service.isKnownType(expr.text) -> expr.text
                // (b) nested type through a resolved outer: `R.layout`, `Outer.Inner`
                else -> typeDenotationFqn(expr.receiverExpression)?.let { "$it.$sel".takeIf { f -> service.isKnownType(f) } }
            }
        }
        else -> null // calls, literals, `this`, `super` → instances
    }

    private fun enclosingClassOrObject(offset: Int): KtClassOrObject? {
        var node: PsiElement? = elementAt(offset)
        while (node != null) { if (node is KtClassOrObject) return node; node = node.parent }
        return null
    }

    /** Whether a bare [name] resolves to anything in scope (local, implicit-receiver member, top-level,
     *  import, or a known type). Used by the unresolved-reference diagnostic. */
    fun bareNameResolves(name: String, offset: Int): Boolean {
        if (localsAt(offset).any { it.name == name }) return true
        // Top-level declarations in THIS live file (the module index is disk-based and may lag the buffer).
        if (ktFile.declarations.any { (it is KtNamedFunction && it.name == name) || (it is KtProperty && it.name == name) }) return true
        // Members of an ENCLOSING class of the live buffer — the symbol service indexes disk, not the file
        // being edited, so a same-file member (`field`, `helper()`) won't appear in membersOf() below.
        if (enclosingClassMembersContain(offset, name)) return true
        if (implicitReceiversAt(offset).any { recv ->
                service.membersOf(recv.qualifiedName, recv.typeArguments, null).any { it.name == name }
            }
        ) return true
        if (service.topLevelByName(name).isNotEmpty()) return true
        if (fileContext.imports.any { !it.isStar && it.simpleName == name }) return true
        return service.resolveTypeName(name, fileContext)?.let { service.isKnownType(it) } == true
    }

    /** Whether [name] is a member (function/property or a constructor `val/var` param) of any class enclosing
     *  [offset] in the live buffer. Resolves same-file members the disk-based symbol service can't see yet. */
    private fun enclosingClassMembersContain(offset: Int, name: String): Boolean {
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
    fun companionInScope(offset: Int): Boolean {
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
    fun constructorTypeFqn(name: String, offset: Int): String? {
        if (name.substringAfterLast('.').firstOrNull()?.isUpperCase() != true) return null
        if ('.' !in name && localsAt(offset).any { it.name == name }) return null
        return service.resolveTypeName(name, fileContext)?.takeIf { service.isKnownType(it) }
    }

    fun enclosingClassFqn(offset: Int): String? {
        var node: PsiElement? = elementAt(offset)
        while (node != null) {
            if (node is KtClassOrObject) return node.fqName?.asString()
            node = node.parent
        }
        return null
    }

    /** The candidate set for a bare name-reference completion: locals, enclosing members, top-level. */
    fun scopeSymbolsAt(offset: Int): List<KotlinSymbol> {
        val out = ArrayList<KotlinSymbol>()
        out += localsAt(offset)
        // Members of every implicit `this` (apply/with/run block, extension fn, enclosing class).
        implicitReceiversAt(offset).forEach { recv ->
            out += service.membersOf(recv.qualifiedName, recv.typeArguments, null).filterIsInstance<KotlinSymbol>()
        }
        out += service.topLevelCallables()
        return out
    }

    private fun localVar(p: KtProperty) = KotlinSymbol(
        name = p.name ?: "_",
        kind = SymbolKind.LOCAL_VARIABLE,
        type = service.typeFromText(p.typeReference?.text, fileContext) ?: inferType(p.initializer),
        origin = SOURCE,
        declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
    )

    /** Names bound by a destructuring declaration (`val (a, b) = …`, `for ((k, v) in …)`); types deferred. */
    private fun destructuringLocals(d: KtDestructuringDeclaration): List<KotlinSymbol> =
        d.entries.mapNotNull { e ->
            e.name?.let {
                KotlinSymbol(it, SymbolKind.LOCAL_VARIABLE, origin = SOURCE, declarationNode = runCatching { parsed.adapt(e) }.getOrNull())
            }
        }

    private fun param(p: KtParameter) = KotlinSymbol(
        name = p.name ?: "_",
        kind = SymbolKind.PARAMETER,
        type = service.typeFromText(p.typeReference?.text, fileContext),
        origin = SOURCE,
        declarationNode = runCatching { parsed.adapt(p) }.getOrNull(),
    )

    private fun elementAt(offset: Int): PsiElement? {
        val len = ktFile.textLength
        if (len == 0) return null
        return ktFile.findElementAt(if (offset >= len) len - 1 else offset.coerceAtLeast(0))
    }

    companion object {
        private val SOURCE = SymbolOrigin(fromSource = true, file = null)
    }
}
