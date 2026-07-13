package dev.ide.interp.compose

import dev.ide.interp.ComposablePropertyValue
import dev.ide.interp.Dispatcher
import dev.ide.interp.InterpretedLambda
import dev.ide.interp.InterpreterException
import dev.ide.interp.LambdaProxyStrategy
import dev.ide.interp.OmittedArg
import dev.ide.interp.PreviewResourceResolver
import dev.ide.interp.ReflectiveDispatcher
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
) : Dispatcher {

    private val fallback: Dispatcher = fallback ?: ReflectiveDispatcher(
        loader = loader ?: ReflectiveDispatcher::class.java.classLoader,
        lambdaProxies = LambdaProxyStrategy { lambda, fi, composable ->
            // A composable function-type param: thread the Composer; otherwise let the default proxy run.
            if (composable) composableLambdaProxy(lambda, fi) else null
        },
        // Run interpreted `suspend` blocks (a `LaunchedEffect`/`launch` body) as real, cancellable coroutines
        // off the caller thread, so `delay`-driven timers actually tick instead of busy-looping the UI thread.
        suspendBridge = ComposeSuspendBridge(),
    )

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
            if (callee.isComposable || ComposableAbi.isComposableCall(callee.ownerFqn!!, callee.methodName, loader)) {
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

    /** Thread the live composer through a `@Composable` property getter (`MaterialTheme.colorScheme`,
     *  `MaterialTheme.typography`, …). Returns null when there's no composer (outside a composition) or the
     *  property isn't a composable getter — the interpreter then reads it plainly. */
    override fun readComposableProperty(receiver: Any, propertyName: String): ComposablePropertyValue? {
        val c = composer ?: return null
        val result = ComposableAbi.readComposableProperty(receiver, propertyName, c)
        return if (result === ComposableAbi.NotComposableProperty) null else ComposablePropertyValue(result)
    }

    /** Drive the caller-side group the plugin would emit (keyed by call site), then invoke the composable with
     *  the threaded composer — so it sits in a stable slot across recompositions. */
    private fun invokeComposable(call: RNode.Call, callee: ResolvedCallable.Library, composer: Any, receiver: Any?, args: List<Any?>): Any? {
        ComposableAbi.startGroup(composer, call.callSiteKey.value)
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
            return ComposableAbi.call(
                callee.ownerFqn!!, callee.methodName, effectiveArgs, composer,
                declaredParamCount = callee.paramTypes.size + if (isExtension) 1 else 0,
                lambdaProxy = ::composableLambdaProxy,
                loader = loader,
                receiver = if (isExtension) null else receiver,
                receiverCount = if (isExtension) 1 else 0,
                argsInDeclarationOrder = argsInDeclarationOrder,
                lastArgIsTrailingLambda = lastArgIsTrailingLambda,
            )
        } finally {
            ComposableAbi.endGroup(composer)
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
                    }
                }
                "toString" -> "InterpretedComposableLambda"
                "hashCode" -> System.identityHashCode(lambda)
                "equals" -> callArgs?.getOrNull(0) === lambda
                else -> null
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

    private companion object {
        val COMPOSER: Class<*> = Class.forName("androidx.compose.runtime.Composer")

        /** Sentinel: [resolveResourceCall] returns this when the call isn't a mediated resource function (a real
         *  resolved value is never null, so `!== NOT_A_RESOURCE` cleanly distinguishes the two). */
        val NOT_A_RESOURCE = Any()
    }
}
