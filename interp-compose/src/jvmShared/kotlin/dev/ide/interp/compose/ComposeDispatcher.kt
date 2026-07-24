package dev.ide.interp.compose

import dev.ide.interp.ComposablePropertyValue
import dev.ide.interp.Dispatcher
import dev.ide.interp.ExtensionPropertyValue
import dev.ide.jvm.AsmPeerFactory
import dev.ide.interp.InterpretedLambda
import dev.ide.interp.InterpreterException
import dev.ide.interp.LambdaProxyStrategy
import dev.ide.interp.LibraryExecutor
import dev.ide.interp.OmittedArg
import dev.ide.interp.PreviewResourceResolver
import dev.ide.interp.ReflectiveDispatcher
import dev.ide.interp.SourceObject
import dev.ide.interp.reorderNamedArgs
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import java.lang.reflect.Proxy

/**
 * The Compose bridge (see `docs/compose-interpreter.md`, step 4): the interpreter [Dispatcher] that threads
 * a real `androidx.compose.runtime.Composer` into `@Composable` calls — re-implementing the compiler
 * plugin's composer-passing and caller-side group at interpretation time, against the real runtime — and
 * delegates everything else to a [ReflectiveDispatcher].
 *
 * [composer] is the ambient composer for the current composition pass; the host sets it before interpreting
 * a composable body (from `currentComposer`) and threads each library call through [ComposableAbi].
 *
 * A call is threaded as a composable when its callee is `@Composable` (decoded from metadata/bytecode/PSI,
 * milestone 6) — so a non-composable top-level call inside a composable (e.g. `mutableStateOf(…)`) is left
 * to plain reflection.
 *
 * The fallback [ReflectiveDispatcher] is given a [LambdaProxyStrategy] so that a `@Composable` content lambda
 * passed to a NON-composable callee — `LazyListScope.items(xs) { i -> Text(i) }`, where `items` just registers
 * items but `itemContent` is `@Composable` — still threads a Composer when the lazy layout later invokes it.
 */
class ComposeDispatcher(
    fallback: Dispatcher? = null,
    /** The project's library [ClassLoader] (device preview): a `DexClassLoader` over the project's dexed UI
     *  libraries whose parent is the IDE app loader (so `androidx.compose.runtime.*` and `android.*` resolve
     *  to the IDE's shared runtime, keeping the threaded composer type-compatible). Null → resolve library
     *  classes against the host loader chain (desktop, against Compose-for-Desktop). */
    private val loader: ClassLoader? = null,
    /** Resolves the previewed project's resources (interpreter-mediated path). When set, the androidx
     *  `stringResource`/… composables are short-circuited to it (the real ones read the IDE app's Resources, not
     *  the project's). Null on desktop/lessons. */
    private val resources: PreviewResourceResolver? = null,
    /** Executes library classes only the project's jars carry (the bytecode VM), replacing the DexClassLoader
     *  route on device. Null → unloadable classes keep the honest "cannot load" boundary. */
    private val libraryExecutor: LibraryExecutor? = null,
) : Dispatcher {

    private val suspendBridge = ComposeSuspendBridge()

    private val fallback: Dispatcher = fallback ?: ReflectiveDispatcher(
        loader = loader ?: ReflectiveDispatcher::class.java.classLoader,
        lambdaProxies = LambdaProxyStrategy { lambda, fi, composable ->
            // A composable function-type param threads the Composer. A PLAIN param gets the guarded proxy:
            // real framework code invokes these outside the renderer's guarded composition pass (a
            // `graphicsLayer` block during measure/semantics, an `onClick` from input dispatch), where a
            // propagated InterpreterException would crash the whole app instead of failing the preview.
            if (composable) composableLambdaProxy(lambda, fi) else guardedLambdaProxy(lambda, fi)
        },
        // Run interpreted `suspend` blocks (a `LaunchedEffect`/`launch` body) as real, cancellable coroutines
        // off the caller thread, so `delay`-driven timers actually tick instead of busy-looping the UI thread.
        suspendBridge = suspendBridge,
        libraryFallback = libraryExecutor,
        // Realize an interpreted `object : SomeClass()` as a real subclass when it crosses into library code —
        // reusing the library executor's peer factory (so the dexing path holds on device), else ASM on desktop.
        classProxies = PeerClassProxyFactory((libraryExecutor as? VmLibraryExecutor)?.peerFactory ?: AsmPeerFactory()),
    )

    /** The VM behind [libraryExecutor], when it is the bytecode executor: library COMPOSABLES whose bytes it
     *  holds are interpreted with the composer threaded, instead of reflectively invoked. Its lambda proxies
     *  report failures into [contentLambdaError] too — same crash-to-chip degradation as [guardedLambdaProxy]. */
    private val vmComposables: VmLibraryExecutor? = (libraryExecutor as? VmLibraryExecutor)?.also { executor ->
        executor.lambdaErrorSink = { t -> contentLambdaError = contentLambdaError ?: t }
    }

    /** The live composer for the current composition pass; null outside a composition. */
    @Volatile
    var composer: Any? = null

    /**
     * The first error swallowed while interpreting a `@Composable` content lambda this composition (see
     * [composableLambdaProxy]), or null. Lets the host surface a "preview is partial / has errors" hint after a
     * composition settles — the throw itself can't reach the renderer's error boundary because lazy content
     * (LazyColumn items, Scaffold body) composes during Compose's measure pass, outside `Render`'s try/catch.
     * The host should read it after composing and reset it before the next pass.
     */
    @Volatile
    var contentLambdaError: Throwable? = null

    /** Set while an interpreted NON-composable lambda (a [guardedLambdaProxy] body — a `LazyListScope.() ->
     *  Unit` list builder, a `Modifier.drawBehind`, an `onClick`) is running, so a `@Composable` call made
     *  ILLEGALLY inside it is skipped instead of composed. Such a builder runs at MEASURE time, after the
     *  composition pass, when [composer] still holds the LAST (completed) composition's composer — threading a
     *  composable there composes into that finished composition and triggers endless recomposition (the
     *  reported `LazyColumn { Text(…) }` freeze that hangs the IDE; a composable belongs in an `item { }`).
     *  A composable content lambda ([composableLambdaProxy]) clears it, so `LazyColumn { items { Text } }`
     *  still composes normally. Thread-local + save/restore: the proxies nest and also run on the bridge thread. */
    private val composablesSuppressed = ThreadLocal.withInitial { false }

    override fun dispatch(call: RNode.Call, receiver: Any?, args: List<Any?>): Any? {
        val c = composer
        val callee = call.callee
        // Interpreter-mediated project resources: short-circuit the androidx `stringResource`/… composables to
        // the injected resolver, since the real ones read the IDE app's `Resources`, not the previewed project's.
        // The resolver is fixed for a render, so consistently skipping these composables' groups keeps sibling
        // slot positions stable across recompositions.
        if (resources != null && callee is ResolvedCallable.Library) {
            val res = resolveResourceCall(callee, args)
            if (res !== NOT_A_RESOURCE) return res
        }
        // Navigation-Compose preview interception (see [handleNavCall]): `NavHost`/`composable<T>`/
        // `rememberNavController` can't run against the real androidx.navigation runtime headlessly, so a static
        // preview renders the START destination's content directly, keyed by the route type argument.
        if (callee is ResolvedCallable.Library) {
            val nav = handleNavCall(call, callee, c, receiver, args)
            if (nav !== NOT_NAV) return nav
        }
        // kotlinx.serialization's reified `serializer<T>()` / `T.serializer()` (see [resolveSerializerCall]): a
        // reified-inline library function reflection can't reach — resolve it from the type argument instead.
        if (callee is ResolvedCallable.Library) {
            val ser = resolveSerializerCall(call, callee, receiver)
            if (ser !== NOT_SERIALIZER) return ser
        }
        // `rememberSaveable { init }` — the real one requires a SaveableStateRegistry from a SavedStateRegistryOwner,
        // which the in-app preview composition has none of, so reflecting it crashes. Model it as a plain remember:
        // run `init` and return its value (the Saver + persistence are irrelevant to a static preview). The value
        // is recomputed per pass rather than restored across recompositions — an acceptable degradation over a crash.
        if (callee is ResolvedCallable.Library && callee.methodName == "rememberSaveable" &&
            callee.ownerFqn?.startsWith("androidx.compose.runtime.saveable") == true
        ) {
            (args.lastOrNull { it is InterpretedLambda } as? InterpretedLambda)?.let { return it.invoke(emptyList()) }
        }
        // Windowed composables (`Popup`/`Dialog`, and the Material components built on them — `DropdownMenu`,
        // `AlertDialog`, `ModalBottomSheet`, …) open a REAL OS window (WindowManager/Dialog) anchored to the
        // composable. The in-app preview has no host window to give them, so on device `DropdownMenu(expanded =
        // true)` hangs the preview (window churn every recomposition) and headless it throws. Neither `Popup`
        // nor Material honors `LocalInspectionMode`, so render the component's CONTENT INLINE here instead — the
        // menu/dialog body composes in place (real Material items, just no window), which is what a static
        // preview should show. Strictly additive: any uncertainty (unknown composable, no content lambda) falls
        // through to normal dispatch, so this only fixes the intercepted cases and never regresses others.
        if (c != null && callee is ResolvedCallable.Library) {
            val inlined = renderWindowedInline(call, callee, c, args)
            if (inlined !== NOT_WINDOWED) return inlined
            val dialog = renderMultiSlotDialogInline(call, callee, c, args)
            if (dialog !== NOT_WINDOWED) return dialog
        }
        // A composable can be called top-level (`Text(…)`), as a member of an object/companion
        // (`CardDefaults.cardColors(…)`), or as an EXTENSION on a scope (`RowScope.NavigationBarItem(…)` inside a
        // `NavigationBar { }`). All transform to a `Composer`-taking method: top-level/extension are static on a
        // `…Kt` facade (an extension takes its receiver as the first param), a member is an instance method on the
        // receiver. [invokeComposable] handles the receiver placement per kind.
        val threadable = call.dispatch == DispatchKind.TOP_LEVEL || call.dispatch == DispatchKind.MEMBER ||
            call.dispatch == DispatchKind.EXTENSION
        if (c != null && callee is ResolvedCallable.Library && threadable && callee.ownerFqn != null) {
            // Decide composer-threading by the RUNTIME class — `isComposableCall` reflects the actual method
            // we're about to invoke (a transformed composable has a `Composer` parameter). This is the ground
            // truth, independent of the resolver's decoded `@Composable` flag (which is resolved against the
            // project's classpath and can disagree with the runtime, or be stale). The decoded flag is only a
            // fast-path hint.
            if (callee.isComposable || ComposableAbi.isComposableCall(callee.ownerFqn!!, callee.methodName, loader) ||
                vmComposables?.isComposableCallable(callee.ownerFqn!!, callee.methodName) == true
            ) {
                // A composable called directly inside a NON-composable lambda (`LazyColumn { Text(…) }`, a
                // composable in a list builder instead of an `item { }`) is illegal — composing it here threads
                // the stale composer and hangs the IDE with endless recomposition. Skip it (it returns Unit).
                if (composablesSuppressed.get()) return null
                return invokeComposable(call, callee, c, receiver, args)
            }
            // Treated as non-composable → plain dispatch. If that fails (e.g. `no static Text(1)`, which is
            // exactly what a transformed composable looks like to a plain lookup), append a diagnostic so the
            // preview's error view explains WHY the composer path was skipped.
            return try {
                fallback.dispatch(call, receiver, args)
            } catch (e: InterpreterException) {
                throw InterpreterException((e.message ?: "dispatch failed") + ComposableAbi.diagnose(callee.ownerFqn!!, callee.methodName, loader))
            }
        }
        return fallback.dispatch(call, receiver, args)
    }

    /**
     * A plain functional-interface proxy over an interpreted lambda whose failures are RECORDED instead of
     * thrown. Real framework code invokes these long after dispatch, outside the renderer's error boundary —
     * a `graphicsLayer` block runs during measure/semantics on the UI thread, an `onClick` from input
     * dispatch — so a propagated interpreter failure there is an app crash (and an ANR), not a preview error.
     * The first failure lands in [contentLambdaError] (the partial-render channel the host already surfaces)
     * and the call degrades to the return type's zero value. Suspend invocations (a trailing Continuation)
     * route through the coroutine bridge, exactly like the unguarded default proxy.
     */
    private fun guardedLambdaProxy(lambda: InterpretedLambda, functionalInterface: Class<*>): Any =
        Proxy.newProxyInstance(functionalInterface.classLoader ?: javaClass.classLoader, arrayOf(functionalInterface)) { _, method, callArgs ->
            when (method.name) {
                "invoke" -> {
                    val a = callArgs?.toList() ?: emptyList()
                    if (a.lastOrNull() is kotlin.coroutines.Continuation<*>) suspendBridge.runSuspending(lambda, a)
                    else {
                        // Inside a non-composable lambda, a stray `@Composable` call is illegal — suppress it
                        // (see [composablesSuppressed]) so it can't compose into the stale composer and hang.
                        val prevSuppressed = composablesSuppressed.get()
                        composablesSuppressed.set(true)
                        try {
                            lambda.invoke(a)
                        } catch (t: Throwable) {
                            contentLambdaError = contentLambdaError ?: t
                            zeroReturn(method.returnType)
                        } finally {
                            composablesSuppressed.set(prevSuppressed)
                        }
                    }
                }
                "toString" -> "InterpretedLambda"
                "hashCode" -> System.identityHashCode(lambda)
                "equals" -> callArgs?.getOrNull(0) === lambda
                else -> null
            }
        }

    /** The degraded result of a guarded lambda that failed: the zero value of the SAM's return type, so a
     *  primitive-returning caller doesn't die unboxing null on top of the recorded failure. */
    private fun zeroReturn(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Character.TYPE -> ' '
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    /** Thread the live composer through a `@Composable` property getter (`MaterialTheme.colorScheme`,
     *  `MaterialTheme.typography`, …). Returns null when there's no composer (outside a composition) or the
     *  property isn't a composable getter — the interpreter then reads it plainly. */
    override fun readComposableProperty(receiver: Any, propertyName: String): ComposablePropertyValue? {
        val c = composer ?: return null
        // An instance the VM executor owns: its composable getter exists only in the interpreted world (the
        // peer class reflection sees carries no library methods).
        if (vmComposables?.ownsInstance(receiver) == true) {
            return vmComposables.readComposableProperty(receiver, propertyName, c)?.let { ComposablePropertyValue(it.value) }
        }
        val result = ComposableAbi.readComposableProperty(receiver, propertyName, c)
        return if (result === ComposableAbi.NotComposableProperty) null else ComposablePropertyValue(result)
    }

    /**
     * `androidx.lifecycle` `viewModelScope` on an interpreted `ViewModel` subclass. The real getter lazily
     * builds a scope on `Dispatchers.Main.immediate`, which a headless / non-Android preview has no main
     * dispatcher for (it throws `IllegalStateException`). A static `@Preview` only CONSTRUCTS the ViewModel
     * (e.g. `remember { OrderViewModel() }`) — it never drives the scope's flows — so hand it a plain,
     * Main-free scope: construction and any `…​.stateIn(scope = viewModelScope, …)` property initializer then
     * complete without crashing. One scope per dispatcher (per render); the preview never launches on it.
     */
    override fun readExtensionPropertyOverride(receiver: Any, ownerFqn: String, name: String): ExtensionPropertyValue? {
        if (name == "viewModelScope" && ownerFqn.startsWith("androidx.lifecycle")) {
            return ExtensionPropertyValue(previewViewModelScope)
        }
        return null
    }

    private val previewViewModelScope: Any by lazy {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
    }

    /** Drive the caller-side group the plugin would emit (keyed by call site), then invoke the composable with
     *  the threaded composer — so it sits in a stable slot across recompositions. */
    private fun invokeComposable(call: RNode.Call, callee: ResolvedCallable.Library, composer: Any, receiver: Any?, args: List<Any?>): Any? {
        // Capture the composer position BEFORE opening the call-site group. If the composable throws while it
        // has a node/group open (e.g. a library composable like `Image` whose reflective invocation fails
        // mid-emission), unwinding to this marker closes those dangling opens — so the ENCLOSING composition
        // (the IDE's own UI around the preview) isn't corrupted, which otherwise surfaces on the host's next
        // `endNode` as "Cannot end node insertion, there are no pending operations". On the normal path we close
        // exactly the group we opened.
        val marker = ComposableAbi.currentMarker(composer)
        ComposableAbi.startGroup(composer, call.callSiteKey.value)
        var completed = false
        try {
            // Bind named arguments back to their declared positions before the ABI binds them to JVM slots
            // (`Text(text = …, modifier = …, textAlign = …)`); interior omissions become `OmittedArg` holes the
            // ABI fills from `$default`. A purely positional call is returned unchanged.
            val ordered = reorderNamedArgs(callee.paramNames, call.args, args)
            // An EXTENSION composable's transformed JVM method is STATIC with the receiver (the scope) as its
            // first parameter, so prepend it to the args and invoke statically; receiverCount=1 keeps the
            // `$default` mask numbered over value params only. A MEMBER composable invokes on the receiver
            // instance; a top-level one has no receiver.
            val isExtension = call.dispatch == DispatchKind.EXTENSION
            // A NON-last vararg (`CompositionLocalProvider(vararg values, content)`) compiles to a plain array
            // parameter the compiler packs at the call site; fold the loose args into that typed array so the ABI
            // binds them as ONE parameter. Best-effort: null (couldn't pack — an unloadable element type, named/
            // omitted binding) falls through to the normal positional bind, exactly as before.
            val packed = if (!isExtension) packVararg(callee, ordered) else null
            val bound = packed ?: ordered
            // `reorderNamedArgs` returns the SAME list reference when it didn't reorder (purely positional, or
            // names unknown); a new list — or a vararg pack — means the args are now in declaration order and bind
            // positionally. The trailing-lambda remap then applies only to a SYNTACTIC trailing lambda (`{ }`
            // outside the parens), NOT an in-parens lambda argument like `onCheckedChange = { … }` — which would
            // otherwise land on the callee's last parameter (`Switch`'s `interactionSource`) and corrupt the
            // composition. A packed vararg already placed the content lambda, so no remap.
            val argsInDeclarationOrder = packed != null || ordered !== args
            val lastArgIsTrailingLambda = packed == null && call.args.lastOrNull()?.trailingLambda == true
            val effectiveArgs = if (isExtension) listOf(receiver) + bound else bound
            // The resolver knows the full value-parameter count; omitted args (defaults) are filled by the ABI.
            // A composable whose bytes the VM executor holds (a project-jar library) runs INTERPRETED with the
            // composer threaded; anything host-loadable keeps the reflective ABI against the bundled runtime.
            val result = if (vmComposables != null && vmComposables.hasClass(callee.ownerFqn!!)) {
                vmComposables.callComposable(
                    callee.ownerFqn!!, callee.methodName, effectiveArgs, composer,
                    declaredParamCount = callee.paramTypes.size + if (isExtension) 1 else 0,
                    lambdaProxy = ::composableLambdaProxy,
                    receiver = if (isExtension) null else receiver,
                    receiverCount = if (isExtension) 1 else 0,
                    argsInDeclarationOrder = argsInDeclarationOrder,
                    lastArgIsTrailingLambda = lastArgIsTrailingLambda,
                )
            } else ComposableAbi.call(
                callee.ownerFqn!!, callee.methodName, effectiveArgs, composer,
                declaredParamCount = callee.paramTypes.size + if (isExtension) 1 else 0,
                lambdaProxy = ::composableLambdaProxy,
                loader = loader,
                receiver = if (isExtension) null else receiver,
                receiverCount = if (isExtension) 1 else 0,
                argsInDeclarationOrder = argsInDeclarationOrder,
                lastArgIsTrailingLambda = lastArgIsTrailingLambda,
            )
            completed = true
            return result
        } finally {
            // Normal completion: close exactly the call-site group. Failure: unwind to the pre-call marker so
            // a composable that died with a node/group still open can't corrupt the surrounding composition.
            if (completed) ComposableAbi.endGroup(composer)
            else runCatching { ComposableAbi.endToMarker(composer, marker) }
        }
    }

    /**
     * Pack a NON-last vararg's loose args into a typed array so [ComposableAbi] binds them as the single array
     * parameter the compiler would have packed at the call site (`CompositionLocalProvider(vararg values,
     * content)` → `CompositionLocalProvider(ProvidedValue[], Function2, …)`). Returns the declaration-ordered args
     * with the vararg slot packed, or null when it can't — no vararg, a named/defaulted (`OmittedArg`) binding, an
     * unloadable/array-typed element, or a lambda vararg element. On null the caller binds positionally as before
     * (so the change is inert for every non-vararg composable and never worse than the prior behavior).
     */
    private fun packVararg(callee: ResolvedCallable.Library, ordered: List<Any?>): List<Any?>? {
        val v = callee.varargParamIndex
        val nK = callee.paramTypes.size
        if (v < 0 || v >= nK) return null
        if (ordered.any { it === OmittedArg }) return null
        val fixedAfter = nK - 1 - v
        val varargCount = ordered.size - fixedAfter - v
        if (fixedAfter < 0 || varargCount < 0) return null
        val elementFqn = callee.paramTypes[v]?.qualifiedName ?: return null
        val elementClass = runCatching { Class.forName(elementFqn, false, loader ?: javaClass.classLoader) }.getOrNull() ?: return null
        val array = runCatching { java.lang.reflect.Array.newInstance(elementClass, varargCount) }.getOrNull() ?: return null
        for (i in 0 until varargCount) {
            val e = ordered[v + i]
            if (e is InterpretedLambda) return null // a lambda inside the vararg isn't a plain array element here
            runCatching { java.lang.reflect.Array.set(array, i, e) }.getOrElse { return null }
        }
        return ordered.subList(0, v) + array + ordered.subList(ordered.size - fixedAfter, ordered.size)
    }

    /**
     * Wrap a `@Composable` content lambda as a proxy of its transformed functional type (e.g.
     * `Function3<ColumnScope, Composer, Int, Unit>` for `@Composable ColumnScope.() -> Unit`). When the
     * library composable invokes it, the proxy threads the composer it was handed into the interpreter so the
     * lambda body's composables compose into the right group; non-composer leading args (a scope receiver)
     * are passed to the lambda, the trailing `Composer`/`$changed` are stripped.
     */
    private fun composableLambdaProxy(lambda: InterpretedLambda, functionalInterface: Class<*>): Any =
        Proxy.newProxyInstance(
            functionalInterface.classLoader ?: javaClass.classLoader, arrayOf(functionalInterface),
        ) { _, method, callArgs ->
            when (method.name) {
                "invoke" -> {
                    val a = callArgs?.toList() ?: emptyList()
                    val composerArg = a.firstOrNull { COMPOSER.isInstance(it) }
                    val real = a.takeWhile { !COMPOSER.isInstance(it) } // receiver/value params, before the composer
                    val prev = composer
                    if (composerArg != null) composer = composerArg
                    // Whether composable calls are legal inside THIS lambda is decided by the actual invocation:
                    // Compose passes a composer to a `@Composable` lambda (a `Column {}`/`items {}` content slot →
                    // composables allowed) but NOT to a non-composable one (a `LazyListScope.() -> Unit` list
                    // builder → a stray composable there is suppressed, so it can't compose against the stale
                    // composer and hang). Runtime-derived, so it's robust to how ComposableAbi bound the args.
                    val prevSuppressed = composablesSuppressed.get()
                    composablesSuppressed.set(composerArg == null)
                    try {
                        lambda.invoke(real)
                    } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                        throw ce // recomposition cancellation is control flow — never swallow it
                    } catch (e: Throwable) {
                        // A malformed/half-typed buffer can make an interpreted composable throw mid-composition
                        // (a wrong-typed arg, an unresolved call, an ABI mismatch, or a StackOverflow/OutOfMemory
                        // from runaway user code). This lambda runs during Compose's measure/subcompose pass
                        // (LazyColumn items, Scaffold content), OUTSIDE the preview renderer's try/catch — so
                        // letting anything propagate kills the host thread and takes down the whole IDE (the
                        // Compose preview runs in-process, with no process isolation). Contain ALL throwables incl.
                        // Error: the lambda returns normally (emitting whatever composed before the failure), the
                        // enclosing library composable balances its own groups, and the preview degrades to a
                        // partial render surfaced through [contentLambdaError] instead of crashing. The recursion
                        // and per-pass guards (interp-core Interpreter) trip before most StackOverflow/hang cases
                        // reach here; this is the backstop for anything they don't.
                        contentLambdaError = contentLambdaError ?: e
                        Unit
                    } finally {
                        composer = prev
                        composablesSuppressed.set(prevSuppressed)
                    }
                }
                "toString" -> "InterpretedComposableLambda"
                "hashCode" -> System.identityHashCode(lambda)
                "equals" -> callArgs?.getOrNull(0) === lambda
                else -> null
            }
        }

    /**
     * A windowed composable we render INLINE in the preview: its [method] on an owner in [ownerPrefix], whose
     * trailing `@Composable` content lambda takes the receiver named by [contentReceiverFqn] (an object's
     * `INSTANCE`, e.g. `ColumnScopeInstance` for a menu) or none.
     */
    private data class Windowed(val ownerPrefix: String, val method: String, val contentReceiverFqn: String?)

    /**
     * Multi-slot dialogs the preview renders inline. Unlike [windowed] these have SEVERAL `@Composable` content
     * slots (`AlertDialog`: `icon`/`title`/`text`/`confirmButton`/`dismissButton`) rather than one, so they're
     * rendered by composing every `@Composable`-typed lambda argument (identified by its parameter type) in
     * declaration order — the visible body of a static preview, without the real scrim/window.
     */
    private val multiSlotDialogPrefixes = listOf("androidx.compose.material3", "androidx.compose.material")
    private val multiSlotDialogMethods = setOf("AlertDialog", "BasicAlertDialog")

    /**
     * If [call] is a multi-slot dialog ([multiSlotDialogMethods]), compose each of its `@Composable` content-slot
     * lambdas INLINE under the call-site group and return `Unit`; otherwise [NOT_WINDOWED]. A slot is a lambda
     * argument whose parameter type is a `@Composable` function type (so `onDismissRequest`, a plain `() -> Unit`,
     * is correctly skipped). Balances the group on throw so a failing slot degrades to the preview error view.
     */
    private fun renderMultiSlotDialogInline(call: RNode.Call, callee: ResolvedCallable.Library, composer: Any, args: List<Any?>): Any? {
        val owner = callee.ownerFqn ?: return NOT_WINDOWED
        if (callee.methodName !in multiSlotDialogMethods || multiSlotDialogPrefixes.none { owner.startsWith(it) }) return NOT_WINDOWED
        val ordered = reorderNamedArgs(callee.paramNames, call.args, args)
        val slots = ordered.mapIndexedNotNull { i, a ->
            if (a is InterpretedLambda && callee.paramTypes.getOrNull(i)?.isComposable == true) a else null
        }
        if (slots.isEmpty()) return NOT_WINDOWED
        val marker = ComposableAbi.currentMarker(composer)
        ComposableAbi.startGroup(composer, call.callSiteKey.value)
        var completed = false
        try {
            // The slots (title/text/buttons) are a FIXED set, so composing them sequentially under the call-site
            // group is positionally stable across recomposition (no per-slot movable key needed). AlertDialog's
            // slots take no receiver; a receiver-typed slot would need its scope, which these don't have.
            slots.forEach { it.invoke(emptyList()) }
            completed = true
            return Unit
        } finally {
            if (completed) ComposableAbi.endGroup(composer) else runCatching { ComposableAbi.endToMarker(composer, marker) }
        }
    }

    /**
     * Windowed composables the preview renders inline instead of opening a real OS window. `Popup`/`Dialog` are
     * the primitives; `DropdownMenu` is the common Material component (its content is a `ColumnScope` lambda).
     * Multi-slot dialogs (`AlertDialog`) are handled by [renderMultiSlotDialogInline], not here.
     */
    private val windowed = listOf(
        Windowed("androidx.compose.material3", "DropdownMenu", "androidx.compose.foundation.layout.ColumnScopeInstance"),
        Windowed("androidx.compose.ui.window", "Popup", null),
        Windowed("androidx.compose.ui.window", "Dialog", null),
    )

    /**
     * If [call] is a windowed composable ([windowed]), compose its trailing content lambda INLINE under the
     * call-site group (so it shows in the preview without a real window) and return `Unit`; otherwise return
     * [NOT_WINDOWED] so the caller dispatches normally. The content is the last interpreted lambda in
     * declaration order (a menu's `onDismissRequest` lambda precedes it). Balances its group on throw so a
     * failure surfaces as the preview's error view rather than corrupting the surrounding composition.
     */
    private fun renderWindowedInline(call: RNode.Call, callee: ResolvedCallable.Library, composer: Any, args: List<Any?>): Any? {
        val owner = callee.ownerFqn ?: return NOT_WINDOWED
        val spec = windowed.firstOrNull { it.method == callee.methodName && owner.startsWith(it.ownerPrefix) } ?: return NOT_WINDOWED
        val ordered = reorderNamedArgs(callee.paramNames, call.args, args)
        val content = ordered.lastOrNull { it is InterpretedLambda } as? InterpretedLambda ?: return NOT_WINDOWED
        // The content lambda's receiver (a menu's ColumnScope), best-effort: absent → invoke with none (a body
        // that doesn't use the receiver still composes).
        val receiver = spec.contentReceiverFqn?.let { fqn ->
            runCatching { Class.forName(fqn, false, loader ?: javaClass.classLoader).getField("INSTANCE").get(null) }.getOrNull()
                ?: libraryExecutor?.takeIf { it.hasClass(fqn) }?.objectInstance(fqn)
        }
        val marker = ComposableAbi.currentMarker(composer)
        ComposableAbi.startGroup(composer, call.callSiteKey.value)
        var completed = false
        try {
            content.invoke(listOfNotNull(receiver))
            completed = true
            return Unit
        } finally {
            if (completed) ComposableAbi.endGroup(composer)
            else runCatching { ComposableAbi.endToMarker(composer, marker) }
        }
    }

    /**
     * Resolve a `androidx.compose.ui.res.*` resource composable against [resources], or [NOT_A_RESOURCE] when the
     * call isn't one we mediate / the resolver has no value (so the caller proceeds normally). Covers the String
     * family (`stringResource` with optional `vararg` format args, `stringArrayResource`, `pluralStringResource`;
     * a missing string degrades to empty) and the value-typed family (`colorResource`/`dimensionResource`/
     * `painterResource`, whose Compose values the resolver pre-builds — interp-compose can't name `Color`/`Dp`/
     * `Painter`). A pre-built value class flows into a downstream param through the dispatcher's value-class
     * (un)boxing. Color/dimension/painter fall through only when the resolver returns null (an unknown resource).
     */
    private fun resolveResourceCall(callee: ResolvedCallable.Library, args: List<Any?>): Any? {
        val r = resources ?: return NOT_A_RESOURCE
        val owner = callee.ownerFqn ?: return NOT_A_RESOURCE
        if (!owner.startsWith("androidx.compose.ui.res.")) return NOT_A_RESOURCE
        val id = (args.firstOrNull() as? Number)?.toInt() ?: return NOT_A_RESOURCE
        return when (callee.methodName) {
            "stringResource" -> formatIfArgs(r.string(id) ?: return "", args.drop(1))
            "stringArrayResource" -> (r.stringArray(id) ?: emptyList()).toTypedArray()
            "pluralStringResource" -> {
                val count = (args.getOrNull(1) as? Number)?.toInt() ?: 0
                formatIfArgs(r.plural(id, count) ?: return "", args.drop(2))
            }
            "colorResource" -> r.color(id) ?: NOT_A_RESOURCE
            "dimensionResource" -> r.dimension(id) ?: NOT_A_RESOURCE
            "painterResource" -> r.painter(id) ?: NOT_A_RESOURCE
            else -> NOT_A_RESOURCE
        }
    }

    /** Apply `String.format` when a `stringResource`/`pluralStringResource` was passed `vararg formatArgs`
     *  (a passed `Object[]` is flattened); the raw string is returned unchanged when there are none. */
    private fun formatIfArgs(s: String, extra: List<Any?>): String {
        val fmtArgs = extra.flatMap { if (it is Array<*>) it.toList() else listOf(it) }
        return if (fmtArgs.isEmpty()) s else runCatching { String.format(s, *fmtArgs.toTypedArray()) }.getOrDefault(s)
    }

    // --- Navigation-Compose preview interception -----------------------------------------------------
    // androidx.navigation's `NavHost` needs a live `NavController` + back-stack + Android `Looper`, none of which
    // exist in the in-app preview — reflecting it hangs (device) or throws (headless). So DON'T reflect it: a
    // static `@Preview` of a NavHost should show its START destination, so intercept the three nav entry points
    // and compose that destination's content lambda directly, keyed by the route TYPE the call-site type
    // argument carries (`composable<Home> { }` → key `Home`). No serializers, no real graph — the visible result.

    /** A NavHost builder's collected `composable<T> { content }` registrations: route type FQN → content lambda. */
    private class NavGraphCollector { val destinations = LinkedHashMap<String, InterpretedLambda>() }

    /** The active collector while a NavHost builder lambda runs, so a `composable<T>` inside it registers here. */
    @Volatile private var navCollector: NavGraphCollector? = null

    /**
     * Intercept a Navigation-Compose call, or return [NOT_NAV] to dispatch it normally. `rememberNavController`
     * yields an opaque placeholder (only [renderNavHost] reads a NavHost's args, and it ignores the controller);
     * `composable<T>` registers on the active collector; `NavHost` runs the builder then composes the start
     * destination.
     */
    private fun handleNavCall(call: RNode.Call, callee: ResolvedCallable.Library, composer: Any?, receiver: Any?, args: List<Any?>): Any? {
        val owner = callee.ownerFqn ?: return NOT_NAV
        if (!owner.startsWith("androidx.navigation")) return NOT_NAV
        return when (callee.methodName) {
            "rememberNavController" -> NAV_CONTROLLER_STUB
            "composable" -> registerNavDestination(call, args)
            "NavHost" -> if (composer != null) renderNavHost(call, callee, composer, args) else NOT_NAV
            else -> NOT_NAV
        }
    }

    /** Register a `composable<T> { content }` on the active collector, keyed by T's FQN (from the call-site type
     *  argument). [NOT_NAV] when it isn't inside a NavHost builder (no active collector) or carries no type arg. */
    private fun registerNavDestination(call: RNode.Call, args: List<Any?>): Any? {
        val collector = navCollector ?: return NOT_NAV
        val routeFqn = call.typeArguments.firstOrNull()?.fqn ?: return NOT_NAV
        val content = args.lastOrNull { it is InterpretedLambda } as? InterpretedLambda ?: return NOT_NAV
        collector.destinations[routeFqn] = content
        return Unit
    }

    /** Run a NavHost's builder to collect its destinations, then compose the start destination's content lambda
     *  inline under the call-site group (so the preview shows the start screen). [NOT_NAV] if it isn't a NavHost. */
    private fun renderNavHost(call: RNode.Call, callee: ResolvedCallable.Library, composer: Any, args: List<Any?>): Any? {
        val builder = args.lastOrNull { it is InterpretedLambda } as? InterpretedLambda ?: return NOT_NAV
        val ordered = reorderNamedArgs(callee.paramNames, call.args, args)
        val startIdx = callee.paramNames.indexOf("startDestination")
        val start = if (startIdx >= 0) ordered.getOrNull(startIdx) else args.getOrNull(1)
        val collector = NavGraphCollector()
        val prev = navCollector
        navCollector = collector
        try { builder.invoke(listOf(collector)) } finally { navCollector = prev }
        if (collector.destinations.isEmpty()) return Unit
        val content = matchDestination(start, collector) ?: collector.destinations.values.first()
        val marker = ComposableAbi.currentMarker(composer)
        ComposableAbi.startGroup(composer, call.callSiteKey.value)
        var completed = false
        try {
            content.invoke(List(content.paramCount) { null })
            completed = true
            return Unit
        } finally {
            if (completed) ComposableAbi.endGroup(composer) else runCatching { ComposableAbi.endToMarker(composer, marker) }
        }
    }

    /** The content lambda for [start]'s route, matched against [collector] by route FQN then simple name. */
    private fun matchDestination(start: Any?, collector: NavGraphCollector): InterpretedLambda? {
        val fqn = routeFqnOf(start) ?: return null
        collector.destinations[fqn]?.let { return it }
        val simple = fqn.substringAfterLast('.')
        return collector.destinations.entries.firstOrNull { it.key.substringAfterLast('.') == simple }?.value
    }

    /** The route type FQN of a `startDestination` value — a `@Serializable` object/instance is a [SourceObject]
     *  (its class FQN), a legacy string route is itself, a library route its JVM class. */
    private fun routeFqnOf(value: Any?): String? = when (value) {
        is SourceObject -> value.cls.fqn
        is String -> value
        null -> null
        else -> value.javaClass.name
    }

    // --- kotlinx.serialization `serializer<T>()` -----------------------------------------------------

    /**
     * Resolve a reified `kotlinx.serialization.serializer<T>()` (a reified-inline function reflection can't
     * reach) to a real `KSerializer` for a COMPILED `@Serializable` type, using the call-site type argument to
     * name `T`. Returns [NOT_SERIALIZER] when it isn't a serialization `serializer` call, and `null` (a graceful
     * degrade, no crash) when a serializer can't be obtained — e.g. a project-SOURCE `@Serializable` type, whose
     * `$serializer` is generated at compile time and doesn't exist during preview.
     */
    private fun resolveSerializerCall(call: RNode.Call, callee: ResolvedCallable.Library, receiver: Any?): Any? {
        if (callee.methodName != "serializer") return NOT_SERIALIZER
        if (callee.ownerFqn?.startsWith("kotlinx.serialization") != true) return NOT_SERIALIZER
        val cls = call.typeArguments.firstOrNull()?.loadCandidates?.firstNotNullOfOrNull { loadClass(it) }
            ?: return null
        return runCatching { reflectSerializer(cls) }.getOrNull()
    }

    /** A `KSerializer` for [cls], or null when one can't be obtained (an uncompiled/project-source `@Serializable`
     *  type has no serializer during preview). Tries, in order: the compiled class's generated `$serializer`
     *  singleton (the reliable path for a `@Serializable` type on the classpath), then a static `serializer(KClass)`
     *  off `kotlinx.serialization.SerializersKt` if the runtime exposes one (built-in/contextual types). */
    private fun reflectSerializer(cls: Class<*>): Any? {
        loadClass(cls.name + "\$serializer")?.let { ser ->
            runCatching { ser.getField("INSTANCE").get(null) }.getOrNull()?.let { return it }
        }
        val serializersKt = loadClass("kotlinx.serialization.SerializersKt") ?: return null
        val m = serializersKt.methods.firstOrNull {
            it.name == "serializer" && it.parameterCount == 1 && it.parameterTypes[0] == kotlin.reflect.KClass::class.java
        } ?: return null
        return runCatching { m.invoke(null, cls.kotlin) }.getOrNull()
    }

    private fun loadClass(fqn: String): Class<*>? =
        runCatching { Class.forName(fqn, false, loader ?: javaClass.classLoader) }.getOrNull()

    private companion object {
        val COMPOSER: Class<*> = Class.forName("androidx.compose.runtime.Composer")

        /** Placeholder for `rememberNavController()` — the preview NavHost interceptor ignores the controller. */
        val NAV_CONTROLLER_STUB = Any()

        /** Sentinel: [handleNavCall] returns this when the call isn't an intercepted nav call. */
        val NOT_NAV = Any()

        /** Sentinel: [resolveSerializerCall] returns this when the call isn't a serialization `serializer`. */
        val NOT_SERIALIZER = Any()

        /** Sentinel: [resolveResourceCall] returns this when the call isn't a mediated resource function (a real
         *  resolved value is never null, so `!== NOT_A_RESOURCE` cleanly distinguishes the two). */
        val NOT_A_RESOURCE = Any()

        /** Sentinel: [renderWindowedInline] returns this when the call isn't an inlined windowed composable, so
         *  the caller dispatches it normally. */
        val NOT_WINDOWED = Any()
    }
}
