package dev.ide.interp.compose.spike

import dev.ide.interp.Interpreter
import dev.ide.interp.compose.ComposeDispatcher
import dev.ide.interp.compose.ComposeRuntime
import dev.ide.interp.compose.VmLibraryExecutor
import dev.ide.jvm.ClassBytesSource
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.RParam
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Milestone-A phase-B′ END-TO-END wire: a **source-interpreted** user `@Composable` body drives an
 * **interpreted** composer, calling a library composable through the VM-backed driver — the whole #2 threading in
 * one path. This is the productized form of [ComposePreviewRenderer]'s wiring, but with the composer being an
 * interpreted `VmObject` (from the project runtime) instead of the bridged host composer:
 *
 * - interp-core's `Interpreter` tree-walks a hand-built `Preview` `ResolvedFunction` (as the resolver would lower
 *   real source), wrapped in `ComposeRuntime`'s restart group;
 * - `ComposeRuntime`/`ComposeDispatcher` drive the interpreted composer through `VmComposerOps` (selected because
 *   the composer is VM-owned) — the caller-side + restart groups run via the VM, not host reflection;
 * - the `ProbeComposable` call routes through `ComposeDispatcher` → `VmLibraryExecutor.callComposable`, threading
 *   the `VmObject` composer into the interpreted library composable, which runs its own (interpreted) restart
 *   group and records into a host sink.
 *
 * A recorded `["hi"]` plus a clean composition ("handed") proves the two interpreters thread through one
 * interpreted composer. Only `androidx.compose.runtime` + this spike package are interpreted; the floor is bridged.
 */
class InterpretedSourceComposableSpike {

    private val FIXTURE = "dev.ide.interp.compose.spike.ComposerThreadingSpikeFixture"
    private val PROBES = "dev.ide.interp.compose.spike.ProbesKt"
    private val span = SourceSpan(0, 0)

    @Test fun sourceInterpretedBodyComposesALibraryComposableOnTheInterpretedComposer() {
        val exec = VmLibraryExecutor(
            source = ClassBytesSource.fromClasspath(),
            projectPreferredPrefixes = listOf("androidx.compose.runtime.", "dev.ide.interp.compose.spike."),
        )
        exec.use {
            val dispatcher = ComposeDispatcher(libraryExecutor = exec)
            val runtime = ComposeRuntime(dispatcher)
            val interpreter = Interpreter(emptyMap(), dispatcher, runtime, libraryFallback = exec)

            // The lowered form of: fun Preview(sink) { ProbeComposable("hi", sink) }
            val sinkSlot = SlotId(1)
            val probeCall = RNode.Call(
                ResolvedCallable.Library(
                    "ProbeComposable", PROBES, "ProbeComposable", listOf(null, null),
                    isStatic = true, isConstructor = false, isInline = false, isComposable = true,
                ),
                DispatchKind.TOP_LEVEL, receiver = null,
                args = listOf(
                    RArg(RNode.Const("hi", null, span)),
                    RArg(RNode.Name(Binding.Param(sinkSlot, "sink"), span)),
                ),
                callSiteKey = CallSiteKey(2), source = span,
            )
            val entry = ResolvedFunction(
                "Preview", listOf(RParam(sinkSlot, "sink", null)),
                RNode.Block(listOf(probeCall), false, span), emptyList(),
            )

            val recorded = mutableListOf<String>()
            val sink = Consumer<String> { recorded.add(it) }
            // The driver runs INSIDE the interpreted composition (fed the VmObject composer), replicating
            // ComposePreviewRenderer.Render's core: thread the composer, then interpret the user body under a
            // restart group.
            val driver = Consumer<Any?> { composer ->
                requireNotNull(composer) { "composer handed to host was null" }
                dispatcher.composer = composer
                runtime.invokeComposable(entry.name.hashCode(), restartable = false, force = false, args = emptyList()) {
                    interpreter.call(entry, listOf(sink))
                }
            }
            val result = exec.invokeStatic(FIXTURE, "handComposerToHost", listOf(driver), 0)

            assertEquals("handed", result, "the interpreted composition completed cleanly")
            assertEquals(
                listOf("hi"), recorded,
                "the source-interpreted Preview composed ProbeComposable on the interpreted composer via the VM",
            )
        }
    }
}
