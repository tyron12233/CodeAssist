package dev.ide.android.spike

import android.os.Debug
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.DexPeerFactory
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.Vm
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipInputStream

/**
 * On-device (ART) counterpart of interp-compose's ComposeRuntimeInterpretSpike: the `:jvm-interp` bytecode VM
 * interprets the REAL Android `androidx.compose.runtime` artifact (its classes.jar bundled as an asset, since
 * the app's own copy is dexed) plus a driver fixture, with everything below bridged to the live platform —
 * real ART Kotlin/Java stdlib, real androidx.collection, real android.os. Correctness oracle: the same loops
 * run for real against the app's dexed compose runtime. The VM-COMPOSE-ART lines are the measurement — ops per
 * state cycle, throughput, and allocation counts (the number that decides the typed-operand-stack question).
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.VmComposeRuntimeArtSpike
 *     adb logcat -d -s VmComposeArt
 */
@RunWith(AndroidJUnit4::class)
class VmComposeRuntimeArtSpike {

    private val fixture = "dev/ide/interp/compose/spike/RuntimeSpikeFixture"

    private fun log(message: String) {
        Log.i("VmComposeArt", message)
        println(message)
    }

    private fun newVm(): Vm {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val runtime = HashMap<String, ByteArray>()
        assets.open("vmbench/compose-runtime-android.jar").use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".class")) runtime[entry.name.removeSuffix(".class")] = zip.readBytes()
                    entry = zip.nextEntry
                }
            }
        }
        // Fixture classes (compiled by interp-compose's desktopTest and committed alongside) are served from
        // assets by simple name; everything else under the interpreted prefix comes from the runtime jar.
        val source = ClassBytesSource { name ->
            if (name.startsWith("dev/ide/interp/compose/spike/")) {
                runCatching {
                    assets.open("vmbench/${name.substringAfterLast('/')}.class").use { it.readBytes() }
                }.getOrNull()
            } else runtime[name]
        }
        return Vm(
            source = source,
            policy = InterpretPolicy { name ->
                name.startsWith("androidx/compose/runtime/") || name.startsWith("dev/ide/interp/compose/spike/")
            },
            peerFactory = DexPeerFactory(),
        )
    }

    private fun realRoundTripIntState(writes: Int): Long {
        val state = mutableIntStateOf(0)
        var sum = 0L
        for (i in 0 until writes) {
            state.intValue = i
            sum += state.intValue
        }
        return sum
    }

    private fun realRoundTripBoxedState(writes: Int): Int {
        val state = mutableStateOf("")
        for (i in 0 until writes) state.value = if (state.value.length > 3) "" else state.value + "x"
        return state.value.length
    }

    @Test
    fun interpretsSnapshotStateOnArt() {
        val vm = newVm()
        assertEquals(
            "interpreted int-state round trip",
            realRoundTripIntState(5),
            vm.invokeStatic(fixture, "roundTripIntState", "(I)J", listOf(5)),
        )
        assertEquals(
            "interpreted boxed-state round trip",
            realRoundTripBoxedState(7),
            vm.invokeStatic(fixture, "roundTripBoxedState", "(I)I", listOf(7)),
        )
        log("correctness: interpreted compose-runtime state matches the real dexed runtime")

        vm.invokeStatic(fixture, "roundTripIntState", "(I)J", listOf(2_000)) // warm caches
        val n = 20_000

        Debug.startAllocCounting()
        val allocBefore = Debug.getThreadAllocCount()
        vm.invokeStatic(fixture, "roundTripIntState", "(I)J", listOf(n))
        val allocs = Debug.getThreadAllocCount() - allocBefore
        Debug.stopAllocCounting()
        log("VM-COMPOSE-ART intState allocs: $allocs objects for $n cycles (${allocs.toDouble() / n} per cycle)")

        val before = vm.steps
        val t0 = System.nanoTime()
        val result = vm.invokeStatic(fixture, "roundTripIntState", "(I)J", listOf(n))
        val ns = System.nanoTime() - t0
        val steps = vm.steps - before
        assertEquals(realRoundTripIntState(n), result)
        log(
            "VM-COMPOSE-ART intState: $n write+read cycles = $steps ops in ${ns / 1_000_000}ms " +
                "(~${steps * 1000 / maxOf(ns, 1)}M ops/sec, ${steps / n} ops per cycle, ${ns / n}ns per cycle)",
        )

        vm.invokeStatic(fixture, "roundTripBoxedState", "(I)I", listOf(1_000)) // warm
        val writes = 10_000
        val b0 = vm.steps
        val t1 = System.nanoTime()
        vm.invokeStatic(fixture, "roundTripBoxedState", "(I)I", listOf(writes))
        val bns = System.nanoTime() - t1
        val bsteps = vm.steps - b0
        log(
            "VM-COMPOSE-ART boxedState: $writes writes = $bsteps ops in ${bns / 1_000_000}ms " +
                "(~${bsteps * 1000 / maxOf(bns, 1)}M ops/sec, ${bsteps / writes} ops per write)",
        )
    }

    @Test
    fun composesRemembersAndRecomposesOnArt() {
        val composition = "dev/ide/interp/compose/spike/CompositionSpikeFixture"
        val vm = newVm()
        val before = vm.steps
        val t0 = System.nanoTime()
        val out = vm.invokeStatic(composition, "composeAndRecompose", "()Ljava/lang/String;")
        val ns = System.nanoTime() - t0
        assertEquals(
            "interpreted composition life cycle on ART",
            "values=0;1; runs=2 invalidated=true rememberSurvived=true",
            out,
        )
        log("VM-COMPOSE-ART composition (cold): setContent+recompose = ${vm.steps - before} ops in ${ns / 1_000_000}ms")

        val w0 = vm.steps
        val t1 = System.nanoTime()
        vm.invokeStatic(composition, "composeAndRecompose", "()Ljava/lang/String;")
        val wns = System.nanoTime() - t1
        log("VM-COMPOSE-ART composition (warm): setContent+recompose = ${vm.steps - w0} ops in ${wns / 1_000_000}ms")
    }
}
