package dev.ide.android.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.DexPeerFactory
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.Vm
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Milestone-A **phase B on device (ART)**: the `:jvm-interp` VM interprets the project's CONSISTENT too-new
 * Compose stack — `runtime` + `ui` + `foundation`, all `1.12.0-beta01`, staged as real Android bytecode in the
 * `vmstack` asset — plus the phase-B fixture (`UiStackSpikeFixture`, compiled by interp-compose's desktopTest and
 * staged in `vmbench`), and composes a real `Column { Box(); Box() }` into a recording applier. This materializes
 * the interpreted `LayoutNode` tree on the real device, where `LayoutNode` construction's `android.graphics.Paint`
 * is native (no Skiko). Because the composer is the project's OWN interpreted version, the material3-flip version
 * ceiling doesn't apply — the whole stack that touches the composer is one interpreted world.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.VmUiStackArtSpike
 *     adb logcat -d -s VmUiStackArt
 */
@RunWith(AndroidJUnit4::class)
class VmUiStackArtSpike {

    private fun log(m: String) { Log.i("VmUiStackArt", m); println(m) }

    private fun newVm(): Vm {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        // The consistent project Compose stack (runtime+ui+foundation 1.12.0-beta01, material3 1.5.0-alpha24) as
        // real Android .class bytes — so the VM interprets the WHOLE stack at the project's version.
        val stack = HashMap<String, ByteArray>()
        for (name in assets.list("vmstack").orEmpty()) {
            assets.open("vmstack/$name").use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.name.endsWith(".class")) stack.putIfAbsent(e.name.removeSuffix(".class"), zip.readBytes())
                        zip.closeEntry()
                    }
                }
            }
        }
        log("staged stack classes: ${stack.size}")
        // Fixture classes are served by simple name from vmbench (committed alongside, compiled by desktopTest);
        // everything else under the interpreted prefixes comes from the staged stack.
        val source = ClassBytesSource { name ->
            if (name.startsWith("dev/ide/interp/compose/spike/")) {
                runCatching { assets.open("vmbench/${name.substringAfterLast('/')}.class").use { it.readBytes() } }.getOrNull()
            } else stack[name]
        }
        return Vm(
            source = source,
            policy = InterpretPolicy { name ->
                name.startsWith("androidx/compose/runtime/") ||
                    name.startsWith("androidx/compose/ui/") ||
                    name.startsWith("androidx/compose/foundation/") ||
                    name.startsWith("dev/ide/interp/compose/spike/")
            },
            peerFactory = DexPeerFactory(),
        )
    }

    @Test
    fun interpretsUiStackNodeTreeOnArt() {
        val vm = newVm()
        val t0 = System.nanoTime()
        val tree = try {
            vm.invokeStatic(
                "dev/ide/interp/compose/spike/UiStackSpikeFixture", "composeColumnOfBoxes", "()Ljava/lang/String;",
            )
        } catch (t: Throwable) {
            var c: Throwable? = t
            var depth = 0
            while (c != null && depth < 10) {
                log("cause[$depth]: ${c::class.java.name}: ${c.message?.take(200)}")
                c.stackTrace.take(8).forEach { log("    at $it") }
                if (c.cause === c) break
                c = c.cause; depth++
            }
            throw t
        }
        val ns = System.nanoTime() - t0
        log("VM-UISTACK-ART: Column{Box;Box} -> $tree in ${ns / 1_000_000}ms (interpreted runtime+ui+foundation 1.12 on ART)")
        assertEquals("(((),()))", tree)
    }
}
