package dev.ide.interp.compose

import dev.ide.interp.ComposablePropertyValue
import dev.ide.interp.Dispatcher
import dev.ide.interp.InterpretedLambda
import dev.ide.interp.InterpreterException
import dev.ide.interp.LambdaProxyStrategy
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
) : Dispatcher {

    private val fallback: Dispatcher = fallback ?: ReflectiveDispatcher(
        loader = loader ?: ReflectiveDispatcher::class.java.classLoader,
        lambdaProxies = LambdaProxyStrategy { lambda, fi, composable ->
            // A composable function-type param: thread the Composer; otherwise let the default proxy run.
            if (composable) composableLambdaProxy(lambda, fi) else null
        },
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
            // `reorderNamedArgs` returns the SAME list reference when it didn't reorder (purely positional, or
            // names unknown); a new list means the args are now in declaration order and bind positionally. The
            // trailing-lambda remap then applies only to a SYNTACTIC trailing lambda (`{ }` outside the parens),
            // NOT an in-parens lambda argument like `onCheckedChange = { … }` — which would otherwise land on
            // the callee's last parameter (`Switch`'s `interactionSource`) and corrupt the composition.
            val argsInDeclarationOrder = ordered !== args
            val lastArgIsTrailingLambda = call.args.lastOrNull()?.trailingLambda == true
            // An EXTENSION composable's transformed JVM method is STATIC with the receiver (the scope) as its
            // first parameter, so prepend it to the args and invoke statically; receiverCount=1 keeps the
            // `$default` mask numbered over value params only. A MEMBER composable invokes on the receiver
            // instance; a top-level one has no receiver.
            val isExtension = call.dispatch == DispatchKind.EXTENSION
            val effectiveArgs = if (isExtension) listOf(receiver) + ordered else ordered
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
                    } catch (e: Exception) {
                        // A malformed/half-typed buffer can make an interpreted composable throw mid-composition
                        // (a wrong-typed arg, an unresolved call, an ABI mismatch). This lambda runs during
                        // Compose's measure/subcompose pass (LazyColumn items, Scaffold content), OUTSIDE the
                        // preview renderer's try/catch — so letting it propagate kills the host thread. Swallow
                        // it: the lambda returns normally (emitting whatever composed before the failure), the
                        // enclosing library composable balances its own groups, and the preview degrades to a
                        // partial render instead of crashing. The innermost lambda catches first, so no enclosing
                        // composable frame ever sees the throw. (A `VirtualMachineError`/`StackOverflowError`
                        // still propagates — those aren't recoverable mid-composition.)
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

    private companion object {
        val COMPOSER: Class<*> = Class.forName("androidx.compose.runtime.Composer")
    }
}
