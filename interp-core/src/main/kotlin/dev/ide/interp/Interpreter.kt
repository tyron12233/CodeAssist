package dev.ide.interp

import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.ClassFlavor
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.RParam
import dev.ide.lang.kotlin.interp.RTypeArg
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SecondaryCtor
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
    /** Best-effort PARTIAL evaluation (the Compose preview path): instead of refusing a function that contains
     *  an unsupported construct, run it and SKIP any whole-statement gap (a node the lowerer marked
     *  [RNode.Unsupported]) — so one unsupported widget/statement doesn't blank the whole preview; the rest
     *  still renders. Skipping happens BEFORE the node is evaluated, so no Compose group is left open. Off by
     *  default: the console Run (and tests) still fail loudly so a broken program is reported, not half-run. */
    private val tolerateGaps: Boolean = false,
    /** Live-edit dirty set: the `"name/arity"` keys of source composables whose body CHANGED since the last
     *  render. Such a call is forced to re-run (its `$changed` skip is overridden) so the edit shows, while
     *  unchanged composables still skip and keep their state. Empty = a normal (non-incremental) render. */
    private val dirtyCallees: Set<String> = emptySet(),
    /** Resolves the previewed project's resources (`R.string.x` ids, `@string`/`@color`/… values). Null (the
     *  default, and on desktop/lessons) leaves resource access degrading as before. */
    private val resources: PreviewResourceResolver? = null,
    /** Mediates every escape into real code — library dispatch, reflective property access, singleton class
     *  init (see [InterpreterHooks]). Null (the default) checks nothing. */
    private val hooks: InterpreterHooks? = null,
    /** Executes library classes the loaders cannot load (project-jar dependency code, run in the bytecode VM
     *  on device instead of a DexClassLoader). Null → the honest "cannot load" boundaries stand. */
    private val libraryFallback: LibraryExecutor? = null,
) {
    /** Source classes indexed by fully-qualified name and by simple name (a constructor callee carries the
     *  simple name; an object/enum reference carries the resolved FQN). */
    private val classesByFqn: Map<String, ResolvedClass> = classes.associateBy { it.fqn }
    private val classesBySimpleName: Map<String, ResolvedClass> =
        classes.groupBy { it.simpleName }.mapValues { it.value.first() }

    /** Lazily-materialized singletons: one per source `object`/`companion`, and one per enum entry. */
    private val singletons = HashMap<String, SourceObject>()

    /**
     * Per-thread guard state for the runaway bounds. The one [Interpreter] instance runs re-entrantly on BOTH
     * the composition (UI) thread and the suspend-bridge thread (a `LaunchedEffect` body), so this must be
     * thread-local rather than an instance field. [depth] bounds interpreted recursion (a StackOverflow in a
     * `@Composable` body would crash the app, not just the preview); [deadlineNanos] bounds one whole render
     * pass (0 = disarmed — set so on the managed bridge thread, where a cooperative `delay`-driven timer runs a
     * long wall-clock time and is bounded by the cooperative loop guard instead).
     */
    private class Frame {
        var depth = 0
        var deadlineNanos = 0L
    }

    private val frame = ThreadLocal.withInitial { Frame() }

    /** Bind [args] to [params]: a supplied argument fills its slot; an omitted one (fewer args than params, or
     *  an [OmittedArg] sentinel left by named-argument reordering) takes the parameter's default expression —
     *  evaluated in [env], so a default may read an earlier parameter (Kotlin's rule) — else null. This is how
     *  a call that omits a defaulted argument (`Greeting("x")` for `fun Greeting(name, modifier = Modifier)`)
     *  gets the declared default instead of a null hole. */
    private fun bindParams(env: Env, params: List<RParam>, args: List<Any?>) {
        val varargIdx = params.indexOfFirst { it.vararg }
        params.forEachIndexed { i, p ->
            when {
                // Kotlin binds a `vararg xs: T` parameter as `Array<T>`; the interpreter models it as a List
                // (whose `.size`, `xs[i]`, `for (x in xs)`, `.forEach`/`.map` all work — a raw Java array does
                // not respond to those). Pack the arguments from this slot up to the ones claimed by any fixed
                // parameters that follow (a vararg is almost always last, so that is normally all trailing args).
                // Without this the vararg slot took a single argument, so a use of `xs` as an array crashed
                // ("Not an array: …" on ART / "no property size" on the JVM).
                i == varargIdx -> {
                    val trailingFixed = params.size - i - 1
                    val endExclusive = (args.size - trailingFixed).coerceIn(i, args.size)
                    val packed = ArrayList<Any?>(maxOf(0, endExclusive - i))
                    for (j in i until endExclusive) if (args[j] !== OmittedArg) packed.add(args[j])
                    env.define(p.slot, packed)
                }
                // A fixed parameter AFTER the vararg takes its argument from the tail (rare — a vararg is
                // usually last), so it isn't mis-read as one of the vararg's elements.
                varargIdx in 0 until i -> {
                    val idx = args.size - (params.size - i)
                    val a = if (idx in args.indices) args[idx] else OmittedArg
                    env.define(p.slot, if (a !== OmittedArg) a else defaultValue(p, env))
                }
                else -> {
                    val supplied = i < args.size && args[i] !== OmittedArg
                    env.define(p.slot, if (supplied) args[i] else defaultValue(p, env))
                }
            }
        }
    }

    /**
     * Evaluate parameter [p]'s default expression, or null when it has none. In gap-tolerant mode (the Compose
     * preview path) a default that THROWS must not abort the whole render — a `@Preview` entry's parameters are
     * placeholders whose defaults the preview supplies, and a default like `Build.VERSION.SDK_INT` can't be
     * evaluated off-device (no `android.os.Build` on the desktop classpath), yet the body typically never reads
     * the parameter. So a throwing default degrades to a type-appropriate zero/null and the preview still renders;
     * outside gap tolerance (console Run, tests) the throw propagates so a genuinely broken program is reported.
     */
    private fun defaultValue(p: RParam, env: Env): Any? {
        val default = p.default ?: return null
        if (!tolerateGaps) return eval(default, env)
        return try {
            eval(default, env)
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce // recomposition cancellation is control flow — never swallow it
        } catch (e: Exception) {
            zeroForType(p.type?.qualifiedName)
        }
    }

    /** A benign stand-in for a parameter whose default couldn't be evaluated (gap-tolerant mode): the zero of a
     *  primitive type so a later arithmetic/boolean read doesn't NPE on unboxing, else null. */
    private fun zeroForType(qualifiedName: String?): Any? = when (qualifiedName) {
        "kotlin.Int" -> 0
        "kotlin.Long" -> 0L
        "kotlin.Short" -> 0.toShort()
        "kotlin.Byte" -> 0.toByte()
        "kotlin.Char" -> '\u0000'
        "kotlin.Double" -> 0.0
        "kotlin.Float" -> 0f
        "kotlin.Boolean" -> false
        else -> null
    }

    /** The declared value-parameter count the resolver pinned for [callee] (a source callee carries it in its
     *  `declId`, a library callee in its `paramTypes`) — the authoritative arity to look a source target up by,
     *  since a call may supply FEWER arguments than that when it omits defaulted parameters. */
    private fun declaredArity(callee: ResolvedCallable): Int? = when (callee) {
        is ResolvedCallable.Source -> callee.declId.substringAfterLast('/').toIntOrNull()
        is ResolvedCallable.Library -> callee.paramTypes.size
    }

    /** The lowered source function [callee] targets: the resolver-chosen overload (by declared arity) first, so
     *  an omitted-defaults call finds the full declaration rather than a same-named shorter overload; the exact
     *  call-arity key is the fallback when the declared arity isn't recorded. */
    private fun sourceFunctionFor(callee: ResolvedCallable, argCount: Int): ResolvedFunction? =
        declaredArity(callee)?.let { functions["${callee.displayName}/$it"] }
            ?: functions["${callee.displayName}/$argCount"]

    fun call(fn: ResolvedFunction, args: List<Any?>): Any? = invokeFunction(fn, NO_RECEIVER, args)

    /** Marker for [invokeFunction]: no receiver to bind (a plain call), distinct from a genuine `null` receiver
     *  (a nullable extension receiver, `fun String?.f()` called on null). */
    private val NO_RECEIVER = Any()

    /** Marker distinguishing a `getValue` call (no value argument) from a `setValue` in [callDelegateOp] —
     *  a real `setValue(null)` writes a genuine null, so a plain nullable default would be ambiguous. */
    private val NO_DELEGATE_VALUE = Any()

    /** A stand-in for the `KProperty<*>` a `by`-delegate operator receives. A source delegate that reads
     *  `property.name` sees the delegated property's name (via reflective `getName()`); a delegate that ignores
     *  it is unaffected. Not a real `kotlin.reflect.KProperty` (that interface is far larger than a preview
     *  needs), so a library operator with a strictly-typed `KProperty` parameter falls back to a null property
     *  (see [reflectDelegateOp]). */
    private class InterpretedKProperty(private val propName: String) {
        fun getName(): String = propName
        override fun toString(): String = "property $propName"
    }

    /** Run [fn]'s body with [args] bound to its parameters and, when [receiver] is not [NO_RECEIVER], the
     *  receiver value bound to its [ResolvedFunction.receiverSlot] (an extension receiver / member `this`). */
    private fun invokeFunction(fn: ResolvedFunction, receiver: Any?, args: List<Any?>, reifiedTypes: Map<String, RTypeArg> = emptyMap()): Any? {
        // A function with unsupported nodes is refused outright — UNLESS gap tolerance is on (the preview path),
        // where it runs and skips the gaps (see [tolerateGaps]).
        if (!tolerateGaps && !fn.isComplete) {
            throw InterpreterException("function `${fn.name}` has unsupported nodes: ${fn.diagnostics}")
        }
        val f = frame.get()
        // Recursion guard: an interpreted `fun f() = f()` (or mutual recursion) would otherwise StackOverflow the
        // in-process host thread — an app crash. Bound the interpreted call depth and throw first, so the preview
        // surfaces an error. The bound gives a clean early abort on a large-stack thread (the composition/main
        // thread); a smaller-stack thread (recompose/bridge) can overflow sooner, so the real backstop is the
        // StackOverflowError catch below — the depth guard can't be a single value safe across every stack size.
        if (++f.depth > MAX_CALL_DEPTH) {
            f.depth--
            throw InterpreterException("call depth exceeded $MAX_CALL_DEPTH frames (unbounded recursion) — aborting to avoid crashing the preview")
        }
        // Arm a whole-pass wall-clock deadline at the outermost interpreted call. The per-loop guard bounds any
        // single loop; this additionally bounds a broad/deep call tree, many sequential loops, or heavy library
        // work that no single loop guard sees — so the composition thread can't freeze past an ANR. Disarmed on
        // the managed bridge thread (a legitimate long-running cooperative timer lives there).
        if (f.depth == 1) f.deadlineNanos = if (SuspendContext.isActive) 0L else System.nanoTime() + MAX_RENDER_NANOS
        else checkDeadline(f)
        val env = Env()
        env.defineReified(reifiedTypes)
        if (receiver !== NO_RECEIVER) fn.receiverSlot?.let { env.define(it, receiver) }
        bindParams(env, fn.params, args)
        return try {
            eval(fn.body, env)
        } catch (r: ReturnSignal) {
            r.value
        } catch (so: StackOverflowError) {
            // Backstop below the depth guard: on a smaller stack the real overflow fires before MAX_CALL_DEPTH.
            // Convert it to a bounded InterpreterException so the preview shows an error instead of an Error
            // propagating (and, on an untracked thread, crashing the app). A pre-allocated instance is thrown so
            // no allocation is attempted while the stack is exhausted.
            throw RECURSION_ABORT
        } finally {
            f.depth--
        }
    }

    /** Trip the whole-pass deadline (see [call]) if armed and elapsed. Cheap enough to call per interpreted call. */
    private fun checkDeadline(f: Frame) {
        val dl = f.deadlineNanos
        if (dl != 0L && System.nanoTime() > dl)
            throw InterpreterException("preview interpretation ran longer than ${MAX_RENDER_NANOS / 1_000_000}ms — aborting to avoid hanging the preview")
    }

    /**
     * Materialize a `@PreviewParameter` provider and read its sample values (capped at [limit]). [sourceClass]
     * is the lowered provider when it is project source (instantiated as a [SourceObject]); otherwise
     * [providerFqn] names a library `PreviewParameterProvider` loaded + instantiated reflectively. Reads the
     * provider's `values` (a `Sequence`/`Iterable`/array) and returns up to [limit] elements. Empty when the
     * provider can't be built or has no readable `values` — the caller then renders the preview without args.
     */
    fun previewParameterValues(sourceClass: ResolvedClass?, providerFqn: String?, limit: Int): List<Any?> {
        val provider: Any = when {
            sourceClass != null -> instantiate(sourceClass, emptyList())
            providerFqn != null -> {
                val cls = loadInitialized(providerFqn) ?: return emptyList()
                runCatching {
                    cls.getDeclaredConstructor().also { c -> runCatching { c.isAccessible = true } }.newInstance()
                }.getOrNull() ?: return emptyList()
            }
            else -> return emptyList()
        }
        val values = runCatching { readProperty(provider, "values") }.getOrNull() ?: return emptyList()
        val all: List<Any?> = when (values) {
            is Sequence<*> -> values.toList()
            is Iterable<*> -> values.toList()
            is Array<*> -> values.toList()
            else -> return emptyList()
        }
        return if (limit > 0) all.take(limit) else all
    }

    private fun eval(node: RNode, env: Env): Any? = when (node) {
        is RNode.Const -> node.value
        is RNode.Name -> readBinding(node.binding, env)
        is RNode.Block -> {
            var last: Any? = Unit
            for (st in node.statements) {
                // Partial rendering: skip a whole-statement gap rather than aborting the block. Skipping BEFORE
                // evaluating keeps the composition balanced (the node never opens a group). A nested gap inside
                // an otherwise-supported statement still surfaces — only a statement that IS a gap is skipped.
                if (tolerateGaps && st is RNode.Unsupported) continue
                last = if (tolerateGaps) {
                    // A recoverable cannot-load boundary hit while evaluating a statement (e.g. a Compose icon
                    // whose per-icon facade isn't loadable) skips just that statement — one missing icon leaves
                    // the rest of the UI rendering, instead of the whole preview failing.
                    try { eval(st, env) } catch (b: InterpreterBoundaryException) { Unit }
                } else {
                    eval(st, env)
                }
            }
            last
        }
        is RNode.LocalVar -> {
            env.define(node.slot, node.initializer?.let { eval(it, env) })
            Unit
        }
        is RNode.Assign -> {
            val target = node.target
            val binding = (target as? RNode.Name)?.binding
            when {
                // A local `by`-delegate write: `delegate.setValue(null, property, value)`.
                binding is Binding.DelegatedConvention ->
                    delegateSetValue(env.read(binding.slot), binding.propertyName, thisRef = null, value = eval(node.value, env))
                binding is Binding.Local || binding is Binding.Param ->
                    env.assign(slotOf(binding), eval(node.value, env))
                else -> throw InterpreterException("unsupported assignment target: ${target::class.simpleName}")
            }
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
            // declared in the body is distinct per iteration; the condition reads the enclosing scope. A
            // `break` exits the loop (caught here); a `continue` ends the iteration (caught in runLoopBody).
            val budget = LoopBudget()
            try {
                if (node.doWhile) {
                    do { runLoopBody(node.body, Env(env), node.label); guardLoop(budget) } while (eval(node.condition, env) == true)
                } else {
                    while (eval(node.condition, env) == true) { runLoopBody(node.body, Env(env), node.label); guardLoop(budget) }
                }
            } catch (b: BreakSignal) { /* break exits the loop */
            } catch (b: LabeledBreakSignal) { if (!handledHere(b, node.label)) throw b }
            Unit
        }
        is RNode.ForEach -> {
            val iterable = eval(node.iterable, env) ?: throw InterpreterException("cannot iterate null")
            val iterator = invoke0(iterable, "iterator") ?: throw InterpreterException("no iterator() on ${iterable.javaClass.name}")
            val budget = LoopBudget()
            try {
                while (invoke0(iterator, "hasNext") == true) {
                    // Fresh per-iteration scope: the loop variable (and any closure capturing it) is bound anew
                    // each pass, matching Kotlin's per-iteration capture rather than aliasing one shared slot.
                    val iterEnv = Env(env)
                    iterEnv.define(node.loopVar.slot, invoke0(iterator, "next"))
                    runLoopBody(node.body, iterEnv, node.label)
                    guardLoop(budget)
                }
            } catch (b: BreakSignal) { /* break exits the loop */
            } catch (b: LabeledBreakSignal) { if (!handledHere(b, node.label)) throw b }
            Unit
        }
        is RNode.Break -> throw (node.label?.let { LabeledBreakSignal(it) } ?: BreakSignal)
        is RNode.Continue -> throw (node.label?.let { LabeledContinueSignal(it) } ?: ContinueSignal)
        is RNode.Call -> evalCall(node, env)
        is RNode.Lambda -> { InterpProfile.count("closures"); Closure(node, env) }
        is RNode.StringConcat -> buildString { node.parts.forEach { append(eval(it, env)?.toString() ?: "null") } }
        is RNode.PropertyGet -> {
            val binding = node.binding
            val receiverNode = node.receiver
            // A source enum entry (`Color.RED`) or a source object/companion member (`Config.name`) addressed
            // through a bare type reference — resolve before evaluating the (non-value) type-qualifier receiver.
            // `R.<type>.<name>` (a project resource id): the read's binding owner is the nested synthetic R class
            // (`<ns>.R.string`), which has no bytecode to reflect. Resolve the aapt-shaped id from the injected
            // resolver BEFORE touching the receiver — evaluating the synthetic R chain (`PropertyGet(ObjectRef(
            // "<ns>.R"), "string")`) would fail. Null for a non-R property → falls through to normal handling.
            resourceFieldId(binding)?.let { return it }
            if (isRResourceRead(binding)) {
                // A resolver is wired but couldn't map this field (unknown name/namespace): degrade to an
                // unresolved id (0) — the downstream `stringResource(0)`/… is intercepted (→ empty) rather than
                // crashing the preview on the bytecode-less synthetic `R`.
                if (resources != null) return 0
                // No resolver at all: `id 0` would reach the REAL resource call and throw opaquely, so surface a
                // clear, actionable reason naming the resource instead of the generic "can't load R" object error.
                val owner = (binding as? Binding.Property)?.ownerFqn
                val rType = owner?.substringAfterLast('.')
                throw InterpreterException(
                    "can't resolve resource `R.$rType.${binding.name}` — the preview has no project resource " +
                        "resolver (the module's resources didn't load), so `stringResource`/`colorResource`/… can't resolve"
                )
            }
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
                    // The receiver evaluated to a CLASS
                    if (receiver is Class<*>) readStaticMember(receiver, binding.name)
                    else {
                        val extOwner = (binding as? Binding.Property)?.takeIf { it.isExtension }?.ownerFqn
                        if (extOwner != null) readExtensionProperty(receiver, extOwner, binding.name)
                        else readProperty(receiver, binding.name)
                    }
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
            // `x is T` for a reified T: substitute the concrete type bound in this frame (the erased `T` never
            // matches). An unbound reified param falls back to the literal `typeFqn`.
            val typeFqn = node.reifiedParam?.let { env.reifiedType(it)?.fqn } ?: node.typeFqn
            val matches = isInstanceOf(eval(node.value, env), typeFqn)
            if (node.negated) !matches else matches
        }
        is RNode.Cast -> {
            val v = eval(node.value, env)
            val typeFqn = node.reifiedParam?.let { env.reifiedType(it)?.fqn } ?: node.typeFqn
            when {
                v == null -> if (node.safe || node.nullable) null
                else throw ClassCastException("null cannot be cast to non-null type $typeFqn")
                // true → confirmed match; null → the type couldn't be resolved (a type parameter / unmapped
                // type), so trust the compiler and pass through; false → a confirmed mismatch.
                castMatches(v, typeFqn) == false ->
                    if (node.safe) null
                    else throw ClassCastException("${v.javaClass.name} cannot be cast to $typeFqn")
                else -> v
            }
        }
        is RNode.ClassLiteral -> {
            val recvNode = node.receiver
            // `T::class` for a reified T: load the concrete type bound in this frame (its own candidates), the
            // same class-token stand-in strategy [typeCandidates] uses for a project-source type.
            val reifiedCandidates = node.reifiedParam?.let { env.reifiedType(it)?.loadCandidates }
            val cls: Class<*> = if (recvNode != null) {
                (eval(recvNode, env) ?: throw InterpreterException("cannot take `::class` of a null value")).javaClass
            } else {
                // Try the resolved type then its supertypes; a project-source type isn't on the preview
                // classpath, so a reflectable supertype stands in (the value is only ever a class token).
                (reifiedCandidates ?: node.typeCandidates).firstNotNullOfOrNull { loadClassAcross(it, initialize = false, preferred = classLoader) }
                    ?: if (tolerateGaps) Any::class.java
                    else throw InterpreterException("cannot resolve class literal `${(reifiedCandidates ?: node.typeCandidates).firstOrNull() ?: node.reifiedParam ?: "?"}::class` (not on the preview classpath)")
            }
            if (node.asJava) cls else cls.kotlin
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
                    // Control-flow signals are not exceptions to catch — a `catch (e: Exception)` must NOT
                    // swallow a `return`/`break`/`continue` (they extend RuntimeException). The `finally` still runs.
                    if (t is ReturnSignal || t is LoopSignal) throw t
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
        // A bare `this` the resolver couldn't bind to a slot (no class member / extension / receiver lambda in
        // scope). The resolver now lowers that case to Unsupported, so this is a defensive fallback for the
        // contract node — a clear boundary rather than a cryptic "not yet interpretable".
        is RNode.This -> throw InterpreterException("`this` has no receiver bound in this frame")
        else -> throw InterpreterException("not yet interpretable: ${node::class.simpleName}")
    }

    /** The concrete reified type bound to a call's type argument [arg] in [env]: a passthrough type parameter
     *  (`bar<T>()` inside `fun <reified T> foo()`) re-binds from the caller frame; a concrete type resolves to
     *  itself. Null when unbound / unresolved. */
    private fun resolveTypeArg(arg: RTypeArg, env: Env): RTypeArg? {
        val passthrough = arg.typeParamRef
        return if (passthrough != null) env.reifiedType(passthrough) else arg.takeIf { it.fqn != null }
    }

    /** The reified type-parameter bindings a source-function [call] establishes for its callee's frame — the
     *  callee's type-parameter names mapped to the concrete types resolved from the call's type arguments (in
     *  the caller's [env], so a passthrough type parameter re-binds). Empty for a non-generic call. */
    private fun reifiedBindingsFor(call: RNode.Call, env: Env): Map<String, RTypeArg> {
        val names = call.callee.typeParameterNames
        if (names.isEmpty() || call.typeArguments.isEmpty()) return emptyMap()
        val out = HashMap<String, RTypeArg>(names.size)
        names.forEachIndexed { i, name ->
            call.typeArguments.getOrNull(i)?.let { resolveTypeArg(it, env) }?.let { out[name] = it }
        }
        return out
    }

    /** Every dispatcher handoff funnels here so [hooks] sees ALL non-source calls regardless of which
     *  dispatcher impl (reflective or Compose) serves them: consult the hook, then dispatch. */
    private fun checkedDispatch(call: RNode.Call, receiver: Any?, args: List<Any?>): Any? {
        hooks?.let { h ->
            when (val d = h.beforeCall(call, receiver, args)) {
                is HookDecision.Replace -> return d.value
                is HookDecision.Deny -> throw InterpreterSecurityException(d.reason)
                HookDecision.Proceed -> {}
            }
        }
        return try {
            dispatcher.dispatch(call, receiver, args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // A reflectively-invoked library/user method threw. Surface the REAL cause (its type, message, and
            // stack) instead of the opaque `InvocationTargetException` wrapper, so the preview's error view is
            // actionable rather than showing "InvocationTargetException" with no detail.
            val cause = unwrapInvocationTarget(e)
            // A library function with a reified type parameter (`filterIsInstance`, `enumValues`, …) compiles to a
            // JVM method whose body just `throw UnsupportedOperationException(…)` — it can only be inlined, never
            // called. The general recovery: run it INTERPRETED through the library executor's bytecode VM, which
            // reproduces the compiler's reification transform with the call-site type argument (see
            // [LibraryExecutor.invokeReifiedInline]). If no executor is wired (or it declines — no class bytes, an
            // unmodeled reified op, an unresolvable/source type), convert Kotlin's runtime guard into a clean
            // interpreter boundary (a skippable gap in preview) instead of leaking a raw crash.
            if (cause is UnsupportedOperationException && cause.message?.contains("reified type parameter") == true) {
                reifiedInlineFallback(call, receiver, args)?.let { return it.value }
                throw InterpreterException(
                    "`${call.callee.displayName}` is a library function with a reified type parameter — it can " +
                        "only be inlined at compile time, so the interpreter can't call it directly",
                )
            }
            throw cause
        }
    }

    /** Recover a library reified inline call (one that threw the reification-marker guard) by running it through
     *  the library executor's bytecode VM. Builds the reified type map (each callee type-parameter name → the JVM
     *  internal name of its resolved concrete argument; unresolved ones are skipped — the marker only reads the
     *  reified names) and the full JVM argument list (an extension receiver is prepended). Null when there is no
     *  executor, the callee shape is unsupported, or no type argument resolved. */
    private fun reifiedInlineFallback(call: RNode.Call, receiver: Any?, args: List<Any?>): LibraryValue? {
        val callee = call.callee as? ResolvedCallable.Library ?: return null
        val typeFqn = call.typeArguments.firstOrNull()?.fqn
        // `enumValues<T>()` / `enumValueOf<T>(name)` — computed directly from the concrete enum type (a source
        // enum from its lowered entries, a library enum reflectively), rather than through the VM: the enum's
        // identity is the type argument, which we already have.
        if (typeFqn != null && (callee.displayName == "enumValues" || callee.displayName == "enumValueOf")) {
            enumReifiedIntrinsic(callee.displayName, typeFqn, args)?.let { return it }
        }
        // Everything else: run the reified inline INTERPRETED through the library executor's VM.
        val exec = libraryFallback ?: return null
        val owner = callee.ownerFqn ?: return null
        val types = HashMap<String, String>()
        callee.typeParameterNames.forEachIndexed { i, name ->
            call.typeArguments.getOrNull(i)?.fqn?.let { types[name] = (KOTLIN_TYPE_TO_JVM[it] ?: it).replace('.', '/') }
        }
        if (types.isEmpty()) return null
        val jvmArgs = when (call.dispatch) {
            DispatchKind.TOP_LEVEL -> args
            DispatchKind.EXTENSION -> listOf(receiver) + args
            else -> return null
        }
        return exec.invokeReifiedInline(owner, callee.displayName, types, jvmArgs)
    }

    /** `enumValues<T>()` → all entries of enum [typeFqn] (as a List, the interpreter's array model);
     *  `enumValueOf<T>(name)` → the matching entry. Handles a source enum (its lowered entries) and a library
     *  enum (reflective). Null when [typeFqn] isn't a known enum. */
    private fun enumReifiedIntrinsic(name: String, typeFqn: String, args: List<Any?>): LibraryValue? {
        sourceClass(typeFqn)?.takeIf { it.flavor == ClassFlavor.ENUM }?.let { sc ->
            return when (name) {
                "enumValues" -> LibraryValue(sc.enumEntries.map { enumEntryInstance(sc, it) })
                else -> {
                    val n = args.firstOrNull() as? String
                    LibraryValue(sc.enumEntries.firstOrNull { it.name == n }?.let { enumEntryInstance(sc, it) }
                        ?: throw InterpreterException("no enum constant $typeFqn.$n"))
                }
            }
        }
        val cls = loadClassAcross(typeFqn, initialize = true, preferred = classLoader)?.takeIf { it.isEnum } ?: return null
        val constants = cls.enumConstants?.toList() ?: return null
        return when (name) {
            "enumValues" -> LibraryValue(constants)
            else -> {
                val n = args.firstOrNull() as? String
                LibraryValue(constants.firstOrNull { (it as? Enum<*>)?.name == n }
                    ?: throw InterpreterException("no enum constant $typeFqn.$n"))
            }
        }
    }

    /** Peel nested [java.lang.reflect.InvocationTargetException] wrappers to the innermost real cause. */
    private fun unwrapInvocationTarget(thrown: Throwable): Throwable {
        var cur = thrown
        while (cur is java.lang.reflect.InvocationTargetException) cur = cur.targetException ?: cur.cause ?: return cur
        return cur
    }

    /** Consult [hooks] before a reflective property read; [Handled] carries a Replace value (a legitimate
     *  null included), null means proceed with the real read. */
    private fun hookPropertyRead(ownerFqn: String?, name: String, receiver: Any?): Handled? {
        val h = hooks ?: return null
        return when (val d = h.beforePropertyRead(ownerFqn, name, receiver)) {
            is HookDecision.Replace -> Handled(d.value)
            is HookDecision.Deny -> throw InterpreterSecurityException(d.reason)
            HookDecision.Proceed -> null
        }
    }

    /** [loadClassAcross] WITH static init, gated by [InterpreterHooks.beforeClassInit] — running `<clinit>`
     *  is code execution, so a hooked host may veto it. A denied class simply behaves as not loadable (the
     *  callers' existing honest "cannot load …" boundaries report it). */
    private fun loadInitialized(fqn: String): Class<*>? =
        if (hooks?.beforeClassInit(fqn) == false) null
        else loadClassAcross(fqn, initialize = true, preferred = classLoader)

    private fun evalCall(call: RNode.Call, env: Env): Any? {
        InterpProfile.count("calls")
        val callee = call.callee
        // Inside a `sequence { … }` block (running on a generator's producer thread), `yield(v)` / `yieldAll(vs)`
        // stream elements to the consumer through the active generator. Intercepted by name — a generator is
        // active only inside such a block, so this can't hijack an unrelated `yield`.
        activeGenerator.get()?.let { gen ->
            if (call.args.size == 1) when (callee.displayName) {
                "yield" -> { gen.emit(eval(call.args[0].value, env)); return Unit }
                "yieldAll" -> { forEachElement(eval(call.args[0].value, env)) { gen.emit(it) }; return Unit }
            }
        }
        // Operators are computed intrinsically: arithmetic/comparison on numbers and structural equality have
        // no JVM method to invoke (a synthetic callee). Arithmetic on a non-number falls through to dispatch.
        if (call.dispatch == DispatchKind.OPERATOR) {
            val op = callee.displayName
            val left = eval(call.receiver ?: throw InterpreterException("operator without receiver"), env)
            // A project-source class with `operator fun plus`/`minus`/`compareTo`/… dispatches to that member
            // (also drives `a += b`, which desugars through the arithmetic operator). Routed here so the RHS is
            // evaluated once, by `dispatchSourceMember`. `eq`/`ne` fall through to structural equality below
            // (`==` uses the class's `equals`, which a data class already provides — not a `compareTo`).
            if (left is SourceObject) {
                when {
                    op in COMPARISON -> {
                        val cmp = (dispatchSourceMember(left, "compareTo", call, env) as? Number)?.toInt()
                            ?: throw InterpreterException("`compareTo` on ${left.cls.fqn} did not return an Int")
                        return when (op) { "lt" -> cmp < 0; "gt" -> cmp > 0; "le" -> cmp <= 0; else -> cmp >= 0 }
                    }
                    op in ARITHMETIC -> return dispatchSourceMember(left, op, call, env)
                }
            }
            val right = eval(call.args.first().value, env)
            when {
                op == "eq" -> return left == right
                op == "ne" -> return left != right
                op == "refeq" -> return left === right // `===` referential identity
                op == "refne" -> return left !== right // `!==`
                op in COMPARISON -> return compare(op, left, right)
                op == "plus" && left is String -> return left + right?.toString() // String.plus(Any?)
                op in ARITHMETIC && left is Number && right is Number -> return arithmetic(op, left, right)
                op in ARITHMETIC -> {} // a real operator method on a user/library type → fall through to dispatch
            }
        }
        // Array construction (`arrayOf(…)`, `Array(n) { … }`, `intArrayOf(…)`, `xs.toTypedArray()`, …): these
        // are compiler intrinsics with no invocable JVM method (the reflective path fails with a bogus owner
        // `kotlin`/`kotlin.Array`), so build the result here. Modeled as a List (the interpreter's
        // size/index/iteration paths support List — the vararg parameter model does the same).
        // `sequence { … }` — a lazy generator. It takes a `suspend SequenceScope.() -> Unit` with no reflectable
        // synchronous form, so run the block on a producer thread (see [interpretedSequence]); `yield` streams
        // elements on demand, which is what makes an infinite generator terminate under `take(n)`.
        if (callee is ResolvedCallable.Library && callee.ownerFqn?.endsWith("SequencesKt") == true &&
            callee.displayName == "sequence" && call.args.size == 1
        ) {
            (eval(call.args[0].value, env) as? InterpretedLambda)?.let { return interpretedSequence(it) }
        }
        // `enumValues<T>()` / `enumValueOf<T>(name)` — reified inlines whose owner is the bare `kotlin` package
        // (not a reflectable facade), so intercept before dispatch and compute from the concrete enum type.
        if (callee is ResolvedCallable.Library && call.dispatch == DispatchKind.TOP_LEVEL &&
            (callee.displayName == "enumValues" || callee.displayName == "enumValueOf")
        ) {
            call.typeArguments.firstOrNull()?.let { resolveTypeArg(it, env) }?.fqn?.let { fqn ->
                enumReifiedIntrinsic(callee.displayName, fqn, call.args.map { eval(it.value, env) })?.let { return it.value }
            }
        }
        // Coroutine builders. `runBlocking { }` runs its block to a result on the calling thread (a suspend
        // context, so `delay` sleeps — runBlocking's real semantics). Inside a coroutine scope, `launch { }` /
        // `async { }` run their child block cooperatively: `launch` is fire-and-forget (a Job), `async` captures
        // the result for `await`. Gated to the coroutines package so a same-named user function isn't hijacked,
        // and `launch`/`async` only inside a scope so a preview's SuspendBridge-driven `launch` is untouched.
        if (callee is ResolvedCallable.Library && callee.ownerFqn?.contains("coroutines") == true && call.args.isNotEmpty()) {
            when (callee.displayName) {
                "runBlocking" -> (eval(call.args.last().value, env) as? InterpretedLambda)?.let { block ->
                    return SuspendContext.runManaged { withCoroutineScope { runCoroutineBlock(block) } }
                }
                "launch" -> if (coroutineDepth.get() > 0) (eval(call.args.last().value, env) as? InterpretedLambda)?.let { block ->
                    runCoroutineBlock(block); return CoroutineJob()
                }
                "async" -> if (coroutineDepth.get() > 0) (eval(call.args.last().value, env) as? InterpretedLambda)?.let { block ->
                    return CoroutineDeferred(runCoroutineBlock(block))
                }
            }
        }
        arrayConstructionIntrinsic(call, env)?.let { return it.value }
        // Invoking a function value (`fn(x)`, `callback()`) where `fn` is a local/param/property holding a
        // lambda: an interpreted lambda is called directly; a JVM functional object goes through the dispatcher
        // (its `invoke` method).
        if (call.dispatch == DispatchKind.INVOKE) {
            val target = call.receiver?.let { eval(it, env) }
            // A source class with `operator fun invoke(...)` — dispatch to that member (RHS evaluated once there).
            if (target is SourceObject) return dispatchSourceMember(target, "invoke", call, env)
            val argv = call.args.map { eval(it.value, env) }
            return if (target is InterpretedLambda) target.invoke(argv) else checkedDispatch(call, target, argv)
        }
        // A source constructor materializes a [SourceObject] (the type isn't compiled at preview/run time).
        if (callee is ResolvedCallable.Source && call.dispatch == DispatchKind.CONSTRUCTOR) {
            sourceClass(callee)?.let { cls ->
                val argv = call.args.map { eval(it.value, env) }
                // Pick a secondary constructor when the call's arity can't fit the primary (fewer than the
                // required primary params, or more than the primary declares). Match a secondary by arity — a
                // best-effort selection, since same-arity constructor overloads collide on the declId key; the
                // primary is preferred whenever the arguments fit it.
                val n = call.args.size
                val requiredPrimary = cls.primaryParams.count { it.default == null }
                if (n !in requiredPrimary..cls.primaryParams.size) {
                    cls.secondaryCtors.firstOrNull { it.params.size == n }?.let { secondary ->
                        val obj = newSourceObject(cls)
                        constructViaSecondary(cls, obj, secondary, reorderNamedArgs(secondary.params.map { it.name }, call.args, argv))
                        return obj
                    }
                }
                return instantiate(cls, reorderNamedArgs(cls.primaryParams.map { it.name }, call.args, argv))
            }
        }
        // A source enum's static members addressed through the type: `Color.values()` / `Color.valueOf("RED")`
        // — the receiver is the enum type (not an instance), so resolve it without evaluating the receiver.
        enumStaticCall(call, env)?.let { return it.value }
        // A source function is interpreted recursively (its body is available). A `@Composable` one is run
        // through the composable invoker (restart group + recomposition) when a Compose host is present.
        if (callee is ResolvedCallable.Source && call.dispatch == DispatchKind.TOP_LEVEL) {
            InterpProfile.count("src")
            // Look the target up by its DECLARED arity, not the call's argument count: a call that omits
            // trailing defaulted parameters (`Greeting("Compose")` for `fun Greeting(name, modifier = Modifier)`)
            // has fewer args than the function's declared arity, and would otherwise miss (`no source function
            // Greeting/1`). Named arguments are reordered into declared positions and omitted defaults filled
            // by [bindParams].
            val target = sourceFunctionFor(callee, call.args.size)
                ?: throw InterpreterException("no source function `${callee.displayName}/${call.args.size}`")
            val argv = reorderNamedArgs(target.params.map { it.name }, call.args, call.args.map { eval(it.value, env) })
            val reified = reifiedBindingsFor(call, env)
            val invoke = { invokeFunction(target, NO_RECEIVER, argv, reified) }
            return if (callee.isComposable && composableInvoker != null) {
                // The dirty set is keyed by declared `name/arity` (the program keys), so use the target's.
                val key = "${callee.displayName}/${target.params.size}"
                val force = key in dirtyCallees
                if (InterpTrace.enabled) InterpTrace.log("dispatch @Composable $key key=${call.callSiteKey.value} force=$force")
                composableInvoker.invokeComposable(call.callSiteKey.value, target.returnsUnit, force, argv, invoke)
            } else {
                invoke()
            }
        }
        // A project-source top-level EXTENSION function is interpreted (its body is available). The extension
        // receiver value is bound to the function's receiver slot; the value args to its parameters. A LIBRARY
        // extension carries its `…Kt` facade owner and is reflected by the dispatcher instead — only a Source
        // extension reaches here (without this it fell through to reflection → "extension has no owner").
        if (callee is ResolvedCallable.Source && call.dispatch == DispatchKind.EXTENSION) {
            InterpProfile.count("src")
            val target = sourceFunctionFor(callee, call.args.size)
                ?: throw InterpreterException("no source extension `${callee.displayName}/${call.args.size}`")
            val recv = call.receiver?.let { eval(it, env) }
            val argv = reorderNamedArgs(target.params.map { it.name }, call.args, call.args.map { eval(it.value, env) })
            return invokeFunction(target, recv, argv, reifiedBindingsFor(call, env))
        }
        // A handful of stdlib functions are `@kotlin.internal.InlineOnly` — they have NO callable JVM method
        // (they exist only to be inlined), so the reflective dispatcher can never find them. We execute them as
        // intrinsics, running the interpreted lambda in-process. This also keeps the ambient composer intact for
        // a composable call inside the lambda (e.g. `repeat(n) { Text(...) }`) — exactly what the inlined form
        // would do — so the composables compose into the enclosing group rather than blowing up the dispatcher.
        if (callee is ResolvedCallable.Library &&
            (callee.ownerFqn in INLINE_INTRINSIC_FACADES || callee.ownerFqn?.let { it.startsWith("kotlinx.coroutines") || it.startsWith("androidx.compose.runtime") } == true)
        ) {
            evalInlineIntrinsic(call, env)?.let { return it.value }
        }
        // A static method invoked through a class qualifier (`System.currentTimeMillis()`): the receiver is a
        // bare type reference to a class with no object/companion singleton, so there is no instance to
        // evaluate — dispatch it statically on the owning class (the callee already carries that owner).
        if (callee is ResolvedCallable.Library && staticHolderReceiver(call.receiver) != null) {
            val args = call.args.map { eval(it.value, env) }
            val staticCall = call.copy(dispatch = DispatchKind.TOP_LEVEL, receiver = null)
            return checkedDispatch(staticCall, null, args)
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
            return checkedDispatch(call, scope, args)
        }
        // `super.foo(...)`: dispatch to the SUPERCLASS implementation, skipping the lexical class's own override.
        if (call.dispatch == DispatchKind.SUPER) {
            val receiver = call.receiver?.let { eval(it, env) }
            if (receiver is SourceObject) {
                val lexical = (callee as? ResolvedCallable.Source)?.declId?.substringBeforeLast('/')?.substringBeforeLast('.')
                val m = lexical?.let {
                    // By declared arity first (an omitted-defaults super call), then the exact call arity.
                    (declaredArity(callee)?.let { a -> superMethod(it, "${callee.displayName}/$a") }
                        ?: superMethod(it, "${callee.displayName}/${call.args.size}"))
                }
                if (m != null) {
                    val argv = reorderNamedArgs(m.params.map { it.name }, call.args, call.args.map { eval(it.value, env) })
                    return callMethod(m, receiver, argv)
                }
                // The universal `Any`/`Object` supers have a meaningful default even without a real super
                // instance — an `override fun toString()` calling `super.toString()` expects a String, not Unit.
                when (callee.displayName) {
                    "toString" -> if (call.args.isEmpty()) return "${receiver.cls.simpleName}@${Integer.toHexString(System.identityHashCode(receiver))}"
                    "hashCode" -> if (call.args.isEmpty()) return System.identityHashCode(receiver)
                    "equals" -> if (call.args.size == 1) return receiver === eval(call.args[0].value, env)
                }
                // Any other binary/library superclass method (`Activity.onCreate`): there is no source body and a
                // SourceObject isn't a real subclass instance to reflect a `super` call into, so it is a no-op —
                // the override still lowers and runs, and a preview never needs the framework's own behavior. (On
                // the console-run path this is handled for real: the bytecode VM builds a peer of the actual
                // superclass, so `super.foo()` invokes the compiled superclass method.) Arguments are still
                // evaluated for their side effects.
                call.args.forEach { eval(it.value, env) }
                return Unit
            }
            // A non-source receiver can't carry a super relationship the interpreter models — honest boundary.
            throw InterpreterException("`super.${callee.displayName}` on a non-source receiver is not supported")
        }
        // Everything else (library/member/constructor) goes through the host dispatcher — except a member call
        // whose receiver turns out to be a source instance, which can't be reflected and is interpreted instead
        // (covers both a `Source` member callee and a synthetic `contains`/`componentN` on a source object).
        val receiver = call.receiver?.let { eval(it, env) }
        // `deferred.await()` — the `async` builder produced a completed [CoroutineDeferred]; return its value.
        if (receiver is CoroutineDeferred && callee.displayName == "await") return receiver.value
        if (receiver is SourceObject && call.dispatch == DispatchKind.MEMBER) {
            return dispatchSourceMember(receiver, callee.displayName, call, env)
        }
        // Numeric conversions (`(progress * 100).toInt()`, `x.toFloat()`) and bitwise operators (`x and y`,
        // `1 shl 4`, `x.inv()`) are compiler intrinsics on `Int`/`Long` — the boxed JVM type has no such method,
        // so the reflective dispatcher would fail. Compute them here. A bitwise name only reaches this branch
        // with a `Number` receiver (a user's own `infix fun Foo.and` has a non-Number receiver), so it is safe
        // to claim the call.
        if (call.dispatch == DispatchKind.MEMBER && receiver is Number) {
            if (call.args.isEmpty()) {
                numericConversion(receiver, callee.displayName)?.let { return it.value }
                if (callee.displayName == "inv") return bitwiseInv(receiver)
            } else if (call.args.size == 1 && callee.displayName in BITWISE) {
                val rhs = eval(call.args.first().value, env)
                if (rhs is Number) return bitwiseBinary(callee.displayName, receiver, rhs)
                throw InterpreterException("non-numeric operand for bitwise `${callee.displayName}`: $rhs")
            }
        }
        val args = call.args.map { eval(it.value, env) }
        // A library `suspend` function (`delay`, a Ktor/Room/… suspend API, any project suspend fun already
        // handled above). Invoked GENERICALLY through the continuation bridge — the real method takes a trailing
        // `Continuation`; we append a blocking one and park the fiber until it resumes. No per-function code: any
        // suspend function works the same way. Only on a coroutine fiber (a `runBlocking`/`launch`/`LaunchedEffect`
        // body); a stray suspend call off one keeps the honest boundary rather than blocking the UI thread.
        if (callee is ResolvedCallable.Library && callee.isSuspend && SuspendContext.isActive) {
            return dispatchSuspend(call, receiver, args)
        }
        return try {
            checkedDispatch(call, receiver, args)
        } catch (e: InterpreterException) {
            // A hook refusal is already precise — don't re-attribute it to the inline-only boundary.
            throw if (e is InterpreterSecurityException) e else refineInlineOnlyError(call, e)
        }
    }

    /** Invoke a real library `suspend` function through the continuation bridge: append a blocking
     *  [kotlin.coroutines.Continuation] to the argument list, dispatch reflectively, and — if it suspends
     *  ([COROUTINE_SUSPENDED]) — park the fiber until the continuation resumes, returning its value (or
     *  rethrowing its failure). This is the ONE path every suspend function takes; nothing is hardcoded per
     *  function. Runs on a coroutine fiber, so blocking the thread is the fiber's own suspension. */
    private fun dispatchSuspend(call: RNode.Call, receiver: Any?, args: List<Any?>): Any? {
        SuspendContext.markSuspended() // a real suspension point → the enclosing loop counts as cooperative
        return callSuspendBlocking { cont ->
            checkedDispatch(call, receiver, args + cont)
        }.let { if (it === Unit || it == kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) Unit else it }
    }

    /** Run [invoke] (a real suspend-function call given the continuation), parking the current fiber thread if it
     *  suspends. Pure `kotlin.coroutines`: a fresh [kotlin.coroutines.Continuation] resumes a latch, and its
     *  result (or exception) is returned once available. */
    private fun callSuspendBlocking(invoke: (kotlin.coroutines.Continuation<Any?>) -> Any?): Any? {
        val latch = java.util.concurrent.CountDownLatch(1)
        val outcome = java.util.concurrent.atomic.AtomicReference<Result<Any?>>()
        val cont = kotlin.coroutines.Continuation<Any?>(kotlin.coroutines.EmptyCoroutineContext) { r ->
            outcome.set(r); latch.countDown()
        }
        val immediate = invoke(cont)
        if (immediate !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) return immediate
        latch.await() // the fiber's suspension: block this thread until the real coroutine resumes it
        return outcome.get().getOrThrow()
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
     * Build an array-construction intrinsic ([ARRAY_OF_FUNCTIONS] / [ARRAY_CONSTRUCTORS] / [TO_ARRAY_FUNCTIONS]
     * / `arrayOfNulls`), or null if [call] isn't one. Kotlin's `arrayOf`/`Array(size){}`/… are compiler
     * intrinsics with no invocable JVM method, so the reflective dispatcher can't run them (they surface as a
     * "cannot load class `kotlin`/`kotlin.Array`" boundary). Each result is a `List` — the interpreter's index
     * (`a[i]`), size (`a.size`), iteration (`for`/`forEach`), and transform (`map`/…) paths all work on List,
     * whereas a raw Java array responds to none of them; the `vararg` parameter model uses the same List form.
     * Gated to a non-source callee so a user's own `class Array` / `fun arrayOf` isn't hijacked.
     */
    private fun arrayConstructionIntrinsic(call: RNode.Call, env: Env): Handled? {
        if (call.callee is ResolvedCallable.Source) return null
        val name = call.callee.displayName
        return when {
            // arrayOf(a, b, c) / intArrayOf(1, 2) / emptyArray() → the (evaluated) elements, spreads flattened.
            name in ARRAY_OF_FUNCTIONS && call.dispatch == DispatchKind.TOP_LEVEL -> {
                val out = ArrayList<Any?>(call.args.size)
                for (a in call.args) {
                    val v = eval(a.value, env)
                    if (a.spread) toElementList(v).forEach { out.add(it) } else out.add(v)
                }
                Handled(out)
            }
            // arrayOfNulls(n) → n nulls.
            name == "arrayOfNulls" && call.dispatch == DispatchKind.TOP_LEVEL && call.args.size == 1 -> {
                val n = intArg(call.args[0], env)
                Handled(ArrayList<Any?>(n).apply { repeat(n) { add(null) } })
            }
            // Array(size) { init } / IntArray(size) { init } / … → apply `init` to each index 0 until size.
            name in ARRAY_CONSTRUCTORS && call.args.size == 2 -> {
                val n = intArg(call.args[0], env)
                val init = eval(call.args[1].value, env) as? InterpretedLambda
                Handled(ArrayList<Any?>(n).apply { for (i in 0 until n) add(init?.invoke(listOf(i))) })
            }
            // IntArray(size) / DoubleArray(size) / … (no init) → zero-filled to the primitive's zero.
            name in ARRAY_CONSTRUCTORS && name != "Array" && call.args.size == 1 -> {
                val n = intArg(call.args[0], env)
                val zero = primitiveArrayZero(name)
                Handled(ArrayList<Any?>(n).apply { repeat(n) { add(zero) } })
            }
            // xs.toTypedArray() / xs.toIntArray() / … → the elements as a List (arrays are Lists here anyway).
            name in TO_ARRAY_FUNCTIONS && call.dispatch == DispatchKind.EXTENSION ->
                Handled(toElementList(call.receiver?.let { eval(it, env) }))
            else -> null
        }
    }

    /** The int value of an argument (an array size), 0 when it isn't a number or is negative. */
    private fun intArg(arg: RArg, env: Env): Int = ((eval(arg.value, env) as? Number)?.toInt() ?: 0).coerceAtLeast(0)

    /** The zero element a size-only primitive-array constructor fills with (`IntArray(3)` → three `0`s). */
    private fun primitiveArrayZero(ctor: String): Any = when (ctor) {
        "LongArray" -> 0L
        "DoubleArray" -> 0.0
        "FloatArray" -> 0f
        "ShortArray" -> 0.toShort()
        "ByteArray" -> 0.toByte()
        "CharArray" -> ' '
        "BooleanArray" -> false
        else -> 0 // IntArray
    }

    /** Any array-like value (List/Collection, real Java array of objects or primitives) flattened to a List;
     *  a single non-collection value becomes a one-element list, null an empty one. */
    private fun toElementList(v: Any?): List<Any?> = when (v) {
        null -> emptyList()
        is List<*> -> v
        is Collection<*> -> v.toList()
        is Array<*> -> v.toList()
        is IntArray -> v.toList()
        is LongArray -> v.toList()
        is DoubleArray -> v.toList()
        is FloatArray -> v.toList()
        is ShortArray -> v.toList()
        is ByteArray -> v.toList()
        is CharArray -> v.toList()
        is BooleanArray -> v.toList()
        else -> listOf(v)
    }

    /** Iterate any `forEach`-able receiver (collection / sequence / object or primitive array / map entries) —
     *  the element supply for the `forEach`/`forEachIndexed` intrinsics. */
    private fun forEachElement(recv: Any?, block: (Any?) -> Unit) {
        when (recv) {
            null -> {}
            is Iterable<*> -> recv.forEach(block)
            is Sequence<*> -> recv.forEach(block)
            is Map<*, *> -> recv.entries.forEach(block)
            is Array<*> -> recv.forEach(block)
            is IntArray -> recv.forEach { block(it) }
            is LongArray -> recv.forEach { block(it) }
            is DoubleArray -> recv.forEach { block(it) }
            is FloatArray -> recv.forEach { block(it) }
            is ShortArray -> recv.forEach { block(it) }
            is ByteArray -> recv.forEach { block(it) }
            is CharArray -> recv.forEach { block(it) }
            is BooleanArray -> recv.forEach { block(it) }
            else -> throw InterpreterException("`forEach` receiver is not iterable (${recv::class.simpleName})")
        }
    }

    /**
     * Execute a known `@InlineOnly` stdlib intrinsic ([STDLIB_FACADE] callees), or return null if [call] isn't
     * one. Covers the scope functions — both the `it`-lambda forms (`let`/`also`/`takeIf`/`takeUnless`) and the
     * receiver-lambda forms (`apply`/`with`/`run`), whose body binds an implicit `this`: the lowerer gives a
     * receiver lambda a leading `<this>` slot, so passing the receiver as the lambda's first argument binds it.
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
                val budget = LoopBudget()
                for (i in 0 until times) { action.invoke(listOf(i)); guardLoop(budget) }
                Handled(Unit)
            }
            // `iterable.forEach { element -> … }` / `forEachIndexed { i, element -> … }` — inline HOFs, run HERE
            // (like `repeat`) so a body that EMITS COMPOSABLES in a loop composes into the ambient composition
            // with the enclosing composer intact. The reflective path (real `CollectionsKt.forEach`) runs the
            // loop body inside a library frame, which drives the enclosing content lambda's composables through a
            // group discipline the interpreter doesn't control — the `Column { list.forEach { Row { … } } }`
            // preview failure. Iterating here keeps composable-group balance the same as an interpreted `for`.
            name == "forEach" && call.dispatch == DispatchKind.EXTENSION && args.size == 1 -> {
                val action = lambda(args[0].value)
                val budget = LoopBudget()
                forEachElement(receiver()) { element -> action.invoke(listOf(element)); guardLoop(budget) }
                Handled(Unit)
            }
            name == "forEachIndexed" && call.dispatch == DispatchKind.EXTENSION && args.size == 1 -> {
                val action = lambda(args[0].value)
                val budget = LoopBudget()
                var i = 0
                forEachElement(receiver()) { element -> action.invoke(listOf(i++, element)); guardLoop(budget) }
                Handled(Unit)
            }
            // `iterable.filterIsInstance<R>()` — a reified inline extension. Its JVM method exists but its body is
            // just `throw UnsupportedOperationException` (a reified type parameter can only be inlined), so it can
            // never be dispatched reflectively. The call site's reified type argument IS captured on the node, so
            // filter here (`isInstanceOf` handles source + library element types). The type-taking overload
            // `filterIsInstance(klass)` (one value arg) is left to normal dispatch.
            name == "filterIsInstance" && call.dispatch == DispatchKind.EXTENSION && args.isEmpty() -> {
                val typeFqn = call.typeArguments.firstOrNull()?.let { resolveTypeArg(it, env) }?.fqn
                    ?: throw InterpreterException("`filterIsInstance` needs a resolvable reified type argument")
                val out = ArrayList<Any?>()
                forEachElement(receiver()) { if (isInstanceOf(it, typeFqn)) out.add(it) }
                Handled(out)
            }
            // `key(vararg keys, block)` — an inline @Composable that wraps `block` in a movable group keyed by
            // `keys` so changing a key resets the subtree's state. There is no reflectively-invocable form here:
            // it is @InlineOnly, and its vararg `keys` compiles to a NON-last `Object[]` the compiler packs at the
            // call site (which ComposableAbi can't bind — the key lands in the array slot as a scalar → an
            // "ABI invoke mismatch"). Run the block in the ambient composition, like `repeat` runs its action; the
            // block's composables thread the current composer. The movable-group identity isn't modeled (the keys
            // are pure identity markers, not evaluated) — an acceptable preview degradation vs. crashing. Returns
            // the block's value (`key(a) { … }` can be used for its result).
            name == "key" && call.dispatch == DispatchKind.TOP_LEVEL && args.isNotEmpty() ->
                Handled(lambda(args.last().value).invoke(emptyList()))
            // `delay(millis)` — kept as a special case (arbitrary OTHER suspend functions go through the general
            // continuation bridge; see `dispatchSuspend`). Two reasons delay stays here: the resolver canonicalizes
            // it to a package-owner callee that isn't reflectively dispatchable, and an interruptible `Thread.sleep`
            // gives clean cooperative-timer cancellation (a cancelled coroutine interrupts the sleep) that the
            // SuspendBridge tests depend on. Under a managed coroutine only; off one it throws (never blocks the UI).
            name == "delay" && call.dispatch == DispatchKind.TOP_LEVEL && args.size == 1 -> {
                val millis = (eval(args[0].value, env) as? Number)?.toLong()
                    ?: throw InterpreterException("`delay` requires a numeric millisecond argument")
                if (!SuspendContext.isActive)
                    throw InterpreterException("`delay` outside an interpreted coroutine (no suspend bridge)")
                if (millis > 0) Thread.sleep(millis) // interruptible: coroutine cancellation → InterruptedException
                SuspendContext.markSuspended() // a real suspension → the enclosing loop is cooperative, not runaway
                Handled(Unit)
            }
            // `yield()` — a cooperation point. In the single-block interpreter model there's nothing to yield TO,
            // so it's a no-op; deliberately does NOT mark a suspension, so a `while (true) { yield() }` with no
            // real work stays bounded by the runaway guard rather than spinning forever off-thread.
            name == "yield" && call.dispatch == DispatchKind.TOP_LEVEL && args.isEmpty() -> {
                Thread.yield(); Handled(Unit)
            }
            // `ensureActive()` — a cancellation check. Cancellation is delivered by interrupting the bridge thread
            // (which surfaces at the next `delay`), so this is a no-op cooperation point.
            name == "ensureActive" && args.isEmpty() -> Handled(Unit)
            // `withContext(context) { block }` — run the block. The interpreter already runs on the bridge's
            // background thread, so the dispatcher switch is a no-op; the (evaluated) context is ignored. A
            // suspend call inside the block (`delay`) still suspends. `coroutineScope`/`supervisorScope { block }`
            // likewise just run their block sequentially (child-concurrency — `launch`/`async` inside — is not
            // modeled and degrades). The block may be a `CoroutineScope`-receiver lambda; [runSuspendBlock] binds
            // a null receiver when it has a `<this>` slot (a block using `this.launch` isn't supported anyway).
            name == "withContext" && call.dispatch == DispatchKind.TOP_LEVEL && args.size == 2 ->
                Handled(withCoroutineScope { runSuspendBlock(lambda(args[1].value)) })
            // `coroutineScope`/`supervisorScope { block }` — run the block under a coroutine scope so a child
            // `launch`/`async` inside runs cooperatively (structured concurrency: all children complete before
            // the scope returns, which the eager run guarantees).
            (name == "coroutineScope" || name == "supervisorScope") && call.dispatch == DispatchKind.TOP_LEVEL && args.size == 1 ->
                Handled(withCoroutineScope { runSuspendBlock(lambda(args[0].value)) })
            // `withFrameNanos { t -> … }` / `withFrameMillis { t -> … }` — a frame-driven animation loop
            // (`while (running) { withFrameNanos { … } }`). Real vsync isn't available in the interpreter, so
            // simulate a ~60fps cadence with a short interruptible sleep and hand the block a monotonic frame
            // time; marks a suspension so the enclosing loop counts as cooperative (not runaway). Under the
            // coroutine bridge only (off the UI thread); outside it throws, degrading to the old behavior.
            (name == "withFrameNanos" || name == "withFrameMillis") && args.size == 1 -> {
                if (!SuspendContext.isActive)
                    throw InterpreterException("`$name` outside an interpreted coroutine (no suspend bridge)")
                Thread.sleep(FRAME_MILLIS) // interruptible: cancellation aborts the animation loop
                SuspendContext.markSuspended()
                val now = System.nanoTime()
                val frameTime = if (name == "withFrameMillis") now / 1_000_000L else now
                Handled(lambda(args[0].value).invoke(listOf(frameTime)))
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
            // run { … } (the no-receiver top-level form)
            name == "run" && call.dispatch == DispatchKind.TOP_LEVEL && args.size == 1 ->
                Handled(lambda(args[0].value).invoke(emptyList()))
            // x.apply { this -> … } → runs the receiver-lambda with `x` bound as its `this`, returns x. The lambda
            // already carries a leading `<this>` slot (the lowerer adds it for a receiver lambda), so passing the
            // receiver as the sole argument binds it — same shape as `also`, but yielding x.
            name == "apply" && call.dispatch == DispatchKind.EXTENSION && args.size == 1 -> {
                val recv = receiver()
                lambda(args[0].value).invoke(listOf(recv))
                Handled(recv)
            }
            // x.run { this -> … } (the extension form) → runs the receiver-lambda with `x` as `this`, returns its result.
            name == "run" && call.dispatch == DispatchKind.EXTENSION && args.size == 1 ->
                Handled(lambda(args[0].value).invoke(listOf(receiver())))
            // with(x) { this -> … } → like `x.run { … }` but `x` is the FIRST argument, not the receiver.
            name == "with" && call.dispatch == DispatchKind.TOP_LEVEL && args.size == 2 ->
                Handled(lambda(args[1].value).invoke(listOf(eval(args[0].value, env))))
            // `xs.sumOf { selector }` / `xs.sum()` — @InlineOnly (no JVM method); sum the (selected) elements,
            // preserving Int/Long/Double as Kotlin does. `withIndex()`/ranges are Iterable, so all flow through.
            name == "sumOf" && call.dispatch == DispatchKind.EXTENSION && args.size == 1 -> {
                val elems = (receiver() as? Iterable<*>) ?: return null
                val sel = lambda(args[0].value)
                Handled(numericSum(elems.map { sel.invoke(listOf(it)) }))
            }
            name == "sum" && call.dispatch == DispatchKind.EXTENSION && args.isEmpty() ->
                (receiver() as? Iterable<*>)?.let { Handled(numericSum(it.toList())) }
            // `xs.maxOf/minOf { selector }` — the max/min of the selected Comparable values.
            (name == "maxOf" || name == "minOf") && call.dispatch == DispatchKind.EXTENSION && args.size == 1 -> {
                val elems = (receiver() as? Iterable<*>)?.toList() ?: return null
                if (elems.isEmpty()) throw InterpreterException("`$name` on an empty collection")
                val sel = lambda(args[0].value)
                Handled(reduceByComparison(elems.map { sel.invoke(listOf(it)) }, wantMax = name == "maxOf"))
            }
            // `list.getOrElse(index) { default }` — the element, or the lambda's result (given the index) when out of bounds.
            name == "getOrElse" && call.dispatch == DispatchKind.EXTENSION && args.size == 2 -> {
                val list = receiver() as? List<*> ?: return null
                val idx = (eval(args[0].value, env) as? Number)?.toInt() ?: return null
                Handled(if (idx in list.indices) list[idx] else lambda(args[1].value).invoke(listOf(idx)))
            }
            // `s.uppercase()` / `s.lowercase()` — @InlineOnly one-liners over the receiver CharSequence.
            (name == "uppercase" || name == "lowercase") && call.dispatch == DispatchKind.EXTENSION && args.isEmpty() ->
                (receiver() as? CharSequence)?.toString()?.let { Handled(if (name == "uppercase") it.uppercase() else it.lowercase()) }
            // `s.split(delimiter)` — the common single-String/Char delimiter form (the vararg + defaulted
            // ignoreCase/limit make the reflective overload hard to bind).
            name == "split" && call.dispatch == DispatchKind.EXTENSION && args.size == 1 -> {
                val s = (receiver() as? CharSequence)?.toString() ?: return null
                when (val d = eval(args[0].value, env)) {
                    is String -> Handled(s.split(d))
                    is Char -> Handled(s.split(d))
                    else -> null
                }
            }
            // `buildList/buildString/buildMap { this -> … }` — create the mutable builder, run the receiver-lambda
            // with it as `this` (its leading `<this>` slot), and yield the built result. The trailing lambda is
            // the last arg (a `buildList(capacity) { }` overload passes the capacity first).
            name == "buildList" && call.dispatch == DispatchKind.TOP_LEVEL && args.isNotEmpty() -> {
                val list = ArrayList<Any?>()
                lambda(args.last().value).invoke(listOf(list)); Handled(list)
            }
            name == "buildString" && call.dispatch == DispatchKind.TOP_LEVEL && args.isNotEmpty() -> {
                val sb = StringBuilder()
                lambda(args.last().value).invoke(listOf(sb)); Handled(sb.toString())
            }
            name == "buildMap" && call.dispatch == DispatchKind.TOP_LEVEL && args.isNotEmpty() -> {
                val map = LinkedHashMap<Any?, Any?>()
                lambda(args.last().value).invoke(listOf(map)); Handled(map)
            }
            // The empty/blank predicate family is `@InlineOnly` (each compiles to a one-liner over a real
            // receiver method like `isBlank()`/`isEmpty()`/`length`), so no JVM method exists to reflect into.
            // They take no lambda — compute them directly on the evaluated receiver, branching by runtime type
            // so the CharSequence and Collection/Map overloads of `isNotEmpty`/`isNullOrEmpty` both resolve.
            call.dispatch == DispatchKind.EXTENSION && args.isEmpty() && name in EMPTY_BLANK_PREDICATES ->
                evalEmptyBlankPredicate(name, receiver())
            // `"fmt".format(args)` (extension on a String) / `String.format(fmt, args)` (companion) + their
            // Locale overloads — @InlineOnly delegations to java.lang.String.format, so no JVM method exists to
            // reflect. Route to the real formatter here.
            name == "format" -> {
                val recv = runCatching { call.receiver?.let { eval(it, env) } }.getOrNull()
                val argv = args.flatMap { val v = eval(it.value, env); if (v is Array<*>) v.toList() else listOf(v) }
                when {
                    recv is CharSequence -> doStringFormat(recv.toString(), argv)               // "fmt".format([locale,] args)
                    argv.isEmpty() -> null
                    argv[0] is java.util.Locale && argv.size >= 2 ->                              // String.format(locale, fmt, args)
                        Handled(String.format(argv[0] as java.util.Locale, argv[1]?.toString() ?: "null", *argv.drop(2).toTypedArray()))
                    else -> doStringFormat(argv[0]?.toString() ?: "null", argv.drop(1))          // String.format(fmt, args)
                }
            }
            // The precondition family — @InlineOnly (they carry contracts). A failed check throws the real
            // exception, which surfaces as a preview error exactly as the compiled app would behave (rather than
            // the opaque "inline-only function not modeled").
            name == "error" && call.dispatch == DispatchKind.TOP_LEVEL && args.size == 1 ->
                throw IllegalStateException((eval(args[0].value, env) ?: "null").toString())
            name == "TODO" && call.dispatch == DispatchKind.TOP_LEVEL ->
                throw NotImplementedError("An operation is not implemented" +
                    (if (args.isNotEmpty()) ": ${eval(args[0].value, env)}" else "."))
            name == "require" && call.dispatch == DispatchKind.TOP_LEVEL && args.isNotEmpty() -> {
                if (eval(args[0].value, env) != true) throw IllegalArgumentException(lazyMessage(args, env, "Failed requirement."))
                Handled(Unit)
            }
            name == "check" && call.dispatch == DispatchKind.TOP_LEVEL && args.isNotEmpty() -> {
                if (eval(args[0].value, env) != true) throw IllegalStateException(lazyMessage(args, env, "Check failed."))
                Handled(Unit)
            }
            name == "requireNotNull" && call.dispatch == DispatchKind.TOP_LEVEL && args.isNotEmpty() -> {
                val v = eval(args[0].value, env) ?: throw IllegalArgumentException(lazyMessage(args, env, "Required value was null."))
                Handled(v)
            }
            name == "checkNotNull" && call.dispatch == DispatchKind.TOP_LEVEL && args.isNotEmpty() -> {
                val v = eval(args[0].value, env) ?: throw IllegalStateException(lazyMessage(args, env, "Required value was null."))
                Handled(v)
            }
            // `kotlin.math.*` — @InlineOnly delegations to java.lang.Math (no JVM method to reflect).
            name in MATH_UNARY && call.dispatch == DispatchKind.TOP_LEVEL && args.size == 1 -> {
                val x = (eval(args[0].value, env) as? Number)?.toDouble() ?: return null
                Handled(MATH_UNARY.getValue(name)(x))
            }
            // `abs(x)` — type-preserving (Int/Long/Float/Double keep their type; other numbers → Double).
            name == "abs" && call.dispatch == DispatchKind.TOP_LEVEL && args.size == 1 ->
                when (val x = eval(args[0].value, env)) {
                    is Int -> Handled(if (x < 0) -x else x)
                    is Long -> Handled(if (x < 0) -x else x)
                    is Float -> Handled(Math.abs(x))
                    is Double -> Handled(Math.abs(x))
                    is Number -> Handled(Math.abs(x.toDouble()))
                    else -> null
                }
            // `min(a, b)` / `max(a, b)` — two numbers; compare in Double, return the original (type-preserving).
            (name == "min" || name == "max") && call.dispatch == DispatchKind.TOP_LEVEL && args.size == 2 -> {
                val a = eval(args[0].value, env) as? Number ?: return null
                val b = eval(args[1].value, env) as? Number ?: return null
                val pickA = if (name == "max") a.toDouble() >= b.toDouble() else a.toDouble() <= b.toDouble()
                Handled(if (pickA) a else b)
            }
            // `hypot(x, y)` / `atan2(y, x)` — two-argument functions.
            (name == "hypot" || name == "atan2") && call.dispatch == DispatchKind.TOP_LEVEL && args.size == 2 -> {
                val a = (eval(args[0].value, env) as? Number)?.toDouble() ?: return null
                val b = (eval(args[1].value, env) as? Number)?.toDouble() ?: return null
                Handled(if (name == "hypot") Math.hypot(a, b) else Math.atan2(a, b))
            }
            // `x.pow(n)` — Double.pow(Double) / Double.pow(Int); both fold to Math.pow in Double.
            name == "pow" && call.dispatch == DispatchKind.EXTENSION && args.size == 1 -> {
                val base = (receiver() as? Number)?.toDouble() ?: return null
                val exp = (eval(args[0].value, env) as? Number)?.toDouble() ?: return null
                Handled(Math.pow(base, exp))
            }
            // `x.roundToInt()` / `x.roundToLong()` — ties-up rounding on a Double/Float receiver.
            (name == "roundToInt" || name == "roundToLong") && call.dispatch == DispatchKind.EXTENSION && args.isEmpty() -> {
                val x = (receiver() as? Number)?.toDouble() ?: return null
                Handled(if (name == "roundToInt") Math.round(x).toInt() else Math.round(x))
            }
            // `list.elementAtOrElse(index) { default }` — @InlineOnly; the in-range element, else the default lambda.
            name == "elementAtOrElse" && call.dispatch == DispatchKind.EXTENSION && args.size == 2 -> {
                val list = (receiver() as? List<*>) ?: return null
                val idx = (eval(args[0].value, env) as? Number)?.toInt() ?: return null
                Handled(if (idx in list.indices) list[idx] else lambda(args[1].value).invoke(listOf(idx)))
            }
            // `xs.firstNotNullOf { transform }` / `firstNotNullOfOrNull { }` — the first non-null transform result;
            // @InlineOnly. `firstNotNullOf` throws when none is found, the `OrNull` variant yields null.
            (name == "firstNotNullOf" || name == "firstNotNullOfOrNull") && call.dispatch == DispatchKind.EXTENSION && args.size == 1 -> {
                val transform = lambda(args[0].value)
                val budget = LoopBudget()
                var found: Any? = null
                var done = false
                forEachElement(receiver()) { element ->
                    if (!done) { val v = transform.invoke(listOf(element)); guardLoop(budget); if (v != null) { found = v; done = true } }
                }
                if (found == null && name == "firstNotNullOf")
                    throw NoSuchElementException("No element of the collection was transformed to a non-null value.")
                Handled(found)
            }
            else -> null
        }
    }

    /** java.lang.String.format for the @InlineOnly `format` intrinsics: an optional leading Locale in [argv]
     *  selects the locale-aware overload; otherwise the args are the format arguments. */
    private fun doStringFormat(fmt: String, argv: List<Any?>): Handled {
        val loc = argv.firstOrNull() as? java.util.Locale
        val fa = (if (loc != null) argv.drop(1) else argv).toTypedArray()
        return Handled(if (loc != null) String.format(loc, fmt, *fa) else String.format(fmt, *fa))
    }

    /** The message for a failed `require`/`check`/`*NotNull`: the second argument is a lazy-message lambda
     *  (`require(x) { "..." }`); [fallback] is the stdlib's default when none is supplied. */
    private fun lazyMessage(args: List<RArg>, env: Env, fallback: String): String {
        val node = args.getOrNull(1)?.value ?: return fallback
        val lambda = eval(node, env) as? InterpretedLambda ?: return fallback
        return runCatching { lambda.invoke(emptyList())?.toString() }.getOrNull() ?: fallback
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

    /** Run a suspend scoping block (`withContext`/`coroutineScope` body) — a `CoroutineScope`-receiver lambda.
     *  Binds a null receiver when the lowered lambda carries a `<this>` slot (paramCount ≥ 1); a block that uses
     *  `this` — `this.launch { }` — isn't modeled anyway and degrades. */
    private fun runSuspendBlock(block: InterpretedLambda): Any? =
        block.invoke(if (block.paramCount >= 1) listOf<Any?>(null) else emptyList())

    /** Sum boxed numbers preserving Kotlin's result type: Double if any is floating, else Long if any is Long,
     *  else Int — so `sumOf { intSelector }` yields an Int, matching the compiled program. */
    private fun numericSum(values: List<Any?>): Any {
        val nums = values.map { it as? Number ?: 0 }
        return when {
            nums.any { it is Double || it is Float } -> nums.sumOf { it.toDouble() }
            nums.any { it is Long } -> nums.sumOf { it.toLong() }
            else -> nums.sumOf { it.toInt() }
        }
    }

    /** The max (or min) of [values] by natural ordering — for `maxOf`/`minOf`'s selected Comparable results. */
    @Suppress("UNCHECKED_CAST")
    private fun reduceByComparison(values: List<Any?>, wantMax: Boolean): Any? =
        values.reduce { a, b ->
            val cmp = (a as Comparable<Any?>).compareTo(b)
            if ((wantMax && cmp >= 0) || (!wantMax && cmp <= 0)) a else b
        }

    private fun readBinding(binding: Binding, env: Env): Any? = when (binding) {
        is Binding.Local, is Binding.Param -> env.read(slotOf(binding))
        // A local `by`-delegate read: `delegate.getValue(null, property)` (a local delegate's `thisRef` is null).
        is Binding.DelegatedConvention -> delegateGetValue(env.read(binding.slot), binding.propertyName, thisRef = null)
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
        is Binding.Receiver -> throw InterpreterException("`this` (${binding.type?.qualifiedName ?: "receiver"}) has no value bound in this frame")
        else -> throw InterpreterException("cannot read binding: ${binding::class.simpleName}")
    }

    /** Materialize the singleton a bare type reference denotes: an `object`'s `INSTANCE` static field, or a
     *  type's companion via the `Companion` static field (`Modifier` → `Modifier.Companion`, the empty
     *  modifier; `Color` for `Color.Red`). Read from the runtime class — works for library objects on the
     *  classpath; a project-source object isn't compiled at preview time, so it fails the honest boundary. */
    private fun objectInstance(fqn: String): Any? {
        val cls = loadInitialized(fqn)
            ?: run {
                // An object only the project's library jars carry materializes in the library executor.
                libraryFallback?.takeIf { it.hasClass(fqn) }?.objectInstance(fqn)?.let { return it }
                throw InterpreterException("cannot load `$fqn` (a project-source object isn't available to the interpreter)")
            }
        runCatching { cls.getField("INSTANCE") }.getOrNull()?.let { return it.get(null) } // a Kotlin `object`
        // A type's companion — `Companion` by default, but a NAMED companion (`kotlin.random.Random.Default`)
        // uses its own name, so find it by pattern rather than by the literal name `Companion`.
        companionField(cls)?.let { f -> return runCatching { f.isAccessible = true; f.get(null) }.getOrNull() }
        throw InterpreterException("`$fqn` has no object/companion instance")
    }

    /** The companion-object static field of [cls], under ANY companion name. Kotlin compiles a companion to a
     *  `public static final` field on the outer class whose name equals its nested companion class's simple name
     *  — `Companion` by default, but a NAMED companion (`kotlin.random.Random.Default`) uses its own name. Null
     *  when [cls] has no companion. Lets `Random.nextInt(…)` reach the `Default` companion instance instead of
     *  being mistaken for a bare static holder. */
    private fun companionField(cls: Class<*>): java.lang.reflect.Field? =
        cls.declaredFields.firstOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.type.enclosingClass == cls && it.type.simpleName == it.name
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
        val obj = newSourceObject(cls)
        construct(cls, obj, orderedValues)
        return obj
    }

    /** A [SourceObject] with its [SourceObject.proxyInvoker] wired to this interpreter's member dispatch, so the
     *  reflective dispatcher can proxy it as the library interface it implements when it crosses into library
     *  code (an `object : Comparator` handed to `sortedWith`). Virtual dispatch by `name/arity` is used, so an
     *  override on the instance's own class wins. */
    private fun newSourceObject(cls: ResolvedClass): SourceObject {
        val obj = SourceObject(cls)
        obj.proxyInvoker = { name, args ->
            val m = findSourceMethod(cls, "$name/${args.size}")
                ?: throw InterpreterException("no member `$name/${args.size}` on ${cls.fqn}")
            callMethod(m, obj, args)
        }
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

    /** Run a SECONDARY constructor on the shared [obj]: bind its own parameters, run its delegation, then its
     *  body. A `this(…)` delegation runs the constructor whose arity matches the delegation arguments — ANOTHER
     *  secondary (recursing here) or, when the argument count fits the primary, the primary (binding its params +
     *  super + init steps); a `super(…)`/implicit delegation (a class with no primary, or one delegating straight
     *  to its superclass) runs the primary path once (superclass + property initializers + init blocks). Each
     *  constructor's own body runs after its delegation, so a `this → this → primary` chain runs the initializers
     *  once (at the primary) and every body in order, matching Kotlin. */
    private fun constructViaSecondary(cls: ResolvedClass, obj: SourceObject, ctor: SecondaryCtor, argv: List<Any?>) {
        val env = Env()
        env.define(cls.receiverSlot, obj)
        bindParams(env, ctor.params, argv)
        if (ctor.delegatesToThis) {
            val delegArgv = ctor.delegationArgs.map { eval(it.value, env) }
            val n = delegArgv.size
            val requiredPrimary = cls.primaryParams.count { it.default == null }
            // Delegate to a same-class secondary of the delegation's arity when it can't be the primary — so a
            // `this(a) : this(a, 0)` chain reaches the right constructor, not the primary with a short arg list.
            val targetSecondary = if (n !in requiredPrimary..cls.primaryParams.size)
                cls.secondaryCtors.firstOrNull { it !== ctor && it.params.size == n } else null
            if (targetSecondary != null) {
                constructViaSecondary(cls, obj, targetSecondary, reorderNamedArgs(targetSecondary.params.map { it.name }, ctor.delegationArgs, delegArgv))
            } else {
                construct(cls, obj, reorderNamedArgs(cls.primaryParams.map { it.name }, ctor.delegationArgs, delegArgv))
            }
        } else {
            construct(cls, obj, emptyList())
        }
        eval(ctor.body, env)
    }

    /** A member function declared on [cls] or inherited from a source supertype (the runtime instance's own
     *  class is searched first, so an override wins — virtual dispatch). */
    private fun findSourceMethod(cls: ResolvedClass, key: String, seen: MutableSet<String> = HashSet()): ResolvedFunction? {
        if (!seen.add(cls.fqn)) return null
        cls.methods[key]?.let { return it }
        for (sup in cls.supertypes) sourceClass(sup)?.let { findSourceMethod(it, key, seen)?.let { m -> return m } }
        return null
    }

    /** The implementation a `super.key` call binds to: the nearest `key` (`"name/arity"`) declared on a SOURCE
     *  supertype of [lexicalFqn], skipping [lexicalFqn]'s own override. Null when the lexical class is unknown or
     *  no source supertype declares it (a binary superclass method — the caller no-ops the super call). */
    private fun superMethod(lexicalFqn: String, key: String): ResolvedFunction? {
        val cls = sourceClass(lexicalFqn) ?: return null
        val seen = HashSet<String>().apply { add(cls.fqn) } // exclude the lexical class so its override is skipped
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
        // A NESTED source `object` reached through its enclosing type — `State.Loading` where `State` is a sealed
        // interface (no companion). The warm path lowers `State.Loading` straight to one ObjectRef; while the
        // index is still cold it arrives here as a member read, so resolve the nested object's singleton.
        sourceClass("${cls.fqn}.$name")?.takeIf { it.flavor == ClassFlavor.OBJECT }?.let { return Handled(objectSingleton(it)) }
        val holder = if (cls.flavor == ClassFlavor.OBJECT || cls.flavor == ClassFlavor.COMPANION) cls
            else companionOf(cls) ?: return null
        return Handled(readSourceProperty(objectSingleton(holder), name))
    }

    /** Interpret a member function body with [receiver] bound to its receiver slot. */
    private fun callMethod(fn: ResolvedFunction, receiver: SourceObject, args: List<Any?>): Any? {
        val env = Env()
        fn.receiverSlot?.let { env.define(it, receiver) }
        bindParams(env, fn.params, args)
        return try {
            eval(fn.body, env)
        } catch (r: ReturnSignal) {
            r.value
        }
    }

    private fun dispatchSourceMember(receiver: SourceObject, name: String, call: RNode.Call, env: Env): Any? {
        val arity = call.args.size
        // Prefer the resolver's declared arity so an omitted-defaults member call (`obj.f()` for `fun f(x = 0)`)
        // finds the full declaration; the exact call arity is the fallback. Named args are reordered and omitted
        // defaults filled by [callMethod]'s [bindParams].
        val m = declaredArity(call.callee)?.let { findSourceMethod(receiver.cls, "$name/$it") }
            ?: findSourceMethod(receiver.cls, "$name/$arity")
        if (m != null) {
            val argv = reorderNamedArgs(m.params.map { it.name }, call.args, call.args.map { eval(it.value, env) })
            return callMethod(m, receiver, argv)
        }
        synthesizedMember(receiver, name, call, env)?.let { return it.value }
        // A member the class inherits via `: I by delegate` (not overridden): forward it to the delegate object.
        delegatedMemberDispatch(receiver, name, call, env)?.let { return it.value }
        throw InterpreterException("no member `$name/$arity` on source class ${receiver.cls.fqn}")
    }

    /** Dispatch [name] on an interface-delegate (`class C : I by field`) when the class itself doesn't declare
     *  it: a source delegate interprets the member, a library delegate reflects it. Null when no delegate has
     *  the member (the caller then reports the honest "no member"). Arguments are evaluated once here. */
    private fun delegatedMemberDispatch(receiver: SourceObject, name: String, call: RNode.Call, env: Env): Handled? {
        if (receiver.cls.interfaceDelegates.isEmpty()) return null
        val arity = call.args.size
        val argv = call.args.map { eval(it.value, env) }
        for (d in receiver.cls.interfaceDelegates) {
            val delegate = receiver.fields[d.fieldName] ?: continue
            if (delegate is SourceObject) {
                findSourceMethod(delegate.cls, "$name/$arity")?.let { return Handled(callMethod(it, delegate, argv)) }
            } else {
                runCatching { checkedDispatch(call.copy(dispatch = DispatchKind.MEMBER), delegate, argv) }
                    .onSuccess { return Handled(it) }
            }
        }
        return null
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

    private fun readSourceProperty(receiver: SourceObject, name: String): Any? {
        // A `by`-delegated member reads through its delegate's `.value` (the `State`/`Lazy` convention) — the
        // hidden `name$delegate` field holds the delegate object. Reading a `MutableState.value` registers the
        // snapshot dependency, so the composable re-reads it after a write invalidates the scope.
        receiver.cls.delegatedProperties[name]?.let { delegateField ->
            val delegate = receiver.fields[delegateField]
                ?: throw InterpreterException("delegate `$delegateField` for `$name` not initialized on ${receiver.cls.fqn}")
            return readProperty(delegate, "value")
        }
        // A member `by`-delegate on the general convention: `delegate.getValue(this, property)`.
        receiver.cls.conventionDelegatedProperties[name]?.let { delegateField ->
            return delegateGetValue(receiver.fields[delegateField], name, thisRef = receiver)
        }
        if (receiver.fields.containsKey(name)) return receiver.fields[name]
        if (name == "name" && receiver.enumName != null) return receiver.enumName
        if (name == "ordinal" && receiver.enumOrdinal >= 0) return receiver.enumOrdinal
        // A computed property (`val isOver get() = …`) has no backing field — it is lowered as a zero-arg getter
        // method keyed `name/0`; invoke it on the receiver.
        findSourceMethod(receiver.cls, "$name/0")?.let { return callMethod(it, receiver, emptyList()) }
        // A property the class inherits via `: I by delegate` (not overridden): read it from the delegate object.
        for (d in receiver.cls.interfaceDelegates) {
            val delegate = receiver.fields[d.fieldName] ?: continue
            if (delegate is SourceObject) {
                runCatching { readSourceProperty(delegate, name) }.onSuccess { return it }
            } else {
                readProperty(delegate, name)?.let { return it }
            }
        }
        throw InterpreterException("no property `$name` on source class ${receiver.cls.fqn}")
    }

    /** Read a `by`-delegate's value: `delegate.getValue(thisRef, property)`. [thisRef] is null for a local
     *  delegate and the enclosing instance for a member delegate (Kotlin's own convention). */
    private fun delegateGetValue(delegate: Any?, propertyName: String, thisRef: Any?): Any? =
        callDelegateOp(requireDelegate(delegate, propertyName), "getValue", thisRef, InterpretedKProperty(propertyName), NO_DELEGATE_VALUE)

    /** Write a `by`-delegate's value: `delegate.setValue(thisRef, property, value)`. */
    private fun delegateSetValue(delegate: Any?, propertyName: String, thisRef: Any?, value: Any?) {
        callDelegateOp(requireDelegate(delegate, propertyName), "setValue", thisRef, InterpretedKProperty(propertyName), value)
    }

    private fun requireDelegate(delegate: Any?, propertyName: String): Any =
        delegate ?: throw InterpreterException("property delegate for `$propertyName` is not initialized")

    /** Invoke a delegate's `getValue`/`setValue` operator. A source delegate interprets the member directly; a
     *  library delegate reflects it (best-effort — see [reflectDelegateOp]). [value] === [NO_DELEGATE_VALUE]
     *  selects `getValue` (no value argument); any other value selects `setValue`. */
    private fun callDelegateOp(delegate: Any, op: String, thisRef: Any?, property: Any?, value: Any?): Any? {
        val args = if (value === NO_DELEGATE_VALUE) listOf(thisRef, property) else listOf(thisRef, property, value)
        if (delegate is SourceObject) {
            val m = findSourceMethod(delegate.cls, "$op/${args.size}")
                ?: throw InterpreterException("property delegate ${delegate.cls.fqn} has no `operator fun $op`")
            return callMethod(m, delegate, args)
        }
        return reflectDelegateOp(delegate, op, args)
    }

    /** Reflect a library delegate's operator. The synthetic [InterpretedKProperty] is passed first; if the
     *  operator declares a real `KProperty` parameter (so reflection rejects the fake), retry with a null
     *  property — which works for the common operators that ignore the property metadata
     *  (`Delegates.observable`/`notNull`). A still-failing call is an honest boundary, not a crash. */
    private fun reflectDelegateOp(delegate: Any, op: String, args: List<Any?>): Any? {
        val m = publicMethod(delegate.javaClass, op, args.size)
            ?: delegate.javaClass.methods.firstOrNull { it.name == op && it.parameterCount == args.size }
            ?: throw InterpreterException("no `$op` on delegate ${delegate.javaClass.name}")
        runCatching { m.isAccessible = true }
        fun invokeWith(a: List<Any?>): Any? =
            try { m.invoke(delegate, *a.toTypedArray()) }
            catch (e: java.lang.reflect.InvocationTargetException) { throw unwrapInvocationTarget(e) }
        return try {
            invokeWith(args)
        } catch (e: IllegalArgumentException) {
            val nulled = args.toMutableList().also { if (it.size >= 2) it[1] = null }
            try { invokeWith(nulled) }
            catch (e2: Throwable) { throw InterpreterException("library property delegate `${delegate.javaClass.name}.$op` is not supported in preview") }
        }
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

    /** Tri-state cast check: `true` = [value] is a [typeFqn]; `false` = confirmed NOT (the type loaded and
     *  rejected it); `null` = the type couldn't be resolved at all (a type parameter / unmapped type), so the
     *  cast can't be verified and the caller trusts the compiler. Distinguishes "not an instance" from "don't
     *  know the type" — which [isInstanceOf] collapses to `false` (fine for `is`, wrong for `as`). */
    private fun castMatches(value: Any, typeFqn: String): Boolean? {
        if (value is SourceObject) return sourceTypeMatches(value.cls, typeFqn)
        val candidates = listOfNotNull(
            KOTLIN_TYPE_TO_JVM[typeFqn], typeFqn, if ('.' !in typeFqn) "java.lang.$typeFqn" else null,
        )
        var anyLoaded = false
        for (c in candidates) {
            val cls = loadClassAcross(c, initialize = false, preferred = classLoader) ?: continue
            anyLoaded = true
            if (cls.isInstance(value)) return true
        }
        return if (anyLoaded) false else null
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
        val cls = loadInitialized(ref.fqn) ?: return null
        val hasSingleton = runCatching { cls.getField("INSTANCE") }.getOrNull() != null || companionField(cls) != null
        return if (hasSingleton) null else cls
    }

    /** The resource id an `R.<type>.<name>` read denotes, via the injected [resources] resolver. The read lowers
     *  to a nested `PropertyGet` whose binding owner is the synthetic R subclass (`com.example.R.string`) — which
     *  has no runtime class here — so the id is resolved from the binding's owner + field name. Null when there is
     *  no resolver, the binding isn't a property with a known owner, or it isn't a known project resource. */
    private fun resourceFieldId(binding: Binding): Int? {
        val r = resources ?: return null
        val owner = (binding as? Binding.Property)?.ownerFqn ?: return null
        return r.rClassField(owner, binding.name)
    }

    /** Whether [binding] reads a field off a synthetic `R` resource sub-class — its owner is `<pkg>.R.<type>`
     *  (`com.example.R.string`). Used to degrade an unresolved resource read to id 0 instead of crashing on the
     *  bytecode-less `R`. Independent of the resolver, so it holds even when none is wired. */
    private fun isRResourceRead(binding: Binding): Boolean {
        val owner = (binding as? Binding.Property)?.ownerFqn ?: return false
        val rClass = owner.substringBeforeLast('.', "") // `<pkg>.R`
        return rClass.isNotEmpty() && rClass.substringAfterLast('.') == "R"
    }

    /** Read a static member `name` off [cls]: a public static field (`System.out`, `Integer.MAX_VALUE`) first,
     *  then a static no-arg getter (`getName()`, mangling-aware for a value-class-typed property). */
    private fun readStaticMember(cls: Class<*>, name: String): Any? {
        hookPropertyRead(cls.name, name, null)?.let { return it.value }
        runCatching { cls.getField(name) }.getOrNull()
            ?.takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            ?.let { runCatching { it.isAccessible = true }; return it.get(null) }
        val getter = "get" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        cls.methods.firstOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && mangledNameMatches(it.name, getter)
        }?.let { runCatching { it.isAccessible = true }; return it.invoke(null) }
        // Not a static field/getter — but `name` may be a NESTED CLASS reached through its enclosing type
        // (`Build.VERSION`, `Build.VERSION_CODES`); return that Class so a further static read off it resolves
        // (`Build.VERSION.SDK_INT`). The PropertyGet handler treats a Class-valued receiver as a static holder.
        loadInitialized("${cls.name}\$$name")?.let { return it }
        throw InterpreterException("no static member `$name` on ${cls.name}")
    }

    /** The singleton of a nested `object` named [name] declared inside [enclosing] — `Icons.AutoMirrored`
     *  compiles to the class `<enclosing>$<name>` with its own static `INSTANCE`. Null when there is no such
     *  nested class or it isn't an object (no `INSTANCE`). */
    private fun nestedObjectInstance(enclosing: Class<*>, name: String): Any? {
        val nested = loadInitialized("${enclosing.name}\$$name")
            ?: return null
        return runCatching { nested.getField("INSTANCE") }.getOrNull()?.get(null)
    }

    /** Read a property by reflection: the Kotlin getter (`value` → `getValue()`), else a same-named no-arg
     *  method. A `MutableState.value` read goes through the real `getValue()`, so the snapshot system records
     *  the dependency on the enclosing recompose scope — which is what drives recomposition. */
    private fun readProperty(receiver: Any, name: String): Any? {
        if (receiver is SourceObject) return readSourceProperty(receiver, name)
        hookPropertyRead(null, name, receiver)?.let { return it.value }
        // An instance the library executor produced: its getters exist only in the executor's world.
        if (libraryFallback?.ownsInstance(receiver) == true) {
            libraryFallback.propertyOrNull(receiver, name)?.let { return it.value }
        }
        val getter = "get" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        (noArgMethod(receiver, getter) ?: noArgMethod(receiver, name))?.let {
            val v = it.invoke(receiver)
            if (InterpTrace.isComposeState(receiver, name)) InterpTrace.log("read  $name = $v on ${InterpTrace.id(receiver)}")
            return v
        }
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
        if (receiver is SourceObject) {
            // A `by`-delegated member writes through its delegate's `.value` setter (`MutableState.setValue`),
            // so the snapshot system records the mutation and invalidates the enclosing recompose scope — which
            // is what makes a state change from a click actually re-render. A plain field write would not.
            receiver.cls.delegatedProperties[name]?.let { delegateField ->
                val delegate = receiver.fields[delegateField]
                    ?: throw InterpreterException("delegate `$delegateField` for `$name` not initialized on ${receiver.cls.fqn}")
                writeProperty(delegate, "value", value); return
            }
            // A member `by`-delegate on the general convention: `delegate.setValue(this, property, value)`.
            receiver.cls.conventionDelegatedProperties[name]?.let { delegateField ->
                delegateSetValue(receiver.fields[delegateField], name, thisRef = receiver, value = value); return
            }
            receiver.fields[name] = value; return
        }
        hooks?.let { h ->
            when (val d = h.beforePropertyWrite(name, receiver)) {
                is HookDecision.Replace -> return // a stubbed write is simply skipped
                is HookDecision.Deny -> throw InterpreterSecurityException(d.reason)
                HookDecision.Proceed -> {}
            }
        }
        if (libraryFallback?.ownsInstance(receiver) == true && libraryFallback.writeProperty(receiver, name, value)) return
        val setter = "set" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val m = (oneArgMethod(receiver, setter) ?: oneArgMethod(receiver, name))
            ?: throw InterpreterException("no writable property `$name` on ${receiver.javaClass.name}")
        if (InterpTrace.isComposeState(receiver, name)) InterpTrace.log("write $name <- $value on ${InterpTrace.id(receiver)}")
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
        hookPropertyRead(ownerFqn, name, null)?.let { return it.value }
        val getterName = "get" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val cls = loadInitialized(ownerFqn)
            ?: run {
                libraryFallback?.takeIf { it.hasClass(ownerFqn) }?.let { return it.invokeStatic(ownerFqn, getterName, emptyList()) }
                throw InterpreterBoundaryException("cannot load facade `$ownerFqn` for top-level property `$name`")
            }
        val getter = getterName
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
        hookPropertyRead(ownerFqn, name, receiver)?.let { return it.value }
        val getterName = "get" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val cls = loadClassAcross(ownerFqn, initialize = false, preferred = classLoader)
            ?: run {
                libraryFallback?.takeIf { it.hasClass(ownerFqn) }?.let { return it.invokeStatic(ownerFqn, getterName, listOf(receiver), leadingReceivers = 1) }
                // A missing library facade (e.g. a Compose icon's per-icon `…Kt`) is a recoverable boundary:
                // partial rendering skips the one statement rather than failing the whole preview.
                throw InterpreterBoundaryException("cannot load facade `$ownerFqn` for extension property `$name`")
            }
        val getter = getterName
        val m = cls.methods.firstOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 1 && mangledNameMatches(it.name, getter)
        } ?: throw InterpreterException("no extension-property getter `$name` on `$ownerFqn`")
        runCatching { m.isAccessible = true }
        return m.invoke(null, receiver)
    }

    /** A no-arg method matching [name], allowing a mangled `name-<hash>` (a value-class-typed getter like
     *  `Color.Red` → `getRed-<hash>(): long`). Prefers a public-declaring type (invokable under the JDK module
     *  system); falls back to the concrete class with a best-effort `setAccessible`. Cached per (class, name):
     *  the lookup walks the class hierarchy (`getDeclaredMethods`) and the interpreter re-reads the same property
     *  every iteration of a loop (e.g. a `while (!x.done) { … }` guard) — this was the hot path in the ANR trace.
     *  A holder boxes the nullable result so "no such method" is cached too. */
    private class NoArgMethodHolder(val method: java.lang.reflect.Method?)
    private val noArgMethodCache = java.util.concurrent.ConcurrentHashMap<Class<*>, java.util.concurrent.ConcurrentHashMap<String, NoArgMethodHolder>>()

    private fun noArgMethod(receiver: Any, name: String): java.lang.reflect.Method? {
        val cls = receiver.javaClass
        val perClass = noArgMethodCache.getOrPut(cls) { java.util.concurrent.ConcurrentHashMap() }
        perClass[name]?.let { return it.method }
        val m = publicMethod(cls, name, 0)
            ?: cls.methods.firstOrNull { mangledNameMatches(it.name, name) && it.parameterCount == 0 }
                ?.also { runCatching { it.isAccessible = true } }
        perClass[name] = NoArgMethodHolder(m)
        return m
    }

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
        // A source class's no-arg operator/member (`operator fun iterator()`, and the returned iterator's own
        // `hasNext()`/`next()` when it too is a source type) — interpret it instead of reflecting bytecode that
        // doesn't exist. Drives `for (x in sourceObj)`.
        if (receiver is SourceObject) {
            findSourceMethod(receiver.cls, "$name/0")?.let { return callMethod(it, receiver, emptyList()) }
        }
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

    /** Kotlin's bitwise infix operators (`and`/`or`/`xor`/`shl`/`shr`/`ushr`) — intrinsics on `Int`/`Long` with
     *  no invocable JVM method. Shifts keep the receiver's width and take an `Int` count; and/or/xor widen to
     *  `Long` if either operand is `Long`, else operate as `Int` (Byte/Short promote to Int, as in Kotlin). */
    private fun bitwiseBinary(op: String, a: Number, b: Number): Any = when (op) {
        "shl" -> if (a is Long) a shl b.toInt() else a.toInt() shl b.toInt()
        "shr" -> if (a is Long) a shr b.toInt() else a.toInt() shr b.toInt()
        "ushr" -> if (a is Long) a ushr b.toInt() else a.toInt() ushr b.toInt()
        "and" -> if (a is Long || b is Long) a.toLong() and b.toLong() else a.toInt() and b.toInt()
        "or" -> if (a is Long || b is Long) a.toLong() or b.toLong() else a.toInt() or b.toInt()
        "xor" -> if (a is Long || b is Long) a.toLong() xor b.toLong() else a.toInt() xor b.toInt()
        else -> throw InterpreterException("unknown bitwise operator `$op`")
    }

    /** `x.inv()` — bitwise complement, keeping the receiver's width (`Long` stays `Long`, else `Int`). */
    private fun bitwiseInv(a: Number): Any = if (a is Long) a.inv() else a.toInt().inv()

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
            // A local function's `return` is LOCAL — it returns from this closure, not the enclosing function. A
            // plain lambda's bare `return` is non-local (it must propagate to the enclosing function's frame).
            return if (node.isLocalFunction) {
                try { eval(node.body, callEnv) } catch (r: ReturnSignal) { r.value }
            } else {
                eval(node.body, callEnv)
            }
        }
    }

    // --- sequence builder (`sequence { yield(…) }`) -------------------------------------------------------

    /** The generator whose `sequence { … }` block is running on THIS thread, so `yield`/`yieldAll` reach it. */
    private val activeGenerator = ThreadLocal<SeqGenerator?>()

    /** Build the lazy `Sequence` a `sequence { … }` block denotes. Each `iterator()` runs the block on a fresh
     *  producer thread that hands ONE element across per consumer pull — so an infinite generator terminates
     *  under `take(n)`/`first()` (the producer parks after the last element pulled; it is a daemon thread). */
    private fun interpretedSequence(block: InterpretedLambda): Sequence<Any?> = Sequence { SeqGenerator(block) }

    private inner class SeqGenerator(private val block: InterpretedLambda) : Iterator<Any?> {
        private val demand = java.util.concurrent.SynchronousQueue<Any>()
        private val supply = java.util.concurrent.SynchronousQueue<Any>()
        private var started = false
        private var finished = false
        private var ready: Boolean? = null // null = not fetched; true = a value is buffered; false = exhausted
        private var value: Any? = null

        private fun startIfNeeded() {
            if (started) return
            started = true
            Thread {
                try {
                    demand.take() // wait for the first pull before running any of the block
                    val prev = activeGenerator.get()
                    activeGenerator.set(this)
                    try { block.invoke(listOf(SEQ_SCOPE)) } finally { activeGenerator.set(prev) }
                    supply.put(SEQ_DONE)
                } catch (t: Throwable) {
                    runCatching { supply.put(SeqFail(t)) }
                }
            }.apply { isDaemon = true; name = "interp-sequence"; start() }
        }

        /** Hand [v] to the consumer (called by `yield` on the producer thread), then park until the next pull. */
        fun emit(v: Any?) {
            supply.put(SeqVal(v))
            demand.take()
        }

        private fun fetch() {
            if (ready != null || finished) return
            startIfNeeded()
            demand.put(SEQ_UNIT)
            when (val s = supply.take()) {
                is SeqVal -> { value = s.v; ready = true }
                is SeqFail -> { finished = true; ready = false; throw s.t }
                else -> { finished = true; ready = false } // SEQ_DONE
            }
        }

        override fun hasNext(): Boolean { fetch(); return ready == true }
        override fun next(): Any? {
            fetch()
            if (ready != true) throw NoSuchElementException()
            ready = null
            return value
        }
    }

    /** The receiver a `sequence { … }` block runs against (its `SequenceScope`); `yield`/`yieldAll` are
     *  intercepted by name so nothing is dispatched on it. */
    private val SEQ_SCOPE = Any()
    private val SEQ_UNIT = Any()
    private object SEQ_DONE
    private class SeqVal(val v: Any?)
    private class SeqFail(val t: Throwable)

    // --- coroutine builders (`runBlocking`/`coroutineScope`/`launch`/`async`/`await`) --------------------

    /** Nesting depth of a coroutine scope on THIS thread (a `runBlocking`/`coroutineScope`/`supervisorScope`
     *  block). `launch`/`async` are intercepted ONLY inside one, so a preview's own `scope.launch` (driven by
     *  the SuspendBridge on its coroutine thread) is left to that path. */
    private val coroutineDepth = ThreadLocal.withInitial { 0 }
    private val COROUTINE_SCOPE = Any()
    private class CoroutineJob
    private class CoroutineDeferred(val value: Any?)

    private fun <T> withCoroutineScope(body: () -> T): T {
        coroutineDepth.set(coroutineDepth.get() + 1)
        try { return body() } finally { coroutineDepth.set(coroutineDepth.get() - 1) }
    }

    /** Run a coroutine builder's block (a `CoroutineScope.() -> T` receiver lambda), binding the scope marker so
     *  a `this.launch { }` inside it resolves; child `launch`/`async` run cooperatively (eagerly, in order),
     *  which is a correct single-threaded execution — the same serialization a single dispatcher gives. */
    private fun runCoroutineBlock(block: InterpretedLambda): Any? =
        block.invoke(if (block.paramCount >= 1) listOf<Any?>(COROUTINE_SCOPE) else emptyList())

    /**
     * A lexical scope. Slots are unique within a function, but a declaration inside a loop or lambda executes
     * many times — so each scope holds only its OWN bindings and chains to its [parent]. [read] walks outward
     * to find an enclosing binding; [define] introduces a binding in this scope (params, local `val`/`var`,
     * loop variable); [assign] updates the binding in whichever scope owns it (so a mutated outer `var` stays
     * visible). This per-scope isolation is what makes closures capture per-iteration values.
     *
     * Backed by two small parallel arrays scanned linearly rather than a `HashMap`: a scope almost always holds
     * a handful of slots (a function's params, a loop variable, a lambda's params), so a linear scan over 0–4
     * ints beats hashing, and the arrays are allocated lazily on first [define] — a body scope that introduces
     * no locals (a `while`/`for` body, an expression lambda) costs nothing. This is the per-render hot path:
     * the previous `HashMap` allocated a node table plus a node per entry for every scope, every recomposition.
     */
    private class Env(private val parent: Env? = null) {
        init { InterpProfile.count("env") }
        private var keys: IntArray? = null
        private var values: Array<Any?>? = null
        private var size = 0

        /** Reified type-parameter bindings of the function this Env frames (`T` → its concrete type token).
         *  Set once at [invokeFunction] via [defineReified]; looked up the parent chain so a lambda / catch
         *  body inside a reified function still resolves `T`. Null (the common case) when the function is
         *  non-generic. */
        private var reified: Map<String, RTypeArg>? = null

        fun defineReified(m: Map<String, RTypeArg>) { if (m.isNotEmpty()) reified = m }

        /** The concrete type bound to reified type parameter [name] in this frame or an enclosing one, or null. */
        fun reifiedType(name: String): RTypeArg? {
            var e: Env? = this
            while (e != null) {
                e.reified?.get(name)?.let { return it }
                e = e.parent
            }
            return null
        }

        fun read(slot: SlotId): Any? {
            val key = slot.value
            var e: Env? = this
            while (e != null) {
                val i = e.indexOf(key)
                if (i >= 0) return e.values!![i]
                e = e.parent
            }
            return null
        }

        fun define(slot: SlotId, value: Any?) {
            val key = slot.value
            val i = indexOf(key)
            if (i >= 0) { values!![i] = value; return } // redefine in this scope (shadow/overwrite)
            var ks = keys
            var vs = values
            if (ks == null) { ks = IntArray(INITIAL); vs = arrayOfNulls(INITIAL); keys = ks; values = vs }
            else if (size == ks.size) { ks = ks.copyOf(size * 2); vs = vs!!.copyOf(size * 2); keys = ks; values = vs }
            ks[size] = key
            vs!![size] = value
            size++
        }

        fun assign(slot: SlotId, value: Any?) {
            val key = slot.value
            var e: Env? = this
            while (e != null) {
                val i = e.indexOf(key)
                if (i >= 0) { e.values!![i] = value; return }
                e = e.parent
            }
            define(slot, value) // not previously declared (shouldn't happen post-resolution) → bind here
        }

        /** Index of [key] in this scope's own slots, or -1. Linear scan — scopes are tiny. */
        private fun indexOf(key: Int): Int {
            val ks = keys ?: return -1
            for (i in 0 until size) if (ks[i] == key) return i
            return -1
        }

        private companion object {
            const val INITIAL = 4
        }
    }

    /** Non-local control transfer for `return`; no stack trace (it's control flow, not an error). */
    private class ReturnSignal(val value: Any?) : RuntimeException(null, null, false, false)

    /** Loop control transfers for `break`/`continue` — stackless singletons (no per-throw allocation in a hot
     *  loop), caught by the enclosing [RNode.While]/[RNode.ForEach]. The unlabeled jumps reuse the singletons;
     *  a labeled jump (`break@outer`) carries its target [label] so an inner loop rethrows it to the loop it
     *  names. Both cases are stackless (no fill-in of the trace). */
    private sealed class LoopSignal : RuntimeException(null, null, false, false) { abstract val label: String? }
    private object BreakSignal : LoopSignal() { override val label: String? get() = null }
    private object ContinueSignal : LoopSignal() { override val label: String? get() = null }
    private class LabeledBreakSignal(override val label: String) : LoopSignal()
    private class LabeledContinueSignal(override val label: String) : LoopSignal()

    /** True when a break/continue [signal] should be handled by a loop tagged [loopLabel]: an unlabeled jump
     *  always stops at its innermost enclosing loop; a labeled jump only at the loop whose label it names. */
    private fun handledHere(signal: LoopSignal, loopLabel: String?): Boolean =
        signal.label == null || signal.label == loopLabel

    /** Run a loop body in [bodyEnv], swallowing a `continue` that targets THIS loop (an unlabeled one, or one
     *  labeled [loopLabel]); a `continue` naming an outer loop, and any `break`, propagate out. */
    private fun runLoopBody(body: RNode, bodyEnv: Env, loopLabel: String?) {
        try {
            eval(body, bodyEnv)
        } catch (c: ContinueSignal) { /* continue → fall through to the next iteration */
        } catch (c: LabeledContinueSignal) { if (!handledHere(c, loopLabel)) throw c }
    }

    /** Per-loop budget for the runaway guard. Reset whenever the loop cooperates (suspends via `delay`), so only
     *  CONSECUTIVE non-suspending iterations count. Allocated once per loop entry (not per iteration). Captures
     *  the enclosing pass deadline once ([call] arms it before any loop runs) so [guardLoop] needs no per-iteration
     *  thread-local read. */
    private inner class LoopBudget {
        var iterations = 0
        var startNanos = System.nanoTime()
        var seenSuspends = SuspendContext.suspendTicks()
        val deadlineNanos = frame.get().deadlineNanos
    }

    /**
     * Runaway-loop guard. An interpreted preview runs on the app's UI thread (a `@Composable` body) or a
     * `LaunchedEffect` coroutine, so an unbounded loop hangs the WHOLE app — an ANR, not just a bad preview.
     * Loops CAN become unbounded through no fault of the source: `tolerateGaps` skips an [RNode.Unsupported]
     * statement, so a suspend call the interpreter can't run inside `while (!done) { delay(…); progress() }` is
     * elided, turning a suspending loop into a busy one that never exits (the reported Memory-Match timer
     * `while (!game.isWon) { delay(1000); seconds++ }`). Bound every loop by BOTH an iteration count (cheap
     * infinite loops trip this fast) and wall-clock time (an expensive-bodied loop trips this first), throwing so
     * the enclosing effect aborts / the preview shows an error instead of freezing.
     *
     * A loop that genuinely SUSPENDS each pass (a real `delay`, under the coroutine [SuspendBridge]) is
     * cooperative, NOT runaway — a timer legitimately runs for minutes of mostly-sleeping wall-clock. Detected
     * via [SuspendContext.suspendTicks]: when the body suspended since the last check, the budget resets, so only
     * a tight busy loop (no suspension) is ever bounded.
     */
    private fun guardLoop(b: LoopBudget) {
        val ticks = SuspendContext.suspendTicks()
        if (ticks != b.seenSuspends) { // the body suspended → cooperative; reset the budget and carry on
            b.seenSuspends = ticks
            b.iterations = 0
            b.startNanos = System.nanoTime()
            return
        }
        if (++b.iterations >= MAX_LOOP_ITERATIONS)
            throw InterpreterException("loop exceeded $MAX_LOOP_ITERATIONS iterations — aborting to avoid hanging the preview")
        // Check wall-clock EVERY iteration: a loop of few iterations but with an expensive body (nested work,
        // heavy reflective dispatch) would slip a sampled check and freeze far past the budget. A pure spin loop
        // trips the iteration count first, so the added nanoTime cost only lands on loops doing real work, where
        // the body dominates it. Bound by BOTH the per-loop budget and the captured whole-pass deadline (so
        // several sequential loops in one pass can't sum past an ANR).
        val now = System.nanoTime()
        if (now - b.startNanos > MAX_LOOP_NANOS)
            throw InterpreterException("loop ran longer than ${MAX_LOOP_NANOS / 1_000_000}ms — aborting to avoid hanging the preview")
        if (b.deadlineNanos != 0L && now > b.deadlineNanos)
            throw InterpreterException("preview interpretation ran longer than ${MAX_RENDER_NANOS / 1_000_000}ms — aborting to avoid hanging the preview")
    }

    /** Carries a non-`Throwable` value thrown by interpreted `throw` (e.g. a source exception object), so a
     *  `try`/`catch` can still match and bind it. A real `Throwable` is thrown directly. */
    private class KotlinThrow(val value: Any?) : RuntimeException(null, null, false, false)

    private companion object {
        /** Runaway-loop guard bounds (see [guardLoop]). Generous enough that a real preview's loops never trip
         *  them, tight enough that a runaway loop can't hang the app past an ANR. */
        const val MAX_LOOP_ITERATIONS = 1_000_000
        const val MAX_LOOP_NANOS = 3_000_000_000L // 3s wall-clock — well under Android's 5s input-dispatch ANR
        const val FRAME_MILLIS = 16L // simulated ~60fps cadence for `withFrameNanos`/`withFrameMillis`

        /** Recursion + whole-pass bounds (see [call]). [MAX_CALL_DEPTH] gives a clean early abort on a large-stack
         *  thread; the StackOverflowError catch in [call] is the backstop for smaller stacks. */
        const val MAX_CALL_DEPTH = 500
        const val MAX_RENDER_NANOS = 4_000_000_000L // 4s per render pass — under the 5s input-dispatch ANR

        /** Pre-allocated (no allocation once the stack is exhausted) for the StackOverflowError backstop in [call].
         *  Reused across threads/renders — safe, since it is thrown, not mutated. */
        val RECURSION_ABORT = InterpreterException("call stack overflowed (unbounded recursion) — aborting to avoid crashing the preview")

        val ARITHMETIC = setOf("plus", "minus", "times", "div", "rem")
        val COMPARISON = setOf("lt", "le", "gt", "ge")
        val BITWISE = setOf("and", "or", "xor", "shl", "shr", "ushr")
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
        /** The facades that declare the functions the interpreter models as intrinsics: the `@InlineOnly` scope
         *  functions (`repeat`/`let`/`run`/…) on `StandardKt`, the empty/blank predicates on the string
         *  (`StringsKt`) and collection (`CollectionsKt`) facades, and the coroutines `delay` on `DelayKt` (a
         *  suspend function with no reflectable synchronous form — modeled as an interruptible sleep under the
         *  coroutine [SuspendBridge]). */
        val INLINE_INTRINSIC_FACADES = setOf(
            "kotlin.StandardKt", "kotlin.text.StringsKt", "kotlin.collections.CollectionsKt",
            "kotlinx.coroutines.DelayKt",
            // The precondition family (`require`/`check`/`error`/`requireNotNull`/`checkNotNull`) — @InlineOnly
            // (they carry contracts), so no JVM method exists to reflect. `TODO` lives on `StandardKt` above.
            "kotlin.PreconditionsKt",
            // `kotlin.math.*` (sqrt/abs/pow/roundToInt/…) — @InlineOnly delegations to java.lang.Math.
            "kotlin.math.MathKt",
        )
        /** The `@InlineOnly` empty/blank predicates, dispatched by name in [evalEmptyBlankPredicate]. */
        val EMPTY_BLANK_PREDICATES = setOf("isNotBlank", "isNotEmpty", "isNullOrBlank", "isNullOrEmpty")

        /** `kotlin.math` single-argument functions modeled over [java.lang.Math] (all compute in `Double`).
         *  `round` is ties-to-even ([Math.rint], matching `kotlin.math.round`); `Double.roundToInt/Long` (ties
         *  up) are handled separately. `abs`/`min`/`max` are type-preserving and also handled separately. */
        val MATH_UNARY: Map<String, (Double) -> Double> = mapOf(
            "sqrt" to Math::sqrt, "cbrt" to Math::cbrt,
            "floor" to Math::floor, "ceil" to Math::ceil, "round" to Math::rint,
            "sin" to Math::sin, "cos" to Math::cos, "tan" to Math::tan,
            "asin" to Math::asin, "acos" to Math::acos, "atan" to Math::atan,
            "sinh" to Math::sinh, "cosh" to Math::cosh, "tanh" to Math::tanh,
            "exp" to Math::exp, "expm1" to Math::expm1,
            "ln" to Math::log, "log10" to Math::log10, "ln1p" to Math::log1p,
            "log2" to { x -> Math.log(x) / LN2 }, "sign" to Math::signum,
        )
        private val LN2 = Math.log(2.0)

        /** `arrayOf`/`intArrayOf`/… vararg factories + `emptyArray` — compiler intrinsics with no JVM method,
         *  built by [arrayConstructionIntrinsic]. `arrayOfNulls` is handled separately (it takes a size). */
        val ARRAY_OF_FUNCTIONS = setOf(
            "arrayOf", "emptyArray", "intArrayOf", "longArrayOf", "doubleArrayOf", "floatArrayOf",
            "shortArrayOf", "byteArrayOf", "charArrayOf", "booleanArrayOf",
        )

        /** `Array(size){init}` and the primitive `IntArray(size)[{init}]` factories (capitalized → lowered as
         *  constructor calls to `kotlin.Array`/`kotlin.IntArray`, which aren't real invocable constructors). */
        val ARRAY_CONSTRUCTORS = setOf(
            "Array", "IntArray", "LongArray", "DoubleArray", "FloatArray",
            "ShortArray", "ByteArray", "CharArray", "BooleanArray",
        )

        /** `Collection.toTypedArray()` / `Iterable.toIntArray()` / … — return the elements as a List. */
        val TO_ARRAY_FUNCTIONS = setOf(
            "toTypedArray", "toIntArray", "toLongArray", "toDoubleArray", "toFloatArray",
            "toShortArray", "toByteArray", "toCharArray", "toBooleanArray",
        )
    }
}

/** Thrown when the interpreter meets a construct it cannot execute — the honest boundary, never a guess.
 *  Open for [InterpreterSecurityException] (a hook refusal), which boundary handling treats identically. */
open class InterpreterException(message: String) : RuntimeException(message)

/** A RECOVERABLE cannot-load boundary: a value depends on a library class/facade that isn't available to the
 *  interpreter (a Compose icon's `…Kt` facade, an unresolved dependency). Under partial rendering the statement
 *  that hit it is skipped rather than failing the whole preview — one unloadable icon leaves the rest of the UI
 *  intact. A hard [InterpreterException] still aborts (a genuine interpretation gap, not a missing dependency). */
class InterpreterBoundaryException(message: String) : InterpreterException(message)

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

    /** Invokes a member by `name` with `args` and returns the result — set by the interpreter at construction so
     *  the reflective dispatcher can wrap this object in a JVM interface [java.lang.reflect.Proxy] when it's
     *  passed to library code that expects the interface it implements (`object : Comparator` handed to
     *  `sortedWith`, `object : NestedScrollConnection` to `Modifier.nestedScroll`). Null until construction sets it. */
    @JvmField var proxyInvoker: ((String, List<Any?>) -> Any?)? = null

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
