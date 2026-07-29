package dev.ide.android.spike

import android.util.Log
import androidx.activity.compose.setContent
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
import java.util.zip.ZipInputStream
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (ART) perf of the Material3 interpret flip: how much does INTERPRETING Material3 (the flip) cost
 * vs. REFLECTING it through the app's bundled runtime (`ComposableAbi`), and what does a realistic screen cost
 * interpreted? Composes N sibling instances in one real composition and times each `dispatch` (the interpret +
 * compose of one call). Reports `cold` (first — includes lazy class parse/overload-select cache fill) and
 * `warm` (median of the rest — the steady-state edit/recompose cost). JVM/JIT-free ART numbers on the emulator;
 * a physical device differs, but the interpreted-vs-reflective RATIO is the signal.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.VmMaterial3PerfArtSpike
 *     adb logcat -d -s VmM3Perf
 */
@RunWith(AndroidJUnit4::class)
class VmMaterial3PerfArtSpike {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val span = SourceSpan(0, 0)
    private val N = 20

    private fun jarBytes(asset: String): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        instrumentation.context.assets.open(asset).use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) { val e = zip.nextEntry ?: break; if (e.name.endsWith(".class")) out[e.name.removeSuffix(".class")] = zip.readBytes(); zip.closeEntry() }
            }
        }
        return out
    }
    private fun assetBytes(a: String) = instrumentation.context.assets.open(a).use { it.readBytes() }
    private fun log(m: String) { Log.i("VmM3Perf", m); println(m) }

    private fun noArg() = object : InterpretedLambda { override val paramCount = 0; override fun invoke(args: List<Any?>): Any? = null }
    private fun oneArg() = object : InterpretedLambda { override val paramCount = 1; override fun invoke(args: List<Any?>): Any? = null }

    private fun buttonCall(key: Int) = RNode.Call(
        ResolvedCallable.Library("Button", "androidx.compose.material3.ButtonKt", "Button", listOf(null, null), isStatic = true, isConstructor = false, isInline = false, isComposable = true),
        DispatchKind.TOP_LEVEL, receiver = null,
        args = listOf(RArg(RNode.Const(0, null, span)), RArg(RNode.Const(0, null, span), trailingLambda = true)),
        callSiteKey = CallSiteKey(key), source = span,
    )
    private fun noArgCall(facade: String, name: String, key: Int) = RNode.Call(
        ResolvedCallable.Library(name, facade, name, emptyList(), isStatic = true, isConstructor = false, isInline = false, isComposable = true),
        DispatchKind.TOP_LEVEL, receiver = null, args = emptyList(), callSiteKey = CallSiteKey(key), source = span,
    )

    /** Compose N siblings in one real composition, timing each dispatch. Returns (coldMs, warmMedianMs). */
    private fun measure(d: ComposeDispatcher, call: (Int) -> RNode.Call, args: (Int) -> List<Any?>): Pair<Double, Double> {
        val times = LongArray(N)
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    d.composer = currentComposer
                    var i = 0
                    while (i < N) {
                        val t0 = System.nanoTime()
                        runCatching { d.dispatch(call(i), receiver = null, args = args(i)) }
                        times[i] = System.nanoTime() - t0
                        i++
                    }
                }
            }
            instrumentation.waitForIdleSync()
        }
        val cold = times[0] / 1_000_000.0
        val rest = times.drop(1).sorted()
        val warm = rest[rest.size / 2] / 1_000_000.0
        return cold to warm
    }

    @Test
    fun material3InterpretVsReflectPerfOnArt() {
        val m3 = jarBytes("vmbench/material3-android.jar")
        val fixture = mapOf(
            "dev/ide/interp/compose/jetpacksamples/JetpackSamplesKt" to assetBytes("vmbench/JetpackSamplesKt.class"),
            "dev/ide/interp/compose/jetpacksamples/ComposableSingletons\$JetpackSamplesKt" to assetBytes("vmbench/ComposableSingletons\$JetpackSamplesKt.class"),
        )
        val interpreted = ComposeDispatcher(
            libraryExecutor = VmLibraryExecutor(
                source = ClassBytesSource { name -> fixture[name] ?: m3[name] },
                projectPreferredPrefixes = listOf("androidx.compose.material3."),
                peerFactory = DexPeerFactory(),
            ),
        )
        val reflective = ComposeDispatcher() // no executor -> ComposableAbi against the app's bundled Material3

        // Cold attribution + dex-cache win: time ONE Button on a FRESH executor (nothing parsed/dexed yet),
        // splitting the cost into class parse vs. per-peer D8 dexing. cold1 = process cache cleared (D8 runs);
        // cold2 = another fresh executor with the cache warm (D8 skipped) — the every-reopen edit-loop cost.
        fun oneColdButton(diskDir: java.nio.file.Path? = null): Triple<Double, Double, Double> {
            val ex = VmLibraryExecutor(
                source = ClassBytesSource { name -> fixture[name] ?: m3[name] },
                projectPreferredPrefixes = listOf("androidx.compose.material3."),
                peerFactory = DexPeerFactory(diskDir),
            )
            val cd = ComposeDispatcher(libraryExecutor = ex)
            dev.ide.jvm.VmProfile.reset(); DexPeerFactory.reset()
            var ms = 0.0
            ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        cd.composer = currentComposer
                        val t0 = System.nanoTime()
                        runCatching { cd.dispatch(buttonCall(0), receiver = null, args = listOf<Any?>(noArg(), noArg())) }
                        ms = (System.nanoTime() - t0) / 1_000_000.0
                    }
                }
                instrumentation.waitForIdleSync()
            }
            return Triple(ms, dev.ide.jvm.VmProfile.parseNanos.get() / 1_000_000.0, DexPeerFactory.dexNanos.get() / 1_000_000.0)
        }
        DexPeerFactory.clearCache()
        val (cold1Ms, parse1, dex1) = oneColdButton() // cache miss: D8 runs
        val cold1Snap = DexPeerFactory.snapshot()
        val peerNames = DexPeerFactory.dexedNames.toList()
        val (cold2Ms, parse2, dex2) = oneColdButton() // fresh executor, cache warm: D8 skipped
        val cold2Snap = DexPeerFactory.snapshot()

        val (ic, iw) = measure(interpreted, ::buttonCall) { listOf<Any?>(noArg(), noArg()) }
        val (rc, rw) = measure(reflective, ::buttonCall) { listOf<Any?>(noArg(), noArg()) }
        val (mc, mw) = measure(interpreted, { k -> noArgCall("dev.ide.interp.compose.jetpacksamples.JetpackSamplesKt", "MiniScreenSample", k) }, { emptyList() })

        log("COLD Button (cache MISS): total=%.1fms | parse=%.0fms | dex=%.0fms | %s | other=%.1fms".format(cold1Ms, parse1, dex1, cold1Snap, cold1Ms - parse1 - dex1))
        log("COLD Button (cache HIT ): total=%.1fms | parse=%.0fms | dex=%.0fms | %s | other=%.1fms".format(cold2Ms, parse2, dex2, cold2Snap, cold2Ms - parse2 - dex2))
        log("  => dex cache cuts reopen cold from %.0fms to %.0fms".format(cold1Ms, cold2Ms))
        log("  peers dexed (${peerNames.size}): ${peerNames.joinToString(", ")}")

        // Disk cache (cross app-restart): dex to a disk dir, drop the MEM cache (simulating a restart), then a
        // fresh factory over the SAME dir must reload dex from disk with D8 skipped.
        val diskDir = java.nio.file.Files.createTempDirectory("peer-dex-disk-test")
        DexPeerFactory.clearCache()
        val (disk1Ms, _, disk1Dex) = oneColdButton(diskDir) // writes disk
        DexPeerFactory.clearCache()                          // drop MEM only; disk retains
        val (disk2Ms, _, disk2Dex) = oneColdButton(diskDir)  // must hit DISK
        log("  DISK cache: write cold=%.0fms (dex=%.0fms), reload-after-restart=%.0fms (dex=%.0fms, %d D8 runs)".format(disk1Ms, disk1Dex, disk2Ms, disk2Dex, DexPeerFactory.dexCount.get()))
        diskDir.toFile().deleteRecursively()
        log("N=$N per-composable ms on ART:")
        log("  Button  interpreted: cold=%.2f warm=%.3f".format(ic, iw))
        log("  Button  reflective : cold=%.2f warm=%.3f".format(rc, rw))
        log("  Button  interpreted/reflective warm ratio: %.1fx".format(iw / rw))
        log("  MiniScreen interpreted: cold=%.2f warm=%.3f".format(mc, mw))
    }
}
