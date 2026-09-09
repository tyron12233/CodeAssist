package dev.ide.android.spike

import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.interp.compose.ComposableAbi
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Discovery spike (not a regression test) for the on-device Compose **interpreter** — see
 * `docs/compose-interpreter.md`. It validates, by reflection and with NO interpreter yet, the single
 * riskiest assumption of that design: that we can drive the **real** `androidx.compose.runtime` from inside
 * a real composition by hand — thread a real `Composer` into a precompiled (plugin-mangled) `@Composable`,
 * and have a `mutableStateOf` change drive recomposition.
 *
 * Like [KotlinCompilerArtSpikeTest], the interesting path swallows nothing: each rung logs to logcat under
 * [TAG] and the first on-device run may fail — the failure (a `NoSuchMethodError`, a slot-table desync, …)
 * is the deliverable, pinpointing which rung of the Compose ABI breaks.
 *
 * Run on a connected device/emulator:
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest --tests dev.ide.android.spike.ComposeAbiSpikeTest
 *     adb logcat -s ComposeAbiSpike
 *
 * The [ComposableAbi] helper here (discover the transformed method, append composer + trailing `$changed`
 * ints, supply argument values) is the prototype of the future `interp-compose` ABI adapter.
 */
@RunWith(AndroidJUnit4::class)
class ComposeAbiSpikeTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** Rung 1 — sanity: a real composition renders a *compiled* composable without crashing. */
    @Test
    fun rung1_harnessComposesCompiledComposable() {
        val runs = AtomicInteger(0)
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    runs.incrementAndGet()
                    Spacer(Modifier) // ordinary, plugin-compiled call — proves the harness composes
                }
            }
            instrumentation.waitForIdleSync()
        }
        Log.i(TAG, "rung1: compiled composition ran $runs time(s)")
        assertTrue("the compiled composable body should have run at least once", runs.get() >= 1)
    }

    /** Rung 2 — the core question: reflectively invoke a real composable, threading the live composer. */
    @Test
    fun rung2_reflectiveCallIntoRealComposable() {
        val ok = AtomicReference<Boolean>(false)
        val error = AtomicReference<Throwable?>(null)
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val composer: Any = currentComposer
                    // Give the reflective call a stable caller-side group — what the plugin emits per
                    // call-site — so the slot table stays balanced (and would survive recomposition).
                    ComposableAbi.startGroup(composer, CALL_SITE_KEY)
                    try {
                        // Spacer(modifier: Modifier) → transformed `Spacer(Modifier, Composer, int $changed)`.
                        ComposableAbi.call(
                            ownerFqn = "androidx.compose.foundation.layout.SpacerKt",
                            method = "Spacer",
                            originalArgs = listOf<Any?>(Modifier),
                            composer = composer,
                        )
                        ok.set(true)
                    } catch (t: Throwable) {
                        error.set(t)
                        Log.e(TAG, "rung2: reflective composable call FAILED", t)
                    } finally {
                        ComposableAbi.endGroup(composer)
                    }
                }
            }
            instrumentation.waitForIdleSync()
        }
        error.get()?.let { Log.e(TAG, "rung2: throwable was ${it.javaClass.name}: ${it.message}") }
        assertNull("reflective composable call threw: ${error.get()}", error.get())
        assertTrue("reflective composable call did not complete", ok.get())
        Log.i(TAG, "rung2: reflective Spacer dispatch + composer threading OK")
    }

    /** Rung 3 — reactivity: a `mutableStateOf` read in the composition recomposes when it changes. */
    @Test
    fun rung3_stateChangeRecomposes() {
        val state = mutableStateOf("a")
        val runs = AtomicInteger(0)
        ActivityScenario.launch(SpikeComposeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    runs.incrementAndGet()
                    @Suppress("UNUSED_EXPRESSION") state.value // establishes the recomposition dependency
                    Spacer(Modifier)
                }
            }
            instrumentation.waitForIdleSync()
            val before = runs.get()
            Log.i(TAG, "rung3: composed $before time(s) before state change")
            scenario.onActivity { state.value = "b" } // mutate on the UI thread → schedules recomposition
            val recomposed = awaitUntil(timeoutMs = 3000) { runs.get() > before }
            Log.i(TAG, "rung3: composed ${runs.get()} time(s) after state change (recomposed=$recomposed)")
            assertTrue("state change should trigger a recomposition", recomposed)
        }
    }

    /** Poll [predicate] on this (instrumentation) thread while recomposition runs on the UI thread. */
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
        const val TAG = "ComposeAbiSpike"
        // Any stable per-call-site int; the plugin hashes the source position — an interpreter uses the
        // PSI offset. Here a constant is enough (one call site).
        const val CALL_SITE_KEY = 0x5ACE_0001.toInt()
    }
}
