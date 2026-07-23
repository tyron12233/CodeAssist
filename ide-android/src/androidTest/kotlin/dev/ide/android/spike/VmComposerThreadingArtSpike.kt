package dev.ide.android.spike

import android.util.Log
import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.DexPeerFactory
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.Vm
import dev.ide.jvm.VmMethodView
import dev.ide.jvm.interpretedMethods
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (ART) twin of interp-compose's ComposerThreadingInterpretSpike: a Compose-plugin-COMPILED
 * composable's bytecode (bundled as an asset) runs interpreted in the VM with the app's REAL dexed Compose
 * runtime bridged — the live Composer crosses in as an argument, the interpreted restart lambda crosses out
 * as a proxy the real runtime re-invokes on invalidation. This is the execution model for a library
 * composable that exists only in a project jar.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.VmComposerThreadingArtSpike
 *     adb logcat -d -s VmComposerArt
 */
@RunWith(AndroidJUnit4::class)
class VmComposerThreadingArtSpike {

    private val facade = "dev.ide.interp.compose.composerfixture.ComposerFixtureKt"

    private fun log(message: String) {
        Log.i("VmComposerArt", message)
        println(message)
    }

    private fun newVm(): Vm {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val source = ClassBytesSource { name ->
            if (name.startsWith("dev/ide/interp/compose/composerfixture/")) {
                runCatching {
                    assets.open("vmbench/${name.substringAfterLast('/')}.class").use { it.readBytes() }
                }.getOrNull()
            } else null
        }
        return Vm(
            source = source,
            policy = InterpretPolicy { it.startsWith("dev/ide/interp/compose/composerfixture/") },
            peerFactory = DexPeerFactory(),
        )
    }

    private fun composerView(vm: Vm, name: String): VmMethodView =
        vm.interpretedMethods(facade).first { v ->
            v.name == name && v.paramDescriptors.contains("Landroidx/compose/runtime/Composer;")
        }

    @OptIn(InternalComposeApi::class)
    @Test
    fun interpretedComposableComposesAndRecomposesOnArt() {
        val vm = newVm()
        val view = composerView(vm, "CountingLabel")
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = ControlledComposition(UnitApplier, recomposer)
        val state = mutableIntStateOf(0)
        val entries = mutableListOf<String>()
        val t0 = System.nanoTime()
        composition.setContent {
            view.invoke(null, listOf(state, entries, currentComposer, 0))
        }
        val composeNs = System.nanoTime() - t0
        assertEquals(listOf("run:0:0"), entries)

        state.intValue = 5
        composition.recordModificationsOf(setOf(state))
        val t1 = System.nanoTime()
        assertTrue("state read registered", composition.recompose())
        composition.applyChanges()
        val recomposeNs = System.nanoTime() - t1
        assertEquals(listOf("run:0:0", "run:5:10"), entries)
        log("VM-COMPOSER-ART: initial compose ${composeNs / 1_000_000}ms, recompose ${recomposeNs / 1_000_000}ms (interpreted composable, real dexed runtime)")

        composition.dispose()
        recomposer.cancel()
    }

    @Test
    fun realContentLambdaCrossesIntoTheInterpretedWrapperOnArt() {
        val vm = newVm()
        val view = composerView(vm, "Wrapper")
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = Composition(UnitApplier, recomposer)
        val entries = mutableListOf<String>()
        val content: @Composable () -> Unit = { entries.add("inner") }
        composition.setContent {
            view.invoke(null, listOf(entries, content, currentComposer, 0))
        }
        assertEquals(listOf("wrap-start", "inner", "wrap-end"), entries)
        composition.dispose()
        recomposer.cancel()
    }

    private object UnitApplier : Applier<Unit> {
        override val current: Unit get() = Unit
        override fun down(node: Unit) {}
        override fun up() {}
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun clear() {}
    }
}
