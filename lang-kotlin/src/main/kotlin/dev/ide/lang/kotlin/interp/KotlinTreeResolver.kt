package dev.ide.lang.kotlin.interp

import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.symbols.DefaultImports
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
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
) {
    private val resolver = KotlinResolver(ktFile, parsed, service)

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
        for (i in receiverScopes.indices.reversed()) {
            val rs = receiverScopes[i]
            val matches = rs.type.qualifiedName == fqn ||
                runCatching { service.supertypesOf(rs.type.qualifiedName).any { (it as? KotlinType)?.qualifiedName == fqn } }.getOrDefault(false)
            if (matches) return RNode.Name(Binding.Local(rs.slot, rs.type.qualifiedName.substringAfterLast('.'), mutable = false), SourceSpan(0, 0))
        }
        return null
    }

    /** Lower the first top-level function in the file (test convenience). */
    fun lowerFirstFunction(): ResolvedFunction? =
        ktFile.declarations.filterIsInstance<KtNamedFunction>().firstOrNull()?.let { lowerFunction(it) }

    fun lowerFunction(fn: KtNamedFunction): ResolvedFunction {
        reset()
        scopes.addLast(HashMap())
        val params = fn.valueParameters.map { p ->
            val slot = newSlot()
            val name = p.name ?: "_"
            bind(name, Binding.Param(slot, name))
            RParam(slot, name, service.typeFromText(p.typeReference?.text, resolver.fileContext))
        }
        val body = when {
            fn.hasBlockBody() -> fn.bodyBlockExpression?.let { lowerBlock(it) } ?: emptyBlock(fn)
            else -> fn.bodyExpression?.let { lower(it) } ?: unsupported("empty body", fn)
        }
        scopes.removeLast()
        return ResolvedFunction(fn.name ?: "<anonymous>", params, body, diagnostics.toList())
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
        val methods = memberFns.filter { it.bodyBlockExpression != null || it.bodyExpression != null }.associate { fn ->
            val rf = lowerMemberFunction(fn, ctx)
            "${rf.name}/${rf.params.size}" to rf
        }

        // Then the constructor/init pass in one fresh scope; its leftover diagnostics are the class's own.
        reset()
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
        for (prop in bodyProps) {
            val init = prop.initializer ?: continue
            val pname = prop.name ?: continue
            steps += prop.textRange.startOffset to
                RNode.PropertySet(thisRef(ctx, prop), Binding.Property(pname, fqn, backingField = false), lower(init), span(prop))
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
            diagnostics = classDiags,
        )
    }

    /** Lower a class member function: a receiver slot (slot 0) is allocated first so `this`/implicit-member
     *  access binds to it, then the value parameters, then the body — all with [ctx] active. */
    private fun lowerMemberFunction(fn: KtNamedFunction, ctx: ClassContext): ResolvedFunction {
        reset()
        scopes.addLast(HashMap())
        classStack.addLast(ctx)
        val thisSlot = newSlot() // slot 0
        val params = fn.valueParameters.map { p ->
            val slot = newSlot()
            val name = p.name ?: "_"
            bind(name, Binding.Param(slot, name))
            RParam(slot, name, service.typeFromText(p.typeReference?.text, resolver.fileContext))
        }
        val body = when {
            fn.hasBlockBody() -> fn.bodyBlockExpression?.let { lowerBlock(it) } ?: emptyBlock(fn)
            else -> fn.bodyExpression?.let { lower(it) } ?: unsupported("empty body", fn)
        }
        classStack.removeLast()
        scopes.removeLast()
        return ResolvedFunction(fn.name ?: "<anonymous>", params, body, diagnostics.toList(), receiverSlot = thisSlot)
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
        is KtDestructuringDeclaration -> destructuringNode(e)
        is KtReturnExpression -> RNode.Return(e.returnedExpression?.let { lower(it) }, span(e))
        is KtBinaryExpression -> binaryNode(e)
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

        var chain: RNode? = elseBody
        for (entry in branches.asReversed()) {
            val cond = whenCondition(entry, subject != null, ::subjectRef, span)
            val body = entry.expression?.let { lower(it) } ?: emptyBlock(entry)
            chain = RNode.If(cond, body, chain, span)
        }
        val result = chain ?: elseBody ?: unsupported("empty when", e)
        return if (subjectSlot != null) {
            RNode.Block(listOf(RNode.LocalVar(subjectSlot, "\$subject", false, lower(subject!!), span), result), isExpression = true, span)
        } else {
            result
        }
    }

    /** A branch's condition as a boolean, OR-ing its comma-separated parts (`if (a) true else b`). Each part is
     *  `subject == value` (or the bare expression, subjectless), `subject is T`, or `subject in range`. */
    private fun whenCondition(entry: KtWhenEntry, hasSubject: Boolean, subjectRef: () -> RNode, span: SourceSpan): RNode {
        val parts = entry.conditions.map { c ->
            when (c) {
                is KtWhenConditionWithExpression -> {
                    val value = c.expression ?: return unsupported("empty when condition", entry)
                    if (hasSubject)
                        RNode.Call(synthOperator("eq"), DispatchKind.OPERATOR, subjectRef(), listOf(RArg(lower(value))), CallSiteKey(span.start), span)
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
                    val contains = RNode.Call(synthMember("contains"), DispatchKind.MEMBER, lower(range), listOf(RArg(subjectRef())), CallSiteKey(span.start), span)
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
        // A bare member property of the enclosing class (`id` inside one of `Project`'s methods) reads through
        // the implicit `this` receiver.
        classStack.lastOrNull()?.let { ctx ->
            if (name in ctx.propertyNames)
                return RNode.PropertyGet(thisRef(ctx, e), Binding.Property(name, ctx.fqn, backingField = false), span(e))
        }
        // A bare top-level property (`PI`) reads as a property get with no receiver. Member properties of an
        // enclosing class without an explicit receiver are not yet modeled → Unsupported (sound, not guessed).
        val prop = service.topLevelByName(name).firstOrNull { it.kind == SymbolKind.FIELD }
        if (prop != null) {
            // A top-level property's getter is a STATIC method on its `…Kt` file facade (`getLocalTextStyle()`),
            // so the binding records the facade (not the package) as the owner the interpreter reflects into.
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
        val receiver = lower(e.receiverExpression)
        if (receiver is RNode.Unsupported) return receiver
        return when (val sel = e.selectorExpression) {
            is KtCallExpression -> callNode(sel, receiver, e.receiverExpression)
            is KtNameReferenceExpression -> propertyGet(sel.getReferencedName(), receiver, e.receiverExpression, e)
            else -> unsupported("qualified selector ${sel?.let { it::class.simpleName }}", e)
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
        val cond = RNode.Call(synthOperator("ne"), DispatchKind.OPERATOR, tmpRef(), listOf(RArg(RNode.Const(null, null, span))), CallSiteKey(span.start), span)
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
            val comp = RNode.Call(synthMember("component${i + 1}"), DispatchKind.MEMBER, tmpRef, emptyList(), CallSiteKey(span(entry).start), span(entry))
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
        RArg(lower(expr), va.getArgumentName()?.asName?.identifier, va.getSpreadElement() != null)
    }

    private fun callNode(call: KtCallExpression, receiverNode: RNode?, receiverExpr: KtExpression? = null): RNode {
        checkNamedArguments(call)
        // A generic call whose type arguments can't be inferred (`mutableStateOf()` with no value argument) is
        // invalid Kotlin — the editor flags `kt.cannotInferType`. The arity fallback in `chooseCallee` would
        // still pick the callee and lower a malformed (under-applied) call that crashes the run reflectively;
        // surface the honest reason instead so the preview names the gap rather than dying opaquely.
        val uninferable = runCatching { resolver.uninferableTypeParameters(call) }.getOrDefault(emptyList())
        if (uninferable.isNotEmpty()) {
            val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: "?"
            return unsupported("not enough information to infer type variable ${uninferable.joinToString(", ")} for `$name`", call)
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
                return RNode.Call(callee, DispatchKind.MEMBER, thisRef(ctx, call), lowerArgs(call), CallSiteKey(call.textRange.startOffset), span(call))
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
                return RNode.Call(callee, DispatchKind.MEMBER, receiverNode, lowerArgs(call), CallSiteKey(call.textRange.startOffset), span(call))
            }
            // A bare capitalized call the editor resolver didn't surface a constructor for. A source class
            // (e.g. one with an implicit no-arg constructor) builds a [SourceObject]; otherwise a stdlib/library
            // type (`IllegalArgumentException("x")`) is instantiated reflectively.
            if (receiverNode == null && callName != null && callName.first().isUpperCase()) {
                fileClasses[callName]?.takeIf { it.flavor == ClassFlavor.CLASS }?.let { sc ->
                    val callee = ResolvedCallable.Source(callName, "${sc.fqn}/$arity", emptyList(), isConstructor = true)
                    return RNode.Call(callee, DispatchKind.CONSTRUCTOR, null, lowerArgs(call), CallSiteKey(call.textRange.startOffset), span(call))
                }
                runCatching { service.resolveTypeName(callName, resolver.fileContext) }.getOrNull()?.let { typeFqn ->
                    // Only fabricate a reflective constructor when there's POSITIVE evidence the name is a
                    // constructible type: a known/loadable type, an already-qualified FQN, or a name being
                    // THROWN (`throw IllegalArgumentException("x")` — a stdlib exception the resolver couldn't
                    // qualify, loaded via `java.lang` at runtime). An unresolved capitalized call with none of
                    // these is almost always a library FUNCTION not on the index (a Compose composable like
                    // `SuggestionChip`); fabricating a `SuggestionChip()` constructor would crash the running
                    // composition with "cannot load class". Fall through to the honest Unsupported instead.
                    val constructible = service.isKnownType(typeFqn) || '.' in typeFqn || call.parent is KtThrowExpression
                    if (constructible) {
                        val ctor = ResolvedCallable.Library(callName, typeFqn, "<init>", List(arity) { null }, isStatic = false, isConstructor = true, isInline = false)
                        return RNode.Call(ctor, DispatchKind.CONSTRUCTOR, null, lowerArgs(call), CallSiteKey(call.textRange.startOffset), span(call))
                    }
                }
            }
            return unsupported(callDiagnostic(call), call)
        }
        val args = lowerArgs(call)
        val key = CallSiteKey(call.textRange.startOffset)
        val callee = toCallable(chosen)
        if (chosen.isExtension) {
            // A MEMBER extension of an in-scope receiver scope (`RowScope.weight`, declared inside `RowScope`)
            // dispatches ON that scope instance, with the explicit/implicit `Modifier` as its extension receiver
            // — `memberExtensionsInScope` is what surfaced it, so the scope IS in scope here.
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
        val cond = e.condition?.let { lower(it) } ?: return unsupported("if without condition", e)
        val then = e.then?.let { lower(it) } ?: return unsupported("if without body", e)
        return RNode.If(cond, then, e.`else`?.let { lower(it) }, span(e))
    }

    private fun binaryNode(e: KtBinaryExpression): RNode {
        val left = e.left ?: return unsupported("binary without lhs", e)
        val right = e.right ?: return unsupported("binary without rhs", e)
        val token = e.operationToken
        if (token == KtTokens.EQ) {
            // Indexed assignment (`xs[i] = v`, the `set` operator) isn't modeled yet — keep it sound rather than
            // mis-lowering the `get` we'd produce for the LHS into a plain Assign.
            if (left is KtArrayAccessExpression) return unsupported("indexed assignment (set) not supported", e)
            // The LHS is a local/param (`i = …` → Assign) or a property (`count.value = …`, a `MutableState`, or a
            // `by`-delegated local — both already lowered to a PropertyGet → write through its setter via PropertySet).
            val lhs = lower(left)
            return when (lhs) {
                is RNode.Unsupported -> lhs
                is RNode.PropertyGet -> RNode.PropertySet(lhs.receiver, lhs.binding, lower(right), span(e))
                else -> RNode.Assign(lhs, lower(right), span(e))
            }
        }
        val key = CallSiteKey(e.textRange.startOffset)
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
        // `a..b` → an `IntRange` (the only range modeled); other element types hit the honest boundary.
        if (token == KtTokens.RANGE) {
            val lt = runCatching { resolver.inferType(left) }.getOrNull()
            if (lt == null || lt.qualifiedName == "kotlin.Int") {
                val ctor = ResolvedCallable.Library("IntRange", "kotlin.ranges.IntRange", "<init>", listOf(null, null), isStatic = false, isConstructor = true, isInline = false)
                return RNode.Call(ctor, DispatchKind.CONSTRUCTOR, null, listOf(RArg(lower(left)), RArg(lower(right))), key, span(e))
            }
            return unsupported("range over ${lt.qualifiedName}", e)
        }
        // Comparison / equality desugar to an OPERATOR call the interpreter evaluates intrinsically (the
        // callee is synthetic — these need no library method to invoke).
        (COMPARISON[token] ?: EQUALITY[token])?.let { op ->
            return RNode.Call(
                synthOperator(op), DispatchKind.OPERATOR, lower(left), listOf(RArg(lower(right))), key, span(e),
            )
        }
        val convention = ARITHMETIC[token] ?: return unsupported("operator ${e.operationReference.text}", e)
        val leftType = resolver.inferType(left)
        // String concatenation (no `String.plus` member is reliably in the index), or an UNKNOWN left type
        // (e.g. an enum-entry property like `Color.RED.name`): lower to a synthetic OPERATOR call the
        // interpreter resolves at run time by the actual value — numeric arithmetic or string concat — rather
        // than rejecting it here.
        if (leftType == null || (token == KtTokens.PLUS && leftType.qualifiedName == "kotlin.String")) {
            return RNode.Call(
                synthOperator(convention), DispatchKind.OPERATOR, lower(left), listOf(RArg(lower(right))),
                CallSiteKey(e.textRange.startOffset), span(e),
            )
        }
        val op = service.membersOf(leftType.qualifiedName, leftType.typeArguments, null)
            .filterIsInstance<KotlinSymbol>()
            .firstOrNull { it.name == convention && it.kind == SymbolKind.METHOD && it.paramTypes.size == 1 }
            ?: return unsupported("no `$convention` on ${leftType.qualifiedName}", e)
        return RNode.Call(
            toCallable(op), DispatchKind.OPERATOR, lower(left), listOf(RArg(lower(right))),
            CallSiteKey(e.textRange.startOffset), span(e),
        )
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
        return RNode.Call(toCallable(get), DispatchKind.MEMBER, receiver, args, CallSiteKey(e.textRange.startOffset), span(e))
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
                return RNode.Call(synthOperator("minus"), DispatchKind.OPERATOR, zero, listOf(RArg(v)), CallSiteKey(span.start), span)
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
        val bumped = RNode.Call(synthOperator(op), DispatchKind.OPERATOR, read, listOf(RArg(one)), CallSiteKey(span.start), span)
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

    private fun chooseCallee(call: KtCallExpression): KotlinSymbol? {
        val candidates = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
            // Dedup by SIGNATURE (kind + name + param types), IGNORING the declaring owner. A method with the
            // same signature from different owners is the same call shape: an override surfacing from both the
            // class and its supertype (`SnapshotStateList.add` + `MutableList.add`), the same callable present
            // twice (a stdlib jar on the classpath AND bundled), or a member extension declared on the same
            // receiver by sibling scopes (`RowScope.weight` + `ColumnScope.weight`, both on `Modifier`). Keying
            // on the owner left these as distinct candidates that could never narrow → a false ambiguity.
            // The vararg index IS part of the key: `listOf(element: T)` and `listOf(vararg elements: T)` both
            // decode to params `[T]`, so without it the dedup would merge them and DROP the vararg overload —
            // leaving `listOf("a", "b")` (which only the vararg accepts) unresolvable.
            .distinctBy { c -> c.kind.toString() + "/" + c.name + "/" + c.varargParamIndex + "/" + c.paramTypes.map { (it as? KotlinType)?.qualifiedName } }
        if (candidates.isEmpty()) return null
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
        val typed = byArity.filter { c -> argsBindable(c, valueArgs, exact = false) }
        typed.singleOrNull()?.let { return it }
        if (typed.isEmpty()) return null // nothing applicable → genuine no-match, never guess
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
                else runCatching { pt.isAssignableFrom(at) }.getOrDefault(true)
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
        )
    }

    // --- helpers ---

    private val strType get() = service.typeByFqn("kotlin.String")
    private val boolType get() = service.typeByFqn("kotlin.Boolean")

    private val ARITHMETIC = mapOf(
        KtTokens.PLUS to "plus", KtTokens.MINUS to "minus", KtTokens.MUL to "times",
        KtTokens.DIV to "div", KtTokens.PERC to "rem",
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

    private fun reset() { scopes.clear(); slotCounter = 0; diagnostics.clear() }
}
