package dev.ide.android.spike

import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.interp.compose.ComposePreviewRenderer
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The device render surface: [ComposePreviewRenderer] composes a lowered `@Preview` into real Compose UI
 * inside the IDE's composition. Renders a no-arg preview whose body is `Spacer(Modifier)` (a real leaf
 * composable) through the renderer and asserts it composes into the live tree without error — exactly the
 * component the editor preview panel embeds.
 */
@RunWith(AndroidJUnit4::class)
class ComposePreviewRenderSpikeTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun rendersAPreviewThroughTheRenderer() {
        val span = SourceSpan(0, 0)
        // @Preview @Composable fun Preview() { Spacer(Modifier) }
        val spacer = RNode.Call(
            callee = ResolvedCallable.Library(
                displayName = "Spacer", ownerFqn = "androidx.compose.foundation.layout.SpacerKt",
                methodName = "Spacer", paramTypes = emptyList(), isStatic = true, isConstructor = false,
                isInline = false, isComposable = true, descriptorPrecise = true,
            ),
            dispatch = DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Const(Modifier, null, span))), // the Modifier companion as a constant value
            callSiteKey = CallSiteKey(0x9A2), source = span,
        )
        val preview = ResolvedFunction(
            name = "Preview",
            params = emptyList(),
            body = RNode.Block(listOf(spacer), isExpression = false, source = span),
            diagnostics = emptyList(),
        )

        // Composition errors propagate out of setContent (failing the test); SideEffect runs only after a
        // successful composition pass, so it confirms the preview actually composed.
        val composed = AtomicBoolean(false)
        val renderer = ComposePreviewRenderer()
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    renderer.Render(preview, emptyMap())
                    SideEffect { composed.set(true) }
                }
            }
            instrumentation.waitForIdleSync()
        }
        assertTrue("the preview should have composed into the real runtime", composed.get())
        Log.i(TAG, "ComposePreviewRenderer composed a preview into the real runtime OK")
    }

    private companion object {
        const val TAG = "ComposePreviewRender"
    }
}
