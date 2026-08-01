package dev.ide.interp.compose.spike

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
}
