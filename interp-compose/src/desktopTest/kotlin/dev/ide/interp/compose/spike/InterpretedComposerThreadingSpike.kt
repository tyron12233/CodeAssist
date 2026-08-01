package dev.ide.interp.compose.spike

import dev.ide.interp.compose.VmComposerOps
import dev.ide.interp.compose.VmLibraryExecutor
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.Vm
import dev.ide.jvm.isVmPeer
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Milestone-A phase-B′ (the two-interpreter threading) — bootstrapping proof. The too-new preview keeps the user
 * `@Preview` source-interpreted and threads an INTERPRETED composer produced by the project's own runtime. The
 * first thing that must hold: HOST code (the future VM-backed `ComposableAbi`/`ComposeRuntime`) can obtain the
 * interpreted composer out of an interpreted composition. Here an interpreted composition hands its
 * `currentComposer` to a host callback, and we verify the received value is a `VmObject` (the interpreted
 * composer), not a host `Composer` — the seam the source-interpreter side threads.
 */
class InterpretedComposerThreadingSpike {

    private val OWNER = "dev/ide/interp/compose/spike/ComposerThreadingSpikeFixture"

    private fun newVm() = Vm(policy = InterpretPolicy { name ->
        name.startsWith("androidx/compose/runtime/") ||
            name.startsWith("dev/ide/interp/compose/spike/")
    })

    @Test fun hostCapturesTheInterpretedComposer() {
        var captured: Any? = null
        val sink = Consumer<Any?> { captured = it }
        val result = newVm().invokeStatic(
            OWNER, "handComposerToHost", "(Ljava/util/function/Consumer;)Ljava/lang/String;", listOf(sink),
        )
        assertEquals("handed", result, "the interpreted composition pass completed")
        assertNotNull(captured, "the interpreted composition handed its currentComposer to host code")
        assertTrue(
            isVmPeer(captured),
            "the captured composer is an interpreted VmObject (the project-runtime composer), not a host Composer",
        )
    }

    /** FQN form of [OWNER] for the [VmLibraryExecutor] path (which resolves by dotted name). */
    private val OWNER_FQN = "dev.ide.interp.compose.spike.ComposerThreadingSpikeFixture"

    @Test fun hostDrivesTheInterpretedComposerGroupProtocolViaTheVm() {
        // The core of the VM-backed ComposableAbi: instead of host reflection on `composer.javaClass`, drive the
        // interpreted composer's group ops THROUGH the VM (`invokeInstance`). If a group is opened but not closed
        // (or the slot table desyncs), `composeInitial` throws a Start/end imbalance and the fixture never returns
        // "handed" — so a clean "handed" is the proof the VM-driven caller-side group balanced.
        val exec = VmLibraryExecutor(
            source = ClassBytesSource.fromClasspath(),
            projectPreferredPrefixes = listOf("androidx.compose.runtime.", "dev.ide.interp.compose.spike."),
        )
        exec.use {
            var drove = false
            val sink = Consumer<Any?> { composer ->
                requireNotNull(composer) { "composer handed to host was null" }
                exec.invokeInstance(composer, "startReplaceGroup", listOf(0x51A17), 0)
                exec.invokeInstance(composer, "endReplaceGroup", emptyList(), 0)
                drove = true
            }
            val result = exec.invokeStatic(OWNER_FQN, "handComposerToHost", listOf(sink), 0)
            assertTrue(drove, "the host callback ran and drove the composer group ops via the VM")
            assertEquals("handed", result, "the interpreted composition completed cleanly — the VM-driven group balanced")
        }
    }

    @Test fun vmComposerOpsDrivesAFullRestartCycleOnTheInterpretedComposer() {
        // The productized VM-backed ComposableAbi: VmComposerOps drives the full protocol the interpreter emits —
        // a caller-side replace group (per library call) and a restart group (per source-composable body, with the
        // $changed skip fast path and scope registration) — on an interpreted composer, all through the VM. If any
        // op desyncs the slot table, composeInitial throws a Start/end imbalance and the fixture never returns
        // "handed". This is the same driver ComposeRuntime/ComposeDispatcher now select via opsFor.
        val exec = VmLibraryExecutor(
            source = ClassBytesSource.fromClasspath(),
            projectPreferredPrefixes = listOf("androidx.compose.runtime.", "dev.ide.interp.compose.spike."),
        )
        exec.use {
            val ops = VmComposerOps(exec)
            val log = mutableListOf<String>()
            val sink = Consumer<Any?> { composer ->
                requireNotNull(composer) { "composer handed to host was null" }
                assertTrue(exec.ownsComposer(composer), "the handed composer is a VM-owned interpreted Composer")
                // A caller-side replace group (where a library composable call would sit).
                val marker = ops.currentMarker(composer)
                ops.startGroup(composer, 0xA1)
                ops.endGroup(composer)
                // A restart group (a source-composable body): open, run the $changed skip fast path, close, register.
                val group = ops.startRestartGroup(composer, 0xB2)
                val changed = ops.argsChanged(group, listOf("x"))
                val skipping = ops.isSkipping(group)
                val scope = ops.endRestartGroup(group)
                ops.updateScope(scope) { /* recomposition is phase B′.2 */ }
                log.add("marker=$marker;changed=$changed;skipping=$skipping")
            }
            val result = exec.invokeStatic(OWNER_FQN, "handComposerToHost", listOf(sink), 0)
            assertEquals("handed", result, "composition balanced after a full VM-driven restart cycle")
            assertEquals(1, log.size, "the full op cycle ran exactly once")
        }
    }
}
