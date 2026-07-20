package dev.ide.lang.kotlin.interp

import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.resolve.*
import dev.ide.lang.kotlin.symbols.DefaultImports
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtWhenConditionInRange
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression

/**
 * Lowers a Kotlin function body to a [ResolvedTree] (see `docs/compose-interpreter.md`) — the **sound**
 * resolution the interpreter needs, kept totally separate from the editor's best-effort `KotlinResolver`
 * (which this reuses only for type inference and candidate enumeration).
 *
 * Skeleton scope (v0): constants, locals/params, `if`, `return`, local `val`/`var`, blocks, member &
 * top-level calls with **exact** callee selection (single candidate, or a unique arg-type match — an
 * ambiguous overload is rejected, never guessed), property reads, the `+ - * / %` operator conventions
 * desugared to calls, and `=` assignment. Everything else lowers to [RNode.Unsupported] with a reason, so
 * the contract stays total. Each gap is also recorded as a [LoweringDiagnostic].
 *
 * Known stubs (intentional, for follow-up): exact JVM owner/descriptor for binary callees
 * ([ResolvedCallable.Library.descriptorPrecise] = false), `@Composable` detection, smart-casts, string
 * templates, `when`, loops, lambdas, and full type-directed overload resolution.
 */
class KotlinTreeResolver(
    private val ktFile: KtFile,
    parsed: KotlinParsedFile,
    private val service: KotlinSymbolService,
    /** Memo caches shared with the rest of the keystroke (the analyzer's diagnostics resolver), so the lowerer
     *  reuses inference/overload work already done for this snapshot instead of recomputing it cold. Null → a
     *  private cache (the standalone case, e.g. tests). See [dev.ide.lang.kotlin.resolve.KotlinResolverCaches]. */
    caches: dev.ide.lang.kotlin.resolve.KotlinResolverCaches? = null,
) {
    private val resolver =
        if (caches != null) KotlinResolver(ktFile, parsed, service, caches) else KotlinResolver(ktFile, parsed, service)

    private val scopes = ArrayDeque<MutableMap<String, Binding>>()
    private var slotCounter = 0
    private val diagnostics = ArrayList<LoweringDiagnostic>()

    /** The implicit `this` receivers of the enclosing receiver-lambdas (`RowScope.() -> Unit` content slots),
     *  each bound to the slot the scope instance arrives in at runtime — so a member extension of the scope
     *  (`RowScope.weight`) can dispatch onto it, and a bare extension call can use it as its extension receiver. */
    private data class ReceiverScope(val slot: SlotId, val type: KotlinType)
    private val receiverScopes = ArrayDeque<ReceiverScope>()

    /** A member function's signature (arity + parameter names), used to synthesize a [ResolvedCallable.Source]
     *  for a bare/`this`-qualified call to a member of the enclosing class. */
    private data class MethodSig(val arity: Int, val paramNames: List<String>)

    /** The class whose body is being lowered — so a bare member access (`id`, `compute()`) and an explicit
     *  `this`/`this.member` resolve against it. [thisSlot] is the slot the receiver object is bound to at
     *  runtime (always slot 0; the receiver is allocated before any param). */
    private data class ClassContext(
        val thisSlot: SlotId,
        val fqn: String,
        val propertyNames: Set<String>,
        val methods: Map<String, List<MethodSig>>,
    )
    private val classStack = ArrayDeque<ClassContext>()

    private fun thisRef(ctx: ClassContext, e: PsiElement? = null): RNode =
        RNode.Name(Binding.Local(ctx.thisSlot, "this", mutable = false), e?.let { span(it) } ?: SourceSpan(0, 0))

    private fun isThisReceiver(node: RNode?, ctx: ClassContext?): Boolean =
        ctx != null && node is RNode.Name && (node.binding as? Binding.Local)?.slot == ctx.thisSlot

    /** A lightweight index of the file's source types, so a member call on a source-typed receiver the editor
     *  resolver can't resolve — a synthesized data-class member (`toString`/`copy`/`componentN`), a companion
     *  member reached through the class name, or an enum-entry member — still lowers to a [DispatchKind.MEMBER]
     *  [ResolvedCallable.Source] for the interpreter. */
    private data class FileClassInfo(
        val fqn: String,
        val simpleName: String,
        val flavor: ClassFlavor,
        val isData: Boolean,
        val propertyNames: Set<String>,
        val methodArities: Set<String>,
        val companionMethodArities: Set<String>,
        val enumEntryNames: Set<String>,
        /** Direct supertype simple names (keys into the file-class index for inherited-member walks). */
        val supertypeSimpleNames: List<String>,
    ) {
        /** Whether `name(arity args)` is a member declared directly on this type (or a companion member via the
         *  class name, an enum static, or a data-class generated member) — NOT counting inherited ones, which
         *  the resolver checks via [acceptsSourceMember]. */
        fun declaresOrSynthesizes(name: String, arity: Int): Boolean {
            if ("$name/$arity" in methodArities || "$name/$arity" in companionMethodArities) return true
            if (flavor == ClassFlavor.ENUM && ((name == "values" && arity == 0) || (name == "valueOf" && arity == 1))) return true
            if (!isData) return false
            return (name == "toString" && arity == 0) || (name == "hashCode" && arity == 0) ||
                (name == "equals" && arity == 1) || name == "copy" ||
                (arity == 0 && Regex("component\\d+").matches(name))
        }
    }

    private val fileClasses: Map<String, FileClassInfo> by lazy { buildFileClassIndex() }

    private fun buildFileClassIndex(): Map<String, FileClassInfo> {
        val out = LinkedHashMap<String, FileClassInfo>()
        fun visit(decl: KtClassOrObject) {
            if (decl is KtEnumEntry) return
            val simple = decl.name
            val ktClass = decl as? KtClass
            val flavor = when {
                decl is KtObjectDeclaration && decl.isCompanion() -> ClassFlavor.COMPANION
                decl is KtObjectDeclaration -> ClassFlavor.OBJECT
                ktClass?.isEnum() == true -> ClassFlavor.ENUM
                ktClass?.isInterface() == true -> ClassFlavor.INTERFACE
                else -> ClassFlavor.CLASS
            }
            if (simple != null) {
                fun arities(c: KtClassOrObject) = c.declarations.filterIsInstance<KtNamedFunction>()
                    .mapNotNull { f -> f.name?.let { "$it/${f.valueParameters.size}" } }.toSet()
                val props = buildSet {
                    decl.primaryConstructorParameters.filter { it.hasValOrVar() }.forEach { it.name?.let(::add) }
                    decl.declarations.filterIsInstance<KtProperty>().forEach { it.name?.let(::add) }
                }
                val companion = decl.declarations.filterIsInstance<KtObjectDeclaration>().firstOrNull { it.isCompanion() }
                val entries = if (flavor == ClassFlavor.ENUM)
                    decl.declarations.filterIsInstance<KtEnumEntry>().mapNotNull { it.name }.toSet() else emptySet()
                val supers = decl.superTypeListEntries.mapNotNull {
                    it.typeReference?.text?.substringBefore('<')?.trim()?.substringAfterLast('.')?.takeIf { s -> s.isNotEmpty() }
                }
                out[simple] = FileClassInfo(
                    qualifiedNameOf(simple, decl), simple, flavor, decl.isData(),
                    props, arities(decl), companion?.let { arities(it) } ?: emptySet(), entries, supers,
                )
            }
            decl.declarations.filterIsInstance<KtClassOrObject>().forEach { visit(it) }
        }
        ktFile.declarations.filterIsInstance<KtClassOrObject>().forEach { visit(it) }
        return out
    }

    /** Whether `name(arity)` is callable on an instance of [info] — declared/synthesized on it OR inherited
     *  from a source supertype (so a subclass call to an inherited method lowers to a source MEMBER call). */
    private fun acceptsSourceMember(info: FileClassInfo, name: String, arity: Int, seen: MutableSet<String> = HashSet()): Boolean {
        if (!seen.add(info.simpleName)) return false
        if (info.declaresOrSynthesizes(name, arity)) return true
        return info.supertypeSimpleNames.any { sup -> fileClasses[sup]?.let { acceptsSourceMember(it, name, arity, seen) } == true }
    }

    /** The property names and method signatures an instance of [info] exposes including those inherited from
     *  source supertypes — the members a method body can reference by bare name / implicit `this`. */
    private fun inheritedMembers(info: FileClassInfo, seen: MutableSet<String> = HashSet()): Pair<Set<String>, Map<String, List<MethodSig>>> {
        if (!seen.add(info.simpleName)) return emptySet<String>() to emptyMap()
        val props = info.propertyNames.toMutableSet()
        val methods = HashMap<String, MutableList<MethodSig>>()
        info.methodArities.forEach { key ->
            val name = key.substringBeforeLast('/'); val arity = key.substringAfterLast('/').toIntOrNull() ?: 0
            methods.getOrPut(name) { ArrayList() }.add(MethodSig(arity, emptyList()))
        }
        for (sup in info.supertypeSimpleNames) {
            val si = fileClasses[sup] ?: continue
            val (sp, sm) = inheritedMembers(si, seen)
            props += sp
            sm.forEach { (n, sigs) -> methods.getOrPut(n) { ArrayList() }.addAll(sigs) }
        }
        return props to methods
    }

    /** The source type of [recvExpr]: its inferred type, a bare type-name (object/companion holder), or the
     *  enum behind a `Enum.ENTRY` qualifier. Null when the receiver isn't a known source type. */
    private fun sourceClassOfReceiver(recvExpr: KtExpression?): FileClassInfo? {
        if (recvExpr == null) return null
        runCatching { resolver.inferType(recvExpr)?.qualifiedName }.getOrNull()?.let { fqn ->
            (fileClasses.values.firstOrNull { it.fqn == fqn } ?: fileClasses[fqn.substringAfterLast('.')])?.let { return it }
        }
        (recvExpr as? KtNameReferenceExpression)?.getReferencedName()?.let { fileClasses[it] }?.let { return it }
        if (recvExpr is KtDotQualifiedExpression) {
            val base = (recvExpr.receiverExpression as? KtNameReferenceExpression)?.getReferencedName()
            val sel = (recvExpr.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
            base?.let { fileClasses[it] }?.takeIf { it.flavor == ClassFlavor.ENUM && sel in it.enumEntryNames }?.let { return it }
        }
        return null
    }

    /** A read of the innermost in-scope receiver whose type is (a subtype of) [fqn] — the scope instance to
     *  dispatch a member extension on, or to use as a bare extension call's receiver. Null when none matches. */
    private fun findScopeReceiver(fqn: String): RNode? {
        fun scopeNode(rs: ReceiverScope) =
            RNode.Name(Binding.Local(rs.slot, rs.type.qualifiedName.substringAfterLast('.'), mutable = false), SourceSpan(0, 0))
        for (i in receiverScopes.indices.reversed()) {
            val rs = receiverScopes[i]
            val matches = rs.type.qualifiedName == fqn ||
                runCatching { service.supertypesOf(rs.type.qualifiedName).any { (it as? KotlinType)?.qualifiedName == fqn } }.getOrDefault(false)
            if (matches) return scopeNode(rs)
        }
        // Fallback for an UNQUALIFIED declaring name (the resolver records some builtin/mapped-type members like
        // `MutableList.add` by simple name): match a scope by simple name, so `apply { add(x) }` /
        // `buildList { add(x) }` dispatch the member ON the scope instance instead of a receiver-less TOP_LEVEL
        // call (which would try to load a class literally named `MutableList`). Only for a dotless name, so the
        // normal fully-qualified path is unchanged.
        if ('.' !in fqn) {
            for (i in receiverScopes.indices.reversed()) {
                val rs = receiverScopes[i]
                if (rs.type.qualifiedName.substringAfterLast('.') == fqn) return scopeNode(rs)
            }
        }
        return null
    }

    /** Lower the first top-level function in the file (test convenience). */
    fun lowerFirstFunction(): ResolvedFunction? =
        ktFile.declarations.filterIsInstance<KtNamedFunction>().firstOrNull()?.let { lowerFunction(it) }

    fun lowerFunction(fn: KtNamedFunction): ResolvedFunction {
        reset(fn.textRange.startOffset)
        scopes.addLast(HashMap())
        // An EXTENSION function binds its receiver to slot 0 (like a member's `this`) and registers it as an
        // implicit receiver scope, so `this` and bare-member access in the body resolve to it — and the
        // interpreter binds the actual receiver value to that slot on an EXTENSION-dispatch call. Without this a
        // project-source top-level extension had no receiver slot, and the interpreter fell through to the
        // reflective dispatcher (which can't reflect an uncompiled source function → "extension has no owner").
        val recvType = fn.receiverTypeReference?.text?.let { service.typeFromText(it, resolver.fileContext) }
        val receiverSlot = if (fn.receiverTypeReference != null) newSlot() else null // slot 0 when present
        var pushedReceiver = false
        if (receiverSlot != null && recvType != null) {
            receiverScopes.addLast(ReceiverScope(receiverSlot, recvType)); pushedReceiver = true
        }
        val params = loweredValueParams(fn.valueParameters)
        val body = when {
            fn.hasBlockBody() -> fn.bodyBlockExpression?.let { lowerBlock(it) } ?: emptyBlock(fn)
            else -> fn.bodyExpression?.let { lower(it) } ?: unsupported("empty body", fn)
        }
        if (pushedReceiver) receiverScopes.removeLast()
        scopes.removeLast()
        return ResolvedFunction(fn.name ?: "<anonymous>", params, body, diagnostics.toList(), receiverSlot = receiverSlot, returnsUnit = returnsUnit(fn))
    }

    /** Lower a function's value parameters: bind each (so the body and each default may reference the others),
     *  then capture each parameter's default-value expression so a call that omits a defaulted argument
     *  (`Greeting("x")` for `fun Greeting(name: String, modifier: Modifier = Modifier)`) can fill it at call
     *  time. All parameters are bound BEFORE any default lowers so a default may reference a sibling; a default
     *  that doesn't lower cleanly is dropped (its diagnostics rolled back), so an unused, un-interpretable
     *  default never blocks the whole function. */
    private fun loweredValueParams(valueParameters: List<KtParameter>): List<RParam> {
        val bound = valueParameters.map { p ->
            val slot = newSlot()
            val name = p.name ?: "_"
            bind(name, Binding.Param(slot, name))
            Triple(slot, name, p)
        }
        return bound.map { (slot, name, p) ->
            RParam(slot, name, service.typeFromText(p.typeReference?.text, resolver.fileContext),
                default = p.defaultValue?.let { lowerParamDefault(it) })
        }
    }

    /** Lower a parameter default, returning null (and rolling back any diagnostics it produced) when it can't
     *  lower cleanly — an omitted argument then falls back to `null`, exactly as before defaults were modeled,
     *  rather than marking the whole function incomplete over a default the call may never use. */
    private fun lowerParamDefault(expr: KtExpression): RNode? {
        val before = diagnostics.size
        val node = lower(expr)
        if (diagnostics.size > before) {
            while (diagnostics.size > before) diagnostics.removeAt(diagnostics.size - 1)
            return null
        }
        return node
    }

    /** Lower a top-level `val`/`var` (`private val XColor = Color(0xFF…)`) as a synthetic zero-arg getter
     *  keyed `name/0`. A SOURCE top-level property has no compiled `…Kt` facade to reflect, so a read of it
     *  ([nameNode]) lowers to a TOP_LEVEL call of this. Its body is the initializer (or, for a computed
     *  top-level property, the getter body). Non-extension only — an extension property needs its receiver. */
    fun lowerTopLevelProperty(prop: KtProperty): ResolvedFunction {
        reset(prop.textRange.startOffset)
        scopes.addLast(HashMap())
        val getter = prop.getter
        val body = when {
            prop.initializer != null -> lower(prop.initializer!!)
            getter?.hasBlockBody() == true -> getter.bodyBlockExpression?.let { lowerBlock(it) } ?: emptyBlock(prop)
            getter?.bodyExpression != null -> lower(getter.bodyExpression!!)
            else -> unsupported("top-level property without a value", prop)
        }
        scopes.removeLast()
        return ResolvedFunction(prop.name ?: "<anonymous>", emptyList(), body, diagnostics.toList())
    }

    /** Whether [fn] returns `Unit` — a block body with no explicit return type, or one declared `: Unit`. An
     *  expression body without an explicit type is treated as non-Unit (unknown), conservatively keeping it out
     *  of the Compose restartable/skippable path (a value-returning composable must always re-run). */
    private fun returnsUnit(fn: KtNamedFunction): Boolean =
        when (fn.typeReference?.text?.trim()) {
            null -> fn.hasBlockBody()
            "Unit", "kotlin.Unit" -> true
            else -> false
        }

    // --- source class / object / enum lowering ---

    /** Lower every class/object/enum in the file (recursing into nested types), so the interpreter can
     *  materialize project-source instances rather than reflecting bytecode that doesn't exist yet. */
    fun lowerClasses(): List<ResolvedClass> {
        val out = ArrayList<ResolvedClass>()
        fun rec(decls: List<KtDeclaration>) {
            decls.filterIsInstance<KtClassOrObject>().filter { it !is KtEnumEntry }.forEach { c ->
                lowerClass(c)?.let { out.add(it) }
                rec(c.declarations)
            }
        }
        rec(ktFile.declarations)
        return out
    }

    private fun lowerClass(decl: KtClassOrObject): ResolvedClass? {
        val simpleName = decl.name ?: return null
        val ktClass = decl as? KtClass
        val flavor = when {
            decl is KtObjectDeclaration && decl.isCompanion() -> ClassFlavor.COMPANION
            decl is KtObjectDeclaration -> ClassFlavor.OBJECT
            ktClass?.isEnum() == true -> ClassFlavor.ENUM
            ktClass?.isInterface() == true -> ClassFlavor.INTERFACE
            else -> ClassFlavor.CLASS
        }
        val fqn = qualifiedNameOf(simpleName, decl)
        val primaryKtParams = decl.primaryConstructorParameters
        val bodyProps = decl.declarations.filterIsInstance<KtProperty>()
        // Computed body properties (`val isDraw get() = …`): no backing field, lowered as zero-arg getter
        // methods (`name/0`) the interpreter invokes on a read (see Interpreter.readSourceProperty).
        val computedProps = bodyProps.filter {
            it.initializer == null && it.delegateExpression == null &&
                (it.getter?.bodyExpression != null || it.getter?.bodyBlockExpression != null)
        }
        val memberFns = decl.declarations.filterIsInstance<KtNamedFunction>().filter { it.name != null }
        val ownPropertyNames = buildSet {
            primaryKtParams.filter { it.hasValOrVar() }.forEach { it.name?.let(::add) }
            bodyProps.forEach { it.name?.let(::add) }
        }
        val ownMethodSigs = memberFns.groupBy { it.name!! }
            .mapValues { (_, fns) -> fns.map { MethodSig(it.valueParameters.size, it.valueParameters.map { p -> p.name ?: "_" }) } }
        // Merge in members inherited from source supertypes so a method body can reference them by bare name /
        // implicit `this` (the receiver instance dispatches them virtually at run time).
        val inhProps = HashSet<String>()
        val inhMethods = HashMap<String, MutableList<MethodSig>>()
        fileClasses[simpleName]?.supertypeSimpleNames?.forEach { sup ->
            fileClasses[sup]?.let { si ->
                val (p, m) = inheritedMembers(si)
                inhProps += p
                m.forEach { (n, sigs) -> inhMethods.getOrPut(n) { ArrayList() }.addAll(sigs) }
            }
        }
        val propertyNames = ownPropertyNames + inhProps
        val methodSigs = (ownMethodSigs.keys + inhMethods.keys).associateWith { name ->
            ownMethodSigs[name].orEmpty() + inhMethods[name].orEmpty()
        }
        val ctx = ClassContext(SlotId(0), fqn, propertyNames, methodSigs)

        // Member functions first — each resets the shared slot/diagnostic state and is self-contained. An
        // abstract/bodyless member isn't lowered (it has no implementation to run — a concrete override or an
        // interface default supplies one, found via the supertype walk); it stays in [methodSigs] so a sibling
        // body can still call it by name.
        val methods = buildMap {
            memberFns.filter { it.bodyBlockExpression != null || it.bodyExpression != null }.forEach { fn ->
                val rf = lowerMemberFunction(fn, ctx)
                put("${rf.name}/${rf.params.size}", rf)
            }
            // Each computed `val`/`var` getter becomes a `name/0` method (read-only; the setter, if any, is not modeled).
            computedProps.forEach { p ->
                val rf = lowerComputedProperty(p, ctx)
                put("${rf.name}/0", rf)
            }
        }

        // Then the constructor/init pass in one fresh scope; its leftover diagnostics are the class's own.
        reset(decl.textRange.startOffset)
        scopes.addLast(HashMap())
        classStack.addLast(ctx)
        val thisSlot = newSlot() // slot 0 — equals ctx.thisSlot
        val primaryParams = primaryKtParams.map { p ->
            val slot = newSlot()
            val name = p.name ?: "_"
            bind(name, Binding.Param(slot, name)) // visible to initializers and init blocks
            RClassParam(
                slot, name, service.typeFromText(p.typeReference?.text, resolver.fileContext),
                isProperty = p.hasValOrVar(), mutable = p.isMutable, default = p.defaultValue?.let { lower(it) },
            )
        }
        // The superclass primary-constructor invocation (`: A(x)`), lowered with the ctor params in scope so its
        // args can reference them. Interfaces (no call entry) aren't constructed — only the one call entry.
        val superCall = decl.superTypeListEntries.filterIsInstance<KtSuperTypeCallEntry>().firstOrNull()?.let { entry ->
            val name = entry.typeReference?.text?.substringBefore('<')?.trim()?.takeIf { it.isNotEmpty() }
            name?.let {
                val superFqn = runCatching { service.resolveTypeName(it, resolver.fileContext) }.getOrNull() ?: it
                val args = entry.valueArguments.mapNotNull { va ->
                    (va as? KtValueArgument)?.getArgumentExpression()?.let { e -> RArg(lower(e), va.getArgumentName()?.asName?.identifier) }
                }
                SuperCall(superFqn, args)
            }
        }
        val enumEntries = if (flavor == ClassFlavor.ENUM) lowerEnumEntries(decl) else emptyList()
        // Body-property initializers + init blocks, interleaved in source order.
        val steps = ArrayList<Pair<Int, RNode>>()
        val delegatedProps = LinkedHashMap<String, String>() // property name → hidden delegate-object field
        for (prop in bodyProps) {
            val pname = prop.name ?: continue
            val offset = prop.textRange.startOffset
            val init = prop.initializer
            val delegate = prop.delegateExpression
            val field = Binding.Property(pname, fqn, backingField = false)
            when {
                init != null ->
                    steps += offset to RNode.PropertySet(thisRef(ctx, prop), field, lower(init), span(prop))
                // `var x by mutableStateOf(v)` — store the DELEGATE OBJECT in a hidden `x$delegate` field;
                // reads/writes of `x` route through the delegate's `.value` (see Interpreter), so a write hits
                // the real `MutableState.setValue()` and drives recomposition — the member form of a delegated
                // local. Any non-`.value` delegate (no State/Lazy convention) is Unsupported (sound).
                delegate != null -> {
                    val missing = runCatching { resolver.missingDelegateOperators(prop) }.getOrDefault(emptyList())
                    val step: RNode = when {
                        missing.isNotEmpty() ->
                            unsupported("property delegate operator(s) ${missing.joinToString(", ")} not in scope (import them)", prop)
                        delegateValueProperty(delegate) == null ->
                            unsupported("property delegate is not a `.value` delegate (State/Lazy)", prop)
                        else -> {
                            val d = lower(delegate)
                            if (d is RNode.Unsupported) d
                            else {
                                val delegateField = "$pname\$delegate"
                                delegatedProps[pname] = delegateField
                                RNode.PropertySet(thisRef(ctx, prop), Binding.Property(delegateField, fqn, backingField = false), d, span(prop))
                            }
                        }
                    }
                    steps += offset to step
                }
                // A computed getter (lowered as a method above) or an abstract/bodyless member has no init step.
                else -> {}
            }
        }
        for (anon in decl.getAnonymousInitializers()) {
            val body = anon.body ?: continue
            steps += anon.textRange.startOffset to lower(body)
        }
        val initSteps = steps.sortedBy { it.first }.map { it.second }
        val supertypes = decl.superTypeListEntries.mapNotNull { ste ->
            val name = ste.typeReference?.text?.substringBefore('<')?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            runCatching { service.resolveTypeName(name, resolver.fileContext) }.getOrNull() ?: name
        }
        val classDiags = diagnostics.toList()
        classStack.removeLast()
        scopes.removeLast()

        return ResolvedClass(
            fqn = fqn, simpleName = simpleName, flavor = flavor,
            isData = decl.isData(), isSealed = ktClass?.isSealed() == true,
            isAbstract = decl.modifierList?.text?.contains("abstract") == true,
            primaryParams = primaryParams, initSteps = initSteps, methods = methods,
            receiverSlot = thisSlot, supertypes = supertypes, superCall = superCall, enumEntries = enumEntries,
            diagnostics = classDiags, delegatedProperties = delegatedProps,
        )
    }

    /** Lower a class member function: a receiver slot (slot 0) is allocated first so `this`/implicit-member
     *  access binds to it, then the value parameters, then the body — all with [ctx] active. */
    private fun lowerMemberFunction(fn: KtNamedFunction, ctx: ClassContext): ResolvedFunction {
        reset(fn.textRange.startOffset)
        scopes.addLast(HashMap())
        classStack.addLast(ctx)
        val thisSlot = newSlot() // slot 0
        val params = loweredValueParams(fn.valueParameters)
        val body = when {
            fn.hasBlockBody() -> fn.bodyBlockExpression?.let { lowerBlock(it) } ?: emptyBlock(fn)
            else -> fn.bodyExpression?.let { lower(it) } ?: unsupported("empty body", fn)
        }
        classStack.removeLast()
        scopes.removeLast()
        return ResolvedFunction(fn.name ?: "<anonymous>", params, body, diagnostics.toList(), receiverSlot = thisSlot, returnsUnit = returnsUnit(fn))
    }

    /** Lower a computed property's getter (`val isDraw get() = …`) as a zero-arg member function keyed
     *  `name/0`; a read of the property routes to it (the interpreter invokes it when there's no backing field). */
    private fun lowerComputedProperty(prop: KtProperty, ctx: ClassContext): ResolvedFunction {
        reset(prop.textRange.startOffset)
        scopes.addLast(HashMap())
        classStack.addLast(ctx)
        val thisSlot = newSlot() // slot 0
        val getter = prop.getter
        val body = when {
            getter?.hasBlockBody() == true -> getter.bodyBlockExpression?.let { lowerBlock(it) } ?: emptyBlock(prop)
            else -> getter?.bodyExpression?.let { lower(it) } ?: unsupported("computed property without a getter body", prop)
        }
        classStack.removeLast()
        scopes.removeLast()
        return ResolvedFunction(prop.name ?: "<anonymous>", emptyList(), body, diagnostics.toList(), receiverSlot = thisSlot)
    }

    private fun lowerEnumEntries(decl: KtClassOrObject): List<REnumEntry> =
        decl.declarations.filterIsInstance<KtEnumEntry>().mapIndexed { i, e ->
            val callArgs = e.superTypeListEntries.filterIsInstance<KtSuperTypeCallEntry>().firstOrNull()?.valueArguments
                ?: e.initializerList?.initializers?.filterIsInstance<KtSuperTypeCallEntry>()?.firstOrNull()?.valueArguments
                ?: emptyList()
            val args = callArgs.mapNotNull { va ->
                (va as? KtValueArgument)?.getArgumentExpression()?.let { RArg(lower(it), va.getArgumentName()?.asName?.identifier) }
            }
            REnumEntry(e.name ?: "", i, args)
        }

    /** The fully-qualified name of [decl]: the file package + any enclosing class names + [simpleName]. */
    private fun qualifiedNameOf(simpleName: String, decl: KtClassOrObject): String {
        val enclosing = generateSequence(decl.parent) { it.parent }
            .filterIsInstance<KtClassOrObject>().mapNotNull { it.name }.toList().asReversed()
        val pkg = resolver.fileContext.packageName.takeUnless { it.isBlank() }
        val segments = listOfNotNull(pkg) + enclosing + simpleName
        return segments.joinToString(".")
    }

    /** Lower a single expression (test convenience; uses a fresh scope). */
    fun lower(e: KtExpression): RNode = when (e) {
        is KtParenthesizedExpression -> e.expression?.let { lower(it) } ?: unsupported("empty parens", e)
        is KtConstantExpression -> constNode(e)
        is KtStringTemplateExpression -> stringNode(e)
        is KtNameReferenceExpression -> nameNode(e)
        is KtDotQualifiedExpression -> qualifiedNode(e)
        is KtSafeQualifiedExpression -> safeCallNode(e)
        is KtCallExpression -> callNode(e, receiverNode = null)
        is KtIfExpression -> ifNode(e)
        is KtIsExpression -> isNode(e)
        is KtTryExpression -> tryNode(e)
        is KtThrowExpression -> RNode.Throw(e.thrownExpression?.let { lower(it) } ?: return unsupported("throw without value", e), span(e))
        // Unlabeled `break` / `continue` (the common case); a labeled jump stays an honest boundary.
        is KtBreakExpression -> if (e.getLabelName() == null) RNode.Break(span(e)) else unsupported("labeled break", e)
        is KtContinueExpression -> if (e.getLabelName() == null) RNode.Continue(span(e)) else unsupported("labeled continue", e)
        is KtDestructuringDeclaration -> destructuringNode(e)
        is KtReturnExpression -> RNode.Return(e.returnedExpression?.let { lower(it) }, span(e))
        is KtBinaryExpression -> binaryNode(e)
        is KtBinaryExpressionWithTypeRHS -> castNode(e)
        is KtClassLiteralExpression -> classLiteralNode(e, asJava = false)
        is KtCallableReferenceExpression -> callableRefNode(e)
        is KtArrayAccessExpression -> arrayAccessNode(e)
        is KtPostfixExpression -> incDecNode(e)
        is KtPrefixExpression -> incDecNode(e)
        is KtWhileExpression -> whileNode(e, doWhile = false)
        is KtDoWhileExpression -> whileNode(e, doWhile = true)
        is KtForExpression -> forNode(e)
        is KtWhenExpression -> whenNode(e)
        is KtLambdaExpression -> lambdaNode(e)
        is KtBlockExpression -> lowerBlock(e)
        is KtProperty -> localVarNode(e)
        is KtThisExpression -> {
            // `this` binds to the innermost lambda-receiver scope if one is active, else the enclosing class's
            // implicit receiver (both arrive as a slot-bound value the interpreter reads from its env).
            val rs = receiverScopes.lastOrNull()
            val ctx = classStack.lastOrNull()
            when {
                rs != null -> RNode.Name(Binding.Local(rs.slot, "this", mutable = false), span(e))
                ctx != null -> thisRef(ctx, e)
                else -> RNode.This(Binding.Receiver(resolver.implicitReceiversAt(e.textRange.startOffset).firstOrNull()), span(e))
            }
        }
        else -> unsupported(e::class.simpleName ?: "expression", e)
    }

    // --- expressions ---

    private fun constNode(e: KtConstantExpression): RNode {
        val t = e.text.trim()
        val (value, fqn) = when {
            t == "true" -> true to "kotlin.Boolean"
            t == "false" -> false to "kotlin.Boolean"
            t == "null" -> return RNode.Const(null, null, span(e))
            t.startsWith("'") -> parseChar(t) to "kotlin.Char"
            else -> parseNumber(t) ?: (null to "kotlin.Int")
        }
        return if (value == null) unsupported("unparseable literal `$t`", e)
        else RNode.Const(value, service.typeByFqn(fqn), span(e))
    }

    /** Parse a Kotlin numeric literal → (boxed value, type FQN). Handles hex (`0xFFD32F2F`, a `Color(Long)`
     *  argument) / binary (`0b1010`) prefixes, digit separators (`1_000`), the `u`/`U` (unsigned) and `L`
     *  (long) suffixes, and floats (`1.5`, `1e5`, `1.5f`). An integer literal that overflows `Int` widens to
     *  `Long` — matching Kotlin, so a 32-bit ARGB hex like `0xFFD32F2F` types as `Long`. Null when unparseable. */
    private fun parseNumber(raw: String): Pair<Any, String>? {
        val t = raw.replace("_", "")
        val lower = t.lowercase()
        // Hex / binary INTEGER literal — radix-prefixed, so its a-f / e digits are NOT a float exponent/suffix.
        if (lower.startsWith("0x") || lower.startsWith("0b")) {
            val radix = if (lower[1] == 'x') 16 else 2
            var body = t.substring(2)
            val isLong = body.endsWith("L") || body.endsWith("l")
            if (isLong) body = body.dropLast(1)
            if (body.endsWith("u") || body.endsWith("U")) body = body.dropLast(1) // model UInt/ULong as Int/Long
            val asLong = body.toLongOrNull(radix) ?: body.toULongOrNull(radix)?.toLong() ?: return null
            return integerValue(asLong, isLong)
        }
        // Float / Double — a fractional point, a decimal exponent, or an f/F suffix.
        if ('.' in t || 'e' in lower || t.endsWith("f") || t.endsWith("F")) {
            return if (t.endsWith("f") || t.endsWith("F")) t.dropLast(1).toFloatOrNull()?.let { it to "kotlin.Float" }
            else t.toDoubleOrNull()?.let { it to "kotlin.Double" }
        }
        // Decimal integer.
        var body = t
        val isLong = body.endsWith("L") || body.endsWith("l")
        if (isLong) body = body.dropLast(1)
        if (body.endsWith("u") || body.endsWith("U")) body = body.dropLast(1)
        val asLong = body.toLongOrNull() ?: return null
        return integerValue(asLong, isLong)
    }

    /** An integer literal's boxed value + type: `Long` when an `L` suffix is present or the value doesn't fit
     *  `Int` (Kotlin's auto-widening), else `Int`. */
    private fun integerValue(value: Long, isLong: Boolean): Pair<Any, String> =
        if (isLong || value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value to "kotlin.Long"
        else value.toInt() to "kotlin.Int"

    private fun stringNode(e: KtStringTemplateExpression): RNode {
        val entries = e.entries
        if (entries.isEmpty()) return RNode.Const("", strType, span(e))
        if (entries.size == 1 && entries[0] is KtLiteralStringTemplateEntry)
            return RNode.Const((entries[0] as KtLiteralStringTemplateEntry).text, strType, span(e))
        // Interpolation → concat: literal/escape parts are constants, `$x` / `${expr}` parts are the lowered
        // expression (stringified at runtime by the interpreter).
        val parts = entries.map { entry ->
            when (entry) {
                is KtLiteralStringTemplateEntry -> RNode.Const(entry.text, strType, span(entry))
                is KtEscapeStringTemplateEntry -> RNode.Const(entry.unescapedValue, strType, span(entry))
                else -> entry.expression?.let { lower(it) } ?: return unsupported("empty template entry", e)
            }
        }
        return RNode.StringConcat(parts, span(e))
    }

    /**
     * `when` → a nested `if`/`else` chain. A subject (`when (x) { … }`) is evaluated once into a temp local
     * and each branch compares against it (`==`, `is T`, or `in range`); a subjectless `when { … }` uses the
     * branch conditions directly.
     */
    private fun whenNode(e: KtWhenExpression): RNode {
        val span = span(e)
        val branches = e.entries.filter { !it.isElse }
        val elseBody = e.entries.firstOrNull { it.isElse }?.expression?.let { lower(it) }

        val subject = e.subjectExpression
        val subjectSlot = if (subject != null) newSlot() else null
        fun subjectRef() = RNode.Name(Binding.Local(subjectSlot!!, "\$subject", mutable = false), span)
        // `when (x) { is T -> x.member }` smart-casts the subject `x` to `T` in that branch (when `x` is a
        // simple name; the body references it by name, not the synthetic `$subject` local the condition uses).
        val subjName = (subject as? KtNameReferenceExpression)?.getReferencedName()

        var chain: RNode? = elseBody
        for (entry in branches.asReversed()) {
            val cond = whenCondition(entry, subject != null, ::subjectRef, span)
            val narrow = if (subjName != null) whenEntryNarrowing(entry, subjName) else emptyMap()
            val body = withNarrowing(narrow) { entry.expression?.let { lower(it) } ?: emptyBlock(entry) }
            chain = RNode.If(cond, body, chain, span)
        }
        val result = chain ?: elseBody ?: unsupported("empty when", e)
        return if (subjectSlot != null) {
            RNode.Block(listOf(RNode.LocalVar(subjectSlot, "\$subject", false, lower(subject!!), span), result), isExpression = true, span)
        } else {
            result
        }
    }

    /** The subject narrowing for a `when` branch: `{subjName → T}` when the branch's SOLE condition is a
     *  positive `is T` (a mixed `is A, is B` comma branch doesn't smart-cast), else empty. */
    private fun whenEntryNarrowing(entry: KtWhenEntry, subjName: String): Map<String, KotlinType> {
        val c = entry.conditions.singleOrNull() as? KtWhenConditionIsPattern ?: return emptyMap()
        return if (c.isNegated) emptyMap() else narrowingTo(subjName, c.typeReference?.text)
    }

    /** A branch's condition as a boolean, OR-ing its comma-separated parts (`if (a) true else b`). Each part is
     *  `subject == value` (or the bare expression, subjectless), `subject is T`, or `subject in range`. */
    private fun whenCondition(entry: KtWhenEntry, hasSubject: Boolean, subjectRef: () -> RNode, span: SourceSpan): RNode {
        val parts = entry.conditions.map { c ->
            when (c) {
                is KtWhenConditionWithExpression -> {
                    val value = c.expression ?: return unsupported("empty when condition", entry)
                    if (hasSubject)
                        RNode.Call(synthOperator("eq"), DispatchKind.OPERATOR, subjectRef(), listOf(RArg(lower(value))), csk(span.start), span)
                    else lower(value)
                }
                is KtWhenConditionIsPattern -> {
                    val typeText = c.typeReference?.text?.substringBefore('<')?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return unsupported("`is` without a type", entry)
                    val fqn = runCatching { service.resolveTypeName(typeText, resolver.fileContext) }.getOrNull() ?: typeText
                    RNode.TypeCheck(subjectRef(), fqn, c.isNegated, span)
                }
                is KtWhenConditionInRange -> {
                    val range = c.rangeExpression ?: return unsupported("`in` without a range", entry)
                    val contains = RNode.Call(synthMember("contains"), DispatchKind.MEMBER, lower(range), listOf(RArg(subjectRef())), csk(span.start), span)
                    if (c.isNegated) negate(contains, span) else contains
                }
                else -> return unsupported("when condition ${c::class.simpleName}", entry)
            }
        }
        return parts.reduceRightOrNull { p, acc -> RNode.If(p, RNode.Const(true, boolType, span), acc, span) }
            ?: RNode.Const(false, boolType, span)
    }

    /** Boolean negation as `if (x) false else true` (no dedicated node needed). */
    private fun negate(value: RNode, span: SourceSpan): RNode =
        RNode.If(value, RNode.Const(false, boolType, span), RNode.Const(true, boolType, span), span)

    private fun nameNode(e: KtNameReferenceExpression): RNode {
        val name = e.getReferencedName()
        resolveLocal(name)?.let { binding ->
            // A `by`-delegated local reads as `delegate.value` — the slot holds the delegate object.
            if (binding is Binding.DelegatedLocal) {
                return RNode.PropertyGet(delegateRef(binding, e), binding.valueProperty, span(e))
            }
            return RNode.Name(binding, span(e))
        }
        // A bare member property of an active receiver scope (`with(x) { someProp }`, `apply { prop }`): resolve
        // it against the innermost lambda-receiver whose type ACTUALLY declares it, reading through that scope's
        // `this` slot. (A bare member CALL is handled by callNode's implicit-receiver path; this is the property
        // read.) The member-existence gate is essential: `propertyBinding` has a best-effort fallback that binds
        // ANY name, which for a BARE name would shadow a top-level object/type (`MaterialTheme`/`Color` used
        // inside a `Column {}`/`buildAnnotatedString {}` scope) with a bogus property read on the scope.
        for (i in receiverScopes.indices.reversed()) {
            val rs = receiverScopes[i]
            val declaresIt = runCatching {
                service.membersForCompletion(rs.type.qualifiedName, rs.type.typeArguments, name)
                    .any { it.name == name && it.kind == SymbolKind.FIELD && (!it.isExtension || extensionInScope(it)) }
            }.getOrDefault(false)
            if (declaresIt) propertyBinding(name, rs.type)?.let {
                return RNode.PropertyGet(RNode.Name(Binding.Local(rs.slot, "this", mutable = false), span(e)), it, span(e))
            }
        }
        // A bare member property of the enclosing class (`id` inside one of `Project`'s methods) reads through
        // the implicit `this` receiver.
        classStack.lastOrNull()?.let { ctx ->
            if (name in ctx.propertyNames)
                return RNode.PropertyGet(thisRef(ctx, e), Binding.Property(name, ctx.fqn, backingField = false), span(e))
        }
        // A bare companion-member read (`SIZE` inside the class whose `companion object` declares `const val
        // SIZE`): companion members are in scope unqualified within the class, so resolve it through the
        // companion singleton — otherwise it falls through and is mis-read as a type/object reference that then
        // fails at render ("a project-source object isn't available").
        for (ctx in classStack.asReversed()) {
            val companionFqn = "${ctx.fqn}.Companion"
            val isCompanionMember = runCatching {
                service.membersForCompletion(companionFqn, emptyList(), name)
                    .any { it.name == name && it.kind == SymbolKind.FIELD }
            }.getOrDefault(false)
            if (isCompanionMember) {
                return RNode.PropertyGet(
                    RNode.Name(Binding.ObjectRef(companionFqn, "Companion"), span(e)),
                    Binding.Property(name, companionFqn, backingField = false), span(e),
                )
            }
        }
        // A bare top-level property (`PI`) reads as a property get with no receiver. Member properties of an
        // enclosing class without an explicit receiver are not yet modeled → Unsupported (sound, not guessed).
        val prop = service.topLevelByName(name).firstOrNull { it.kind == SymbolKind.FIELD }
        if (prop != null) {
            // A SOURCE top-level `val`/`var` (`private val XColor = Color(…)`) has no compiled `…Kt` facade to
            // reflect — it is lowered as a synthetic zero-arg getter `name/0` in the program (see
            // KotlinPreviewLowering); read it as a TOP_LEVEL source call so the interpreter runs its initializer.
            if (prop.origin.fromSource && !prop.isExtension) {
                return RNode.Call(toCallable(prop), DispatchKind.TOP_LEVEL, null, emptyList(), csk(e.textRange.startOffset), span(e))
            }
            // A LIBRARY top-level property's getter is a STATIC method on its `…Kt` file facade
            // (`getLocalTextStyle()`), so the binding records the facade (not the package) as the reflect owner.
            return RNode.PropertyGet(null, Binding.Property(name, prop.declaringClassFqn ?: prop.packageName, backingField = false), span(e))
        }
        // A bare type name used as a VALUE is its singleton: an `object` (its `INSTANCE`) or a type with a
        // companion (`Modifier` → `Modifier.Companion`, the empty modifier; `Color` for `Color.Red`). The
        // interpreter materializes it reflectively from the runtime class.
        val typeFqn = runCatching { service.resolveTypeName(name, resolver.fileContext) }.getOrNull()
        if (typeFqn != null) {
            return RNode.Name(Binding.ObjectRef(typeFqn, name), span(e))
        }
        return unsupported("unresolved name `$name`", e)
    }

    private fun qualifiedNode(e: KtDotQualifiedExpression): RNode {
        if (e.receiverExpression is KtSuperExpression) return superNode(e)
        // `X::class.java` / `expr::class.java` — the compiler lowers `::class.java` straight to the JVM class
        // constant, so fold the `.java` selector into the class literal (yielding a `Class` not a `KClass`)
        // rather than lowering `X::class` to a KClass and then modeling `KClass.java` at eval.
        val recvExpr = e.receiverExpression
        val sel0 = e.selectorExpression
        if (recvExpr is KtClassLiteralExpression && sel0 is KtNameReferenceExpression &&
            (sel0.getReferencedName() == "java" || sel0.getReferencedName() == "javaObjectType")
        ) return classLiteralNode(recvExpr, asJava = true)
        val receiver = lower(e.receiverExpression)
        if (receiver is RNode.Unsupported) return receiver
        return when (val sel = e.selectorExpression) {
            is KtCallExpression -> callNode(sel, receiver, e.receiverExpression)
            is KtNameReferenceExpression -> propertyGet(sel.getReferencedName(), receiver, e.receiverExpression, e)
            else -> unsupported("qualified selector ${sel?.let { it::class.simpleName }}", e)
        }
    }

    /**
     * `Foo::class` (a type literal) or `expr::class` (an instance literal). A receiver that denotes a TYPE
     * lowers to a [RNode.ClassLiteral] carrying the resolved FQN plus its loadable supertype FQNs (a
     * project-source type isn't compiled at preview time, so a reflectable supertype stands in for it); any
     * other receiver is a value whose runtime class is taken. [asJava] is set by [qualifiedNode] when the
     * `.java` selector is applied.
     */
    private fun classLiteralNode(e: KtClassLiteralExpression, asJava: Boolean): RNode {
        val recvExpr = e.receiverExpression ?: return unsupported("class literal without a receiver", e)
        val typeFqn = runCatching { resolver.typeDenotationFqn(recvExpr) }.getOrNull()
        if (typeFqn != null) {
            return RNode.ClassLiteral(receiver = null, typeCandidates = classLoadCandidates(typeFqn), asJava = asJava, source = span(e))
        }
        // An instance class literal `expr::class` — the runtime class of the evaluated receiver (also the path
        // for a bare `object` reference, whose singleton materializes and gives its class).
        val recv = lower(recvExpr)
        if (recv is RNode.Unsupported) return recv
        return RNode.ClassLiteral(receiver = recv, typeCandidates = emptyList(), asJava = asJava, source = span(e))
    }

    /** [fqn] followed by its transitive supertype FQNs (bounded) — the ordered candidates the interpreter tries
     *  to load at eval. A mapped Kotlin type (`kotlin.String`, `kotlin.collections.List`) has no `.class` under
     *  its Kotlin FQN, so its JVM type (`java.lang.String`, `java.util.List`) is listed first; a project-source
     *  class (uncompiled at preview time) has no loadable class of its own, so its nearest reflectable supertype
     *  stands in. */
    private fun classLoadCandidates(fqn: String): List<String> {
        val candidates = LinkedHashSet<String>()
        val visited = HashSet<String>()
        fun add(f: String, depth: Int) {
            if (depth > 6 || !visited.add(f)) return
            dev.ide.lang.kotlin.symbols.Builtins.javaTypeFor(f)?.let { candidates += it } // JVM form preferred
            candidates += f
            runCatching { service.supertypesOf(f) }.getOrNull()?.forEach { st ->
                (st as? KotlinType)?.qualifiedName?.let { add(it, depth + 1) }
            }
        }
        add(fqn, 0)
        return candidates.toList()
    }

    /**
     * `super.foo(args)` / `super.prop` — same instance, superclass implementation. The receiver is the
     * enclosing class's `this` (so a source-superclass method runs on the right object); the call carries
     * [DispatchKind.SUPER] and the lexical class FQN so the interpreter starts the method lookup at the
     * supertypes, skipping this class's own override. A super call to a binary superclass (`super.onCreate`)
     * has no source body — the interpreter no-ops it; the point here is that lowering produces no diagnostic,
     * so an unrelated overriding member (a `MainActivity.onCreate`) doesn't block the file's previews.
     */
    private fun superNode(e: KtDotQualifiedExpression): RNode {
        val ctx = classStack.lastOrNull() ?: return unsupported("`super` outside a class body", e)
        val receiver = thisRef(ctx, e)
        return when (val sel = e.selectorExpression) {
            is KtCallExpression -> {
                val name = (sel.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                    ?: return unsupported("super call without a simple name", e)
                val arity = sel.valueArguments.size
                val callee = ResolvedCallable.Source(name, "${ctx.fqn}.$name/$arity", emptyList(), isConstructor = false)
                RNode.Call(callee, DispatchKind.SUPER, receiver, lowerArgs(sel), csk(sel.textRange.startOffset), span(e))
            }
            // `super.prop` reads the inherited/overridden property off the same instance.
            is KtNameReferenceExpression ->
                if (sel.getReferencedName() in ctx.propertyNames)
                    RNode.PropertyGet(receiver, Binding.Property(sel.getReferencedName(), ctx.fqn, backingField = false), span(e))
                else unsupported("super property `${sel.getReferencedName()}` (not a known member)", e)
            else -> unsupported("super selector ${sel?.let { it::class.simpleName }}", e)
        }
    }

    /** Resolve `receiverNode.name` to a property read. `this.member` of the enclosing class resolves against
     *  the class context; otherwise the receiver's (best-effort) inferred type drives the binding. */
    private fun propertyGet(name: String, receiverNode: RNode, receiverExpr: KtExpression, e: PsiElement): RNode {
        val ctx = classStack.lastOrNull()
        if (isThisReceiver(receiverNode, ctx) && name in ctx!!.propertyNames) {
            return RNode.PropertyGet(receiverNode, Binding.Property(name, ctx.fqn, backingField = false), span(e))
        }
        // Inference is best-effort and can throw on a deep chain; degrade to null (→ Unsupported with a reason)
        // instead of letting the throw crash the whole function's lowering.
        val recvType = runCatching { resolver.inferType(receiverExpr) }.getOrNull()
        return propertyBinding(name, recvType)?.let { RNode.PropertyGet(receiverNode, it, span(e)) }
            ?: unsupported("unresolved property `$name` (extension not imported)", e)
    }

    /** `receiver?.selector` → `receiver.let { tmp -> if (tmp != null) tmp.selector else null }`, lowered with a
     *  temp local so the receiver is evaluated once. */
    private fun safeCallNode(e: KtSafeQualifiedExpression): RNode {
        val recv = lower(e.receiverExpression)
        if (recv is RNode.Unsupported) return recv
        val span = span(e)
        val tmpSlot = newSlot()
        val tmpRef = { RNode.Name(Binding.Local(tmpSlot, "\$sc", mutable = false), span) }
        val selected = when (val sel = e.selectorExpression) {
            is KtCallExpression -> callNode(sel, tmpRef(), e.receiverExpression)
            is KtNameReferenceExpression -> propertyGet(sel.getReferencedName(), tmpRef(), e.receiverExpression, e)
            else -> return unsupported("safe-call selector ${sel?.let { it::class.simpleName }}", e)
        }
        if (selected is RNode.Unsupported) return selected
        val cond = RNode.Call(synthOperator("ne"), DispatchKind.OPERATOR, tmpRef(), listOf(RArg(RNode.Const(null, null, span))), csk(span.start), span)
        val ifNode = RNode.If(cond, selected, RNode.Const(null, null, span), span)
        return RNode.Block(listOf(RNode.LocalVar(tmpSlot, "\$sc", mutable = false, recv, span), ifNode), isExpression = true, span)
    }

    private fun isNode(e: KtIsExpression): RNode {
        val value = lower(e.leftHandSide)
        if (value is RNode.Unsupported) return value
        val typeText = e.typeReference?.text?.substringBefore('<')?.trim()?.takeIf { it.isNotEmpty() }
            ?: return unsupported("`is` without a type", e)
        val fqn = runCatching { service.resolveTypeName(typeText, resolver.fileContext) }.getOrNull() ?: typeText
        return RNode.TypeCheck(value, fqn, e.isNegated, span(e))
    }

    /** `value as T` / `value as? T` — a runtime cast. The target type's generic args are stripped (the JVM
     *  check is erased anyway) and a trailing `?` records target-nullability so an unsafe `as T?` accepts null. */
    private fun castNode(e: KtBinaryExpressionWithTypeRHS): RNode {
        val value = lower(e.left)
        if (value is RNode.Unsupported) return value
        val rawType = e.right?.text?.trim()?.takeIf { it.isNotEmpty() } ?: return unsupported("cast without a type", e)
        val nullable = rawType.endsWith("?")
        val typeText = rawType.removeSuffix("?").substringBefore('<').trim()
        val fqn = runCatching { service.resolveTypeName(typeText, resolver.fileContext) }.getOrNull() ?: typeText
        val safe = e.operationReference.getReferencedNameElementType() == KtTokens.AS_SAFE
        return RNode.Cast(value, fqn, safe, nullable, span(e))
    }

    /**
     * A callable reference (`::foo`, `obj::method`) — desugared to a synthesized lambda that forwards its
     * arguments to the target, reusing the normal call machinery. Covers a top-level function reference and a
     * BOUND member reference on a value receiver (the common `onClick = vm::handle` / `::doThing` callbacks);
     * an unbound `Type::method` / static / constructor / property reference stays an honest boundary.
     */
    private fun callableRefNode(e: KtCallableReferenceExpression): RNode {
        val span = span(e)
        val name = e.callableReference.getReferencedName()
        val receiverExpr = e.receiverExpression
        if (receiverExpr == null) {
            val sym = service.topLevelByName(name).firstOrNull { it.kind == SymbolKind.METHOD }
                ?: return unsupported("callable reference `::$name` (unresolved)", e)
            return synthRefLambda(toCallable(sym), DispatchKind.TOP_LEVEL, receiverNode = null, arity = sym.paramTypes.size, span = span)
        }
        // `expr::method` — a BOUND reference requires the receiver to be a VALUE expression (typeable); a bare
        // type name (`Foo::method`, an unbound/static reference) doesn't infer a type and stays unsupported.
        val recvType = runCatching { resolver.inferType(receiverExpr) }.getOrNull()
            ?: return unsupported("callable reference `${receiverExpr.text}::$name` (unbound or untyped receiver)", e)
        val sym = service.membersForCompletion(recvType.qualifiedName, recvType.typeArguments, name)
            .firstOrNull { it.name == name && it.kind == SymbolKind.METHOD && !it.isExtension }
            ?: return unsupported("callable reference `${receiverExpr.text}::$name` (no such member)", e)
        val recvNode = lower(receiverExpr)
        if (recvNode is RNode.Unsupported) return recvNode
        // Bind the receiver once into a temp the synthesized lambda captures (so `obj` is evaluated at the point
        // the reference is taken, not on each call) — the same pattern as a safe-call temp.
        val tmpSlot = newSlot()
        val ref = RNode.Name(Binding.Local(tmpSlot, "\$ref", mutable = false), span)
        val lambda = synthRefLambda(toCallable(sym), DispatchKind.MEMBER, ref, arity = sym.paramTypes.size, span = span)
        return RNode.Block(listOf(RNode.LocalVar(tmpSlot, "\$ref", mutable = false, recvNode, span), lambda), isExpression = true, span)
    }

    /** Build `{ p0, … -> callee(p0, …) }` for a callable reference of the given [arity] — a normal lambda the
     *  interpreter runs as a [Closure], so library/source dispatch and lambda proxying are all reused. */
    private fun synthRefLambda(callee: ResolvedCallable, dispatch: DispatchKind, receiverNode: RNode?, arity: Int, span: SourceSpan): RNode {
        val slots = (0 until arity).map { newSlot() }
        val params = slots.mapIndexed { i, slot -> RParam(slot, "p$i", null) }
        val argRefs = slots.map { RArg(RNode.Name(Binding.Local(it, "p", mutable = false), span)) }
        val call = RNode.Call(callee, dispatch, receiverNode, argRefs, csk(span.start), span)
        return RNode.Lambda(params, call, captures = emptyList(), source = span)
    }

    private fun tryNode(e: KtTryExpression): RNode {
        val body = lowerBlock(e.tryBlock)
        val catches = e.catchClauses.map { cc ->
            scopes.addLast(HashMap())
            val param = cc.catchParameter
            val slot = newSlot()
            val name = param?.name ?: "e"
            bind(name, Binding.Local(slot, name, mutable = false))
            val typeFqn = param?.typeReference?.text?.substringBefore('<')?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { service.resolveTypeName(it, resolver.fileContext) }.getOrNull() ?: it }
            val cbody = cc.catchBody?.let { lower(it) } ?: emptyBlock(cc)
            scopes.removeLast()
            RCatch(slot, name, typeFqn, cbody)
        }
        val finallyBlock = e.finallyBlock?.finalExpression?.let { lowerBlock(it) }
        return RNode.Try(body, catches, finallyBlock, span(e))
    }

    /** `val (a, b) = e` → a temp local holding `e`, then a local per entry reading `tmp.componentN()`. */
    private fun destructuringNode(e: KtDestructuringDeclaration): RNode {
        val init = e.initializer ?: return unsupported("destructuring without initializer", e)
        val initNode = lower(init)
        if (initNode is RNode.Unsupported) return initNode
        val span = span(e)
        val tmpSlot = newSlot()
        val statements = ArrayList<RNode>()
        statements += RNode.LocalVar(tmpSlot, "\$destr", mutable = false, initNode, span)
        e.entries.forEachIndexed { i, entry ->
            val name = entry.name ?: "_"
            val slot = newSlot()
            val tmpRef = RNode.Name(Binding.Local(tmpSlot, "\$destr", mutable = false), span(entry))
            val comp = RNode.Call(synthMember("component${i + 1}"), DispatchKind.MEMBER, tmpRef, emptyList(), csk(span(entry).start), span(entry))
            bind(name, Binding.Local(slot, name, mutable = false))
            statements += RNode.LocalVar(slot, name, mutable = false, comp, span(entry))
        }
        return RNode.Block(statements, isExpression = false, span)
    }

    /**
     * Resolve a `receiver.name` property read to a [Binding.Property]. An **extension** property (`Int.dp`,
     * `Modifier.fillMaxSize` value props) compiles to a static getter on a `…Kt` facade with the receiver as
     * the first argument, so we record the facade in `ownerFqn` and flag `isExtension` — the interpreter then
     * reflects `DpKt.getDp(int)` instead of looking for a (non-existent) `getDp()` on `java.lang.Integer`.
     * A plain member property keeps the receiver type as `ownerFqn` (informational) and an instance getter.
     */
    private fun propertyBinding(name: String, recvType: KotlinType?): Binding.Property? {
        val candidates = recvType?.let { rt ->
            runCatching {
                service.membersForCompletion(rt.qualifiedName, rt.typeArguments, name)
                    .filter { it.name == name && it.kind == SymbolKind.FIELD }
            }.getOrNull()
        }.orEmpty()
        // A plain member property wins over an extension of the same name (Kotlin's member-first rule).
        if (candidates.any { !it.isExtension }) {
            return Binding.Property(name, recvType?.qualifiedName, backingField = false, isExtension = false)
        }
        candidates.firstOrNull { it.isExtension }?.let { ext ->
            // An extension property reads only when it is actually in scope. `16.dp`/`14.sp` without
            // `import androidx.compose.ui.unit.{dp,sp}` does NOT compile, so the sound lowering is
            // Unsupported (null) — never a fabricated getter on the receiver.
            if (!extensionInScope(ext)) return null
            return Binding.Property(name, ext.declaringClassFqn ?: ext.packageName, backingField = false, isExtension = true)
        }
        // No candidate at all → best-effort plain member binding (e.g. an as-yet-unindexed member).
        return Binding.Property(name, recvType?.qualifiedName, backingField = false, isExtension = false)
    }

    /**
     * Whether the extension [sym] is actually in scope here — Kotlin resolves an extension only when it is
     * imported (explicitly or via a star/default import) or declared in the file's own package. Without this
     * gate the resolver binds `16.dp` even when `androidx.compose.ui.unit.dp` was never imported, and the
     * interpreter then reflects a getter the program cannot legally call. No package info → don't guess a
     * rejection (allow).
     */
    private fun extensionInScope(sym: KotlinSymbol): Boolean {
        val pkg = sym.packageName ?: sym.declaringClassFqn?.substringBeforeLast('.', "")?.ifEmpty { null } ?: return true
        val ctx = resolver.fileContext
        if (pkg == ctx.packageName || DefaultImports.isDefaultImported(pkg)) return true
        return ctx.imports.any { imp ->
            if (imp.isStar) imp.packageName == pkg else imp.fqn == "$pkg.${sym.name}"
        }
    }

    private fun lowerArgs(call: KtCallExpression): List<RArg> = call.valueArguments.map { va ->
        val expr = va.getArgumentExpression() ?: return@map RArg(unsupported("empty argument", call))
        // A `KtLambdaArgument` is a trailing lambda (written outside the parens) — it binds to the LAST value
        // parameter. A lambda inside the parens (named or positional) is an ordinary `KtValueArgument`.
        RArg(lower(expr), va.getArgumentName()?.asName?.identifier, va.getSpreadElement() != null, va is KtLambdaArgument)
    }

    private fun callNode(call: KtCallExpression, receiverNode: RNode?, receiverExpr: KtExpression? = null): RNode {
        // Invoking a function value held by a local/param (`fn(x)`, `callback()`): a bare call whose callee name
        // is a local in scope is an `invoke` on that value, not a named-function call (a local shadows a
        // same-named function). The interpreter calls an interpreted lambda directly, or `invoke()`s a JVM one.
        val bareCalleeName = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        if (receiverNode == null && bareCalleeName != null) {
            resolveLocal(bareCalleeName)?.let { binding ->
                val recv = if (binding is Binding.DelegatedLocal) RNode.PropertyGet(delegateRef(binding, call), binding.valueProperty, span(call))
                else RNode.Name(binding, span(call))
                return RNode.Call(synthMember("invoke"), DispatchKind.INVOKE, recv, lowerArgs(call), csk(call.textRange.startOffset), span(call))
            }
        }
        checkNamedArguments(call)
        // A coroutine suspend intrinsic the interpreter models (`delay`/`yield`/`withContext`/`coroutineScope`/
        // …). These frequently DON'T resolve to a clean Call: `delay`'s overloads are ambiguous across coroutines
        // artifacts (→ Unsupported → skipped → a `while { delay(); … }` timer busy-loops), and even when they
        // resolve their owner may be the package rather than a facade the interpreter keys on. Canonicalize a
        // genuine kotlinx.coroutines intrinsic to a kotlinx.coroutines-owned Call so the interpreter runs it
        // (`delay` = an interruptible sleep, the scoping ones just run their block) under the coroutine bridge.
        // Gated on a real kotlinx.coroutines candidate so a same-named user function isn't hijacked.
        if (receiverNode == null && bareCalleeName in COROUTINE_INTRINSICS) {
            coroutineIntrinsicOwner(call, bareCalleeName!!)?.let { owner ->
                val callee = ResolvedCallable.Library(
                    displayName = bareCalleeName, ownerFqn = owner, methodName = bareCalleeName,
                    paramTypes = List(call.valueArguments.size) { null }, isStatic = true, isConstructor = false, isInline = false,
                )
                return RNode.Call(callee, DispatchKind.TOP_LEVEL, null, lowerArgs(call), csk(call.textRange.startOffset), span(call))
            }
        }
        // `flow.collect { action }` / `collectLatest` — the collect extension is `inline` (no JVM method → it
        // lowers to Unsupported → gets skipped), so canonicalize a genuine `kotlinx.coroutines.flow` collect to a
        // dispatchable MEMBER Call the interpreter routes to the coroutine bridge (a blocking collect on the
        // bridge thread). Gated on a real flow-package candidate so a same-named user method isn't hijacked.
        if (receiverNode != null && bareCalleeName in FLOW_COLLECT_NAMES && call.valueArguments.size == 1 && isFlowCollectCall(call, bareCalleeName!!)) {
            val callee = ResolvedCallable.Library(
                displayName = bareCalleeName, ownerFqn = "kotlinx.coroutines.flow.FlowKt", methodName = bareCalleeName,
                paramTypes = listOf(null), isStatic = false, isConstructor = false, isInline = false,
            )
            return RNode.Call(callee, DispatchKind.MEMBER, receiverNode, lowerArgs(call), csk(call.textRange.startOffset), span(call))
        }
        // `iterable.forEach { }` / `forEachIndexed { }` — the Kotlin stdlib inline extension. On device its
        // overload resolution contends against `java.lang.Iterable.forEach(Consumer)` (a Java 8 default MEMBER,
        // present on API 24+): a trailing lambda's type is un-inferable so both look applicable, and the call
        // lowers either to Unsupported (a false "unresolved/ambiguous call") or to the Java member (MEMBER on
        // `java.lang.Iterable`). BOTH bypass the `forEach` inline intrinsic (the interpreter's `evalInlineIntrinsic`
        // only fires for a CollectionsKt-owned EXTENSION), so a `Column { list.forEach { Row { } } }` runs the loop
        // in a library frame and its child composables never render. Canonicalize a genuine kotlin.collections
        // `forEach`/`forEachIndexed` (CollectionsKt for List/Set/Iterable, ArraysKt for arrays) to a
        // CollectionsKt-owned EXTENSION Call so the interpreter runs it as an intrinsic (the loop body composes
        // into the ambient composition). Gated on a real kotlin.collections candidate so a same-named user
        // extension isn't hijacked; Map.forEach (MapsKt, a destructuring `(k, v)` lambda) is intentionally left
        // to normal resolution.
        if (receiverNode != null && bareCalleeName in COLLECTION_FOREACH_NAMES && call.valueArguments.size == 1 &&
            isCollectionForEachCall(call, bareCalleeName!!)
        ) {
            val callee = ResolvedCallable.Library(
                displayName = bareCalleeName, ownerFqn = "kotlin.collections.CollectionsKt", methodName = bareCalleeName,
                paramTypes = listOf(null), isStatic = false, isConstructor = false, isInline = true,
            )
            return RNode.Call(callee, DispatchKind.EXTENSION, receiverNode, lowerArgs(call), csk(call.textRange.startOffset), span(call))
        }
        // A generic call whose type arguments can't be inferred (`mutableStateOf()` with no value argument) is
        // invalid Kotlin — the editor flags `kt.cannotInferType`. The arity fallback in `chooseCallee` would
        // still pick the callee and lower a malformed (under-applied) call that crashes the run reflectively;
        // surface the honest reason instead so the preview names the gap rather than dying opaquely.
        val uninferable = runCatching { resolver.uninferableTypeParameters(call) }.getOrDefault(emptyList())
        if (uninferable.isNotEmpty()) {
            val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: "?"
            return unsupported("not enough information to infer type variable ${uninferable.joinToString(", ")} for `$name`", call)
        }
        // A call that omits a REQUIRED argument (`Button { }` — no `onClick`) doesn't compile. The arity
        // fallback in `chooseCallee` would still pick the overload and lower a call with a null stand-in for the
        // missing parameter, which then RUNS in the preview as if valid. Reject it with the honest reason so the
        // preview reports the gap instead of silently rendering invalid code. Sound across overloads (backs off
        // unless every candidate is missing a required parameter; see `missingRequiredArgument`).
        runCatching { resolver.missingRequiredArgument(call) }.getOrNull()?.let { missing ->
            val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: "?"
            return unsupported("no value passed for required parameter $missing of `$name`", call)
        }
        // A bare or `this`-qualified call to a member of the enclosing class dispatches on `this` — resolve it
        // against the class context directly (the editor resolver doesn't model source implicit receivers).
        val ctx = classStack.lastOrNull()
        if (ctx != null && (receiverNode == null || isThisReceiver(receiverNode, ctx))) {
            val callName = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            val arity = call.valueArguments.size
            val sig = callName?.let { ctx.methods[it] }?.firstOrNull { it.arity == arity }
            if (callName != null && sig != null) {
                val callee = ResolvedCallable.Source(
                    displayName = callName, declId = "${ctx.fqn}.$callName/$arity",
                    paramNames = sig.paramNames, isConstructor = false,
                )
                return RNode.Call(callee, DispatchKind.MEMBER, thisRef(ctx, call), lowerArgs(call), csk(call.textRange.startOffset), span(call))
            }
        }
        val chosen = chooseCallee(call)
        if (chosen == null) {
            // The editor resolver couldn't resolve it; if the receiver is a known source type and the member is
            // one the interpreter can run/synthesize, dispatch it on the source instance.
            val callName = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            val arity = call.valueArguments.size
            val srcCls = if (receiverNode != null) sourceClassOfReceiver(receiverExpr) else null
            if (callName != null && srcCls != null && acceptsSourceMember(srcCls, callName, arity)) {
                val callee = ResolvedCallable.Source(callName, "${srcCls.fqn}.$callName/$arity", emptyList(), isConstructor = false)
                return RNode.Call(callee, DispatchKind.MEMBER, receiverNode, lowerArgs(call), csk(call.textRange.startOffset), span(call))
            }
            // A qualified call `Type.Nested(args)` on a TYPE receiver whose selector is a NESTED CLASS is a
            // CONSTRUCTOR of that nested class (`GridCells.Fixed(2)`) — not a member of the outer type, so
            // chooseCallee finds no candidate. Recover it from the receiver's `ObjectRef` (a bare type used as a
            // value lowers to one): a source nested class in this file builds a SourceObject; a library nested
            // class is reflected (its JVM name joins outer+nested with `$`, not `.`).
            val outerFqn = ((receiverNode as? RNode.Name)?.binding as? Binding.ObjectRef)?.fqn
            if (callName != null && outerFqn != null && callName.first().isUpperCase()) {
                val nestedDotted = "$outerFqn.$callName"
                fileClasses[callName]?.takeIf { it.fqn == nestedDotted && it.flavor == ClassFlavor.CLASS }?.let {
                    val callee = ResolvedCallable.Source(callName, "$nestedDotted/$arity", emptyList(), isConstructor = true)
                    return RNode.Call(callee, DispatchKind.CONSTRUCTOR, null, lowerArgs(call), csk(call.textRange.startOffset), span(call))
                }
                if (service.isKnownType(nestedDotted) && !service.isObject(nestedDotted)) {
                    val ctor = ResolvedCallable.Library(callName, "$outerFqn\$$callName", "<init>", List(arity) { null }, isStatic = false, isConstructor = true, isInline = false)
                    return RNode.Call(ctor, DispatchKind.CONSTRUCTOR, null, lowerArgs(call), csk(call.textRange.startOffset), span(call))
                }
            }
            // A bare capitalized call the editor resolver didn't surface a constructor for. A source class
            // (e.g. one with an implicit no-arg constructor) builds a [SourceObject]; otherwise a stdlib/library
            // type (`IllegalArgumentException("x")`) is instantiated reflectively.
            if (receiverNode == null && callName != null && callName.first().isUpperCase()) {
                fileClasses[callName]?.takeIf { it.flavor == ClassFlavor.CLASS }?.let { sc ->
                    val callee = ResolvedCallable.Source(callName, "${sc.fqn}/$arity", emptyList(), isConstructor = true)
                    return RNode.Call(callee, DispatchKind.CONSTRUCTOR, null, lowerArgs(call), csk(call.textRange.startOffset), span(call))
                }
                runCatching { service.resolveTypeName(callName, resolver.fileContext) }.getOrNull()?.let { typeFqn ->
                    // Only fabricate a reflective constructor when there's POSITIVE evidence the name is a
                    // constructible type: a known/loadable type, or a name being THROWN
                    // (`throw IllegalArgumentException("x")` — a stdlib exception the resolver couldn't qualify,
                    // loaded via `java.lang` at runtime). An unresolved capitalized call without those is almost
                    // always a library FUNCTION not on the index (a Compose composable like `Text`/`SuggestionChip`);
                    // fabricating a `Text()` constructor crashes the running composition with "cannot load class".
                    // (Do NOT treat a dotted [typeFqn] as evidence: `resolveTypeName` import-qualifies a bare
                    // function name too, e.g. `Text` -> `androidx.compose.material3.Text`, so dots prove nothing.)
                    val constructible = service.isKnownType(typeFqn) || call.parent is KtThrowExpression
                    if (constructible) {
                        val ctor = ResolvedCallable.Library(callName, typeFqn, "<init>", List(arity) { null }, isStatic = false, isConstructor = true, isInline = false)
                        return RNode.Call(ctor, DispatchKind.CONSTRUCTOR, null, lowerArgs(call), csk(call.textRange.startOffset), span(call))
                    }
                }
            }
            return unsupported(callDiagnostic(call), call)
        }
        val args = lowerArgs(call)
        val key = csk(call.textRange.startOffset)
        val callee = toCallable(chosen)
        if (chosen.isExtension) {
            // A MEMBER extension of an in-scope receiver scope (`RowScope.weight`, declared inside `RowScope`)
            // dispatches ON that scope instance, with the explicit/implicit `Modifier` as its extension receiver
            // — `scopeMemberExtensions` is what surfaced it, so the scope IS in scope here.
            val dispatchScope = chosen.declaringClassFqn?.let { findScopeReceiver(it) }
            if (dispatchScope != null) {
                val extReceiver = receiverNode ?: chosen.receiverTypeFqn?.let { findScopeReceiver(it) }
                if (extReceiver != null) {
                    return RNode.Call(callee, DispatchKind.MEMBER_EXTENSION, extReceiver, args, key, span(call), dispatchReceiver = dispatchScope)
                }
            }
            // A bare extension call resolves against an implicit receiver (`itemsIndexed(...)` on the
            // `LazyListScope` the `LazyColumn { }` lambda provides) — that scope is the extension receiver.
            if (receiverNode == null) {
                chosen.receiverTypeFqn?.let { findScopeReceiver(it) }?.let { extReceiver ->
                    return RNode.Call(callee, DispatchKind.EXTENSION, extReceiver, args, key, span(call))
                }
            }
            return RNode.Call(callee, DispatchKind.EXTENSION, receiverNode, args, key, span(call))
        }
        // A bare call to a MEMBER of an in-scope implicit receiver — `item`/`items(count, …)` are MEMBERS of
        // the `LazyListScope` interface a `LazyColumn { }` lambda provides (NOT extensions), so they dispatch
        // ON that scope instance. Without this they fall to TOP_LEVEL with a null receiver, and the member's
        // `$default` synthetic gets a null `$this` → `item$default(null, …)` NPE. (A genuine top-level call's
        // declaring class is a `…Kt` facade, never an in-scope receiver, so it stays TOP_LEVEL.)
        if (receiverNode == null && chosen.kind == SymbolKind.METHOD) {
            chosen.declaringClassFqn?.let { findScopeReceiver(it) }?.let { scope ->
                return RNode.Call(callee, DispatchKind.MEMBER, scope, args, key, span(call))
            }
        }
        val dispatch = when {
            chosen.kind == SymbolKind.CONSTRUCTOR -> DispatchKind.CONSTRUCTOR
            receiverNode != null -> DispatchKind.MEMBER
            else -> DispatchKind.TOP_LEVEL
        }
        return RNode.Call(callee, dispatch, receiverNode, args, key, span(call))
    }

    /**
     * A field-debuggable reason for an unresolved/ambiguous call: the callee name, how many candidates the
     * resolver found (`candidates=0` ⇒ nothing in scope / not on the indexed classpath; a positive count that
     * still didn't narrow ⇒ a genuine ambiguity or arg-type mismatch), and — for a `recv.foo()` call — the
     * inferred receiver type (`recv=…`, absent when it couldn't be inferred). Surfaced through the preview's
     * lowering diagnostics so a device failure names the exact gap instead of a bare "unresolved".
     */
    private fun callDiagnostic(call: KtCallExpression): String {
        val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: "?"
        val n = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList()).size
        val recv = (call.parent as? KtDotQualifiedExpression)?.takeIf { it.selectorExpression === call }
            ?.let { runCatching { resolver.inferType(it.receiverExpression)?.qualifiedName }.getOrNull() }
        return "unresolved/ambiguous call `$name` (candidates=$n${recv?.let { ", recv=$it" } ?: ""})"
    }

    private fun ifNode(e: KtIfExpression): RNode {
        val condExpr = e.condition ?: return unsupported("if without condition", e)
        val cond = lower(condExpr)
        // Smart-cast: `if (x is T) { x.member }` resolves `x`'s members against `T` in the then-branch (and the
        // else-branch of an `if (x !is T)`). The condition is lowered first, unnarrowed.
        val then = e.then?.let { withNarrowing(conditionNarrowings(condExpr, whenTrue = true)) { lower(it) } }
            ?: return unsupported("if without body", e)
        val otherwise = e.`else`?.let { withNarrowing(conditionNarrowings(condExpr, whenTrue = false)) { lower(it) } }
        return RNode.If(cond, then, otherwise, span(e))
    }

    /** Run [block] with a smart-cast narrowing scope active on the resolver (so member/property resolution
     *  inside sees the narrowed types), balanced on exit. A no-op for an empty narrowing. */
    private inline fun <R> withNarrowing(narrowed: Map<String, KotlinType>, block: () -> R): R {
        if (narrowed.isEmpty()) return block()
        resolver.pushNarrowing(narrowed)
        try { return block() } finally { resolver.popNarrowing() }
    }

    /** The smart-cast narrowings (`name → type`) that hold when [cond] is [whenTrue]: an `x is T` on a simple
     *  name narrows `x` to `T`; `&&` conjoins both sides' true-narrowings, `||` both sides' false-narrowings; a
     *  `!is` (or the false branch) flips which side narrows. Only simple-name receivers narrow — sound for code
     *  that compiles, since the interpreter dispatches on the runtime class regardless. */
    private fun conditionNarrowings(cond: KtExpression?, whenTrue: Boolean): Map<String, KotlinType> =
        when (val c = unwrapParens(cond)) {
            is KtIsExpression -> {
                val name = (c.leftHandSide as? KtNameReferenceExpression)?.getReferencedName()
                if (name != null && whenTrue != c.isNegated) narrowingTo(name, c.typeReference?.text) else emptyMap()
            }
            is KtBinaryExpression -> when (c.operationToken) {
                KtTokens.ANDAND -> if (whenTrue) conditionNarrowings(c.left, true) + conditionNarrowings(c.right, true) else emptyMap()
                KtTokens.OROR -> if (!whenTrue) conditionNarrowings(c.left, false) + conditionNarrowings(c.right, false) else emptyMap()
                else -> emptyMap()
            }
            else -> emptyMap()
        }

    /** Narrow [name] to the (generic-erased, non-null) classifier named by [typeText]; empty if it won't resolve. */
    private fun narrowingTo(name: String, typeText: String?): Map<String, KotlinType> {
        val text = typeText?.substringBefore('<')?.removeSuffix("?")?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        val t = runCatching { service.typeFromText(text, resolver.fileContext) }.getOrNull() ?: return emptyMap()
        return mapOf(name to t)
    }

    private fun unwrapParens(e: KtExpression?): KtExpression? =
        if (e is KtParenthesizedExpression) unwrapParens(e.expression) else e

    private fun binaryNode(e: KtBinaryExpression): RNode {
        val left = e.left ?: return unsupported("binary without lhs", e)
        val right = e.right ?: return unsupported("binary without rhs", e)
        val token = e.operationToken
        if (token == KtTokens.EQ) {
            // Indexed assignment (`xs[i] = v`, the `set` operator) lowers to the receiver's `set(index…, value)`
            // member — the write mirror of [arrayAccessNode]'s `get`.
            if (left is KtArrayAccessExpression) return indexedSetNode(left, right, e)
            // The LHS is a local/param (`i = …` → Assign) or a property (`count.value = …`, a `MutableState`, or a
            // `by`-delegated local — both already lowered to a PropertyGet → write through its setter via PropertySet).
            val lhs = lower(left)
            return when (lhs) {
                is RNode.Unsupported -> lhs
                is RNode.PropertyGet -> RNode.PropertySet(lhs.receiver, lhs.binding, lower(right), span(e))
                else -> RNode.Assign(lhs, lower(right), span(e))
            }
        }
        // Augmented assignment (`count += 1`, `total -= n`) → read-modify-write `a = a.op(b)`: an Assign for a
        // local/param, a PropertySet for a property (a member, a `by`-delegated `MutableState`, `count.value`) so
        // the write drives recomposition. Mirrors the `++`/`--` path in [incDecNode]. (Kotlin's `plusAssign`
        // in-place form is not modeled; the read-modify-write covers numbers/strings, the common state case.)
        AUGMENTED[token]?.let { op ->
            val read = lower(left)
            if (read is RNode.Unsupported) return read
            val rhs = lower(right)
            if (rhs is RNode.Unsupported) return rhs
            val combined = RNode.Call(synthOperator(op), DispatchKind.OPERATOR, read, listOf(RArg(rhs)), csk(e.textRange.startOffset), span(e))
            return when (read) {
                is RNode.Name -> RNode.Assign(read, combined, span(e))
                is RNode.PropertyGet -> RNode.PropertySet(read.receiver, read.binding, combined, span(e))
                else -> unsupported("augmented-assignment target", e)
            }
        }
        // `a && b` / `a || b` → a short-circuiting `if` (the RHS isn't evaluated when the LHS already decides
        // the result). The RHS is lowered under the LHS's smart-cast narrowing, so `x is T && x.member` and
        // `x !is T || x.member` resolve `x`'s `T`-members in the RHS.
        if (token == KtTokens.ANDAND || token == KtTokens.OROR) {
            val span = span(e)
            val lhs = lower(left)
            val and = token == KtTokens.ANDAND
            val rhs = withNarrowing(conditionNarrowings(left, whenTrue = and)) { lower(right) }
            return if (and) RNode.If(lhs, rhs, RNode.Const(false, boolType, span), span)
            else RNode.If(lhs, RNode.Const(true, boolType, span), rhs, span)
        }
        val key = csk(e.textRange.startOffset)
        // `a ?: b` → `a.let { t -> if (t != null) t else b }`, lowered with a temp local (evaluate `a` once).
        if (token == KtTokens.ELVIS) {
            val span = span(e)
            val tmpSlot = newSlot()
            val tmpRef = { RNode.Name(Binding.Local(tmpSlot, "\$elvis", mutable = false), span) }
            val cond = RNode.Call(synthOperator("ne"), DispatchKind.OPERATOR, tmpRef(), listOf(RArg(RNode.Const(null, null, span))), key, span)
            val ifNode = RNode.If(cond, tmpRef(), lower(right), span)
            return RNode.Block(listOf(RNode.LocalVar(tmpSlot, "\$elvis", mutable = false, lower(left), span), ifNode), isExpression = true, span)
        }
        // `a in c` / `a !in c` → `c.contains(a)` (reflective on the runtime receiver / source-member dispatch).
        if (token == KtTokens.IN_KEYWORD || token == KtTokens.NOT_IN) {
            val contains = RNode.Call(synthMember("contains"), DispatchKind.MEMBER, lower(right), listOf(RArg(lower(left))), key, span(e))
            return if (token == KtTokens.NOT_IN) negate(contains, span(e)) else contains
        }
        // `a..b` → a range. The integral/char element types have a modeled range CONSTRUCTED directly (their
        // member `rangeTo` is a primitive member the reflective interpreter can't invoke); every other element
        // type — `Float`/`Double` (`ClosedFloatingPointRange`, e.g. a Slider's `valueRange = 0f..50f`) or a user
        // `Comparable` — resolves through its in-scope `rangeTo` EXTENSION (`RangesKt.rangeTo(a, b)`), which is
        // how Kotlin declares those.
        if (token == KtTokens.RANGE) {
            val lt = runCatching { resolver.inferType(left) }.getOrNull()
            val lowLeft = lower(left)
            val lowRight = lower(right)
            fun rangeCtor(fqn: String) = RNode.Call(
                ResolvedCallable.Library(fqn.substringAfterLast('.'), fqn, "<init>", listOf(null, null), isStatic = false, isConstructor = true, isInline = false),
                DispatchKind.CONSTRUCTOR, null, listOf(RArg(lowLeft), RArg(lowRight)), key, span(e),
            )
            when (lt?.qualifiedName) {
                null, "kotlin.Int" -> return rangeCtor("kotlin.ranges.IntRange") // null = the common `0..n`
                "kotlin.Long" -> return rangeCtor("kotlin.ranges.LongRange")
                "kotlin.Char" -> return rangeCtor("kotlin.ranges.CharRange")
            }
            val elem = lt!!.qualifiedName
            service.extensionsFor(elem, lt.typeArguments, "rangeTo")
                .firstOrNull { it.name == "rangeTo" && it.kind == SymbolKind.METHOD && it.paramTypes.size == 1 && extensionInScope(it) }
                ?.let { return RNode.Call(toCallable(it), DispatchKind.EXTENSION, lowLeft, listOf(RArg(lowRight)), key, span(e)) }
            service.membersNamed(elem, lt.typeArguments, "rangeTo")
                .firstOrNull { it.kind == SymbolKind.METHOD && !it.isExtension && it.paramTypes.size == 1 && it.declaringClassFqn != null }
                ?.let { return RNode.Call(toCallable(it), DispatchKind.MEMBER, lowLeft, listOf(RArg(lowRight)), key, span(e)) }
            return unsupported("range over $elem", e)
        }
        // Comparison / equality desugar to an OPERATOR call the interpreter evaluates intrinsically (the
        // callee is synthetic — these need no library method to invoke).
        (COMPARISON[token] ?: EQUALITY[token])?.let { op ->
            return RNode.Call(
                synthOperator(op), DispatchKind.OPERATOR, lower(left), listOf(RArg(lower(right))), key, span(e),
            )
        }
        // An infix function call (`a to b`, `1 shl 2`, `x downTo y`): the operation token is an IDENTIFIER and
        // the operation reference is the function name. It desugars to `a.name(b)` — a member or an in-scope
        // extension of the left operand's type.
        if (token == KtTokens.IDENTIFIER) return infixNode(e, left, right, key)
        val convention = ARITHMETIC[token] ?: return unsupported("operator ${e.operationReference.text}", e)
        val leftType = resolver.inferType(left)
        // String concatenation (no `String.plus` member is reliably in the index), or an UNKNOWN left type
        // (e.g. an enum-entry property like `Color.RED.name`): lower to a synthetic OPERATOR call the
        // interpreter resolves at run time by the actual value — numeric arithmetic or string concat — rather
        // than rejecting it here.
        if (leftType == null || (token == KtTokens.PLUS && leftType.qualifiedName == "kotlin.String")) {
            return RNode.Call(
                synthOperator(convention), DispatchKind.OPERATOR, lower(left), listOf(RArg(lower(right))),
                csk(e.textRange.startOffset), span(e),
            )
        }
        // Member-first (`Int.plus`, `BigDecimal.plus`): a single-param MEMBER operator → an OPERATOR call on the
        // receiver. Failing that, an in-scope single-param EXTENSION operator (`List.plus`/`Set.plus`/`Map.plus`
        // are stdlib `Collection<T>.plus(T)` extensions, NOT members) → an EXTENSION call (a static facade method
        // taking the receiver first). Mirrors [infixNode]; without the extension branch a `list + x` would be
        // dispatched as a non-existent instance `plus` on the collection.
        val leftFqn = leftType.qualifiedName
        service.membersNamed(leftFqn, leftType.typeArguments, convention)
            .firstOrNull { it.kind == SymbolKind.METHOD && !it.isExtension && it.paramTypes.size == 1 }
            ?.let { return RNode.Call(toCallable(it), DispatchKind.OPERATOR, lower(left), listOf(RArg(lower(right))), key, span(e)) }
        service.extensionsFor(leftFqn, leftType.typeArguments, convention)
            .firstOrNull { it.name == convention && it.kind == SymbolKind.METHOD && it.paramTypes.size == 1 && extensionInScope(it) }
            ?.let { return RNode.Call(toCallable(it), DispatchKind.EXTENSION, lower(left), listOf(RArg(lower(right))), key, span(e)) }
        return unsupported("no `$convention` on $leftFqn", e)
    }

    /**
     * `a NAME b` — an infix function call. Kotlin parses it as a binary expression whose operation token is an
     * IDENTIFIER; it means exactly `a.NAME(b)`. The callee is a single-parameter MEMBER of the left operand's
     * type (Kotlin's member-first rule) or, failing that, an in-scope single-parameter EXTENSION on it
     * (`1 to 2`, `0 until n`, `n downTo 1`, `x shl 2` — `to`/`until`/`downTo` are stdlib extensions, the bit
     * ops are `Int`/`Long` members). The receiver type must be known and the function must resolve, else an
     * honest Unsupported — we never fabricate a callee for an infix we can't bind (soundness, like every other
     * call path here). A source-declared infix member resolves through `membersNamed` like any other member.
     */
    private fun infixNode(e: KtBinaryExpression, left: KtExpression, right: KtExpression, key: CallSiteKey): RNode {
        val name = e.operationReference.getReferencedName()
        val leftType = runCatching { resolver.inferType(left) }.getOrNull()
            ?: return unsupported("infix `$name` on an unknown receiver type", e)
        val recv = lower(left)
        if (recv is RNode.Unsupported) return recv
        val args = listOf(RArg(lower(right)))
        // Member-first: a single-param method named [name] declared on (or inherited by) the left type.
        service.membersNamed(leftType.qualifiedName, leftType.typeArguments, name)
            .firstOrNull { it.kind == SymbolKind.METHOD && !it.isExtension && it.paramTypes.size == 1 }
            ?.let { return RNode.Call(toCallable(it), DispatchKind.MEMBER, recv, args, key, span(e)) }
        // Else an in-scope extension on the left type (imported or default-imported, like `to`/`until`).
        service.extensionsFor(leftType.qualifiedName, leftType.typeArguments, name)
            .firstOrNull { it.name == name && it.kind == SymbolKind.METHOD && it.paramTypes.size == 1 && extensionInScope(it) }
            ?.let { return RNode.Call(toCallable(it), DispatchKind.EXTENSION, recv, args, key, span(e)) }
        return unsupported("unresolved infix function `$name` on ${leftType.qualifiedName}", e)
    }

    /**
     * `xs[i]` → a MEMBER call of the receiver's `get(index…)` operator (`items[selectedItem]` →
     * `items.get(selectedItem)`). The receiver type drives callee selection (the indexed-access operator is a
     * plain member the interpreter invokes reflectively). Indexed *assignment* (`xs[i] = v`, the `set` operator)
     * is handled (rejected) in [binaryNode]; here we only read. Unknown receiver type / no matching `get` → Unsupported.
     */
    private fun arrayAccessNode(e: KtArrayAccessExpression): RNode {
        val arrayExpr = e.arrayExpression ?: return unsupported("indexed access without an array expression", e)
        val receiver = lower(arrayExpr)
        if (receiver is RNode.Unsupported) return receiver
        val recvType = runCatching { resolver.inferType(arrayExpr) }.getOrNull()
            ?: return unsupported("indexed access on an unknown type", e)
        val indices = e.indexExpressions
        val get = service.membersOf(recvType.qualifiedName, recvType.typeArguments, null)
            .filterIsInstance<KotlinSymbol>()
            .firstOrNull { it.name == "get" && it.kind == SymbolKind.METHOD && it.paramTypes.size == indices.size }
            ?: return unsupported("no `get` operator (arity ${indices.size}) on ${recvType.qualifiedName}", e)
        val args = indices.map { RArg(lower(it)) }
        return RNode.Call(toCallable(get), DispatchKind.MEMBER, receiver, args, csk(e.textRange.startOffset), span(e))
    }

    /**
     * `xs[i] = v` → a MEMBER call of the receiver's `set(index…, value)` operator (`cells[index] = current` →
     * `cells.set(index, current)`). The write mirror of [arrayAccessNode]: the receiver type drives callee
     * selection and `set` takes the index expression(s) with the value as its LAST argument (arity =
     * indices + 1). Unknown receiver type / no matching `set` → Unsupported.
     */
    private fun indexedSetNode(lhs: KtArrayAccessExpression, valueExpr: KtExpression, e: KtBinaryExpression): RNode {
        val arrayExpr = lhs.arrayExpression ?: return unsupported("indexed assignment without an array expression", e)
        val receiver = lower(arrayExpr)
        if (receiver is RNode.Unsupported) return receiver
        val recvType = runCatching { resolver.inferType(arrayExpr) }.getOrNull()
            ?: return unsupported("indexed assignment on an unknown type", e)
        val indices = lhs.indexExpressions
        val set = service.membersOf(recvType.qualifiedName, recvType.typeArguments, null)
            .filterIsInstance<KotlinSymbol>()
            .firstOrNull { it.name == "set" && it.kind == SymbolKind.METHOD && it.paramTypes.size == indices.size + 1 }
            ?: return unsupported("no `set` operator (arity ${indices.size + 1}) on ${recvType.qualifiedName}", e)
        val args = indices.map { RArg(lower(it)) } + RArg(lower(valueExpr))
        return RNode.Call(toCallable(set), DispatchKind.MEMBER, receiver, args, csk(e.textRange.startOffset), span(e))
    }

    /**
     * The modeled unary expressions:
     * - `t++`/`++t`/`t--`/`--t` → a read-modify-write of `t` — a local/param (`i++` → `i = i + 1`, an
     *   [RNode.Assign]) or a property (`count.value++` → an [RNode.PropertySet], covering a `MutableState`),
     *   using the intrinsic numeric `plus`/`minus`. Statement-position result only.
     * - `x!!` → [RNode.NotNull] (NPE if null).
     * - `!x` → `if (x) false else true`; unary `-x` → `0 - x` (the intrinsic numeric operator); unary `+x` → x.
     */
    private fun incDecNode(e: KtUnaryExpression): RNode {
        val base = e.baseExpression ?: return unsupported("unary without operand", e)
        val span = span(e)
        when (e.operationToken) {
            KtTokens.EXCLEXCL -> {
                val v = lower(base); if (v is RNode.Unsupported) return v
                return RNode.NotNull(v, span)
            }
            KtTokens.EXCL -> {
                val v = lower(base); if (v is RNode.Unsupported) return v
                return negate(v, span)
            }
            KtTokens.MINUS -> {
                val v = lower(base); if (v is RNode.Unsupported) return v
                val zero = RNode.Const(0, service.typeByFqn("kotlin.Int"), span)
                return RNode.Call(synthOperator("minus"), DispatchKind.OPERATOR, zero, listOf(RArg(v)), csk(span.start), span)
            }
            KtTokens.PLUS -> return lower(base) // unary plus is identity
        }
        val op = when (e.operationToken) {
            KtTokens.PLUSPLUS -> "plus"
            KtTokens.MINUSMINUS -> "minus"
            else -> return unsupported("unary operator ${e.operationReference.text}", e)
        }
        val read = lower(base)
        if (read is RNode.Unsupported) return read
        val one = RNode.Const(1, service.typeByFqn("kotlin.Int"), span)
        val bumped = RNode.Call(synthOperator(op), DispatchKind.OPERATOR, read, listOf(RArg(one)), csk(span.start), span)
        return when (read) {
            is RNode.Name -> RNode.Assign(read, bumped, span)
            is RNode.PropertyGet -> RNode.PropertySet(read.receiver, read.binding, bumped, span)
            else -> unsupported("increment/decrement target", e)
        }
    }

    private fun synthOperator(name: String) = ResolvedCallable.Library(
        displayName = name, ownerFqn = null, methodName = name, paramTypes = emptyList(),
        isStatic = false, isConstructor = false, isInline = false,
    )

    /** A synthetic MEMBER callee invoked reflectively on its runtime receiver by name (`contains`, `componentN`)
     *  — or routed to the interpreter's source-member dispatch when the receiver is a [SourceObject]. */
    private fun synthMember(name: String) = ResolvedCallable.Library(
        displayName = name, ownerFqn = null, methodName = name, paramTypes = emptyList(),
        isStatic = false, isConstructor = false, isInline = false,
    )

    private fun whileNode(e: org.jetbrains.kotlin.psi.KtWhileExpressionBase, doWhile: Boolean): RNode {
        val cond = e.condition?.let { lower(it) } ?: return unsupported("while without condition", e)
        val body = e.body?.let { lower(it) } ?: emptyBlock(e)
        return RNode.While(cond, body, doWhile, span(e))
    }

    private fun forNode(e: KtForExpression): RNode {
        val lp = e.loopParameter ?: return unsupported("for without a loop variable (destructuring?)", e)
        val iterable = e.loopRange?.let { lower(it) } ?: return unsupported("for without an iterable", e)
        if (iterable is RNode.Unsupported) return iterable
        val name = lp.name ?: "_"
        scopes.addLast(HashMap())
        val slot = newSlot()
        bind(name, Binding.Local(slot, name, mutable = false))
        val body = e.body?.let { lower(it) } ?: emptyBlock(e)
        scopes.removeLast()
        // The iterator/hasNext/next conventions are reflected on the runtime value by the interpreter.
        return RNode.ForEach(RParam(slot, name, service.typeFromText(lp.typeReference?.text, resolver.fileContext)), iterable, null, null, null, body, span(e))
    }

    private fun localVarNode(p: KtProperty): RNode {
        val name = p.name ?: "_"
        val slot = newSlot()
        val delegate = p.delegateExpression
        if (delegate != null) {
            // `val/var x by <delegate>` requires the delegate's `getValue` (and `setValue` for a `var`)
            // operator to be in scope — for Compose's `MutableState` these are extensions in
            // `androidx.compose.runtime` (`val text by remember { mutableStateOf(0) }` needs `import
            // androidx.compose.runtime.getValue`). Without it the code doesn't compile, so the preview must
            // surface the gap rather than silently read `.value` (which the interpreter could do regardless).
            val missingOps = runCatching { resolver.missingDelegateOperators(p) }.getOrDefault(emptyList())
            if (missingOps.isNotEmpty()) {
                return unsupported("property delegate operator(s) ${missingOps.joinToString(", ")} not in scope (import them)", p)
            }
            // The slot holds the DELEGATE object; reads/writes of `x` go through its `.value` (the State/
            // MutableState/Lazy convention). Any other delegate is Unsupported (sound: we don't model an
            // arbitrary getValue/setValue convention).
            val valueProperty = delegateValueProperty(delegate)
                ?: return unsupported("property delegate is not a `.value` delegate (State/Lazy)", p)
            val delegateNode = lower(delegate)
            if (delegateNode is RNode.Unsupported) return delegateNode
            bind(name, Binding.DelegatedLocal(slot, name, p.isVar, valueProperty)) // AFTER the delegate is lowered
            return RNode.LocalVar(slot, name, p.isVar, delegateNode, span(p))
        }
        val initializer = p.initializer?.let { lower(it) }
        bind(name, Binding.Local(slot, name, p.isVar)) // registered AFTER the initializer is lowered
        return RNode.LocalVar(slot, name, p.isVar, initializer, span(p))
    }

    /** The `.value` property a `by`-delegate is read/written through, or null if the delegate's type exposes
     *  no `value` member — i.e. it is not a State/MutableState/Lazy-style delegate (the only delegates whose
     *  `getValue`/`setValue` convention forwards to `.value`). Requiring an actual `value` member keeps this
     *  sound: a `Delegates.observable`/`notNull` delegate (no `.value`) correctly falls through to Unsupported. */
    private fun delegateValueProperty(delegate: KtExpression): Binding.Property? {
        val dt = runCatching { resolver.inferType(delegate) }.getOrNull() ?: return null
        val hasValue = runCatching {
            service.membersForCompletion(dt.qualifiedName, dt.typeArguments, "value")
                .any { it.name == "value" && it.kind == SymbolKind.FIELD }
        }.getOrDefault(false)
        if (!hasValue) return null
        return Binding.Property("value", dt.qualifiedName, backingField = false)
    }

    private fun lambdaNode(e: KtLambdaExpression): RNode {
        scopes.addLast(HashMap())
        // A receiver lambda's implicit `this` arrives as the lambda's LEADING argument at runtime (the Compose
        // bridge / a plain proxy passes the scope first), BEFORE any explicit value parameters. So a receiver
        // lambda binds a leading `<this>` slot whether or not it also declares value params — e.g. `itemsIndexed`'s
        // `LazyItemScope.(index, item) -> Unit` gives `[<this>, index, item]`, so `{ i, todo -> }` binds `todo` to
        // the ITEM (arg 2), not the index (arg 1). Missing the receiver shifts every explicit param by one.
        val receiverType = runCatching { resolver.lambdaReceiverType(e) }.getOrNull()
        var pushedReceiver = false
        val params = buildList {
            if (receiverType != null) {
                val slot = newSlot()
                receiverScopes.addLast(ReceiverScope(slot, receiverType)); pushedReceiver = true
                add(RParam(slot, "<this>", receiverType))
            }
            if (e.valueParameters.isNotEmpty()) {
                e.valueParameters.forEach { p ->
                    val slot = newSlot()
                    val name = p.name ?: "_"
                    bind(name, Binding.Local(slot, name, mutable = false))
                    add(RParam(slot, name, service.typeFromText(p.typeReference?.text, resolver.fileContext)))
                }
            } else if (receiverType == null) {
                // The implicit `it` (harmless when unused).
                val slot = newSlot()
                bind("it", Binding.Local(slot, "it", mutable = false))
                add(RParam(slot, "it", null))
            }
        }
        val body = e.bodyExpression?.let { lowerBlock(it) } ?: emptyBlock(e)
        if (pushedReceiver) receiverScopes.removeLast()
        scopes.removeLast()
        return RNode.Lambda(params, body, captures = emptyList(), source = span(e))
    }

    private fun lowerBlock(block: KtBlockExpression): RNode {
        scopes.addLast(HashMap())
        val statements = block.statements.map { lower(it) }
        scopes.removeLast()
        return RNode.Block(statements, isExpression = false, span(block))
    }

    // --- callee selection (sound: never guess between live overloads) ---

    /** The owner (declaring facade, else package) of a genuine coroutine/frame suspend intrinsic candidate for
     *  [name] on [call] — from `kotlinx.coroutines` (delay/yield/withContext/…) or `androidx.compose.runtime`
     *  (withFrameNanos/withFrameMillis) — or null when none matches (so a same-named user function isn't
     *  canonicalized to the interpreter intrinsic). Any such prefixed owner makes the interpreter's gate fire. */
    private fun coroutineIntrinsicOwner(call: KtCallExpression, name: String): String? =
        runCatching { resolver.callTargets(call) }.getOrDefault(emptyList()).firstNotNullOfOrNull { s ->
            if (s.name != name) return@firstNotNullOfOrNull null
            val pkgs = listOf("kotlinx.coroutines", "androidx.compose.runtime")
            when {
                pkgs.any { s.declaringClassFqn?.startsWith(it) == true } -> s.declaringClassFqn
                s.packageName in pkgs -> s.packageName
                else -> null
            }
        }

    /** Whether [call] (named [name]) is a genuine `kotlinx.coroutines.flow` `collect`/`collectLatest` — a
     *  candidate whose package or declaring facade is in the flow package. Gates canonicalizing the flow-collect
     *  bridge so a same-named user method isn't hijacked. */
    private fun isFlowCollectCall(call: KtCallExpression, name: String): Boolean =
        runCatching { resolver.callTargets(call) }.getOrDefault(emptyList()).any { s ->
            s.name == name && (s.packageName == "kotlinx.coroutines.flow" || s.declaringClassFqn?.startsWith("kotlinx.coroutines.flow") == true)
        }

    /** Whether [call] resolves to the Kotlin stdlib `forEach`/`forEachIndexed` inline extension over a
     *  collection/array (`CollectionsKt` for List/Set/Iterable, `ArraysKt` for arrays) — the gate for
     *  canonicalizing it to the interpreter's inline intrinsic. `MapsKt` is deliberately excluded (its entry
     *  lambda is usually destructured). Gated on a genuine kotlin.collections candidate so a same-named user
     *  extension isn't hijacked. */
    private fun isCollectionForEachCall(call: KtCallExpression, name: String): Boolean =
        runCatching { resolver.callTargets(call) }.getOrDefault(emptyList()).any { s ->
            s.name == name &&
                (s.declaringClassFqn == "kotlin.collections.CollectionsKt" || s.declaringClassFqn == "kotlin.collections.ArraysKt")
        }

    private fun chooseCallee(call: KtCallExpression): KotlinSymbol? {
        val raw = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
        if (raw.isEmpty()) return null
        // A TOP-LEVEL extension (`fun String.getSize()`, carrying its declaring package) resolves only when
        // it is actually in scope — imported, same-package, or default-imported. `callTargets` surfaces the
        // receiver type's extensions UNFILTERED (via `membersForCompletion`), which otherwise lets an
        // out-of-scope or wrong-receiver stdlib false positive (`kotlin.jvm.internal.PrimitiveSpreadBuilder`'s
        // `getSize`, keyed on `kotlin.Any`) win the overload tie-break over the real source extension — or
        // resolve at all where nothing legal exists. Member-extensions (`packageName == null`; resolved via
        // their in-scope receiver, e.g. `RowScope.weight`) are NOT import-gated and pass through unchanged.
        val inScope = raw.filter { !it.isExtension || it.packageName == null || extensionInScope(it) }
        // Fast path: the overwhelmingly common call resolves to a single (or zero) candidate. Return it without
        // building the signature-dedup key (a per-candidate string + param-type list) or running the
        // type-directed tie-break ladder below (and the inference it drives). Behaviour-preserving: with one
        // candidate every `ifEmpty` fallback in the ladder yields that same candidate anyway.
        if (inScope.size <= 1) return inScope.firstOrNull()
        // Dedup by SIGNATURE (kind + name + param types), IGNORING the declaring owner. A method with the
        // same signature from different owners is the same call shape: an override surfacing from both the
        // class and its supertype (`SnapshotStateList.add` + `MutableList.add`), the same callable present
        // twice (a stdlib jar on the classpath AND bundled), or a member extension declared on the same
        // receiver by sibling scopes (`RowScope.weight` + `ColumnScope.weight`, both on `Modifier`). Keying
        // on the owner left these as distinct candidates that could never narrow → a false ambiguity.
        // The vararg index IS part of the key: `listOf(element: T)` and `listOf(vararg elements: T)` both
        // decode to params `[T]`, so without it the dedup would merge them and DROP the vararg overload —
        // leaving `listOf("a", "b")` (which only the vararg accepts) unresolvable.
        val candidates = inScope
            .distinctBy { c -> c.kind.toString() + "/" + c.name + "/" + c.varargParamIndex + "/" + c.paramTypes.map { (it as? KotlinType)?.qualifiedName } }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.single()
        val valueArgs = call.valueArguments
        val argCount = valueArgs.size
        // A named argument (or an omitted default) means the source args don't line up 1:1 with the leading
        // declared params, so arity and type matching bind each arg to its DECLARED position by name first.
        val hasNamed = valueArgs.any { it.getArgumentName() != null }
        // A vararg callee accepts any arg count from its fixed-param count up — `mutableStateListOf("a","b")`
        // binds both strings to the one `vararg` param, so it matches even though `argCount != paramCount`.
        fun acceptsVararg(c: KotlinSymbol) = c.varargParamIndex in 0..argCount
        val byArity = if (hasNamed) {
            val usedNames = valueArgs.mapNotNull { it.getArgumentName()?.asName?.identifier }.toSet()
            candidates.filter { c -> (c.paramNames.size >= argCount && usedNames.all { it in c.paramNames }) || acceptsVararg(c) }
                .ifEmpty { candidates.filter { it.paramTypes.size >= argCount } }
                .ifEmpty { candidates }
        } else {
            candidates.filter { it.paramTypes.size == argCount || it.paramNames.size == argCount || acceptsVararg(it) }
                .ifEmpty { candidates.filter { it.paramTypes.isEmpty() && argCount == 0 } }
                .ifEmpty { candidates }
        }
        if (byArity.size == 1) return byArity.single()
        // Tie-break by argument types: keep candidates whose params accept the (inferred) argument types,
        // each arg compared against the param it binds to (named → by name, positional → by position).
        // Then drop overloads with an UNBOUND non-defaulted parameter (Kotlin applicability): Material3's
        // clickable `Card(onClick, …)`/`Button(onClick, …)` would otherwise win the positional most-complete
        // tie-break over the plain `Card { }`, leaving its required `onClick` null → a spuriously-clickable
        // preview node that NPEs on tap. Backs off to the unfiltered set if that would leave nothing (defaults
        // unknown for every candidate — Java bytecode / old cache), so a rejection is never guessed.
        val typed = byArity.filter { c -> argsBindable(c, valueArgs, exact = false) }
            .let { t -> t.filter { requiredParamsSatisfied(it, valueArgs) }.ifEmpty { t } }
        typed.singleOrNull()?.let { return it }
        // `typed` empties when no candidate's args bind — but for a BINARY (library) member overload set this is
        // usually the Java/Kotlin type-name divide, not a real no-match: a Java parameter is a JVM FQN
        // (`java.lang.String`) while the argument is a Kotlin type (`kotlin.String`), so `argsBindable` rejects
        // every overload of `Intent.putExtra(String, …)`. Defer such a set to the RUNTIME dispatcher (which
        // re-resolves the overload by the actual argument values) instead of tying out to "unresolved/ambiguous".
        if (typed.isEmpty()) return deferToRuntimeMember(byArity)
        // More than one applicable overload. Prefer the MOST SPECIFIC: candidates whose parameter types
        // EXACTLY match the (known) argument types (so `f(String)` wins over `f(Any)` for a String argument).
        val exact = typed.filter { c -> argsBindable(c, valueArgs, exact = true) }
        exact.singleOrNull()?.let { return it }
        // Kotlin specificity: a FIXED-arity overload whose parameter count EXACTLY matches the supplied args is
        // more specific than a vararg overload that merely absorbs them with zero varargs. `remember { }` is
        // `remember(calculation)`, NOT `remember(vararg keys, calculation)` — the latter would leave `keys`
        // unfilled (a null array passed to the runtime → `remember` NPEs on `keys.length`). Prefer a unique such
        // candidate before the size-based shim tie-break (a positional call only — a named call is handled below).
        if (!hasNamed) {
            (exact.ifEmpty { typed }).filter { it.varargParamIndex < 0 && it.paramTypes.size == argCount }
                .singleOrNull()?.let { return it }
        }
        // Still tied. For a NAMED-argument call prefer the SMALLEST applicable overload: the user named
        // specific parameters, and a larger overload usually just adds REQUIRED params they didn't mean — e.g.
        // Material3's `Card`/`Button` have a clickable overload whose extra leading `onClick` has no default, so
        // picking it would pass null for a non-null parameter. For a purely positional call keep the most
        // COMPLETE overload (binary-compat shim disambiguation, as `Material3.Text` keeps). Either way require
        // uniqueness — a genuine tie is rejected, never guessed.
        val pool = exact.ifEmpty { typed }
        val targetSize = if (hasNamed) pool.minOf { it.paramTypes.size } else pool.maxOf { it.paramTypes.size }
        val sized = pool.filter { it.paramTypes.size == targetSize }
        sized.singleOrNull()?.let { return it }
        // Still tied. A common cause is the SAME method surfacing from an override AND its supertype where the
        // supertype copy has less-resolved (null / type-parameter) parameter types — e.g. a `SnapshotStateList`
        // override of `add(String)` alongside the Kotlin built-in `MutableList.add(<unresolved>)`. They can't
        // dedup by signature (`String` ≠ null) and a null/`T` parameter "accepts" any argument, so neither wins
        // the type checks. Prefer the candidate whose parameters are the MOST concrete (fewest null / type-param
        // slots) — the real override — requiring uniqueness so a genuine tie is still rejected.
        fun concreteness(c: KotlinSymbol) = c.paramTypes.count { p -> (p as? KotlinType)?.isTypeParameter == false }
        val maxConcrete = sized.maxOfOrNull { concreteness(it) } ?: return null
        val mostConcrete = sized.filter { concreteness(it) == maxConcrete }
        mostConcrete.singleOrNull()?.let { return it }
        // Still tied, but the candidates may be INDISTINGUISHABLE FOR THIS CALL: every SUPPLIED argument binds
        // to a parameter of the same type in all of them — they differ only in parameters the call doesn't
        // supply (which take their defaults), so either renders identically. Pick the smallest deterministically.
        // (`SuggestionChip` has two overloads differing only in a defaulted param the user didn't pass — a
        // genuine overload set like `Icon(ImageVector)` vs `Icon(Painter)` is already narrowed by argument type
        // above, since the supplied arg binds to DIFFERENT types and `boundParamsAgree` would be false.)
        if (mostConcrete.size > 1 && mostConcrete.all { boundParamsAgree(it, mostConcrete.first(), valueArgs) }) {
            return mostConcrete.minByOrNull { it.paramTypes.size }
        }
        // Still tied. If every remaining candidate is the SAME callable surfacing from different sources or with
        // differently-erased parameters — same kind/name/arity/vararg, and each parameter slot pairwise
        // compatible (equal qualified names, or one side an unresolved type-parameter) — it is not a genuine
        // overload set (a generic stdlib `listOf(vararg T)` decoded as `Array<T>` in one source and `T` in
        // another, say). Pick the first deterministically. A genuine overload set — a slot with two DIFFERENT
        // concrete types (`Icon(ImageVector)` vs `Icon(Painter)`) — is NOT collapsed, so it stays rejected as
        // ambiguous until argument types narrow it.
        return mostConcrete.takeIf { pool2 -> pool2.all { sameCallableShape(it, pool2.first()) } }?.first()
            ?: deferToRuntimeMember(byArity)
    }

    /**
     * A tie among BINARY (library) overloads of the SAME method on the SAME owner that static resolution can't
     * narrow — typically a Java overload set (`Intent.putExtra(String, String)` / `(String, CharSequence)` /
     * `(String, Serializable)`) whose parameter types are JVM FQNs the argument's Kotlin type doesn't line up
     * with, or an overload set with no most-specific member. Every candidate shares owner + name, so the runtime
     * reflective dispatcher re-resolves the overload from the ACTUAL argument values (it looks a member up by
     * name + arg types and ignores the callee's declared parameter types). Return one deterministically and defer,
     * rather than failing the whole preview with an "unresolved/ambiguous call". Gated so a source / extension /
     * mixed-owner set — which the interpreter CANNOT re-resolve reflectively — is still rejected as before.
     */
    private fun deferToRuntimeMember(candidates: List<KotlinSymbol>): KotlinSymbol? {
        if (candidates.size < 2) return null
        val first = candidates.first()
        if (first.kind != SymbolKind.METHOD || first.isExtension) return null
        val owner = first.declaringClassFqn ?: return null
        val homogeneous = candidates.all {
            !it.origin.fromSource && !it.isExtension && it.kind == SymbolKind.METHOD &&
                it.name == first.name && it.declaringClassFqn == owner
        }
        return if (homogeneous) first else null
    }

    /** Whether [a] and [b] bind every SUPPLIED argument to a parameter of a compatible type — so the call site
     *  can't distinguish them (they differ only in parameters this call doesn't supply). Each arg's bound
     *  parameter (named → by name, positional/trailing-lambda → by position) is compared by qualified name, with
     *  a null / type-parameter slot treated as compatible. Used to collapse defaulted-param-only overload pairs
     *  (Material3's two `SuggestionChip`s) WITHOUT collapsing a genuine overload set (whose supplied arg binds to
     *  different concrete types, making this false). */
    private fun boundParamsAgree(a: KotlinSymbol, b: KotlinSymbol, valueArgs: List<KtValueArgument>): Boolean {
        val ia = bindIndices(a, valueArgs) ?: return false
        val ib = bindIndices(b, valueArgs) ?: return false
        return valueArgs.indices.all { i ->
            val pa = a.paramTypes.getOrNull(ia[i]) as? KotlinType
            val pb = b.paramTypes.getOrNull(ib[i]) as? KotlinType
            pa == null || pb == null || pa.isTypeParameter || pb.isTypeParameter || pa.qualifiedName == pb.qualifiedName
        }
    }

    /** Whether two candidates are the same callable shape (so they may be collapsed rather than read as an
     *  ambiguity): same kind, name, parameter count and vararg position, with each parameter slot pairwise
     *  compatible — equal qualified names, or at least one side an unresolved type-parameter / unknown type. */
    private fun sameCallableShape(a: KotlinSymbol, b: KotlinSymbol): Boolean {
        if (a.kind != b.kind || a.name != b.name) return false
        if (a.paramTypes.size != b.paramTypes.size) return false
        if (a.varargParamIndex != b.varargParamIndex) return false
        return a.paramTypes.indices.all { i ->
            val pa = a.paramTypes[i] as? KotlinType
            val pb = b.paramTypes[i] as? KotlinType
            pa == null || pb == null || pa.isTypeParameter || pb.isTypeParameter || pa.qualifiedName == pb.qualifiedName
        }
    }

    /** The declared parameter index each value argument binds to: a named arg by its name, a trailing lambda to
     *  the last parameter (Kotlin's trailing-lambda rule), the rest filled left-to-right. Null if a named arg
     *  doesn't match any declared parameter name (so the candidate can't accept this call). */
    private fun bindIndices(callee: KotlinSymbol, valueArgs: List<KtValueArgument>): IntArray? {
        val names = callee.paramNames
        val paramCount = maxOf(callee.paramTypes.size, names.size)
        val vararg = callee.varargParamIndex
        val trailingLambda = valueArgs.lastOrNull() is KtLambdaArgument
        val result = IntArray(valueArgs.size)
        var nextPositional = 0
        for ((i, va) in valueArgs.withIndex()) {
            val name = va.getArgumentName()?.asName?.identifier
            val idx = when {
                name != null -> names.indexOf(name).also { if (it < 0) return null }
                trailingLambda && i == valueArgs.lastIndex -> paramCount - 1
                // Once positional binding reaches the vararg parameter, every remaining positional arg binds to
                // it (a vararg absorbs the tail); `nextPositional` stops advancing.
                vararg in 0..nextPositional -> vararg
                else -> nextPositional++
            }
            if (idx !in 0 until paramCount) return null
            result[i] = idx
        }
        return result
    }

    /** Whether every declared parameter the call does NOT supply is optional — defaulted, the vararg, or (for a
     *  suspend/composable callee) a synthetic trailing param the ABI fills — Kotlin's applicability rule. An
     *  overload with an UNBOUND, non-defaulted value parameter is not a candidate for this call (e.g. Material3's
     *  clickable `Card(onClick, …)` for a plain `Card { }`: its required `onClick` is unfilled). Backs off
     *  (returns true) when defaults are UNKNOWN (`paramHasDefault` empty — Java bytecode / an old cache), so a
     *  rejection is never guessed. */
    private fun requiredParamsSatisfied(callee: KotlinSymbol, valueArgs: List<KtValueArgument>): Boolean {
        val defaults = callee.paramHasDefault
        if (defaults.isEmpty()) return true // unknown → never guess a rejection
        val bound = bindIndices(callee, valueArgs) ?: return false
        val boundSet = bound.toHashSet()
        return defaults.indices.all { i ->
            i in boundSet || i == callee.varargParamIndex || defaults[i]
        }
    }

    /** Whether every (inferred) argument type is assignable to — or, when [exact], equal to — the type of the
     *  parameter it binds to. Unknown arg/param types don't disqualify (we never guess a rejection). */
    private fun argsBindable(callee: KotlinSymbol, valueArgs: List<KtValueArgument>, exact: Boolean): Boolean {
        val indices = bindIndices(callee, valueArgs) ?: return false
        return valueArgs.indices.all { i ->
            // Inference is best-effort and on a deep chain can throw (a resolver gap); a throw must not crash the
            // whole function's lowering — degrade to "couldn't infer this arg" (null), which never disqualifies.
            val at = runCatching { valueArgs[i].getArgumentExpression()?.let(resolver::inferType) }.getOrNull()
            val pt = callee.paramTypes.getOrNull(indices[i]) as? KotlinType
            at == null || pt == null ||
                if (exact) pt.qualifiedName == at.qualifiedName
                // A type-parameter parameter (`listOf(element: T)`) accepts ANY argument — but `isAssignableFrom`
                // on a bare `T` classifier always says no (no supertype chain reaches "T"), which would
                // disqualify every generic overload the moment the argument type IS inferred. So a single-element
                // `listOf(x)` (where both `listOf(element: T)` and `listOf(vararg: T)` match arity) ties out to
                // Unsupported as soon as `x` has a known type (e.g. a project data class). Treat `T` as a wildcard
                // here; the fixed-arity-vs-vararg preference below still picks the element overload.
                else pt.isTypeParameter || runCatching { pt.isAssignableFrom(at) }.getOrDefault(true)
        }
    }

    private fun toCallable(sym: KotlinSymbol): ResolvedCallable {
        val isCtor = sym.kind == SymbolKind.CONSTRUCTOR
        if (sym.origin.fromSource) {
            val owner = sym.owner?.name ?: sym.packageName ?: ""
            return ResolvedCallable.Source(
                displayName = sym.name,
                declId = "$owner.${sym.name}/${sym.paramTypes.size}",
                paramNames = sym.paramNames,
                isConstructor = isCtor,
                isComposable = sym.isComposable,
            )
        }
        val ownerFqn = sym.declaringClassFqn ?: sym.packageName ?: sym.owner?.name
        return ResolvedCallable.Library(
            displayName = sym.name,
            ownerFqn = ownerFqn,
            methodName = if (isCtor) "<init>" else sym.name,
            paramTypes = sym.paramTypes.map { it as? KotlinType },
            isStatic = Modifier.STATIC in sym.modifiers,
            isConstructor = isCtor,
            isInline = sym.isInline,
            isComposable = sym.isComposable,
            descriptorPrecise = sym.declaringClassFqn != null,
            paramNames = sym.paramNames,
            varargParamIndex = sym.varargParamIndex,
        )
    }

    // --- helpers ---

    private val strType get() = service.typeByFqn("kotlin.String")
    private val boolType get() = service.typeByFqn("kotlin.Boolean")

    /** The coroutine/frame suspend functions the interpreter models as intrinsics (see [coroutineIntrinsicOwner]
     *  and the interpreter's `evalInlineIntrinsic`) — canonicalized to a coroutines/compose-runtime-owned Call so
     *  they run even when overload resolution is ambiguous or their owner isn't a facade. */
    private val COROUTINE_INTRINSICS = setOf(
        "delay", "yield", "withContext", "coroutineScope", "supervisorScope", "ensureActive",
        "withFrameNanos", "withFrameMillis",
    )

    /** `Flow.collect { }` terminal operators (inline extensions) the coroutine bridge drives. */
    private val FLOW_COLLECT_NAMES = setOf("collect", "collectLatest")

    /** The collection/array iteration inline HOFs the interpreter models as intrinsics (`evalInlineIntrinsic`)
     *  — canonicalized to a CollectionsKt-owned EXTENSION Call so a composable-emitting loop body renders. */
    private val COLLECTION_FOREACH_NAMES = setOf("forEach", "forEachIndexed")

    private val ARITHMETIC = mapOf(
        KtTokens.PLUS to "plus", KtTokens.MINUS to "minus", KtTokens.MUL to "times",
        KtTokens.DIV to "div", KtTokens.PERC to "rem",
    )
    /** Augmented-assignment tokens → the arithmetic operator of the read-modify-write they desugar to. */
    private val AUGMENTED = mapOf(
        KtTokens.PLUSEQ to "plus", KtTokens.MINUSEQ to "minus", KtTokens.MULTEQ to "times",
        KtTokens.DIVEQ to "div", KtTokens.PERCEQ to "rem",
    )
    private val COMPARISON = mapOf(
        KtTokens.LT to "lt", KtTokens.GT to "gt", KtTokens.LTEQ to "le", KtTokens.GTEQ to "ge",
    )
    private val EQUALITY = mapOf(KtTokens.EQEQ to "eq", KtTokens.EXCLEQ to "ne")

    private fun parseChar(t: String): Char? {
        val inner = t.removePrefix("'").removeSuffix("'")
        return when {
            inner.length == 1 -> inner[0]
            inner == "\\n" -> '\n'; inner == "\\t" -> '\t'; inner == "\\r" -> '\r'
            inner == "\\'" -> '\''; inner == "\\\\" -> '\\'
            else -> null
        }
    }

    /** A read of the delegate object held in [b]'s slot — the receiver of its `.value` get/set. */
    private fun delegateRef(b: Binding.DelegatedLocal, e: PsiElement): RNode =
        RNode.Name(Binding.Local(b.slot, b.name, b.mutable), span(e))

    private fun newSlot(): SlotId = SlotId(slotCounter++)
    private fun bind(name: String, binding: Binding) { scopes.last()[name] = binding }
    private fun resolveLocal(name: String): Binding? {
        for (i in scopes.indices.reversed()) scopes[i][name]?.let { return it }
        return null
    }

    /**
     * Record a lowering diagnostic for any NAMED argument whose name matches no parameter of any function the
     * [call] could resolve to (`colors(containerColor = …)` — a typo / wrong-version parameter). Without this the
     * dispatcher's [reorderNamedArgs] silently bails to POSITIONAL binding, producing a quietly-wrong render; the
     * diagnostic makes the preview report the bad parameter instead (it blocks the render, like any lowering gap).
     * Mirrors the editor's conservative `KotlinSourceAnalyzer.unknownNamedArguments` over the same `callTargets`
     * overload union: it backs off entirely when the target is uncertain — a member call whose receiver can't be
     * typed, a callee that resolves to nothing, or any candidate whose parameter names were stripped/synthetic —
     * so a valid preview is never blocked.
     */
    private fun checkNamedArguments(call: KtCallExpression) {
        val named = call.valueArguments.mapNotNull { it.getArgumentName() }
        if (named.isEmpty()) return
        // A member call we can't type → the target is unknown; don't risk a false positive.
        val parent = call.parent
        if (parent is KtQualifiedExpression && parent.selectorExpression === call &&
            runCatching { resolver.inferType(parent.receiverExpression) }.getOrNull() == null
        ) return
        val targets = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
        if (targets.isEmpty()) return
        // A target whose parameter NAMES are unavailable (count mismatch / synthetic / blank) makes the check
        // unsound: the actually-resolved overload's names may be unknowable. Skip the whole call then.
        if (targets.any { t ->
                t.paramTypes.isNotEmpty() &&
                    (t.paramNames.size != t.paramTypes.size || t.paramNames.any { it.isEmpty() || isSyntheticParamName(it) })
            }
        ) return
        val known = targets.flatMapTo(HashSet()) { it.paramNames.filter { n -> n.isNotEmpty() } }
        for (argName in named) {
            val id = argName.asName.identifier
            if (id in known) continue
            diagnostics += LoweringDiagnostic("Cannot find a parameter with this name: $id", span(argName.referenceExpression))
        }
    }

    /** ASM surfaces stripped Java parameters as `p0`, `p1`, … — useless as named arguments and not validatable. */
    private fun isSyntheticParamName(n: String): Boolean = n.length >= 2 && n[0] == 'p' && n.drop(1).all { it.isDigit() }

    private fun unsupported(reason: String, e: PsiElement): RNode.Unsupported {
        val span = span(e)
        diagnostics += LoweringDiagnostic(reason, span)
        return RNode.Unsupported(reason, e.text.take(80), span)
    }

    private fun emptyBlock(e: PsiElement) = RNode.Block(emptyList(), isExpression = false, span(e))
    private fun span(e: PsiElement): SourceSpan = SourceSpan(e.textRange.startOffset, e.textRange.endOffset)

    /** The source start offset of the lowering unit (function / property / class) currently being lowered —
     *  the base for EDIT-STABLE call-site keys. */
    private var currentUnitStart = 0

    /**
     * A Compose group key for a call at absolute source [offset], made relative to the enclosing lowering unit
     * ([currentUnitStart]). Editing one function shifts every offset AFTER it, which would re-key every call and
     * force the Compose runtime to discard and rebuild the whole preview (losing state); a FUNCTION-RELATIVE key
     * is unchanged when OTHER functions are edited, so the runtime reuses those groups' slots (their `remember`/
     * state survives the edit — the basis of incremental "live edit" re-rendering). Relative keys stay distinct
     * WITHIN a unit (offsets there are distinct), and cross-unit collisions are harmless — each unit's calls sit
     * in their own Compose group scope.
     */
    private fun csk(offset: Int): CallSiteKey = CallSiteKey(offset - currentUnitStart)

    private fun reset(unitStart: Int = 0) { scopes.clear(); slotCounter = 0; diagnostics.clear(); currentUnitStart = unitStart }
}
