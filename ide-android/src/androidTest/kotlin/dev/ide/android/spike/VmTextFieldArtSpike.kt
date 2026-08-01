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

    private fun material3Bytes(): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        instrumentation.context.assets.open("vmbench/material3-android.jar").use { raw ->
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

    private fun log(m: String) { Log.i("VmTextFieldArt", m); println(m) }

    /** Compose `<owner>.<fn>(value:String, onValueChange)` interpreted from staged material3; return the failure. */
    private fun composeTextish(ownerFqn: String, fn: String): Throwable? {
        val bytes = material3Bytes()
        val executor = VmLibraryExecutor(
            source = ClassBytesSource { name -> bytes[name] },
            projectPreferredPrefixes = listOf("androidx.compose.material3."),
            peerFactory = DexPeerFactory(),
        )
        val d = ComposeDispatcher(libraryExecutor = executor)
        // Surface the underlying interpreter failure (a project-Compose class the bundled runtime lacks, e.g.
        // androidx.compose.foundation.style.MutableStyleState for a too-new material3) instead of only the
        // downstream Compose "Start/end imbalance". See the material3-flip version-ceiling analysis.
        executor.lambdaErrorSink = { t ->
            val root = generateSequence(t as Throwable?) { it.cause?.takeIf { c -> c !== it } }.last()
            log("interpreter failure behind the preview crash: ${root::class.java.name}: ${root.message}")
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
