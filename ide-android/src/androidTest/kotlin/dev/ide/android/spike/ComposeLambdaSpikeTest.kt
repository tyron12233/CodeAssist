package dev.ide.android.spike

import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.interp.Interpreter
import dev.ide.interp.compose.ComposeDispatcher
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.RParam
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * Step (lambdas): a `@Composable` **content lambda** is interpreted into the real runtime. The interpreter
 * walks the `ResolvedTree` for `ui(m, a, b) { Box(m, a, b) { Spacer(m) } }`; [ComposeDispatcher] wraps the
 * inner lambda as a `Function3<BoxScope, Composer, Int, Unit>` proxy that threads Box's child composer back
 * into the interpreter, so the nested `Spacer` composes inside the Box. This is the capstone that makes
 * nested Compose (`Column { Text(...) }`-shaped) interpretable.
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.ComposeLambdaSpikeTest
 *     adb logcat -s ComposeLambdaSpike
 */
@RunWith(AndroidJUnit4::class)
class ComposeLambdaSpikeTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun interpretsComposableWithContentLambda() {
        val span = SourceSpan(0, 0)
        val mSlot = SlotId(0); val aSlot = SlotId(1); val bSlot = SlotId(2)
        fun spacer() = RNode.Call(
            callee = lib("androidx.compose.foundation.layout.SpacerKt", "Spacer"),
            dispatch = DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Name(Binding.Param(mSlot, "m"), span))),
            callSiteKey = CallSiteKey(0xBEEF), source = span,
        )
        // Box(modifier, contentAlignment, propagateMinConstraints) { Spacer(m) }
        val content = RNode.Lambda(params = emptyList(), body = RNode.Block(listOf(spacer()), false, span), captures = emptyList(), source = span)
        val box = RNode.Call(
            callee = lib("androidx.compose.foundation.layout.BoxKt", "Box"),
            dispatch = DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(
                RArg(RNode.Name(Binding.Param(mSlot, "m"), span)),
                RArg(RNode.Name(Binding.Param(aSlot, "a"), span)),
                RArg(RNode.Name(Binding.Param(bSlot, "b"), span)),
                RArg(content),
            ),
            callSiteKey = CallSiteKey(0xF00D), source = span,
        )
        val ui = ResolvedFunction(
            name = "ui",
            params = listOf(RParam(mSlot, "m", null), RParam(aSlot, "a", null), RParam(bSlot, "b", null)),
            body = RNode.Block(listOf(box), isExpression = false, source = span),
            diagnostics = emptyList(),
        )

        val dispatcher = ComposeDispatcher()
        val interpreter = Interpreter(functions = emptyMap(), dispatcher = dispatcher)
        val error = AtomicReference<Throwable?>(null)

        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    dispatcher.composer = currentComposer
                    try {
                        // Box(Modifier, Alignment.TopStart, propagateMinConstraints=false) { Spacer(Modifier) }
                        interpreter.call(ui, listOf(Modifier, Alignment.TopStart, false))
                    } catch (t: Throwable) {
                        error.set(t)
                        Log.e(TAG, "interpreting a composable + content lambda FAILED", t)
                    } finally {
                        dispatcher.composer = null
                    }
                }
            }
            instrumentation.waitForIdleSync()
        }

        assertNull("interpreting Box { Spacer } threw: ${error.get()}", error.get())
        Log.i(TAG, "interpreted Box { Spacer(Modifier) } (content lambda) into the real runtime OK")
    }

    private fun lib(owner: String, name: String) = ResolvedCallable.Library(
        displayName = name, ownerFqn = owner, methodName = name, paramTypes = emptyList(),
        isStatic = true, isConstructor = false, isInline = false, isComposable = true, descriptorPrecise = true,
    )

    private companion object {
        const val TAG = "ComposeLambdaSpike"
    }
}
