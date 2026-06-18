package dev.ide.interp

import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.ClassFlavor
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId

/**
 * A tree-walking interpreter over the [ResolvedFunction] / [RNode] contract from `:lang-kotlin` (see
 * `docs/compose-interpreter.md`). The resolver did all the resolution and operator desugaring up front, so
 * this never re-resolves names, dispatch, or operators — it just executes.
 *
 * Step 3 scope (plain Kotlin, no Compose): constants, locals/params, blocks, `if`, `return`, assignment,
 * the primitive numeric operators (handled as intrinsics — `Int.plus` has no JVM method to reflect into),
 * and calls to other **source** functions (interpreted recursively). Anything else — member/extension/
 * constructor/library dispatch, property access, lambdas — throws [InterpreterException], the honest
 * boundary that mirrors the resolver's "reject, don't guess". The reflective library bridge and the Compose
 * layer come next.
 *
 * [functions] is keyed `"name/arity"`; a source call looks its target up there. Everything that isn't a
 * source call or a primitive-operator intrinsic is handed to the [dispatcher] (reflection by default; the
 * Compose-aware dispatcher in `interp-compose` overrides it).
 */
class Interpreter(
    private val functions: Map<String, ResolvedFunction>,
    private val dispatcher: Dispatcher = ReflectiveDispatcher(),
    /** When set, an interpreted `@Composable` source call is run through this (restart group + recomposition);
     *  null runs it directly (no Compose). */
    private val composableInvoker: ComposableInvoker? = null,
    /** Preferred loader for resolving library classes (object refs, property getters) — the project library
     *  [ClassLoader] when previewing on device; null falls back to the host loader chain. */
    private val classLoader: ClassLoader? = null,
    /** Project-source classes/objects/enums lowered for interpretation. Source types aren't compiled at
     *  preview/run time, so the interpreter materializes them as [SourceObject]s from this model rather than
     *  reflecting bytecode. */
    classes: List<ResolvedClass> = emptyList(),
) {
    /** Source classes indexed by fully-qualified name and by simple name (a constructor callee carries the
     *  simple name; an object/enum reference carries the resolved FQN). */
    private val classesByFqn: Map<String, ResolvedClass> = classes.associateBy { it.fqn }
    private val classesBySimpleName: Map<String, ResolvedClass> =
        classes.groupBy { it.simpleName }.mapValues { it.value.first() }

    /** Lazily-materialized singletons: one per source `object`/`companion`, and one per enum entry. */
    private val singletons = HashMap<String, SourceObject>()

    fun call(fn: ResolvedFunction, args: List<Any?>): Any? {
        if (!fn.isComplete) {
            throw InterpreterException("function `${fn.name}` has unsupported nodes: ${fn.diagnostics}")
        }
        val env = Env()
        fn.params.forEachIndexed { i, p -> env.define(p.slot, args.getOrNull(i)) }
        return try {
            eval(fn.body, env)
        } catch (r: ReturnSignal) {
            r.value
        }
    }

    private fun eval(node: RNode, env: Env): Any? = when (node) {
        is RNode.Const -> node.value
        is RNode.Name -> readBinding(node.binding, env)
        is RNode.Block -> {
            var last: Any? = Unit
            for (st in node.statements) last = eval(st, env)
            last
        }
        is RNode.LocalVar -> {
            env.define(node.slot, node.initializer?.let { eval(it, env) })
            Unit
        }
        is RNode.Assign -> {
            val target = node.target
            if (target !is RNode.Name || target.binding !is Binding.Local && target.binding !is Binding.Param) {
                throw InterpreterException("unsupported assignment target: ${target::class.simpleName}")
            }
            env.assign(slotOf(target.binding), eval(node.value, env))
            Unit
        }
        is RNode.If -> {
            // Note: an `else` branch that legitimately evaluates to `null` must stay null — distinguish
            // "no else" (→ Unit) from "else yielded null" rather than coalescing both.
            val otherwise = node.otherwise
            if (eval(node.condition, env) == true) eval(node.then, env)
            else if (otherwise != null) eval(otherwise, env) else Unit
        }
        is RNode.Return -> throw ReturnSignal(node.value?.let { eval(it, env) })
        is RNode.While -> {
            // Each iteration runs the body in a fresh child scope, so a local (or lambda capturing one)
            // declared in the body is distinct per iteration; the condition reads the enclosing scope.
            if (node.doWhile) {
                do { eval(node.body, Env(env)) } while (eval(node.condition, env) == true)
            } else {
                while (eval(node.condition, env) == true) eval(node.body, Env(env))
            }
            Unit
        }
        is RNode.ForEach -> {
            val iterable = eval(node.iterable, env) ?: throw InterpreterException("cannot iterate null")
            val iterator = invoke0(iterable, "iterator") ?: throw InterpreterException("no iterator() on ${iterable.javaClass.name}")
            while (invoke0(iterator, "hasNext") == true) {
                // Fresh per-iteration scope: the loop variable (and any closure capturing it) is bound anew
                // each pass, matching Kotlin's per-iteration capture rather than aliasing one shared slot.
                val iterEnv = Env(env)
                iterEnv.define(node.loopVar.slot, invoke0(iterator, "next"))
                eval(node.body, iterEnv)
            }
            Unit
        }
        is RNode.Call -> evalCall(node, env)
        is RNode.Lambda -> Closure(node, env)
        is RNode.StringConcat -> buildString { node.parts.forEach { append(eval(it, env)?.toString() ?: "null") } }
        is RNode.PropertyGet -> {
            val binding = node.binding
            val receiverNode = node.receiver
            // A source enum entry (`Color.RED`) or a source object/companion member (`Config.name`) addressed
            // through a bare type reference — resolve before evaluating the (non-value) type-qualifier receiver.
            sourceQualifierMember(receiverNode, binding.name)?.let { return it.value }
            val staticHolder = staticHolderReceiver(receiverNode)
            when {
                // A static member read through a class qualifier (`System.out`, `Integer.MAX_VALUE`): the
                // receiver is a bare type reference to a class with no object/companion singleton, so there is
                // no instance — read the static field (or its `getName()` static getter) off the class.
                staticHolder != null -> readStaticMember(staticHolder, binding.name)
                receiverNode == null -> {
                    // A top-level property (`LocalTextStyle`, `PI`) — its getter is a STATIC method on the declaring
                    // `…Kt` facade (`getLocalTextStyle()`); the resolver records the facade in the binding's owner.
                    val owner = (binding as? Binding.Property)?.ownerFqn
                        ?: throw InterpreterException("top-level property read `${binding.name}` not supported (no owner)")
                    readTopLevelProperty(owner, binding.name)
                }
                else -> {
                    val receiver = eval(receiverNode, env)
                        ?: throw InterpreterException("cannot read property `${binding.name}` on a null receiver")
                    val extOwner = (binding as? Binding.Property)?.takeIf { it.isExtension }?.ownerFqn
                    if (extOwner != null) readExtensionProperty(receiver, extOwner, binding.name)
                    else readProperty(receiver, binding.name)
                }
            }
        }
        is RNode.PropertySet -> {
            // `receiver.name = value` — the write path for a `by`-delegated local (`MutableState.value = …`),
            // which goes through the real `setValue()` so the snapshot system invalidates the recompose scope.
            val receiverNode = node.receiver
                ?: throw InterpreterException("top-level property write `${node.binding.name}` not supported")
            val receiver = eval(receiverNode, env)
                ?: throw InterpreterException("cannot write property `${node.binding.name}` on a null receiver")
            writeProperty(receiver, node.binding.name, eval(node.value, env))
            Unit
        }
        is RNode.NotNull -> eval(node.value, env)
            ?: throw NullPointerException("null value passed to `!!`")
        is RNode.TypeCheck -> {
            val matches = isInstanceOf(eval(node.value, env), node.typeFqn)
            if (node.negated) !matches else matches
        }
        is RNode.Throw -> {
            val v = eval(node.value, env)
            if (v is Throwable) throw v else throw KotlinThrow(v)
        }
        is RNode.Try -> {
            try {
                try {
                    eval(node.body, env)
                } catch (t: Throwable) {
                    if (t is ReturnSignal) throw t // control flow, not an exception to catch
                    val thrown = (t as? KotlinThrow)?.value ?: t
                    val handler = node.catches.firstOrNull { c -> c.typeFqn.let { it == null || isInstanceOf(thrown, it) } }
                        ?: throw t
                    val henv = Env(env)
                    henv.define(handler.slot, thrown)
                    eval(handler.body, henv)
                }
            } finally {
                node.finallyBlock?.let { eval(it, env) }
            }
        }
        is RNode.Unsupported -> throw InterpreterException("unsupported construct: ${node.reason} (`${node.text}`)")
        else -> throw InterpreterException("not yet interpretable: ${node::class.simpleName}")
    }

    private fun evalCall(call: RNode.Call, env: Env): Any? {
        val callee = call.callee
        // Operators are computed intrinsically: arithmetic/comparison on numbers and structural equality have
        // no JVM method to invoke (a synthetic callee). Arithmetic on a non-number falls through to dispatch.
        if (call.dispatch == DispatchKind.OPERATOR) {
            val op = callee.displayName
            val left = eval(call.receiver ?: throw InterpreterException("operator without receiver"), env)
            val right = eval(call.args.first().value, env)
            when {
                op == "eq" -> return left == right
                op == "ne" -> return left != right
                op in COMPARISON -> return compare(op, left, right)
                op == "plus" && left is String -> return left + right?.toString() // String.plus(Any?)
                op in ARITHMETIC && left is Number && right is Number -> return arithmetic(op, left, right)
                op in ARITHMETIC -> {} // a real operator method on a user/library type → fall through to dispatch
            }
        }
        // A source constructor materializes a [SourceObject] (the type isn't compiled at preview/run time).
        if (callee is ResolvedCallable.Source && call.dispatch == DispatchKind.CONSTRUCTOR) {
            sourceClass(callee)?.let { cls ->
                val argv = call.args.map { eval(it.value, env) }
                return instantiate(cls, reorderNamedArgs(cls.primaryParams.map { it.name }, call.args, argv))
            }
        }
        // A source enum's static members addressed through the type: `Color.values()` / `Color.valueOf("RED")`
        // — the receiver is the enum type (not an instance), so resolve it without evaluating the receiver.
        enumStaticCall(call, env)?.let { return it.value }
        // A source function is interpreted recursively (its body is available). A `@Composable` one is run
        // through the composable invoker (restart group + recomposition) when a Compose host is present.
        if (callee is ResolvedCallable.Source && call.dispatch == DispatchKind.TOP_LEVEL) {
            val target = functions["${callee.displayName}/${call.args.size}"]
                ?: throw InterpreterException("no source function `${callee.displayName}/${call.args.size}`")
            val argv = call.args.map { eval(it.value, env) }
            val invoke = { call(target, argv) }
            return if (callee.isComposable && composableInvoker != null) {
                composableInvoker.invokeComposable(call.callSiteKey.value, invoke)
            } else {
                invoke()
            }
        }
        // A handful of stdlib functions are `@kotlin.internal.InlineOnly` — they have NO callable JVM method
        // (they exist only to be inlined), so the reflective dispatcher can never find them. We execute them as
        // intrinsics, running the interpreted lambda in-process. This also keeps the ambient composer intact for
        // a composable call inside the lambda (e.g. `repeat(n) { Text(...) }`) — exactly what the inlined form
        // would do — so the composables compose into the enclosing group rather than blowing up the dispatcher.
        if (callee is ResolvedCallable.Library && callee.ownerFqn in INLINE_INTRINSIC_FACADES) {
            evalInlineIntrinsic(call, env)?.let { return it.value }
        }
        // A static method invoked through a class qualifier (`System.currentTimeMillis()`): the receiver is a
        // bare type reference to a class with no object/companion singleton, so there is no instance to
        // evaluate — dispatch it statically on the owning class (the callee already carries that owner).
        if (callee is ResolvedCallable.Library && staticHolderReceiver(call.receiver) != null) {
            val args = call.args.map { eval(it.value, env) }
            val staticCall = call.copy(dispatch = DispatchKind.TOP_LEVEL, receiver = null)
            return dispatcher.dispatch(staticCall, null, args)
        }
        // A MEMBER extension dispatched on an implicit-receiver scope (`RowScope.weight` called as
        // `Modifier.weight(1f)`): it's an INSTANCE method on the scope whose first parameter is the extension
        // receiver, so invoke it on the scope with the extension receiver prepended to the args.
        if (call.dispatch == DispatchKind.MEMBER_EXTENSION) {
            val scope = eval(call.dispatchReceiver ?: throw InterpreterException("member extension `${callee.displayName}` has no scope receiver"), env)
                ?: throw InterpreterException("member extension `${callee.displayName}` scope receiver is null")
            val extReceiver = call.receiver?.let { eval(it, env) }
            // Pass the scope as the dispatch receiver and the extension receiver as the head of the args; the
            // dispatcher invokes it as an instance method on the scope (and threads BOTH receivers through the
            // `$default` synthetic when a value param is defaulted).
            val args = listOf(extReceiver) + call.args.map { eval(it.value, env) }
            return dispatcher.dispatch(call, scope, args)
        }
        // Everything else (library/member/constructor) goes through the host dispatcher — except a member call
        // whose receiver turns out to be a source instance, which can't be reflected and is interpreted instead
        // (covers both a `Source` member callee and a synthetic `contains`/`componentN` on a source object).
        val receiver = call.receiver?.let { eval(it, env) }
        if (receiver is SourceObject && call.dispatch == DispatchKind.MEMBER) {
            return dispatchSourceMember(receiver, callee.displayName, call, env)
        }
        // Numeric conversions (`(progress * 100).toInt()`, `x.toFloat()`) have no JVM method on the boxed type,
        // so the reflective dispatcher would fail — compute them intrinsically.
        if (call.dispatch == DispatchKind.MEMBER && receiver is Number && call.args.isEmpty()) {
            numericConversion(receiver, callee.displayName)?.let { return it.value }
        }
        val args = call.args.map { eval(it.value, env) }
        return try {
            dispatcher.dispatch(call, receiver, args)
        } catch (e: InterpreterException) {
            throw refineInlineOnlyError(call, e)
        }
    }

    /** A source enum's static members reached through the type qualifier: `Enum.values()` → all entries (in
     *  order), `Enum.valueOf(name)` → the matching entry. Null when [call] isn't one. */
    private fun enumStaticCall(call: RNode.Call, env: Env): Handled? {
        if (call.dispatch != DispatchKind.MEMBER) return null
        val ref = (call.receiver as? RNode.Name)?.binding as? Binding.ObjectRef ?: return null
        val cls = sourceClass(ref.fqn)?.takeIf { it.flavor == ClassFlavor.ENUM } ?: return null
        return when (call.callee.displayName) {
            "values" -> Handled(cls.enumEntries.map { enumEntryInstance(cls, it) })
            "valueOf" -> {
                val name = eval(call.args.first().value, env) as? String
                Handled(cls.enumEntries.firstOrNull { it.name == name }?.let { enumEntryInstance(cls, it) }
                    ?: throw InterpreterException("no enum constant ${cls.fqn}.$name"))
            }
            else -> null
        }
    }

    /**
     * Turn the cryptic dispatch failure for an unhandled `@kotlin.internal.InlineOnly` function (e.g.
     * `no static repeat(2) on kotlin.StandardKt`) into a legible boundary message. Such a function emits NO
     * callable JVM method — it exists only to be inlined — so reflection can't find it and a few are modeled as
     * intrinsics ([evalInlineIntrinsic]). We confirm this is the cause (an `inline` top-level/extension callee
     * whose facade genuinely declares no matching method) before rewriting, so an unrelated dispatch error on a
     * normal `inline` function — which DOES emit a method — is left untouched. Runs only on the failure path.
     */
    private fun refineInlineOnlyError(call: RNode.Call, original: InterpreterException): InterpreterException {
        val callee = call.callee
        if (callee !is ResolvedCallable.Library || !callee.isInline) return original
        if (call.dispatch != DispatchKind.TOP_LEVEL && call.dispatch != DispatchKind.EXTENSION) return original
        val owner = callee.ownerFqn ?: return original
        val cls = loadClassAcross(owner, initialize = false, preferred = classLoader) ?: return original
        val hasMethod = cls.methods.any { mangledNameMatches(it.name, callee.displayName) }
        if (hasMethod) return original // a real inline method exists → the failure is something else
        return InterpreterException(
            "`${callee.displayName}` is an inline-only function (no JVM method on `$owner`) the interpreter " +
                "doesn't model yet — only the stdlib scope functions (repeat/let/also/run/takeIf/takeUnless) and " +
                "the empty/blank predicates (isNotBlank/isNotEmpty/isNullOrBlank/isNullOrEmpty) are built in",
        )
    }

    /** A boxed result so a handled intrinsic can legitimately return `null`/`Unit` (distinct from "not one"). */
    private class Handled(val value: Any?)

    /**
     * Execute a known `@InlineOnly` stdlib intrinsic ([STDLIB_FACADE] callees), or return null if [call] isn't
     * one. Only the `it`-lambda / no-receiver-lambda scope functions are handled — the receiver-lambda forms
     * (`run`/`apply`/`with`, whose body binds an implicit `this`) are deliberately left to the dispatcher's
     * honest boundary rather than guessed at.
     */
    private fun evalInlineIntrinsic(call: RNode.Call, env: Env): Handled? {
        val name = call.callee.displayName
        val args = call.args
        fun lambda(node: RNode): InterpretedLambda =
            eval(node, env) as? InterpretedLambda ?: throw InterpreterException("`$name` expects a lambda argument")
        fun receiver(): Any? = eval(call.receiver ?: throw InterpreterException("`$name` has no receiver"), env)
        return when {
            // repeat(times) { index -> … }
            name == "repeat" && call.dispatch == DispatchKind.TOP_LEVEL && args.size == 2 -> {
                val times = (eval(args[0].value, env) as? Number)?.toInt()
                    ?: throw InterpreterException("`repeat` requires an Int count")
                val action = lambda(args[1].value)
                for (i in 0 until times) action.invoke(listOf(i))
                Handled(Unit)
            }
            // x.let { it -> … }
            name == "let" && call.dispatch == DispatchKind.EXTENSION && args.size == 1 ->
                Handled(lambda(args[0].value).invoke(listOf(receiver())))
            // x.also { it -> … } → runs the block, returns x
            name == "also" && call.dispatch == DispatchKind.EXTENSION && args.size == 1 -> {
                val recv = receiver()
                lambda(args[0].value).invoke(listOf(recv))
                Handled(recv)
            }
            // x.takeIf { it -> … } / x.takeUnless { … }
            name == "takeIf" && call.dispatch == DispatchKind.EXTENSION && args.size == 1 -> {
                val recv = receiver()
                Handled(if (lambda(args[0].value).invoke(listOf(recv)) == true) recv else null)
            }
            name == "takeUnless" && call.dispatch == DispatchKind.EXTENSION && args.size == 1 -> {
                val recv = receiver()
                Handled(if (lambda(args[0].value).invoke(listOf(recv)) == true) null else recv)
            }
            // run { … } (the no-receiver top-level form; the `T.run { this -> }` extension is left to dispatch)
            name == "run" && call.dispatch == DispatchKind.TOP_LEVEL && args.size == 1 ->
                Handled(lambda(args[0].value).invoke(emptyList()))
            // The empty/blank predicate family is `@InlineOnly` (each compiles to a one-liner over a real
            // receiver method like `isBlank()`/`isEmpty()`/`length`), so no JVM method exists to reflect into.
            // They take no lambda — compute them directly on the evaluated receiver, branching by runtime type
            // so the CharSequence and Collection/Map overloads of `isNotEmpty`/`isNullOrEmpty` both resolve.
            call.dispatch == DispatchKind.EXTENSION && args.isEmpty() && name in EMPTY_BLANK_PREDICATES ->
                evalEmptyBlankPredicate(name, receiver())
            else -> null
        }
    }

    /** Compute an `@InlineOnly` empty/blank predicate (`isNotBlank`/`isNotEmpty`/`isNullOrBlank`/
     *  `isNullOrEmpty`) on the evaluated [recv]; returns null when [recv]'s runtime type doesn't match any
     *  overload (so the honest dispatch boundary still fires rather than a wrong answer). */
    private fun evalEmptyBlankPredicate(name: String, recv: Any?): Handled? = when (name) {
        "isNullOrBlank" -> Handled((recv as? CharSequence).isNullOrBlank())
        "isNullOrEmpty" -> when (recv) {
            null -> Handled(true)
            is CharSequence -> Handled(recv.isEmpty())
            is Collection<*> -> Handled(recv.isEmpty())
            is Map<*, *> -> Handled(recv.isEmpty())
            else -> null
        }
        "isNotBlank" -> (recv as? CharSequence)?.let { Handled(it.isNotBlank()) }
        "isNotEmpty" -> when (recv) {
            is CharSequence -> Handled(recv.isNotEmpty())
            is Collection<*> -> Handled(recv.isNotEmpty())
            is Map<*, *> -> Handled(recv.isNotEmpty())
            else -> null
        }
        else -> null
    }

    private fun readBinding(binding: Binding, env: Env): Any? = when (binding) {
        is Binding.Local, is Binding.Param -> env.read(slotOf(binding))
        is Binding.ObjectRef -> {
            // A source `object`/`companion` referenced by name is its (lazily-built) singleton; a class name
            // used as a value denotes its companion (`Maths.square()`); everything else (a library
            // object/companion) is materialized reflectively.
            val sc = sourceClass(binding.fqn)
            when {
                sc == null -> objectInstance(binding.fqn)
                sc.flavor == ClassFlavor.OBJECT || sc.flavor == ClassFlavor.COMPANION -> objectSingleton(sc)
                else -> companionOf(sc)?.let { objectSingleton(it) } ?: objectInstance(binding.fqn)
            }
        }
        else -> throw InterpreterException("cannot read binding: ${binding::class.simpleName}")
    }

    /** Materialize the singleton a bare type reference denotes: an `object`'s `INSTANCE` static field, or a
     *  type's companion via the `Companion` static field (`Modifier` → `Modifier.Companion`, the empty
     *  modifier; `Color` for `Color.Red`). Read from the runtime class — works for library objects on the
     *  classpath; a project-source object isn't compiled at preview time, so it fails the honest boundary. */
    private fun objectInstance(fqn: String): Any? {
        val cls = loadClassAcross(fqn, initialize = true, preferred = classLoader)
            ?: throw InterpreterException("cannot load `$fqn` (a project-source object isn't available to the interpreter)")
        runCatching { cls.getField("INSTANCE") }.getOrNull()?.let { return it.get(null) } // a Kotlin `object`
        runCatching { cls.getField("Companion") }.getOrNull()?.let { return it.get(null) } // a type's companion
        throw InterpreterException("`$fqn` has no object/companion instance")
    }

    // --- source types (classes / data classes / objects / companions / enums) ---

    /** The source class a constructor [callee] builds — keyed by the simple name it carries, then the owner
     *  parsed from its `declId`. Null for a library/precompiled type (handled reflectively). */
    private fun sourceClass(callee: ResolvedCallable.Source): ResolvedClass? {
        classesBySimpleName[callee.displayName]?.let { return it }
        return classesByFqn[callee.declId.substringBeforeLast('/')]
    }

    /** A source class by FQN, falling back to its simple name (an `object`/enum reference carries the FQN). */
    private fun sourceClass(fqn: String): ResolvedClass? =
        classesByFqn[fqn] ?: classesBySimpleName[fqn.substringAfterLast('.')]

    /** The companion object of [cls], if it declares one (so `Maths.square()` resolves through `Maths`). */
    private fun companionOf(cls: ResolvedClass): ResolvedClass? =
        classesByFqn["${cls.fqn}.Companion"]
            ?: classesByFqn.values.firstOrNull { it.flavor == ClassFlavor.COMPANION && it.fqn.substringBeforeLast('.') == cls.fqn }

    /** Build an instance of [cls] (its `cls` is the most-derived type, for virtual dispatch + `is` checks). */
    private fun instantiate(cls: ResolvedClass, orderedValues: List<Any?>): SourceObject {
        val obj = SourceObject(cls)
        construct(cls, obj, orderedValues)
        return obj
    }

    /** Run [cls]'s constructor on the shared [obj]: bind the primary params (each `val`/`var` one stored as a
     *  field, defaulted when not supplied), run the superclass constructor (its args may reference this class's
     *  params, so they're bound first; inherited properties land in the same field map), then this class's
     *  body-property initializers and `init` blocks. */
    private fun construct(cls: ResolvedClass, obj: SourceObject, orderedValues: List<Any?>) {
        val env = Env()
        env.define(cls.receiverSlot, obj)
        cls.primaryParams.forEachIndexed { i, p ->
            val provided = i < orderedValues.size && orderedValues[i] !== OmittedArg
            val value = if (provided) orderedValues[i] else p.default?.let { eval(it, env) }
            env.define(p.slot, value)
            if (p.isProperty) obj.fields[p.name] = value
        }
        cls.superCall?.let { sc ->
            sourceClass(sc.fqn)?.let { superCls ->
                val superArgs = sc.args.map { eval(it.value, env) }
                construct(superCls, obj, reorderNamedArgs(superCls.primaryParams.map { it.name }, sc.args, superArgs))
            }
        }
        cls.initSteps.forEach { eval(it, env) }
    }

    /** A member function declared on [cls] or inherited from a source supertype (the runtime instance's own
     *  class is searched first, so an override wins — virtual dispatch). */
    private fun findSourceMethod(cls: ResolvedClass, key: String, seen: MutableSet<String> = HashSet()): ResolvedFunction? {
        if (!seen.add(cls.fqn)) return null
        cls.methods[key]?.let { return it }
        for (sup in cls.supertypes) sourceClass(sup)?.let { findSourceMethod(it, key, seen)?.let { m -> return m } }
        return null
    }

    /** The single shared instance of a source `object`/`companion` (built on first use). */
    private fun objectSingleton(cls: ResolvedClass): SourceObject =
        singletons.getOrPut(cls.fqn) { instantiate(cls, emptyList()) }

    /** The singleton for an enum entry — constructed once with the entry's constructor arguments and tagged
     *  with its `name`/`ordinal`. */
    private fun enumEntryInstance(cls: ResolvedClass, entry: dev.ide.lang.kotlin.interp.REnumEntry): SourceObject =
        singletons.getOrPut("${cls.fqn}#${entry.name}") {
            instantiate(cls, entry.args.map { eval(it.value, Env()) }).also {
                it.enumName = entry.name
                it.enumOrdinal = entry.ordinal
            }
        }

    /** Resolve `Type.member` when `Type` is a source enum (→ the entry) or source object/companion (→ a member
     *  read on its singleton). Null when [receiverNode] isn't a source type qualifier. */
    private fun sourceQualifierMember(receiverNode: RNode?, name: String): Handled? {
        val ref = (receiverNode as? RNode.Name)?.binding as? Binding.ObjectRef ?: return null
        val cls = sourceClass(ref.fqn) ?: return null
        if (cls.flavor == ClassFlavor.ENUM) {
            if (name == "entries") return Handled(cls.enumEntries.map { enumEntryInstance(cls, it) })
            return cls.enumEntries.firstOrNull { it.name == name }?.let { Handled(enumEntryInstance(cls, it)) }
        }
        val holder = if (cls.flavor == ClassFlavor.OBJECT || cls.flavor == ClassFlavor.COMPANION) cls
            else companionOf(cls) ?: return null
        return Handled(readSourceProperty(objectSingleton(holder), name))
    }

    /** Interpret a member function body with [receiver] bound to its receiver slot. */
    private fun callMethod(fn: ResolvedFunction, receiver: SourceObject, args: List<Any?>): Any? {
        val env = Env()
        fn.receiverSlot?.let { env.define(it, receiver) }
        fn.params.forEachIndexed { i, p -> env.define(p.slot, args.getOrNull(i)) }
        return try {
            eval(fn.body, env)
        } catch (r: ReturnSignal) {
            r.value
        }
    }

    private fun dispatchSourceMember(receiver: SourceObject, name: String, call: RNode.Call, env: Env): Any? {
        val arity = call.args.size
        findSourceMethod(receiver.cls, "$name/$arity")?.let { m ->
            return callMethod(m, receiver, call.args.map { eval(it.value, env) })
        }
        synthesizedMember(receiver, name, call, env)?.let { return it.value }
        throw InterpreterException("no member `$name/$arity` on source class ${receiver.cls.fqn}")
    }

    /** The compiler-synthesized members of a data class (`copy`/`componentN`/`equals`/`hashCode`/`toString`),
     *  which the source index never records — computed here from the class's component properties. */
    private fun synthesizedMember(receiver: SourceObject, name: String, call: RNode.Call, env: Env): Handled? {
        val arity = call.args.size
        return when {
            name == "copy" -> Handled(copySource(receiver, call, env))
            name == "toString" && arity == 0 -> Handled(receiver.toString())
            name == "hashCode" && arity == 0 -> Handled(receiver.hashCode())
            name == "equals" && arity == 1 -> Handled(receiver == eval(call.args[0].value, env))
            else -> COMPONENT.matchEntire(name)?.takeIf { arity == 0 }?.let {
                val idx = it.groupValues[1].toInt() - 1
                val comp = receiver.cls.componentNames.getOrNull(idx)
                    ?: throw InterpreterException("no `$name` (only ${receiver.cls.componentNames.size} components) on ${receiver.cls.fqn}")
                Handled(receiver.fields[comp])
            }
        }
    }

    /** `data class` `copy(...)` — re-runs the primary constructor with each component taken from a supplied
     *  argument (named or positional) or, when omitted, the receiver's current value. */
    private fun copySource(receiver: SourceObject, call: RNode.Call, env: Env): SourceObject {
        val cls = receiver.cls
        val argv = call.args.map { eval(it.value, env) }
        val ordered = reorderNamedArgs(cls.primaryParams.map { it.name }, call.args, argv)
        val values = cls.primaryParams.mapIndexed { i, p ->
            if (i < ordered.size && ordered[i] !== OmittedArg) ordered[i] else receiver.fields[p.name]
        }
        return instantiate(cls, values)
    }

    private fun readSourceProperty(receiver: SourceObject, name: String): Any? = when {
        receiver.fields.containsKey(name) -> receiver.fields[name]
        name == "name" && receiver.enumName != null -> receiver.enumName
        name == "ordinal" && receiver.enumOrdinal >= 0 -> receiver.enumOrdinal
        else -> throw InterpreterException("no property `$name` on source class ${receiver.cls.fqn}")
    }

    /** Runtime `is`/`catch` type test. A [SourceObject] matches by walking its source class hierarchy; any
     *  other value matches reflectively (mapping the common Kotlin type names to their JVM classes, and
     *  trying `java.lang.<Name>` for a bare simple name like `Exception`). */
    private fun isInstanceOf(value: Any?, typeFqn: String): Boolean {
        if (value == null) return false
        if (value is SourceObject) return sourceTypeMatches(value.cls, typeFqn)
        val candidates = listOfNotNull(
            KOTLIN_TYPE_TO_JVM[typeFqn], typeFqn, if ('.' !in typeFqn) "java.lang.$typeFqn" else null,
        )
        return candidates.any { loadClassAcross(it, initialize = false, preferred = classLoader)?.isInstance(value) == true }
    }

    private fun sourceTypeMatches(cls: ResolvedClass, typeFqn: String): Boolean {
        val simple = typeFqn.substringAfterLast('.')
        if (cls.fqn == typeFqn || cls.simpleName == simple) return true
        return cls.supertypes.any { sup ->
            sup == typeFqn || sup.substringAfterLast('.') == simple ||
                (sourceClass(sup)?.takeIf { it.fqn != cls.fqn }?.let { sourceTypeMatches(it, typeFqn) } == true)
        }
    }

    /** The class a `Type.member` receiver denotes when `Type` is a **static-member holder** — a bare type
     *  reference (a [Binding.ObjectRef]) to a runtime class that has NO object/companion singleton (a Java
     *  class like `java.lang.System`, or `Integer`/`Math`). Such a qualifier is not a value: its members are
     *  read/invoked statically off the class. Null when [receiverNode] isn't a bare type reference, the class
     *  can't load, or it does have a singleton (a Kotlin `object` / a companion-bearing type like `Color`),
     *  which keeps the existing instance path for those. */
    private fun staticHolderReceiver(receiverNode: RNode?): Class<*>? {
        val ref = (receiverNode as? RNode.Name)?.binding as? Binding.ObjectRef ?: return null
        val cls = loadClassAcross(ref.fqn, initialize = true, preferred = classLoader) ?: return null
        val hasSingleton = runCatching { cls.getField("INSTANCE") }.getOrNull() != null ||
            runCatching { cls.getField("Companion") }.getOrNull() != null
        return if (hasSingleton) null else cls
    }

    /** Read a static member `name` off [cls]: a public static field (`System.out`, `Integer.MAX_VALUE`) first,
     *  then a static no-arg getter (`getName()`, mangling-aware for a value-class-typed property). */
    private fun readStaticMember(cls: Class<*>, name: String): Any? {
        runCatching { cls.getField(name) }.getOrNull()
            ?.takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            ?.let { runCatching { it.isAccessible = true }; return it.get(null) }
        val getter = "get" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        cls.methods.firstOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && mangledNameMatches(it.name, getter)
        }?.let { runCatching { it.isAccessible = true }; return it.invoke(null) }
        throw InterpreterException("no static member `$name` on ${cls.name}")
    }

    /** The singleton of a nested `object` named [name] declared inside [enclosing] — `Icons.AutoMirrored`
     *  compiles to the class `<enclosing>$<name>` with its own static `INSTANCE`. Null when there is no such
     *  nested class or it isn't an object (no `INSTANCE`). */
    private fun nestedObjectInstance(enclosing: Class<*>, name: String): Any? {
        val nested = loadClassAcross("${enclosing.name}\$$name", initialize = true, preferred = classLoader)
            ?: return null
        return runCatching { nested.getField("INSTANCE") }.getOrNull()?.get(null)
    }

    /** Read a property by reflection: the Kotlin getter (`value` → `getValue()`), else a same-named no-arg
     *  method. A `MutableState.value` read goes through the real `getValue()`, so the snapshot system records
     *  the dependency on the enclosing recompose scope — which is what drives recomposition. */
    private fun readProperty(receiver: Any, name: String): Any? {
        if (receiver is SourceObject) return readSourceProperty(receiver, name)
        val getter = "get" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        (noArgMethod(receiver, getter) ?: noArgMethod(receiver, name))?.let { return it.invoke(receiver) }
        // No plain (no-arg) getter — the name may be a nested `object` accessed through its enclosing
        // object/class (`Icons.AutoMirrored`, `Icons.AutoMirrored.Filled`): a nested object compiles to
        // `<Enclosing>$<name>` with its own `INSTANCE`, not a getter on the receiver instance.
        nestedObjectInstance(receiver.javaClass, name)?.let { return it }
        // The property may be `@Composable` (its getter takes a `Composer`, e.g. `MaterialTheme.colorScheme`).
        // The Compose-aware dispatcher threads the live composer through it; the plain reflective dispatcher
        // returns null and we fall through to the honest boundary.
        dispatcher.readComposableProperty(receiver, name)?.let { return it.value }
        throw InterpreterException("no readable property `$name` on ${receiver.javaClass.name}")
    }

    /** Write a property by reflection: the Kotlin setter (`value` → `setValue(x)`), else a same-named one-arg
     *  method. A `MutableState.value` write goes through the real `setValue()`, so the snapshot system records
     *  the mutation and invalidates the enclosing recompose scope — which is what drives recomposition. */
    private fun writeProperty(receiver: Any, name: String, value: Any?) {
        if (receiver is SourceObject) { receiver.fields[name] = value; return }
        val setter = "set" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val m = (oneArgMethod(receiver, setter) ?: oneArgMethod(receiver, name))
            ?: throw InterpreterException("no writable property `$name` on ${receiver.javaClass.name}")
        m.invoke(receiver, value)
    }

    /** A one-arg method matching [name] (mangling-aware), preferring a public-declaring type. */
    private fun oneArgMethod(receiver: Any, name: String): java.lang.reflect.Method? =
        publicMethod(receiver.javaClass, name, 1)
            ?: receiver.javaClass.methods.firstOrNull { mangledNameMatches(it.name, name) && it.parameterCount == 1 }
                ?.also { runCatching { it.isAccessible = true } }

    /** Read a top-level property (`LocalTextStyle`, `kotlin.math.PI`): it compiles to a STATIC getter on its
     *  declaring `…Kt` file facade (`getLocalTextStyle()`), taking no arguments. The facade is initialized
     *  (its `<clinit>` populates the backing field). Name matched mangling-aware (a value-class-typed top-level
     *  property mangles its getter). */
    private fun readTopLevelProperty(ownerFqn: String, name: String): Any? {
        val cls = loadClassAcross(ownerFqn, initialize = true, preferred = classLoader)
            ?: throw InterpreterException("cannot load facade `$ownerFqn` for top-level property `$name`")
        val getter = "get" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val m = cls.methods.firstOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && mangledNameMatches(it.name, getter)
        } ?: throw InterpreterException("no top-level property getter `$name` on `$ownerFqn`")
        runCatching { m.isAccessible = true }
        return m.invoke(null)
    }

    /** Read an extension property (`16.dp`, `density.sp`): its getter is a STATIC method on the declaring
     *  `…Kt` facade taking the receiver as its only argument (`DpKt.getDp(int): Dp`). The JVM name is the
     *  Kotlin getter (`get` + capitalized name), MANGLED when the property's type is an inline value class
     *  (`Dp`/`TextUnit` → `getDp-<hash>`), which [mangledNameMatches] accepts. */
    private fun readExtensionProperty(receiver: Any, ownerFqn: String, name: String): Any? {
        val cls = loadClassAcross(ownerFqn, initialize = false, preferred = classLoader)
            ?: throw InterpreterException("cannot load facade `$ownerFqn` for extension property `$name`")
        val getter = "get" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val m = cls.methods.firstOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 1 && mangledNameMatches(it.name, getter)
        } ?: throw InterpreterException("no extension-property getter `$name` on `$ownerFqn`")
        runCatching { m.isAccessible = true }
        return m.invoke(null, receiver)
    }

    /** A no-arg method matching [name], allowing a mangled `name-<hash>` (a value-class-typed getter like
     *  `Color.Red` → `getRed-<hash>(): long`). Prefers a public-declaring type (invokable under the JDK module
     *  system); falls back to the concrete class with a best-effort `setAccessible`. */
    private fun noArgMethod(receiver: Any, name: String): java.lang.reflect.Method? =
        publicMethod(receiver.javaClass, name, 0)
            ?: receiver.javaClass.methods.firstOrNull { mangledNameMatches(it.name, name) && it.parameterCount == 0 }
                ?.also { runCatching { it.isAccessible = true } }

    private fun slotOf(binding: Binding): SlotId = when (binding) {
        is Binding.Local -> binding.slot
        is Binding.Param -> binding.slot
        else -> throw InterpreterException("not a slot binding: ${binding::class.simpleName}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun compare(op: String, a: Any?, b: Any?): Boolean {
        val c = when {
            a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
            a is Comparable<*> && b != null -> (a as Comparable<Any?>).compareTo(b)
            else -> throw InterpreterException("cannot compare $a and $b")
        }
        return when (op) {
            "lt" -> c < 0; "le" -> c <= 0; "gt" -> c > 0; "ge" -> c >= 0
            else -> throw InterpreterException("unknown comparison `$op`")
        }
    }

    private fun invoke0(receiver: Any, name: String): Any? {
        val m = noArgMethod(receiver, name) ?: throw InterpreterException("no `$name()` on ${receiver.javaClass.name}")
        return m.invoke(receiver)
    }

    private fun arithmetic(op: String, a: Any?, b: Any?): Any {
        if (a !is Number || b !is Number) throw InterpreterException("non-numeric operands for `$op`: $a, $b")
        return when {
            // Kotlin numeric promotion: Double > Float > Long > Int. Float MUST precede Long/Int — without its
            // case `0.82f * 100` fell to `applyI(0, 100)` = 0 (wrong value AND an Int, not a Float).
            a is Double || b is Double -> applyD(op, a.toDouble(), b.toDouble())
            a is Float || b is Float -> applyF(op, a.toFloat(), b.toFloat())
            a is Long || b is Long -> applyL(op, a.toLong(), b.toLong())
            else -> applyI(op, a.toInt(), b.toInt())
        }
    }

    private fun applyI(op: String, x: Int, y: Int): Int = when (op) {
        "plus" -> x + y; "minus" -> x - y; "times" -> x * y; "div" -> x / y; "rem" -> x % y
        else -> throw InterpreterException("unknown operator `$op`")
    }

    private fun applyL(op: String, x: Long, y: Long): Long = when (op) {
        "plus" -> x + y; "minus" -> x - y; "times" -> x * y; "div" -> x / y; "rem" -> x % y
        else -> throw InterpreterException("unknown operator `$op`")
    }

    private fun applyD(op: String, x: Double, y: Double): Double = when (op) {
        "plus" -> x + y; "minus" -> x - y; "times" -> x * y; "div" -> x / y; "rem" -> x % y
        else -> throw InterpreterException("unknown operator `$op`")
    }

    private fun applyF(op: String, x: Float, y: Float): Float = when (op) {
        "plus" -> x + y; "minus" -> x - y; "times" -> x * y; "div" -> x / y; "rem" -> x % y
        else -> throw InterpreterException("unknown operator `$op`")
    }

    /** Kotlin's numeric-conversion members (`toInt`/`toFloat`/…). The boxed JVM type (`java.lang.Float`) has no
     *  such method — they're intrinsics on the primitive — so the reflective dispatcher can't find them; compute
     *  them here. Null when [name] isn't a conversion. */
    private fun numericConversion(n: Number, name: String): Handled? = when (name) {
        "toInt" -> Handled(n.toInt())
        "toLong" -> Handled(n.toLong())
        "toFloat" -> Handled(n.toFloat())
        "toDouble" -> Handled(n.toDouble())
        "toByte" -> Handled(n.toByte())
        "toShort" -> Handled(n.toShort())
        "toChar" -> Handled(n.toInt().toChar())
        else -> null
    }

    /**
     * An interpreted lambda. It captures the [env] in effect where the lambda literal was evaluated, and each
     * invocation runs the body in a FRESH child of that captured scope — so a lambda created once but called
     * repeatedly (e.g. `repeat(n) { … }`) gets distinct param/local bindings per call, and a lambda created
     * per loop iteration captures that iteration's scope. Reads of an enclosing variable resolve outward;
     * writes to one update the scope that owns it. A non-local `return` propagates out as a [ReturnSignal],
     * matching inline-lambda semantics.
     */
    private inner class Closure(private val node: RNode.Lambda, private val env: Env) : InterpretedLambda {
        override val paramCount: Int get() = node.params.size
        override fun invoke(args: List<Any?>): Any? {
            val callEnv = Env(env)
            node.params.forEachIndexed { i, p -> callEnv.define(p.slot, args.getOrNull(i)) }
            return eval(node.body, callEnv)
        }
    }

    /**
     * A lexical scope. Slots are unique within a function, but a declaration inside a loop or lambda executes
     * many times — so each scope holds only its OWN bindings and chains to its [parent]. [read] walks outward
     * to find an enclosing binding; [define] introduces a binding in this scope (params, local `val`/`var`,
     * loop variable); [assign] updates the binding in whichever scope owns it (so a mutated outer `var` stays
     * visible). This per-scope isolation is what makes closures capture per-iteration values.
     */
    private class Env(private val parent: Env? = null) {
        private val slots = HashMap<Int, Any?>()

        fun read(slot: SlotId): Any? {
            var e: Env? = this
            while (e != null) {
                if (e.slots.containsKey(slot.value)) return e.slots[slot.value]
                e = e.parent
            }
            return null
        }

        fun define(slot: SlotId, value: Any?) { slots[slot.value] = value }

        fun assign(slot: SlotId, value: Any?) {
            var e: Env? = this
            while (e != null) {
                if (e.slots.containsKey(slot.value)) { e.slots[slot.value] = value; return }
                e = e.parent
            }
            slots[slot.value] = value // not previously declared (shouldn't happen post-resolution) → bind here
        }
    }

    /** Non-local control transfer for `return`; no stack trace (it's control flow, not an error). */
    private class ReturnSignal(val value: Any?) : RuntimeException(null, null, false, false)

    /** Carries a non-`Throwable` value thrown by interpreted `throw` (e.g. a source exception object), so a
     *  `try`/`catch` can still match and bind it. A real `Throwable` is thrown directly. */
    private class KotlinThrow(val value: Any?) : RuntimeException(null, null, false, false)

    private companion object {
        val ARITHMETIC = setOf("plus", "minus", "times", "div", "rem")
        val COMPARISON = setOf("lt", "le", "gt", "ge")
        val COMPONENT = Regex("component(\\d+)")
        /** Common Kotlin type names → their JVM classes, for `is`/`catch` checks against reflectable values. */
        val KOTLIN_TYPE_TO_JVM = mapOf(
            "kotlin.String" to "java.lang.String",
            "kotlin.CharSequence" to "java.lang.CharSequence",
            "kotlin.Int" to "java.lang.Integer",
            "kotlin.Long" to "java.lang.Long",
            "kotlin.Short" to "java.lang.Short",
            "kotlin.Byte" to "java.lang.Byte",
            "kotlin.Double" to "java.lang.Double",
            "kotlin.Float" to "java.lang.Float",
            "kotlin.Boolean" to "java.lang.Boolean",
            "kotlin.Char" to "java.lang.Character",
            "kotlin.Number" to "java.lang.Number",
            "kotlin.Any" to "java.lang.Object",
            "kotlin.Throwable" to "java.lang.Throwable",
            "kotlin.Exception" to "java.lang.Exception",
            "kotlin.RuntimeException" to "java.lang.RuntimeException",
            "kotlin.IllegalStateException" to "java.lang.IllegalStateException",
            "kotlin.IllegalArgumentException" to "java.lang.IllegalArgumentException",
            "kotlin.NullPointerException" to "java.lang.NullPointerException",
            "kotlin.collections.List" to "java.util.List",
            "kotlin.collections.Map" to "java.util.Map",
            "kotlin.collections.Set" to "java.util.Set",
        )
        /** The stdlib facades that declare the `@InlineOnly` functions the interpreter models as intrinsics:
         *  the scope functions (`repeat`/`let`/`run`/…) on `StandardKt`, and the empty/blank predicates on the
         *  string (`StringsKt`) and collection (`CollectionsKt`) facades. */
        val INLINE_INTRINSIC_FACADES = setOf("kotlin.StandardKt", "kotlin.text.StringsKt", "kotlin.collections.CollectionsKt")
        /** The `@InlineOnly` empty/blank predicates, dispatched by name in [evalEmptyBlankPredicate]. */
        val EMPTY_BLANK_PREDICATES = setOf("isNotBlank", "isNotEmpty", "isNullOrBlank", "isNullOrEmpty")
    }
}

/** Thrown when the interpreter meets a construct it cannot execute — the honest boundary, never a guess. */
class InterpreterException(message: String) : RuntimeException(message)

/**
 * A project-source instance the interpreter materializes from a [ResolvedClass] — source types aren't compiled
 * at preview/run time, so there is no bytecode to reflect. Properties live in [fields] (constructor `val`/`var`
 * params + body properties); [enumName]/[enumOrdinal] are set for an enum entry.
 *
 * `equals`/`hashCode`/`toString` honor data-class semantics so the value behaves the same whether compared by
 * the interpreter (`==` lowers to these) or by host code it is handed to. A non-data class keeps identity
 * semantics, and an enum entry prints its name.
 */
class SourceObject(val cls: ResolvedClass, val fields: MutableMap<String, Any?> = LinkedHashMap()) {
    var enumName: String? = null
    var enumOrdinal: Int = -1

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (!cls.isData || other !is SourceObject || other.cls.fqn != cls.fqn) return false
        return cls.componentNames.all { fields[it] == other.fields[it] }
    }

    override fun hashCode(): Int =
        if (!cls.isData) System.identityHashCode(this)
        else cls.componentNames.fold(0) { acc, n -> acc * 31 + (fields[n]?.hashCode() ?: 0) }

    override fun toString(): String = when {
        enumName != null -> enumName!!
        cls.isData -> "${cls.simpleName}(" + cls.componentNames.joinToString(", ") { "$it=${fields[it]}" } + ")"
        else -> "${cls.simpleName}@" + Integer.toHexString(System.identityHashCode(this))
    }
}
