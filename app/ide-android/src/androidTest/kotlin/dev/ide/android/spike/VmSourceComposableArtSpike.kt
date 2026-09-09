package dev.ide.android.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.DexPeerFactory
import dev.ide.interp.Interpreter
import dev.ide.interp.compose.ComposeDispatcher
import dev.ide.interp.compose.ComposeRuntime
import dev.ide.interp.compose.VmComposeHost
import dev.ide.interp.compose.VmLibraryExecutor
import dev.ide.jvm.ClassBytesSource
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.RParam
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BiConsumer
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Milestone-A **phase B′ on device (ART)**: the two-interpreter threading, end-to-end, on the real device. A
 * SOURCE-interpreted user `@Composable` body (interp-core's `Interpreter` tree-walking a hand-built `Preview`
 * `ResolvedFunction`, as the resolver lowers real source) drives an INTERPRETED composer produced by the
 * project's own too-new Compose runtime (interpreted from the `vmstack` asset, `1.12.0-beta01`), and its
 * `ProbeBox()` library call emits a real `Box` `LayoutNode` into a recording applier.
 *
 * `VmComposeHost.previewDriver` is the phase-D orchestration under test: it wires `ComposeDispatcher` +
 * `ComposeRuntime` + the interpreter and returns the composer callback. The interpreted setup harness
 * (`ComposerThreadingSpikeFixture.composeSourceDrivenTree`, staged in `vmbench`) stands up the interpreted
 * `Composition`/`Recomposer` and hands its `currentComposer` to that callback. The composer is VM-owned, so
 * `ComposeDispatcher.opsFor` selects `VmComposerOps` and the whole group/slot protocol runs through the VM (no
 * host reflection). A recorded `(())` proves the two interpreters thread through one interpreted composer on ART.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.VmSourceComposableArtSpike
 *     adb logcat -d -s VmSourceComposableArt
 */
@RunWith(AndroidJUnit4::class)
class VmSourceComposableArtSpike {

    private val FIXTURE = "dev.ide.interp.compose.spike.ComposerThreadingSpikeFixture"
    private val PROBES = "dev.ide.interp.compose.spike.ProbesKt"
    private val span = SourceSpan(0, 0)

    private fun log(m: String) { Log.i("VmSourceComposableArt", m); println(m) }

    private fun executor(): VmLibraryExecutor {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
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
        val source = ClassBytesSource { name ->
            if (name.startsWith("dev/ide/interp/compose/spike/")) {
                runCatching { assets.open("vmbench/${name.substringAfterLast('/')}.class").use { it.readBytes() } }.getOrNull()
            } else stack[name]
        }
        return VmLibraryExecutor(
            source = source,
            projectPreferredPrefixes = listOf(
                "androidx.compose.runtime.", "androidx.compose.ui.", "androidx.compose.foundation.",
                "dev.ide.interp.compose.spike.",
            ),
            projectExcludedPrefixes = emptyList(),
            peerFactory = DexPeerFactory(),
        )
    }

    @Test
    fun sourceInterpretedBodyEmitsARealNodeOnArt() {
        val exec = executor()
        exec.use {
            val host = VmComposeHost(exec)
            // The lowered form of: fun Preview() { ProbeBox() }
            val entry = ResolvedFunction(
                "Preview", emptyList(),
                RNode.Block(
                    listOf(
                        RNode.Call(
                            ResolvedCallable.Library(
                                "ProbeBox", PROBES, "ProbeBox", emptyList(),
                                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
                            ),
                            DispatchKind.TOP_LEVEL, receiver = null, args = emptyList(),
                            callSiteKey = CallSiteKey(3), source = span,
                        ),
                    ),
                    false, span,
                ),
                emptyList(),
            )
            val driver = host.previewDriver(entry, emptyMap())
            val t0 = System.nanoTime()
            val tree = try {
                exec.invokeStatic(FIXTURE, "composeSourceDrivenTree", listOf(driver), 0)
            } catch (t: Throwable) {
                var c: Throwable? = t
                var depth = 0
                while (c != null && depth < 12) {
                    log("cause[$depth]: ${c::class.java.name}: ${c.message?.take(220)}")
                    c.stackTrace.take(8).forEach { log("    at $it") }
                    if (c.cause === c) break
                    c = c.cause; depth++
                }
                throw t
            }
            log("VM-SRC-ART: source Preview{ ProbeBox() } -> $tree in ${(System.nanoTime() - t0) / 1_000_000}ms (two interpreters, one interpreted composer, on ART)")
            assertEquals("(())", tree)
        }
    }

    /**
     * B′.2 recomposition on ART: a source-interpreted body reads an INTERPRETED `MutableState.value`; a write to
     * it recomposes the body exactly once (initial + one recomposition = two runs). The read subscribes the scope
     * (interp-core routes the `VmObject` state to the executor → the interpreted `getValue`, registering with the
     * interpreted snapshot), the interpreted write invalidates it, and the interpreted Recomposer fires the
     * `VmComposerOps`-registered `updateScope` callback to re-run the source body — all on the real device.
     */
    @Test
    fun sourceInterpretedBodyRecomposesOnArt() {
        val exec = executor()
        exec.use {
            val dispatcher = ComposeDispatcher(libraryExecutor = exec)
            val runtime = ComposeRuntime(dispatcher)
            val interpreter = Interpreter(emptyMap(), dispatcher, runtime, libraryFallback = exec)

            // fun Preview(state) { state.value }
            val stateSlot = SlotId(1)
            val entry = ResolvedFunction(
                "Preview", listOf(RParam(stateSlot, "state", null)),
                RNode.Block(
                    listOf(
                        RNode.PropertyGet(
                            RNode.Name(Binding.Param(stateSlot, "state"), span),
                            Binding.Property("value", "androidx.compose.runtime.MutableState", backingField = false), span,
                        ),
                    ),
                    false, span,
                ),
                emptyList(),
            )

            val runs = AtomicInteger(0)
            val driver = BiConsumer<Any?, Any?> { composer, state ->
                dispatcher.composer = composer
                runtime.invokeComposable(entry.name.hashCode(), restartable = false, force = false, args = emptyList()) {
                    runs.incrementAndGet()
                    interpreter.call(entry, listOf(state))
                }
            }
            val t0 = System.nanoTime()
            try {
                exec.invokeStatic(FIXTURE, "driveRecomposition", listOf(driver, runs), 0)
            } catch (t: Throwable) {
                var c: Throwable? = t
                var depth = 0
                while (c != null && depth < 12) {
                    log("cause[$depth]: ${c::class.java.name}: ${c.message?.take(220)}")
                    c.stackTrace.take(8).forEach { log("    at $it") }
                    if (c.cause === c) break
                    c = c.cause; depth++
                }
                throw t
            }
            log("VM-SRC-ART: recomposition runs=${runs.get()} in ${(System.nanoTime() - t0) / 1_000_000}ms (interpreted state write recomposes the source body on ART)")
            assertEquals(2, runs.get())
        }
    }
}
