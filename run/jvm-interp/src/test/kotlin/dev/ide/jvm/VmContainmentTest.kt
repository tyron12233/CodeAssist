package dev.ide.jvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a preview host turns on so one broken interpreted class cannot take the frame down: a step budget for a
 * runaway loop, containment at the peer boundary (a failed override falls back to the real superclass method),
 * and constant answers for platform methods such as `isInEditMode()`.
 */
class VmContainmentTest {

    private val PEERS = "dev/ide/jvm/fixtures/Peers"
    private val bridgeHost = InterpretPolicy { name -> !name.startsWith("dev/ide/jvm/host/") && InterpretPolicy.DEFAULT.interpret(name) }

    @Test
    fun aStepBudgetStopsARunawayLoop() {
        val vm = Vm(policy = bridgeHost)
        val e = assertFailsWith<VmStepBudgetException> { vm.withStepBudget(50_000) { vm.invokeStatic(PEERS, "spinForever", "()I") } }
        assertEquals(50_000, e.budget)
        // The budget is lifted afterwards: the same thread runs on.
        assertEquals(2, vm.invokeStatic("dev/ide/jvm/fixtures/Peers", "describeEditModeProbe", "()I").let { (it as Int) / 100 })
    }

    @Test
    fun aContainedOverrideFallsBackToTheRealSuperclassMethod() {
        val failures = ArrayList<String>()
        val vm = Vm(
            policy = bridgeHost,
            peerFailure = PeerFailurePolicy { _, cls, method, e -> failures += "$cls.$method: ${e.message}" },
        )
        // The real label() calls kind() through the peer; kind() throws, so the real Shape.kind() runs instead
        // (calling the working sides() override).
        assertEquals("[shape:3]", vm.invokeStatic(PEERS, "labelOfBrokenKind", "()Ljava/lang/String;"))
        assertTrue(failures.single().contains("BrokenKind.kind"), failures.toString())
    }

    @Test
    fun aContainedRunawayOverrideIsStoppedAndDegrades() {
        val failures = ArrayList<Throwable>()
        val vm = Vm(
            policy = bridgeHost,
            peerFailure = PeerFailurePolicy { _, _, _, e -> failures += e },
            peerStepBudget = 100_000,
        )
        // sides() spins; its abstract super has no implementation, so it degrades to 0 and describe() is 0.
        assertEquals(0, vm.invokeStatic(PEERS, "describeSpinner", "()I"))
        assertTrue(failures.single() is VmStepBudgetException)
    }

    @Test
    fun aConstantMethodAnswersForThePlatform() {
        assertEquals(200, Vm(policy = bridgeHost).invokeStatic(PEERS, "describeEditModeProbe", "()I"))
        val editMode = Vm(policy = bridgeHost, constantMethods = mapOf("isInEditMode()Z" to true))
        assertEquals(100, editMode.invokeStatic(PEERS, "describeEditModeProbe", "()I"))
    }
}
