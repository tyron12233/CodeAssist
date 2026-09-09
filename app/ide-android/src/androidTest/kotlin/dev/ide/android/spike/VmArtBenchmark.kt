package dev.ide.android.spike

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.Vm
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (ART) throughput baseline for the `:jvm-interp` bytecode interpreter. ART allocates and collects
 * for real (no JVM escape analysis), so this is where an allocation-sensitive optimization like a typed
 * operand stack must be judged, not on the desktop JVM. The benchmark interprets a compute fixture whose
 * `.class` is bundled as an asset (dexed classes have no `.class` resource on ART), measuring a long-accumulator
 * loop (allocation-heavy: each `+=` boxes a Long on the boxed stack) and small repeated calls.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.VmArtBenchmark
 *     adb logcat -d -s VmArtBench
 */
@RunWith(AndroidJUnit4::class)
class VmArtBenchmark {

    private val bench = "dev/ide/jvm/fixtures/Bench"

    private fun log(message: String) {
        Log.i("VmArtBench", message)
        println(message)
    }

    private fun newVm(): Vm {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bytes = context.assets.open("vmbench/Bench.class").use { it.readBytes() }
        val source = ClassBytesSource { name -> if (name == bench) bytes else null }
        return Vm(source = source, policy = InterpretPolicy { it.startsWith("dev/ide/jvm/fixtures/") })
    }

    @Test
    fun throughputOnArt() {
        val vm = newVm()
        assertEquals(499500L, vm.invokeStatic(bench, "sumTo", "(I)J", listOf(1000)))
        assertEquals(6765, vm.invokeStatic(bench, "fib", "(I)I", listOf(20)))

        repeat(20) { vm.invokeStatic(bench, "sumTo", "(I)J", listOf(200_000)) } // warm
        val iters = 3_000_000

        // Allocation count directly answers whether the boxed operand stack allocates on ART (where the JVM's
        // escape analysis does not apply). Millions of objects here means a typed stack would help; near-zero
        // means boxing is not the cost and the typed stack would not.
        Debug.startAllocCounting()
        val allocBefore = Debug.getThreadAllocCount()
        vm.invokeStatic(bench, "sumTo", "(I)J", listOf(iters))
        val allocs = Debug.getThreadAllocCount() - allocBefore
        Debug.stopAllocCounting()
        log("ART loop allocs: $allocs objects for $iters iters (${allocs.toDouble() / iters} per iter)")

        val before = vm.steps
        val t0 = System.nanoTime()
        vm.invokeStatic(bench, "sumTo", "(I)J", listOf(iters))
        val ns = System.nanoTime() - t0
        val steps = vm.steps - before
        log("ART loop: $iters iters = $steps ops in ${ns / 1_000_000}ms (~${steps * 1000 / maxOf(ns, 1)}M ops/sec)")

        repeat(50) { vm.invokeStatic(bench, "fib", "(I)I", listOf(20)) } // warm
        val calls = 500_000
        val c0 = System.nanoTime()
        repeat(calls) { vm.invokeStatic(bench, "fib", "(I)I", listOf(20)) }
        val cns = System.nanoTime() - c0
        log("ART calls: $calls fib(20) = ${cns / calls}ns/call (~${calls * 1_000_000_000L / cns} calls/sec)")
    }
}
