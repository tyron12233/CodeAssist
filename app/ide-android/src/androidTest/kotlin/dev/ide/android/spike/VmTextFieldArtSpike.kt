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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnose the reported `VmException` crash when a Compose preview uses material3 `TextField` (the RGBScreen2
 * shape: `TextField(value = "…", onValueChange = { })`). Under the Material3 interpret flip the VM interprets
 * `androidx.compose.material3.*`; `TextField` composes far more than `Button`, so it may hit a bytecode gap
 * `Button`/`OutlinedTextField` don't. Renders each into a real [SpikeComposeActivity] and logs the FULL cause
 * chain (the enriched VM errors carry the interpreted `method@pc`).
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.VmTextFieldArtSpike
 *     adb logcat -d -s VmTextFieldArt
 */
@RunWith(AndroidJUnit4::class)
class VmTextFieldArtSpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** The full transitive Compose stack (material3 + foundation + ui + runtime, the project's newer versions),
     *  staged as real Android bytecode under `vmstack/` — so the VM can INTERPRET foundation/ui, not just material3. */
    private fun stackBytes(): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        val assets = instrumentation.context.assets
        for (name in assets.list("vmstack").orEmpty()) {
            assets.open("vmstack/$name").use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.name.endsWith(".class")) out.putIfAbsent(e.name.removeSuffix(".class"), zip.readBytes())
                        zip.closeEntry()
                    }
                }
            }
        }
        return out
    }

    private fun log(m: String) { Log.i("VmTextFieldArt", m); println(m) }

    /** Compose `<owner>.<fn>(value:String, onValueChange)` interpreted from staged material3; return the failure. */
    private fun composeTextish(ownerFqn: String, fn: String): Throwable? {
        val bytes = stackBytes()
        log("staged stack classes: ${bytes.size}")
        val executor = VmLibraryExecutor(
            source = ClassBytesSource { name -> bytes[name] },
            // Milestone A step: interpret the project's newer Compose UI stack (material3 + foundation + ui +
            // animation) from the staged jars, so classes the bundled Compose lacks (foundation.style.*) exist.
            // Runtime stays bridged (the Composer interface is stable across versions).
            projectPreferredPrefixes = listOf(
                "androidx.compose.material3.",
                "androidx.compose.foundation.",
                "androidx.compose.ui.",
                "androidx.compose.animation.",
            ),
            // BRIDGE the runtime plumbing the (bridged) host composer/applier operates on: ui.platform (host
            // CompositionLocals) AND ui.node (LayoutNode/UiApplier — the host applier materializes REAL LayoutNodes,
            // so an interpreted node would be a $Proxy the host can't cast). Interpret the UI *logic*, bridge the
            // *plumbing*. The node classes exist in the bundled Compose, so they bridge to real host objects.
            projectExcludedPrefixes = listOf(
                "androidx.compose.ui.platform.",
                "androidx.compose.ui.node.",
                // The pervasive Modifier INTERFACE + combinators cross the bridge constantly and are version-stable;
                // bridge them so a Modifier is always the host's real interface. Implementations (foundation's
                // PaddingElement etc.) stay interpreted as peers OF that real interface. Interface bridged, impls interpreted.
                "androidx.compose.ui.Modifier",
                "androidx.compose.ui.CombinedModifier",
            ),
            peerFactory = DexPeerFactory(),
        )
        log("foundation.style classes in bytes: ${bytes.keys.count { it.startsWith("androidx/compose/foundation/style/") }}")
        log("has Style bytes: ${bytes.containsKey("androidx/compose/foundation/style/Style")} ; hasClass(Style)=${executor.hasClass("androidx.compose.foundation.style.Style")}")
        val d = ComposeDispatcher(libraryExecutor = executor)
        // Surface the underlying interpreter failure (a project-Compose class the bundled runtime lacks, e.g.
        // androidx.compose.foundation.style.MutableStyleState for a too-new material3) instead of only the
        // downstream Compose "Start/end imbalance". See the material3-flip version-ceiling analysis.
        executor.lambdaErrorSink = { t ->
            val root = generateSequence(t as Throwable?) { it.cause?.takeIf { c -> c !== it } }.last()
            log("interpreter failure behind the preview crash: ${root::class.java.name}: ${root.message?.take(120)}")
            // Dump the reflective site: WHO loads the interpreted-only class (e.g. foundation.style.Style) via the
            // host loader. Frames mentioning dev.ide.jvm/interp reveal the bridge boundary (peer vs bridgeCall vs cast).
            root.stackTrace.take(22).forEach { f -> log("    ROOT at $f") }
        }
        val span = SourceSpan(0, 0)
        val callee = ResolvedCallable.Library(
            displayName = fn, ownerFqn = ownerFqn, methodName = fn,
            paramTypes = listOf(null, null), isStatic = true, isConstructor = false, isInline = false, isComposable = true,
        )
        // TextField(value: String, onValueChange: (String) -> Unit, …) — 2 positional args, rest defaulted.
        val call = RNode.Call(
            callee, DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Const("jdjdjdej", null, span)), RArg(RNode.Const(0, null, span))),
            callSiteKey = CallSiteKey(1), source = span,
        )
        val onValueChange = object : InterpretedLambda {
            override val paramCount = 1 // (String) -> Unit
            override fun invoke(args: List<Any?>): Any? = null
        }
        val composed = AtomicBoolean(false)
        val error = arrayOfNulls<Throwable>(1)
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    d.composer = currentComposer
                    val ok = try {
                        d.dispatch(call, receiver = null, args = listOf<Any?>("jdjdjdej", onValueChange))
                        true
                    } catch (t: Throwable) {
                        error[0] = t; false
                    }
                    if (ok) SideEffect { composed.set(true) }
                }
            }
            instrumentation.waitForIdleSync()
        }
        if (composed.get()) log("$fn: OK (composed into the real UI)")
        return error[0]
    }

    private fun report(label: String, t: Throwable?) {
        if (t == null) { log("$label: no error"); return }
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth < 8) {
            log("$label cause[$depth]: ${c::class.java.name}: ${c.message}")
            c.stackTrace.take(6).forEach { log("    at $it") }
            if (c.cause === c) break
            c = c.cause; depth++
        }
    }

    @Test
    fun textField() {
        val t = composeTextish("androidx.compose.material3.TextFieldKt", "TextField")
        report("TextField", t)
    }

    @Test
    fun outlinedTextFieldControl() {
        val t = composeTextish("androidx.compose.material3.OutlinedTextFieldKt", "OutlinedTextField")
        report("OutlinedTextField", t)
    }
}
