package dev.ide.android.spike

import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.interp.Interpreter
import dev.ide.interp.compose.ComposeDispatcher
import dev.ide.interp.compose.ComposeRuntime
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Step 5: granular recomposition + state, on device. The interpreted composable body reads a real
 * `MutableState` and is wrapped (by [ComposeRuntime]) in a real restart group; when the state changes the
 * real Recomposer invalidates the scope and re-runs the body. Proves the interpreter drives true reactive
 * recomposition through the real runtime — not just a one-shot composition (step 4b).
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.ComposeRecompositionSpikeTest
 *     adb logcat -s ComposeRecompSpike
 */
@RunWith(AndroidJUnit4::class)
class ComposeRecompositionSpikeTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun stateChangeRecomposesInterpretedComposable() {
        val span = SourceSpan(0, 0)
        val stateSlot = SlotId(0)
        val modifierSlot = SlotId(1)
        // ResolvedTree for: fun ui(s: State, m: Modifier) { s.value /* subscribe */; Spacer(m) }
        val readState = RNode.PropertyGet(
            receiver = RNode.Name(Binding.Param(stateSlot, "s"), span),
            binding = Binding.Property("value", ownerFqn = null, backingField = false),
            source = span,
        )
        val spacer = RNode.Call(
            callee = ResolvedCallable.Library(
                displayName = "Spacer", ownerFqn = "androidx.compose.foundation.layout.SpacerKt",
                methodName = "Spacer", paramTypes = emptyList(), isStatic = true, isConstructor = false,
                isInline = false, isComposable = true, descriptorPrecise = true,
            ),
            dispatch = DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Name(Binding.Param(modifierSlot, "m"), span))),
            callSiteKey = CallSiteKey(0xC0DE), source = span,
        )
        val ui = ResolvedFunction(
            name = "ui",
            params = listOf(RParam(stateSlot, "s", null), RParam(modifierSlot, "m", null)),
            body = RNode.Block(listOf(readState, spacer), isExpression = false, source = span),
            diagnostics = emptyList(),
        )

        val dispatcher = ComposeDispatcher()
        val runtime = ComposeRuntime(dispatcher)
        val interpreter = Interpreter(functions = emptyMap(), dispatcher = dispatcher, composableInvoker = runtime)

        val state = mutableStateOf(0)
        val runs = AtomicInteger(0)
        val error = AtomicReference<Throwable?>(null)

        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    dispatcher.composer = currentComposer
                    try {
                        // Wrap the interpreted composable in a restart group; reads of `state` inside subscribe it.
                        // restartable=false → always re-run the body (this spike asserts recomposition fires;
                        // it doesn't exercise the $changed skip path).
                        runtime.invokeComposable(ROOT_KEY, restartable = false, force = false, args = emptyList()) {
                            runs.incrementAndGet()
                            interpreter.call(ui, listOf(state, Modifier))
                        }
                    } catch (t: Throwable) {
                        error.set(t)
                        Log.e(TAG, "interpreted composition FAILED", t)
                    } finally {
                        dispatcher.composer = null
                    }
                }
            }
            instrumentation.waitForIdleSync()
            assertNull("initial interpreted composition threw: ${error.get()}", error.get())
            val before = runs.get()
            Log.i(TAG, "composed $before time(s) before state change")

            scenario.onActivity { state.value = 1 } // mutate on the UI thread → invalidates the restart scope
            val recomposed = awaitUntil(timeoutMs = 4000) { runs.get() > before }
            Log.i(TAG, "composed ${runs.get()} time(s) after state change (recomposed=$recomposed)")
            assertNull("recomposition threw: ${error.get()}", error.get())
            assertTrue("a state change should recompose the interpreted composable", recomposed)
        }
    }

    private fun awaitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            instrumentation.waitForIdleSync()
            Thread.sleep(16)
        }
        return predicate()
    }

    private companion object {
        const val TAG = "ComposeRecompSpike"
        const val ROOT_KEY = 0x5ACE_0002.toInt()
    }
}
