package dev.ide.interp

import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Load [fqn] across the loaders that might hold the project/runtime libraries — [preferred] first (the
 *  project library [ClassLoader] when previewing on device, whose parent is the IDE app loader so it also
 *  resolves the bundled runtime), then the thread-context loader, the interpreter's own, and the system
 *  loader. [initialize] controls whether static initializers run (needed before reading an `INSTANCE`/
 *  `Companion` field). Null if none can load it. */
internal fun loadClassAcross(fqn: String, initialize: Boolean, preferred: ClassLoader? = null): Class<*>? {
    val loaders = listOfNotNull(
        preferred,
        Thread.currentThread().contextClassLoader,
        ReflectiveDispatcher::class.java.classLoader,
        ClassLoader.getSystemClassLoader(),
    ).distinct()
    return loaders.firstNotNullOfOrNull { l -> runCatching { Class.forName(fqn, initialize, l) }.getOrNull() }
}

/** Whether a JVM method/field name corresponds to the Kotlin name [kotlinName]. Kotlin MANGLES the JVM name
 *  of anything that takes/returns an inline value class (`Color`, `Dp`, `TextUnit`, …) to `name-<hash>` — so
 *  the literal name won't match; the `name-` prefix does. The mangling hash never contains `$`, so a `$`
 *  excludes the OTHER synthetics that share the prefix — `getRed-<hash>$annotations` (returns void) and
 *  `foo-<hash>$default` — which must NOT be mistaken for the real member. */
internal fun mangledNameMatches(jvmName: String, kotlinName: String): Boolean =
    jvmName == kotlinName || (jvmName.startsWith("$kotlinName-") && '$' !in jvmName)

/**
 * Find an instance method `name`/[argCount] declared on a **public** class or interface of [cls], so it is
 * invokable without `setAccessible` — a concrete impl like `java.util.Arrays$ArrayList` isn't open under the
 * JDK module system, but the `List`/`Iterator` interface method it overrides is. Returns null if none is
 * found on a public type (callers fall back to a force-accessible lookup). */
internal fun publicMethod(cls: Class<*>, name: String, argCount: Int): Method? {
    val seen = HashSet<Class<*>>()
    val queue = ArrayDeque<Class<*>>()
    queue.add(cls)
    while (queue.isNotEmpty()) {
        val c = queue.removeFirst()
        if (!seen.add(c)) continue
        if (Modifier.isPublic(c.modifiers)) {
            c.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == argCount &&
                    Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers)
            }?.let { return it }
        }
        c.superclass?.let { queue.add(it) }
        c.interfaces.forEach { queue.add(it) }
    }
    return null
}

/**
 * Sentinel marking a parameter slot the call left to its default — produced by [reorderNamedArgs] when a
 * named argument omits a defaulted parameter (so the supplied args no longer fill the leading slots). The
 * binding paths treat it as "not provided" and let the callee substitute its own default (via the Kotlin
 * `$default` synthetic, or Compose's `$default` bitmask).
 */
object OmittedArg

/**
 * Reorder evaluated [args] (in source order, 1:1 with [rawArgs]) into the callee's declared parameter order
 * when the call uses NAMED arguments, returning a dense list of size [paramNames].size with [OmittedArg] in
 * every slot no argument targets. A trailing lambda still binds to the LAST parameter (Kotlin's
 * trailing-lambda rule). Returns [args] unchanged when there are no named arguments, the parameter names
 * aren't known, or an argument can't be mapped — so the positional fast paths stay untouched, and a second
 * call on an already-reordered list (size ≠ [rawArgs].size) is a no-op.
 */
fun reorderNamedArgs(paramNames: List<String>, rawArgs: List<RArg>, args: List<Any?>): List<Any?> {
    if (paramNames.isEmpty() || args.size != rawArgs.size || rawArgs.none { it.name != null }) return args
    val n = paramNames.size
    val nameToIndex = HashMap<String, Int>(n * 2)
    paramNames.forEachIndexed { i, nm -> nameToIndex.putIfAbsent(nm, i) }
    val slots = MutableList<Any?>(n) { OmittedArg }
    // Only a SYNTACTIC trailing lambda (`{ }` outside the parens) binds to the last parameter. A lambda inside
    // the parens — `onCheckedChange = { }` (named) or a positional `f(x, { })` — is a normal value argument
    // that binds by name/position, NOT to the last parameter.
    val trailingLambda = rawArgs.lastOrNull()?.trailingLambda == true
    var nextPositional = 0
    for (i in args.indices) {
        val target = when {
            rawArgs[i].name != null -> nameToIndex[rawArgs[i].name] ?: return args
            trailingLambda && i == args.lastIndex -> n - 1
            else -> nextPositional++
        }
        if (target !in 0 until n) return args
        slots[target] = args[i]
    }
    // Trailing omitted slots need no placeholder — they're simply not supplied and the callee's `$default`
    // machinery fills them. Keep only through the last supplied arg (interior gaps stay as [OmittedArg]); this
    // also keeps the supplied-arg count low, so a runtime method with a different default count still binds.
    val lastSupplied = slots.indexOfLast { it !== OmittedArg }
    return if (lastSupplied == n - 1) slots else slots.subList(0, lastSupplied + 1).toList()
}

/**
 * The seam between the [Interpreter] (which owns control flow, intrinsics, and source-function calls) and
 * the host's way of invoking everything else — precompiled library/member/constructor calls. The
 * interpreter evaluates the receiver and arguments, then hands the call here.
 *
 * This is also where the Compose bridge plugs in: `interp-compose` provides a dispatcher that threads a real
 * `Composer` into `@Composable` calls and falls back to [ReflectiveDispatcher] for the rest.
 */
interface Dispatcher {
    /** Invoke a non-source callee. [receiver] is the evaluated dispatch/extension receiver (null for
     *  top-level/static/constructor); [args] are evaluated, in source order. */
    fun dispatch(call: RNode.Call, receiver: Any?, args: List<Any?>): Any?

    /** Read a property whose getter is `@Composable` — its JVM getter takes a `Composer` (e.g.
     *  `MaterialTheme.colorScheme` → `getColorScheme(Composer, int)`), so the interpreter can't invoke it with
     *  plain reflection. Returns the value boxed in [ComposablePropertyValue], or null when [receiver]'s
     *  [propertyName] getter isn't a composable getter (then the interpreter reads it plainly / reports the
     *  honest boundary). The default reflective dispatcher has no `Composer` to thread, so it returns null. */
    fun readComposableProperty(receiver: Any, propertyName: String): ComposablePropertyValue? = null
}

/** The result of a [Dispatcher.readComposableProperty] — a box so a legitimately-`null` property value is
 *  distinguishable from "not a composable getter" (null box). */
class ComposablePropertyValue(val value: Any?)

/**
 * An interpreted lambda value. When a lambda is passed to a library function, the dispatcher wraps this in a
 * JVM functional-interface proxy so the library can call back into the interpreter. Sharing the defining
 * activation's environment, it reads/writes captured variables directly.
 */
interface InterpretedLambda {
    val paramCount: Int
    fun invoke(args: List<Any?>): Any?
}

/**
 * The host's strategy for turning an interpreted [InterpretedLambda] into a JVM functional-interface proxy of
 * [functionalInterface] when it's passed to a library call. [composableParam] is true when the target
 * parameter's Kotlin type is `@Composable` (a Compose content slot) — the Compose bridge uses it to thread a
 * `Composer` into the lambda even when the callee itself isn't composable (e.g. `LazyListScope.items { … }`).
 * Returning null falls back to [ReflectiveDispatcher]'s plain proxy.
 */
fun interface LambdaProxyStrategy {
    fun proxyOrNull(lambda: InterpretedLambda, functionalInterface: Class<*>, composableParam: Boolean): Any?
}

/**
 * Which of [args] (in the order the dispatcher will pass them) target a `@Composable` function-type parameter
 * of [callee] — so a lambda there must be invoked with a threaded `Composer`. Aligned positionally to the
 * callee's value parameters, with the Kotlin trailing-lambda rule applied: a lambda in the LAST argument slot
 * binds to the last value parameter, so `items(xs) { … }` (whose composable `itemContent` is the final param
 * after defaulted ones) is detected even though the source args don't line up with declaration order.
 * [leadingReceiver] marks a prepended extension receiver that isn't a declared value parameter.
 */
fun composableParamFlags(callee: ResolvedCallable, args: List<Any?>, leadingReceiver: Boolean = false): List<Boolean> {
    val params = (callee as? ResolvedCallable.Library)?.paramTypes ?: return List(args.size) { false }
    val offset = if (leadingReceiver) 1 else 0
    // After named-arg reordering the args sit at their declared positions, so the trailing-lambda remap (a
    // heuristic for purely positional calls) is off — arg i aligns to value param i - offset directly.
    val ordered = args.any { it === OmittedArg }
    return List(args.size) { i ->
        if (i < offset) return@List false
        val pt = if (!ordered && i == args.lastIndex && args[i] is InterpretedLambda)
            params.lastOrNull() else params.getOrNull(i - offset)
        pt?.isComposable == true
    }
}

/**
 * Host strategy for running an interpreted `suspend` lambda (a `LaunchedEffect { }` / `rememberCoroutineScope()
 * .launch { }` block, a `pointerInput` handler) as a REAL, cancellable coroutine. Without it a suspend block is
 * run synchronously on the CALLER thread (the composition/main thread) with any suspend call best-effort/failed
 * — so `while (!done) { delay(…); … }` becomes a busy loop that hangs the app. The bridge runs [lambda] OFF the
 * caller thread and resumes the caller's [kotlin.coroutines.Continuation] when it finishes, so the caller
 * coroutine actually suspends. [args] is the full JVM invoke argument list — its LAST element is the
 * Continuation; the value/receiver params precede it. Returns the `COROUTINE_SUSPENDED` marker (so the caller
 * suspends); null falls back to the built-in best-effort synchronous run.
 */
interface SuspendBridge {
    fun runSuspending(lambda: InterpretedLambda, args: List<Any?>): Any?

    /**
     * Drive `flow.collect { action }` (and `collectLatest`) — the collect extension is `inline` (→ a
     * `FlowCollector` with a suspend `emit`), so the interpreter can't invoke it reflectively. Called on the
     * bridge's managed background thread with the runtime [flow] and the interpreted collector [action]; runs the
     * collection to completion (or until the coroutine is cancelled, for an endless `StateFlow`), invoking
     * [action] with each emitted value. Blocks the calling (bridge) thread — safe because it is a background
     * thread, and cancellation interrupts it. Returns Unit.
     */
    fun collectFlow(flow: Any, action: InterpretedLambda): Any?
}

/**
 * Marks the current thread as executing an interpreted `suspend` block under a [SuspendBridge] — so a suspend
 * intrinsic that must BLOCK (the `delay` sleep) does so only on the bridge's managed background thread, never on
 * the caller/main thread (where it would freeze the UI). Outside a managed context those intrinsics refuse to
 * block (they throw, degrading to the old best-effort behavior). Thread-local because the bridge runs each block
 * on its own coroutine thread.
 */
object SuspendContext {
    private val active = ThreadLocal.withInitial { false }
    private val suspends = ThreadLocal.withInitial { 0L }
    val isActive: Boolean get() = active.get()
    fun <T> runManaged(block: () -> T): T {
        active.set(true)
        try {
            return block()
        } finally {
            active.set(false)
        }
    }

    /** A per-thread count of real suspensions performed by interpreted code (a `delay`). The loop guard reads it
     *  to tell a COOPERATIVE loop (one that actually suspends each pass — a `delay`-driven timer that runs a long
     *  wall-clock time but isn't runaway) from a BUSY one (which never suspends and must be bounded). */
    fun suspendTicks(): Long = suspends.get()
    fun markSuspended() { suspends.set(suspends.get() + 1) }
}

/**
 * The seam for invoking an interpreted **composable** function body. The interpreter calls this instead of
 * running the body directly when a source callee is `@Composable`, so the Compose host (`interp-compose`)
 * can wrap the body in a restart group and register a recomposition that re-runs [body] — re-implementing
 * the compiler plugin's `startRestartGroup … endRestartGroup().updateScope { … }` at interpretation time,
 * against the real runtime. The default (no host) just runs the body once.
 *
 * [restartable] is true for a `Unit`-returning composable (the only shape the compiler makes skippable); when
 * it is set and none of [args] (the call's already-evaluated argument values) changed since the last
 * composition, the host may skip [body] entirely (`composer.skipToGroupEnd()`) instead of re-interpreting the
 * whole subtree — the `$changed` fast path. A non-restartable composable always runs [body] (its result is
 * needed), and [args] is ignored.
 */
fun interface ComposableInvoker {
    fun invokeComposable(callSiteKey: Int, restartable: Boolean, args: List<Any?>, body: () -> Any?): Any?
}

/**
 * The default dispatcher: plain JVM reflection. Instance/member calls reflect on the receiver's **runtime
 * class** (so no precise static owner is needed); top-level/extension calls reflect a static method on the
 * resolved `…Kt` facade (the extension receiver is prepended as the first argument); constructors reflect
 * the resolved type. Anything it can't map throws [InterpreterException] — the honest boundary.
 */
class ReflectiveDispatcher(
    private val loader: ClassLoader = ReflectiveDispatcher::class.java.classLoader,
    /** Host strategy for proxying interpreted lambdas (the Compose bridge threads a Composer); null = plain. */
    private val lambdaProxies: LambdaProxyStrategy? = null,
    /** Host strategy for running an interpreted `suspend` block as a real cancellable coroutine (`delay` &c.
     *  actually suspend). Null → the built-in best-effort synchronous run (suspend calls degrade). */
    private val suspendBridge: SuspendBridge? = null,
) : Dispatcher {

    override fun dispatch(call: RNode.Call, receiver: Any?, args: List<Any?>): Any? {
        val callee = call.callee
        // `flow.collect { action }` / `collectLatest` — the collect extension is `inline` (no reflectable JVM
        // method), so drive it through the coroutine bridge: a blocking collect on this (bridge-managed) thread,
        // invoking the interpreted collector per emission. Only under a bridge + a managed suspend context; a
        // collect elsewhere (or with no bridge) falls through to the honest reflective failure.
        if (suspendBridge != null && SuspendContext.isActive && receiver != null &&
            callee.displayName in FLOW_COLLECT_NAMES && isFlow(receiver)
        ) {
            (args.firstOrNull { it is InterpretedLambda } as? InterpretedLambda)
                ?.let { return suspendBridge.collectFlow(receiver, it) }
        }
        // Bind named arguments back to their declared positions (a no-op for a purely positional call), so a
        // call like `f(b = …, a = …)` or one that omits defaulted params dispatches correctly.
        @Suppress("NAME_SHADOWING")
        val args = if (callee is ResolvedCallable.Library)
            reorderNamedArgs(callee.paramNames, call.args, args) else args
        return when (call.dispatch) {
            // SUPER is resolved by the interpreter (source super → the supertype body; binary super → no-op), so
            // it normally never reaches here; if it does, the only sound thing is a plain instance invocation.
            DispatchKind.MEMBER, DispatchKind.OPERATOR, DispatchKind.INVOKE, DispatchKind.SUPER -> {
                val target = receiver ?: throw InterpreterException("instance call `${callee.displayName}` has no receiver")
                // A value-class member (`Color.copy(alpha = …)`, `Dp.coerceIn(…)`): the receiver is the UNBOXED
                // underlying value (a `Long` for `Color`), and the member compiles to a STATIC `name-<hash>` on
                // the value class taking that value as its first parameter — NOT an instance method on the
                // receiver's runtime class. Route it statically with the receiver prepended (receiverCount = 1,
                // exactly like an extension). Only for MEMBER dispatch; operators have their own handling.
                if (call.dispatch == DispatchKind.MEMBER) {
                    unboxedValueClassOwner(target, callee)?.let { ownerJvm ->
                        val all = listOf(target) + args
                        return invokeStatic(ownerJvm, callee.displayName, all, composableParamFlags(callee, all, leadingReceiver = true), receiverCount = 1)
                    }
                }
                invokeInstance(target, callee.displayName, args, composableParamFlags(callee, args))
            }
            // A member extension (`RowScope.weight`): `receiver` is the scope instance, the extension receiver is
            // the head of `args`. It's an instance method on the scope whose first param is the extension receiver
            // — and BOTH precede its value params, which matters for the defaulted-arg synthetic.
            DispatchKind.MEMBER_EXTENSION -> {
                val target = receiver ?: throw InterpreterException("member extension `${callee.displayName}` has no scope receiver")
                invokeMemberExtension(target, callee.displayName, args, composableParamFlags(callee, args))
            }
            DispatchKind.EXTENSION -> {
                val owner = libraryOwner(callee) ?: throw InterpreterException("extension `${callee.displayName}` has no owner")
                // Extensions compile to static facade methods with the receiver as the first parameter.
                val all = listOfNotNull(receiver) + args
                invokeStatic(owner, callee.displayName, all, composableParamFlags(callee, all, leadingReceiver = receiver != null), receiverCount = if (receiver != null) 1 else 0)
            }
            DispatchKind.TOP_LEVEL -> {
                val owner = libraryOwner(callee) ?: throw InterpreterException("top-level `${callee.displayName}` has no owner")
                invokeStatic(owner, callee.displayName, args, composableParamFlags(callee, args), receiverCount = 0)
            }
            DispatchKind.CONSTRUCTOR -> {
                val owner = libraryOwner(callee) ?: throw InterpreterException("constructor `${callee.displayName}` has no type")
                // The resolved overload's value-parameter count pins the matching `<init>$default` synthetic when
                // a named-arg call omits a defaulted parameter (`SpanStyle(fontWeight = …)`).
                val declaredParams = (callee as? ResolvedCallable.Library)?.let { maxOf(it.paramNames.size, it.paramTypes.size) } ?: args.size
                construct(owner, args, composableParamFlags(callee, args), declaredParams)
            }
        }
    }

    private fun libraryOwner(callee: ResolvedCallable): String? =
        (callee as? ResolvedCallable.Library)?.ownerFqn?.let { jvmName(it) }

    // Reflective method resolution (a `cls.methods` scan + overload pick, and for the `$default` path a
    // transitive-interface walk with `Class.forName($DefaultImpls)`) is deterministic given the class, the
    // Kotlin name, static-ness, and the runtime-TYPE shape of the args — but the interpreter re-runs it for
    // every call on every recomposition. Memoize it per dispatcher (which lives across recompositions). Keyed
    // on the Class IDENTITY (not its name) so two same-named classes from different loaders — the device
    // preview's project DexClassLoader vs the bundled runtime — never alias. A holder boxes the nullable
    // result so a "resolved to nothing" answer is cached too (ConcurrentHashMap forbids null values).
    private class MethodHolder(val method: Method?)
    private val methodCache = java.util.concurrent.ConcurrentHashMap<Class<*>, java.util.concurrent.ConcurrentHashMap<String, MethodHolder>>()

    private fun cacheFor(cls: Class<*>) = methodCache.getOrPut(cls) { java.util.concurrent.ConcurrentHashMap() }

    /** A stable key for [args] by runtime type — enough to pick the same overload, distinguishing null,
     *  omitted (default), an interpreted lambda, and each concrete class. */
    private fun argShape(args: List<Any?>): String = buildString {
        for (a in args) {
            when {
                a == null -> append('∅')
                a === OmittedArg -> append('_')
                a is InterpretedLambda -> append('λ')
                else -> append(a.javaClass.name)
            }
            append(';')
        }
    }

    private fun invokeInstance(target: Any, name: String, args: List<Any?>, composable: List<Boolean>): Any? {
        // An omitted (defaulted) parameter has no exact-arity match — go straight to the `$default` synthetic.
        if (args.none { it === OmittedArg }) {
            findMethod(target.javaClass, name, args, static = false)?.let { m ->
                runCatching { m.isAccessible = true }
                return m.invoke(target, *bindArgs(m.parameterTypes, args, composable, m.genericParameterTypes))
            }
            // No exact-arity match: a vararg method (`fun f(vararg x)`) packs the trailing args into an array.
            findVarargMethod(target.javaClass, name, args, static = false)?.let { return invokeVararg(it, target, args, composable) }
        }
        // A call that omits defaulted params has no exact-arity match — Kotlin emits a STATIC `name$default`
        // synthetic that takes the receiver as its first real parameter, so prepend the target (and that
        // receiver is not numbered in the `$default` mask → receiverCount = 1).
        invokeViaDefaultSynthetic(target.javaClass, name, listOf(target) + args, listOf(false) + composable, receiverCount = 1)
            ?.let { return it.value }
        throw InterpreterException("no method `$name`(${args.size}) on ${target.javaClass.name}")
    }

    /** Invoke a member extension on its scope [scope]: an instance method `name(extensionReceiver, value…)`.
     *  [args] is `[extensionReceiver, value…]`. When a value param is defaulted (omitted), the `$default`
     *  synthetic is needed — and it has TWO non-value leading params (the scope `$this` AND the extension
     *  receiver), so `receiverCount = 2`; the synthetic may live on the scope's interface (see
     *  [invokeViaDefaultSynthetic]'s interface search), not the runtime impl class. */
    private fun invokeMemberExtension(scope: Any, name: String, args: List<Any?>, composable: List<Boolean>): Any? {
        if (args.none { it === OmittedArg }) {
            findMethod(scope.javaClass, name, args, static = false)?.let { m ->
                runCatching { m.isAccessible = true }
                return m.invoke(scope, *bindArgs(m.parameterTypes, args, composable, m.genericParameterTypes))
            }
            findVarargMethod(scope.javaClass, name, args, static = false)?.let { return invokeVararg(it, scope, args, composable) }
        }
        invokeViaDefaultSynthetic(scope.javaClass, name, listOf(scope) + args, listOf(false) + composable, receiverCount = 2)
            ?.let { return it.value }
        throw InterpreterException("no member extension `$name`(${args.size}) on ${scope.javaClass.name}")
    }

    private fun invokeStatic(ownerFqn: String, name: String, args: List<Any?>, composable: List<Boolean>, receiverCount: Int): Any? {
        val cls = loadClass(ownerFqn)
        if (args.none { it === OmittedArg }) {
            findMethod(cls, name, args, static = true)?.let { m ->
                runCatching { m.isAccessible = true }
                return m.invoke(null, *bindArgs(m.parameterTypes, args, composable, m.genericParameterTypes))
            }
            findVarargMethod(cls, name, args, static = true)?.let { return invokeVararg(it, null, args, composable) }
        }
        invokeViaDefaultSynthetic(cls, name, args, composable, receiverCount)?.let { return it.value }
        throw InterpreterException("no static `$name`(${args.size}) on $ownerFqn")
    }

    private class Invoked(val value: Any?)

    /**
     * Invoke [name]'s Kotlin default-arguments synthetic — `<jvmName>$default(realParams…, int mask, Object
     * marker)`, always static — filling the omitted (defaulted) parameters. [realArgs] are the supplied
     * positional args INCLUDING any receiver (the synthetic takes the receiver as its first real param). A set
     * `$default` mask bit tells the synthetic to use the default for that parameter; the marker is always
     * null. [receiverCount] is the number of leading params that are an extension/dispatch receiver (0 for
     * top-level, 1 for extension/member) — NOT numbered in the mask, so a value param at JVM slot `i` has mask
     * bit `i - receiverCount`. Returns null when no fitting synthetic exists (so the caller reports the
     * original "not found"). Assumes ≤32 value parameters (one mask int) — true of every real fn.
     */
    private fun invokeViaDefaultSynthetic(cls: Class<*>, name: String, realArgs: List<Any?>, composable: List<Boolean>, receiverCount: Int): Invoked? {
        val m = findDefaultSynthetic(cls, name, realArgs) ?: return null
        val params = m.parameterTypes
        val n = params.size - 2 // realParams…, int mask, Object marker
        val k = realArgs.size
        // The `$default` synthetic carries an ERASED signature (no `List<Color>` generic type), so value-class
        // COLLECTION element boxing must read the generic types off the REAL method instead (same class, same
        // first-n erased param types). Null when there's no matching real method (then only scalar boxing runs).
        val realGenericParams = realMethodGenericParams(cls, m, n)
        // After named-arg reordering the args are ALREADY in declaration order (with [OmittedArg] holes), so
        // the trailing-lambda remap must be off and an omitted slot is simply left to its default.
        val ordered = realArgs.any { it === OmittedArg }
        val trailingLambda = !ordered && realArgs.lastOrNull() is InterpretedLambda
        val slots = arrayOfNulls<Any?>(params.size)
        var mask = 0
        val provided = BooleanArray(n)
        for (i in 0 until k) {
            val a = realArgs[i]
            if (a === OmittedArg) continue
            val slot = if (trailingLambda && i == k - 1) n - 1 else i
            if (slot !in 0 until n) continue
            slots[slot] = if (a is InterpretedLambda)
                (lambdaProxies?.proxyOrNull(a, params[slot], composable.getOrElse(i) { false }) ?: regularLambdaProxy(a, params[slot]))
            else coerceArg(a, params[slot], realGenericParams?.getOrNull(slot))
            provided[slot] = true
        }
        // A set mask bit i ⇒ use the default for value param i. The receiver(s) precede the value params in
        // the JVM signature but aren't numbered in the mask, so shift by receiverCount.
        for (i in receiverCount until n) if (!provided[i]) { slots[i] = zeroValue(params[i]); mask = mask or (1 shl (i - receiverCount)) }
        for (i in 0 until receiverCount) if (!provided[i]) slots[i] = zeroValue(params[i]) // defensive; receiver is normally supplied
        slots[n] = mask
        slots[n + 1] = null // the synthetic's super-call marker is always null
        runCatching { m.isAccessible = true }
        return Invoked(m.invoke(null, *slots))
    }

    /** The fitting `<name>$default` synthetic (smallest arity) for [realArgs]. Cached — the lookup scans
     *  `cls.methods` AND does a transitive-interface walk with `Class.forName($DefaultImpls)`, the costliest
     *  reflection path; it's deterministic given the class, name, and arg shape. The `$default` synthetic is
     *  static: for a class member it's on the class (in `cls.methods`); for an INTERFACE member (e.g.
     *  `RowScope.weight`) it's static on the interface (jvm-default=all) or its `…$DefaultImpls`
     *  (jvm-default=disable) — neither inherited into the impl's `getMethods()`, so the interfaces are searched. */
    private fun findDefaultSynthetic(cls: Class<*>, name: String, realArgs: List<Any?>): Method? {
        val cache = cacheFor(cls)
        val key = "d|$name|${argShape(realArgs)}"
        cache[key]?.let { return it.method }
        val m = (cls.methods.asSequence() + interfaceDefaultSynthetics(cls, name))
            .filter { Modifier.isStatic(it.modifiers) && isDefaultSynthetic(it.name, name) && fitsDefaultSynthetic(it, realArgs) }
            .minByOrNull { it.parameterCount }
        return m.also { cache[key] = MethodHolder(it) }
    }

    /** Static `<name>$default` synthetics declared on [cls]'s interfaces (transitively) and their `$DefaultImpls`
     *  — where an interface member's defaulted-arg synthetic lives (it isn't inherited into the impl class). */
    private fun interfaceDefaultSynthetics(cls: Class<*>, name: String): List<Method> {
        val out = ArrayList<Method>()
        val seen = HashSet<String>()
        val queue = ArrayDeque<Class<*>>()
        var c: Class<*>? = cls
        while (c != null) { c.interfaces.forEach(queue::add); c = c.superclass }
        while (queue.isNotEmpty()) {
            val iface = queue.removeFirst()
            if (!seen.add(iface.name)) continue
            iface.interfaces.forEach(queue::add)
            runCatching { iface.declaredMethods.filterTo(out) { Modifier.isStatic(it.modifiers) && isDefaultSynthetic(it.name, name) } }
            runCatching { Class.forName("${iface.name}\$DefaultImpls", false, iface.classLoader ?: loader) }
                .getOrNull()?.let { di -> runCatching { di.declaredMethods.filterTo(out) { Modifier.isStatic(it.modifiers) && isDefaultSynthetic(it.name, name) } } }
        }
        return out
    }

    private fun isDefaultSynthetic(jvmName: String, kotlinName: String): Boolean =
        jvmName.endsWith("\$default") && mangledNameMatches(jvmName.removeSuffix("\$default"), kotlinName)

    /** The synthetic is `(realParams…, int mask, Object marker)`; [realArgs] must fit the first `n` reals. */
    private fun fitsDefaultSynthetic(m: Method, realArgs: List<Any?>): Boolean {
        val pc = m.parameterCount
        if (pc < realArgs.size + 2) return false
        val n = pc - 2
        val params = m.parameterTypes
        if (params[n] != Int::class.javaPrimitiveType || params[pc - 1].isPrimitive) return false
        val k = realArgs.size
        val ordered = realArgs.any { it === OmittedArg }
        val trailingLambda = !ordered && realArgs.lastOrNull() is InterpretedLambda
        for (i in 0 until k) {
            val a = realArgs[i]
            if (a === OmittedArg) continue // an omitted slot fits any param (the synthetic supplies its default)
            val slot = if (trailingLambda && i == k - 1) n - 1 else i
            if (slot !in 0 until n) return false
            val p = params[slot]
            when (a) {
                is InterpretedLambda -> if (!p.isInterface) return false
                null -> if (p.isPrimitive) return false
                else -> if (!wrap(p).isInstance(a)) return false
            }
        }
        return true
    }

    private fun zeroValue(type: Class<*>): Any? = when (type) {
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> ' '
        Double::class.javaPrimitiveType -> 0.0
        Float::class.javaPrimitiveType -> 0f
        Boolean::class.javaPrimitiveType -> false
        else -> null
    }

    private fun construct(ownerFqn: String, args: List<Any?>, composable: List<Boolean>, declaredParamCount: Int): Any? {
        // An unqualified type name (a stdlib exception the resolver couldn't fully qualify, e.g.
        // `IllegalArgumentException`) — try the `java.lang` package before giving up.
        val cls = runCatching { Class.forName(ownerFqn, false, loader) }.getOrNull()
            ?: (if ('.' !in ownerFqn) runCatching { Class.forName("java.lang.$ownerFqn", false, loader) }.getOrNull() else null)
            ?: throw InterpreterException("cannot load class `$ownerFqn`")
        // An interior [OmittedArg] hole means a defaulted param was skipped — there's no exact-arity match, so
        // go straight to the synthetic (and a hole must never reach `newInstance`).
        val hasOmitted = args.any { it === OmittedArg }
        if (!hasOmitted) {
            cls.declaredConstructors.filter { it.parameterCount == args.size }
                .firstOrNull { paramsAccept(it.parameterTypes, args) }
                ?.let { runCatching { it.isAccessible = true }; return it.newInstance(*bindArgs(it.parameterTypes, args, composable, it.genericParameterTypes)) }
        }
        // A call that omits a defaulted parameter (`SpanStyle(fontWeight = …)`, or a trimmed trailing default
        // that left no hole) has no exact-arity match — Kotlin emits an `<init>$default` synthetic constructor
        // that fills the defaults. Tried for both shapes, exactly as the static/instance paths try theirs.
        invokeViaDefaultConstructor(cls, args, composable, declaredParamCount)?.let { return it.value }
        // Last resort: an arity match whose types the strict [paramsAccept] rejected (preserves prior behavior).
        if (!hasOmitted) {
            cls.declaredConstructors.firstOrNull { it.parameterCount == args.size }
                ?.let { runCatching { it.isAccessible = true }; return it.newInstance(*bindArgs(it.parameterTypes, args, composable, it.genericParameterTypes)) }
        }
        throw InterpreterException("no constructor(${args.count { it !== OmittedArg }}) on $ownerFqn")
    }

    /**
     * Construct via Kotlin's default-arguments synthetic constructor — `<init>(realParams…, int mask[, int
     * mask…], DefaultConstructorMarker)`, the binary form a call uses when it omits a defaulted constructor
     * parameter. Mirrors [invokeViaDefaultSynthetic] but for a constructor: there is NO receiver (every real
     * param is a value param, numbered 0-based in the mask), there is one `int` mask per 32 value params, and
     * the trailing marker is `DefaultConstructorMarker` (always passed null — the synthetic reads the mask, not
     * the marker). [realArgs] are already in declaration order with [OmittedArg] holes (named-arg reordering
     * ran first, so no trailing-lambda remap is needed). Returns null when no fitting synthetic exists.
     */
    private fun invokeViaDefaultConstructor(cls: Class<*>, realArgs: List<Any?>, composable: List<Boolean>, declaredParamCount: Int): Invoked? {
        val (ctor, maskCount) = findDefaultConstructor(cls, realArgs, declaredParamCount) ?: return null
        val params = ctor.parameterTypes
        val n = params.size - maskCount - 1 // realParams…, int×maskCount, DefaultConstructorMarker
        // The synthetic `<init>$default` signature is erased; read value-class COLLECTION element generics off
        // the REAL constructor (same declaring class, identical first-n erased param types) instead.
        val realGenericParams = cls.declaredConstructors.firstOrNull { c ->
            c.parameterCount == n && (0 until n).all { c.parameterTypes[it] == params[it] }
        }?.genericParameterTypes
        val slots = arrayOfNulls<Any?>(params.size)
        val masks = IntArray(maskCount)
        for (i in 0 until n) {
            // A set mask bit i ⇒ use the default for value param i. An explicit null is "provided" (no bit).
            if (i < realArgs.size && realArgs[i] !== OmittedArg) {
                val a = realArgs[i]
                slots[i] = if (a is InterpretedLambda)
                    (lambdaProxies?.proxyOrNull(a, params[i], composable.getOrElse(i) { false }) ?: regularLambdaProxy(a, params[i]))
                else coerceArg(a, params[i], realGenericParams?.getOrNull(i))
            } else {
                slots[i] = zeroValue(params[i])
                masks[i / 32] = masks[i / 32] or (1 shl (i % 32))
            }
        }
        for (j in 0 until maskCount) slots[n + j] = masks[j]
        slots[params.size - 1] = null // the DefaultConstructorMarker is always null
        runCatching { ctor.isAccessible = true }
        return Invoked(ctor.newInstance(*slots))
    }

    /** The fitting synthetic default-args constructor for [realArgs], paired with its `int` mask count. Prefers
     *  the one whose real-param count equals the resolved overload's [declaredParamCount] (with the matching
     *  mask width `ceil(n/32)`); falls back to the smallest single-mask synthetic that fits when the resolver
     *  didn't carry an exact count (covers any class with ≤32 value parameters). */
    private fun findDefaultConstructor(cls: Class<*>, realArgs: List<Any?>, declaredParamCount: Int): Pair<java.lang.reflect.Constructor<*>, Int>? {
        if (declaredParamCount >= 1) {
            val masks = (declaredParamCount + 31) / 32
            cls.declaredConstructors.firstOrNull {
                defaultConstructorRealParams(it.parameterTypes, masks) == declaredParamCount && fitsRealParams(it.parameterTypes, declaredParamCount, realArgs)
            }?.let { return it to masks }
        }
        return cls.declaredConstructors
            .filter { val n = defaultConstructorRealParams(it.parameterTypes, 1); n >= 0 && fitsRealParams(it.parameterTypes, n, realArgs) }
            .minByOrNull { it.parameterCount }
            ?.let { it to 1 }
    }

    /** The real (value) parameter count of a Kotlin default-args synthetic constructor whose JVM shape is
     *  `(realParams…, int×[maskCount], DefaultConstructorMarker)` — i.e. `params.size - maskCount - 1` — or -1
     *  when [params] isn't that shape for [maskCount] (no trailing marker, or the mask slots aren't `int`). */
    private fun defaultConstructorRealParams(params: Array<Class<*>>, maskCount: Int): Int {
        val n = params.size - maskCount - 1
        if (n < 0 || params.last().name != DEFAULT_CONSTRUCTOR_MARKER) return -1
        if ((0 until maskCount).any { params[n + it] != Int::class.javaPrimitiveType }) return -1
        return n
    }

    /** Whether [realArgs] (already in declaration order, with [OmittedArg] holes) fit the first [n] real params
     *  of a synthetic — like [paramsAccept] but only over the leading reals, with an omitted slot fitting any
     *  param (the synthetic supplies that parameter's default). */
    private fun fitsRealParams(params: Array<Class<*>>, n: Int, realArgs: List<Any?>): Boolean {
        if (realArgs.size > n) return false
        for (i in realArgs.indices) {
            val a = realArgs[i]
            if (a === OmittedArg) continue
            val p = params[i]
            val ok = when (a) {
                is InterpretedLambda -> p.isInterface
                null -> !p.isPrimitive
                // A boxed value-class param (`SpanStyle.fontStyle: FontStyle?`) also accepts the unboxed
                // underlying value the interpreter produced — [boxValueClassIfNeeded] boxes it at invoke time.
                else -> wrap(p).isInstance(a) || acceptsValueClassUnderlying(p, a)
            }
            if (!ok) return false
        }
        return true
    }

    /** Box an unboxed inline-value-class value for a NULLABLE/boxed value-class parameter. An inline value class
     *  (`Color`, `TextUnit`, `FontStyle`) is represented UNBOXED (its underlying primitive) for a non-null
     *  parameter, but BOXED for a nullable one (`fontStyle: FontStyle?` → JVM type `FontStyle`); a value-class
     *  expression evaluates to the unboxed underlying value, so the boxed parameter needs the synthetic static
     *  `box-impl` applied first. Anything already the right type, a null, or a primitive param passes through. */
    private fun boxValueClassIfNeeded(value: Any?, paramType: Class<*>): Any? {
        if (value == null || paramType.isPrimitive || paramType.isInstance(value)) return value
        val box = paramType.methods.firstOrNull {
            it.name == "box-impl" && Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 1 && wrap(it.parameterTypes[0]).isInstance(value)
        } ?: return value
        runCatching { box.isAccessible = true }
        return box.invoke(null, value)
    }

    /**
     * Coerce a supplied [value] to fit [paramType] for a reflective invoke. Handles the scalar value-class case
     * ([boxValueClassIfNeeded]) AND the COLLECTION case: the interpreter represents an inline value class
     * (`Color`, `Dp`, …) UNBOXED, so a `listOf(Color.Red, …)` it builds is a `List<Long>` — but a parameter
     * typed `List<Color>` (erased to `List`) is read back by the callee as boxed `Color`s (`element as Color`),
     * so the raw underlying values must be boxed first or the callee ClassCastExceptions at USE (the reported
     * `Long cannot be cast to androidx.compose.ui.graphics.Color` gradient-brush crash —
     * `Brush.linearGradient(listOf(Color…))`). [genericType] (the parameter's generic type) pins the element
     * value class; when it is a `Collection<ValueClass>` and [value] is a collection, returns a copy with each
     * element boxed (a `List` stays a `List`, a `Set` stays a `Set`).
     */
    private fun coerceArg(value: Any?, paramType: Class<*>, genericType: java.lang.reflect.Type?): Any? {
        val typeArgs = (genericType as? java.lang.reflect.ParameterizedType)?.actualTypeArguments
        // Collection<VC>: box each element (`List<Color>` built from `listOf(Color.Red, …)`).
        if (value is Collection<*> && Collection::class.java.isAssignableFrom(paramType)) {
            valueClassOf(typeArgs?.firstOrNull())?.let { elem ->
                val boxed = value.map { boxValueClassIfNeeded(it, elem) }
                return if (value is Set<*>) LinkedHashSet(boxed) else boxed
            }
        }
        // Map<K, VC> / Map<VC, V>: box value-class keys/values (`Map<String, Color>`).
        if (value is Map<*, *> && Map::class.java.isAssignableFrom(paramType) && typeArgs?.size == 2) {
            val keyVc = valueClassOf(typeArgs[0])
            val valVc = valueClassOf(typeArgs[1])
            if (keyVc != null || valVc != null) {
                val out = LinkedHashMap<Any?, Any?>(value.size)
                value.forEach { (k, v) ->
                    out[if (keyVc != null) boxValueClassIfNeeded(k, keyVc) else k] =
                        if (valVc != null) boxValueClassIfNeeded(v, valVc) else v
                }
                return out
            }
        }
        return boxValueClassIfNeeded(value, paramType)
    }

    /** The generic parameter types of the REAL method behind a `<name>$default` synthetic [syn] (whose own
     *  signature is erased — no `List<Color>`), aligned to the synthetic's [n] real slots. The real method is
     *  either STATIC (its params line up 1:1 with the synthetic's slots — a top-level/extension callable) or an
     *  INSTANCE method (its `$this` receiver is the synthetic's FIRST slot but implicit in the real method, so
     *  its params line up with slots 1..n-1). Try both, matching by name + identical erased param types; align
     *  the found method's generics to synthetic slots (a receiver slot keeps its raw type). Null when neither
     *  matches (a rarer shape, e.g. a member extension) — the caller then boxes only scalars. */
    private fun realMethodGenericParams(cls: Class<*>, syn: Method, n: Int): Array<java.lang.reflect.Type>? {
        val realName = syn.name.removeSuffix("\$default")
        val synParams = syn.parameterTypes
        for (offset in intArrayOf(0, 1)) {
            val realCount = n - offset
            if (realCount < 0) continue
            val real = cls.methods.firstOrNull { r ->
                r.name == realName && !r.name.endsWith("\$default") && r.parameterCount == realCount &&
                    (0 until realCount).all { r.parameterTypes[it] == synParams[it + offset] }
            } ?: continue
            val g = real.genericParameterTypes
            return Array(n) { s -> if (s < offset) synParams[s] else g.getOrElse(s - offset) { synParams[s] } }
        }
        return null
    }

    /** [t] as an inline VALUE CLASS (one with a static `box-impl`), unwrapping declaration-site `out` variance
     *  (`? extends Color`). Null for a raw/type-variable type or a non-value-class — no element boxing needed. */
    private fun valueClassOf(t: java.lang.reflect.Type?): Class<*>? {
        val c = when (t) {
            is Class<*> -> t
            is java.lang.reflect.WildcardType -> t.upperBounds.firstOrNull() as? Class<*>
            else -> null
        } ?: return null
        return if (c.declaredMethods.any { it.name == "box-impl" && Modifier.isStatic(it.modifiers) }) c else null
    }

    /** When [callee] is a value-class member invoked with the UNBOXED underlying receiver (a `Long` for
     *  `Color`), the JVM owner whose static `name-<hash>` form the call must go to — else null. Detected by:
     *  the callee's declared owner loads, is an inline value class (has a static `box-impl`), and the receiver
     *  is NOT already an instance of it (so it is the unboxed underlying, not a boxed value-class instance). */
    private fun unboxedValueClassOwner(receiver: Any, callee: ResolvedCallable): String? {
        val ownerJvm = libraryOwner(callee) ?: return null
        val cls = runCatching { loadClass(ownerJvm) }.getOrNull() ?: return null
        if (cls.isInstance(receiver)) return null
        val isValueClass = cls.declaredMethods.any { it.name == "box-impl" && Modifier.isStatic(it.modifiers) }
        return if (isValueClass) ownerJvm else null
    }

    /** Whether [paramType] is an inline value class whose underlying type accepts [value] — so an unboxed
     *  value-class value fits a boxed value-class parameter (it'll be boxed by [boxValueClassIfNeeded]). */
    private fun acceptsValueClassUnderlying(paramType: Class<*>, value: Any?): Boolean {
        val box = paramType.methods.firstOrNull {
            it.name == "box-impl" && Modifier.isStatic(it.modifiers) && it.parameterCount == 1
        } ?: return false
        return wrap(box.parameterTypes[0]).isInstance(value)
    }

    /** Convert an interpreted lambda arg into a JVM functional-interface proxy of the target parameter type
     *  (composable params route through [lambdaProxies] so the Compose bridge can thread a Composer); pass
     *  everything else through. */
    private fun bindArgs(params: Array<Class<*>>, args: List<Any?>, composable: List<Boolean>, genericParams: Array<java.lang.reflect.Type>? = null): Array<Any?> =
        Array(args.size) { i ->
            (args[i] as? InterpretedLambda)?.let { lam ->
                lambdaProxies?.proxyOrNull(lam, params[i], composable.getOrElse(i) { false })
                    ?: regularLambdaProxy(lam, params[i])
            } ?: coerceArg(args[i], params[i], genericParams?.getOrNull(i))
        }

    private fun regularLambdaProxy(lambda: InterpretedLambda, functionalInterface: Class<*>): Any {
        // A SUSPEND lambda — a `pointerInput { … }` gesture block, a `LaunchedEffect { … }` body, a coroutine
        // builder — is invoked with a trailing `Continuation` argument (its JVM `Function.invoke` is erased to
        // `invoke(Object)`, so suspend can only be seen at CALL time, not from the interface). When a
        // [suspendBridge] is wired the block runs as a REAL cancellable coroutine OFF the caller thread, so
        // suspend calls inside it (`delay`, …) actually suspend rather than busy-loop/fail. Without a bridge the
        // synchronous interpreter can't drive suspension, so a suspend stdlib call it can't thread throws — and
        // uncaught inside Compose's coroutine that crashes the host; the block is run best-effort, SWALLOWing a
        // failure so the preview degrades instead of taking down the IDE. A non-suspend invocation (a map/filter
        // predicate, an `onClick` handler) still propagates, so genuine errors there surface.
        return java.lang.reflect.Proxy.newProxyInstance(
            functionalInterface.classLoader ?: loader, arrayOf(functionalInterface),
        ) { _, method, callArgs ->
            when (method.name) {
                "invoke" -> {
                    val a = callArgs?.toList() ?: emptyList()
                    if (a.lastOrNull() is kotlin.coroutines.Continuation<*>)
                        suspendBridge?.runSuspending(lambda, a) ?: runCatching { lambda.invoke(a) }.getOrDefault(Unit)
                    else lambda.invoke(a)
                }
                "toString" -> "InterpretedLambda"
                "hashCode" -> System.identityHashCode(lambda)
                "equals" -> callArgs?.getOrNull(0) === lambda
                else -> null
            }
        }
    }

    /** Prefer a method declared on a public type (invokable under the module system) that accepts the args.
     *  Matches a mangled JVM name (`name-<hash>`) too, for functions with inline value-class params. Cached. */
    private fun findMethod(cls: Class<*>, name: String, args: List<Any?>, static: Boolean): Method? {
        val cache = cacheFor(cls)
        val key = "m|$name|$static|${argShape(args)}"
        cache[key]?.let { return it.method }
        return findMethodUncached(cls, name, args, static).also { cache[key] = MethodHolder(it) }
    }

    private fun findMethodUncached(cls: Class<*>, name: String, args: List<Any?>, static: Boolean): Method? {
        // Vararg methods are excluded here (`!isVarArgs`) and handled by [findVarargMethod]/[invokeVararg]:
        // their JVM arity counts the array as ONE param, so an exact-arity match would mis-bind the scalar args.
        val byArity = cls.methods.filter { m ->
            mangledNameMatches(m.name, name) && !m.isVarArgs && m.parameterCount == args.size && (Modifier.isStatic(m.modifiers) == static)
        }
        val accepting = byArity.filter { paramsAccept(it.parameterTypes, args) }
        val chosen = accepting.firstOrNull { Modifier.isPublic(it.declaringClass.modifiers) }
            ?: accepting.firstOrNull()
            ?: byArity.firstOrNull { Modifier.isPublic(it.declaringClass.modifiers) }
            ?: byArity.firstOrNull()
            ?: return null
        // The match may be declared on a NON-public type that `getMethods()` surfaced as the only override —
        // e.g. `java.util.Arrays$ArrayList.get` (the result of `listOf(…)`): not invokable under the JDK module
        // system even with setAccessible. Re-resolve to the same instance method on a PUBLIC supertype/interface
        // (`List.get`), which IS invokable (virtual dispatch reaches the real impl). `listOf(…)[i]` → `List.get`.
        if (!static && !Modifier.isPublic(chosen.declaringClass.modifiers)) {
            publicMethod(cls, chosen.name, chosen.parameterCount)?.let { return it }
        }
        return chosen
    }

    /** A vararg method `name(fixed…, T[])` that can absorb [args]: enough args for the fixed params and each
     *  fixed param accepts its arg. The trailing args (beyond the fixed ones) pack into the vararg array. Cached. */
    private fun findVarargMethod(cls: Class<*>, name: String, args: List<Any?>, static: Boolean): Method? {
        val cache = cacheFor(cls)
        val key = "v|$name|$static|${argShape(args)}"
        cache[key]?.let { return it.method }
        return findVarargMethodUncached(cls, name, args, static).also { cache[key] = MethodHolder(it) }
    }

    private fun findVarargMethodUncached(cls: Class<*>, name: String, args: List<Any?>, static: Boolean): Method? {
        val candidates = cls.methods.filter { m ->
            m.isVarArgs && mangledNameMatches(m.name, name) && Modifier.isStatic(m.modifiers) == static &&
                m.parameterCount >= 1 && args.size >= m.parameterCount - 1 &&
                leadingParamsAccept(m.parameterTypes, args, m.parameterCount - 1)
        }
        return candidates.firstOrNull { Modifier.isPublic(it.declaringClass.modifiers) } ?: candidates.firstOrNull()
    }

    /** Invoke a vararg [m]: bind the fixed leading args, pack the remaining args into a fresh array of the
     *  vararg element type (boxing/unboxing handled by [java.lang.reflect.Array.set]), then call. */
    private fun invokeVararg(m: Method, receiver: Any?, args: List<Any?>, composable: List<Boolean>): Any? {
        val pc = m.parameterCount
        val fixed = pc - 1
        val componentType = m.parameterTypes[pc - 1].componentType
        val varargArray = java.lang.reflect.Array.newInstance(componentType, args.size - fixed)
        for (j in 0 until args.size - fixed) {
            val a = args[fixed + j]
            val v = (a as? InterpretedLambda)?.let { lam ->
                lambdaProxies?.proxyOrNull(lam, componentType, composable.getOrElse(fixed + j) { false }) ?: regularLambdaProxy(lam, componentType)
            } ?: boxValueClassIfNeeded(a, componentType)
            java.lang.reflect.Array.set(varargArray, j, v)
        }
        val leading = bindArgs(m.parameterTypes.copyOfRange(0, fixed), args.subList(0, fixed), composable, m.genericParameterTypes.copyOfRange(0, fixed))
        val callArgs = arrayOfNulls<Any?>(pc)
        for (i in 0 until fixed) callArgs[i] = leading[i]
        callArgs[fixed] = varargArray
        runCatching { m.isAccessible = true }
        return m.invoke(receiver, *callArgs)
    }

    private fun leadingParamsAccept(params: Array<Class<*>>, args: List<Any?>, count: Int): Boolean =
        (0 until count).all { i ->
            val p = params[i]
            when (val a = args[i]) {
                null -> !p.isPrimitive
                is InterpretedLambda -> p.isInterface
                else -> wrap(p).isInstance(a) || acceptsValueClassUnderlying(p, a)
            }
        }

    /** Whether each argument fits the (possibly primitive) parameter type — null fits any reference type, an
     *  interpreted lambda fits any functional interface, and an unboxed inline-value-class value fits a boxed
     *  value-class param (`bindArgs` boxes it via `box-impl` at invoke time). */
    private fun paramsAccept(params: Array<Class<*>>, args: List<Any?>): Boolean =
        params.size == args.size && params.indices.all { i ->
            val p = params[i]
            when (val a = args[i]) {
                null -> !p.isPrimitive
                is InterpretedLambda -> p.isInterface
                else -> wrap(p).isInstance(a) || acceptsValueClassUnderlying(p, a)
            }
        }

    private fun wrap(c: Class<*>): Class<*> = when (c) {
        Int::class.javaPrimitiveType -> Integer::class.java
        Long::class.javaPrimitiveType -> java.lang.Long::class.java
        Double::class.javaPrimitiveType -> java.lang.Double::class.java
        Float::class.javaPrimitiveType -> java.lang.Float::class.java
        Boolean::class.javaPrimitiveType -> java.lang.Boolean::class.java
        Char::class.javaPrimitiveType -> Character::class.java
        Byte::class.javaPrimitiveType -> java.lang.Byte::class.java
        Short::class.javaPrimitiveType -> java.lang.Short::class.java
        else -> c
    }

    /** Whether [value]'s runtime type is a `kotlinx.coroutines.flow.Flow` — matched by interface NAME across the
     *  type hierarchy (no kotlinx dependency in interp-core), so a `StateFlow`/`SharedFlow`/custom flow all pass. */
    private fun isFlow(value: Any): Boolean {
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>().apply { add(value.javaClass) }
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            if (c.name == "kotlinx.coroutines.flow.Flow") return true
            c.superclass?.let { queue.add(it) }
            c.interfaces.forEach { queue.add(it) }
        }
        return false
    }

    private fun loadClass(fqn: String): Class<*> =
        runCatching { Class.forName(fqn, false, loader) }.getOrElse {
            throw InterpreterException("cannot load class `$fqn`: ${it.message}")
        }

    /** Map a Kotlin classifier FQN to its JVM class for reflection; `…Kt` facades are already JVM names. */
    private fun jvmName(fqn: String): String = KOTLIN_TO_JVM[fqn] ?: fqn

    private companion object {
        /** The trailing marker parameter of every Kotlin default-args synthetic constructor — distinguishes it
         *  from the real constructor (which shares the leading parameters) when scanning `declaredConstructors`. */
        const val DEFAULT_CONSTRUCTOR_MARKER = "kotlin.jvm.internal.DefaultConstructorMarker"

        /** The `Flow.collect { }` terminal operators the coroutine bridge drives (inline extensions with no JVM
         *  method); routed to [SuspendBridge.collectFlow] when the receiver is a `Flow`. */
        val FLOW_COLLECT_NAMES = setOf("collect", "collectLatest")

        // The common Kotlin↔JVM mapped types (enough for constructors/operators on mapped classes).
        val KOTLIN_TO_JVM = mapOf(
            "kotlin.String" to "java.lang.String",
            "kotlin.CharSequence" to "java.lang.CharSequence",
            "kotlin.Any" to "java.lang.Object",
            "kotlin.Throwable" to "java.lang.Throwable",
            "kotlin.Comparable" to "java.lang.Comparable",
            "kotlin.Number" to "java.lang.Number",
            "kotlin.collections.List" to "java.util.List",
            "kotlin.collections.MutableList" to "java.util.ArrayList",
            "kotlin.collections.Map" to "java.util.Map",
            "kotlin.collections.MutableMap" to "java.util.LinkedHashMap",
            "kotlin.collections.Set" to "java.util.Set",
            "kotlin.text.StringBuilder" to "java.lang.StringBuilder",
            // Common exception aliases (Kotlin declares these as typealiases to the java.lang types), so a
            // `throw IllegalArgumentException(...)` constructs the real JVM exception.
            "kotlin.Exception" to "java.lang.Exception",
            "kotlin.RuntimeException" to "java.lang.RuntimeException",
            "kotlin.Error" to "java.lang.Error",
            "kotlin.IllegalArgumentException" to "java.lang.IllegalArgumentException",
            "kotlin.IllegalStateException" to "java.lang.IllegalStateException",
            "kotlin.IndexOutOfBoundsException" to "java.lang.IndexOutOfBoundsException",
            "kotlin.UnsupportedOperationException" to "java.lang.UnsupportedOperationException",
            "kotlin.NullPointerException" to "java.lang.NullPointerException",
            "kotlin.NumberFormatException" to "java.lang.NumberFormatException",
            "kotlin.ArithmeticException" to "java.lang.ArithmeticException",
            "kotlin.ClassCastException" to "java.lang.ClassCastException",
            "kotlin.NoSuchElementException" to "java.util.NoSuchElementException",
        )
    }
}
