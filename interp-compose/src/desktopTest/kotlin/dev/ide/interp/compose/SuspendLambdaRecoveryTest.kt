package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Regression for the reported preview crash: touching a preview whose `Modifier.pointerInput { … }` block runs
 * a gesture detector (`detectDragGestures`/`awaitEachGesture`) NPE'd on the host thread. That block is a SUSPEND
 * lambda, and the synchronous interpreter can't drive the suspend gesture calls inside it — they throw, and
 * uncaught inside Compose's pointer-input coroutine that crashed the IDE. A suspend lambda is now best-effort:
 * its proxy SWALLOWS a failure so the gesture/effect just doesn't run instead of taking down the host.
 */
class SuspendLambdaRecoveryTest {
    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.compose.SuspendLambdaRecoveryTestKt"

    @Test
    fun aThrowingSuspendLambdaDoesNotCrashTheHost() {
        // `runSuspendBlock { <boom> }` — the suspend block's body throws (a call to an unloadable facade, standing
        // in for a `detectDragGestures`/`delay` the interpreter can't drive). Reaching the end of `call` without
        // an exception escaping is the assertion: the proxy swallowed it rather than crashing the coroutine.
        val boom = RNode.Call(
            ResolvedCallable.Library("nope", "no.such.FacadeKt", "nope", emptyList(), isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.TOP_LEVEL, receiver = null, args = emptyList(), callSiteKey = CallSiteKey(1), source = span,
        )
        val block = RNode.Lambda(emptyList(), RNode.Block(listOf(boom), isExpression = false, span), emptyList(), span)
        val call = RNode.Call(
            ResolvedCallable.Library("runSuspendBlock", facade, "runSuspendBlock", listOf(null), isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(block)), callSiteKey = CallSiteKey(2), source = span,
        )
        val root = ResolvedFunction("Root", emptyList(), RNode.Block(listOf(call), isExpression = false, span), emptyList(), returnsUnit = true)

        val interpreter = Interpreter(functions = emptyMap(), dispatcher = ComposeDispatcher())
        interpreter.call(root, emptyList()) // must not throw
    }
}

/** A fake entry point that runs a SUSPEND block — like `pointerInput`/`LaunchedEffect` invoking their block in a
 *  coroutine. The interpreter proxies the interpreted block as this `suspend () -> Unit`. */
fun runSuspendBlock(block: suspend () -> Unit) {
    runBlocking { block() }
}
