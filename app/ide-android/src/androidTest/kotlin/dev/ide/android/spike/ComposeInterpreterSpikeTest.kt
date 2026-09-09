package dev.ide.android.spike

import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.runtime.currentComposer
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
 * Step 4 (b): the on-device proof that `:interp-core`'s interpreter + the Compose-aware [ComposeDispatcher]
 * drive a **real** composable through the **real** Compose runtime — the interpreter half of the design in
 * `docs/compose-interpreter.md`, building on the ABI spike ([ComposeAbiSpikeTest]).
 *
 * It hand-builds the `ResolvedTree` for `ui(m: Modifier) { Spacer(m) }` (the resolver → on-device-classpath
 * path is a separate concern — here we isolate the interpreter↔Compose integration), then interprets it
 * inside a real composition with the live `currentComposer` threaded into the dispatcher. A green run means
 * the interpreter, the dispatcher's composer threading, and `ComposableAbi`'s mangled-signature call all
 * compose into the real runtime.
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.ComposeInterpreterSpikeTest
 *     adb logcat -s ComposeInterpSpike
 */
@RunWith(AndroidJUnit4::class)
class ComposeInterpreterSpikeTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun interpretsAComposableCallInsideARealComposition() {
        val span = SourceSpan(0, 0)
        val modifierSlot = SlotId(0)
        // ResolvedTree for: fun ui(m: Modifier) { Spacer(m) }
        val spacerCall = RNode.Call(
            callee = ResolvedCallable.Library(
                displayName = "Spacer",
                ownerFqn = "androidx.compose.foundation.layout.SpacerKt",
                methodName = "Spacer",
                paramTypes = emptyList(),
                isStatic = true,
                isConstructor = false,
                isInline = false,
                isComposable = true,
                descriptorPrecise = true,
            ),
            dispatch = DispatchKind.TOP_LEVEL,
            receiver = null,
            args = listOf(RArg(RNode.Name(Binding.Param(modifierSlot, "m"), span))),
            callSiteKey = CallSiteKey(0x5ACE),
            source = span,
        )
        val fn = ResolvedFunction(
            name = "ui",
            params = listOf(RParam(modifierSlot, "m", null)),
            body = RNode.Block(listOf(spacerCall), isExpression = false, source = span),
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
                        interpreter.call(fn, listOf(Modifier))
                    } catch (t: Throwable) {
                        error.set(t)
                        Log.e(TAG, "interpreting a composable call FAILED", t)
                    } finally {
                        dispatcher.composer = null
                    }
                }
            }
            instrumentation.waitForIdleSync()
        }

        assertNull("interpreting a composable call threw: ${error.get()}", error.get())
        Log.i(TAG, "interpreted Spacer(Modifier) through interp-core + ComposeDispatcher into the real runtime OK")
    }

    private companion object {
        const val TAG = "ComposeInterpSpike"
    }
}
