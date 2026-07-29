package dev.ide.android.spike

import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.DexPeerFactory
import dev.ide.interp.InterpretedLambda
import dev.ide.interp.compose.ComposeDispatcher
import dev.ide.interp.compose.VmLibraryExecutor
import dev.ide.jvm.ClassBytesSource
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.SourceSpan
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (ART) validation of the Material3 interpret FLIP against a real DRAWING composable: interpret
 * `Button(onClick = {}) { }` from a staged `material3-android` classes.jar with the :jvm-interp VM, bridging to
 * the app's real dexed Compose runtime/foundation/ui. `Button` composes `Surface` (graphicsLayer/shadow), the
 * ripple `Indication`, and a `Row` — the path the desktop harness couldn't reach (Skiko absent headless).
 *
 * It composes into a REAL Compose UI (an [ActivityScenario] over [SpikeComposeActivity], the same host the
 * production `ComposePreviewRenderer` spike uses), so the emitted `LayoutNode`s land in a real owner/applier
 * and lay out — a headless `UnitApplier` can't receive them. This exercises the VM over Material3's full
 * complexity AND the interpreted-Material3 -> bridged-foundation boundary (the ripple `Indication`) on the real
 * target with [DexPeerFactory]. The staged jar is stable Material3 1.4.0-beta01 (classic `Button(shape: Shape)`;
 * the Expressive `Button(shapes = ButtonDefaults.shapes())` API is in a newer build the cache doesn't carry, so
 * it stays project/device-only — this proves the drawing-composable machinery the Expressive overload reuses).
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.VmButtonArtSpike
 *     adb logcat -d -s VmButtonArt
 */
@RunWith(AndroidJUnit4::class)
class VmButtonArtSpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun material3Bytes(): Map<String, ByteArray> {
        val assets = instrumentation.context.assets
        val out = HashMap<String, ByteArray>()
        assets.open("vmbench/material3-android.jar").use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name.endsWith(".class")) out[e.name.removeSuffix(".class")] = zip.readBytes()
                    zip.closeEntry()
                }
            }
        }
        return out
    }

    private fun log(m: String) { Log.i("VmButtonArt", m); println(m) }

    @Test
    fun interpretsADrawingMaterial3ButtonOnArt() {
        val bytes = material3Bytes()
        log("staged material3 classes: ${bytes.size}")
        val executor = VmLibraryExecutor(
            source = ClassBytesSource { name -> bytes[name] },
            projectPreferredPrefixes = listOf("androidx.compose.material3."),
            peerFactory = DexPeerFactory(),
        )
        assertTrue("material3 ButtonKt must be interpretable from the staged jar", executor.hasClass("androidx.compose.material3.ButtonKt"))
        val d = ComposeDispatcher(libraryExecutor = executor)
        val span = SourceSpan(0, 0)
        val callee = ResolvedCallable.Library(
            displayName = "Button", ownerFqn = "androidx.compose.material3.ButtonKt", methodName = "Button",
            paramTypes = listOf(null, null), isStatic = true, isConstructor = false, isInline = false, isComposable = true,
        )
        val call = RNode.Call(
            callee, DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Const(0, null, span)), RArg(RNode.Const(0, null, span), trailingLambda = true)),
            callSiteKey = CallSiteKey(1), source = span,
        )
        val onClick = object : InterpretedLambda {
            override val paramCount = 0
            override fun invoke(args: List<Any?>): Any? = null
        }
        val content = object : InterpretedLambda {
            override val paramCount = 1 // RowScope receiver, ignored
            override fun invoke(args: List<Any?>): Any? = null
        }

        val composed = AtomicBoolean(false)
        val error = arrayOfNulls<Throwable>(1)
        // Real Compose UI: SpikeComposeActivity provides a genuine owner/applier + Android locals (LocalDensity,
        // LocalConfiguration, …), so Button's emitted LayoutNodes are received and laid out.
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    d.composer = currentComposer
                    // `dispatch` is a plain function (try/catch around it is fine); `SideEffect` is composable, so
                    // it must sit OUTSIDE the try — it runs only after a successful composition pass.
                    val ok = try {
                        d.dispatch(call, receiver = null, args = listOf<Any?>(onClick, content))
                        true
                    } catch (t: Throwable) {
                        error[0] = t
                        false
                    }
                    if (ok) SideEffect { composed.set(true) }
                }
            }
            instrumentation.waitForIdleSync()
        }
        error[0]?.let {
            val root = generateSequence(it) { t -> t.cause }.last()
            log("interpretsADrawingMaterial3ButtonOnArt: THREW ${root::class.java.name}: ${root.message}")
        }
        if (composed.get()) log("interpretsADrawingMaterial3ButtonOnArt: OK (Button composed interpreted into the real UI — Surface+ripple+Row crossed into bridged foundation)")
        assertTrue("interpreted Material3 Button must compose on ART — see VmButtonArt logcat", composed.get() && error[0] == null)
    }
}
