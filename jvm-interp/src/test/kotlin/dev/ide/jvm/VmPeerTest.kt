package dev.ide.jvm

import dev.ide.jvm.fixtures.Peers
import dev.ide.jvm.host.Shape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real-to-interpreted path: an interpreted class that extends a real supertype or implements a real
 * interface is realized as a generated peer, so platform code can hold and invoke it and the calls run in the
 * interpreter. The host package is configured as bridged so the fixtures' real base class is not interpreted.
 * Each case is checked against the same construct compiled and run for real.
 */
class VmPeerTest {

    private val vm = Vm(policy = InterpretPolicy { name ->
        !name.startsWith("dev/ide/jvm/host/") && InterpretPolicy.DEFAULT.interpret(name)
    })

    private val PEERS = "dev/ide/jvm/fixtures/Peers"

    @Test fun realTemplateMethodCallsInterpretedOverride() {
        // Shape.describe() (real) calls sides() (interpreted override) -> 3 * 100.
        assertEquals(Peers.describeTriangle(), vm.invokeStatic(PEERS, "describeTriangle", "()I"))
    }

    @Test fun interpretedSuperCallReachesRealImplementation() {
        // Square.describe() (interpreted) calls super.describe() (real) + 1.
        assertEquals(Peers.describeSquare(), vm.invokeStatic(PEERS, "describeSquare", "()I"))
    }

    @Test fun platformCodeDrivesReturnedInterpretedObject() {
        val shape = vm.invokeStatic(PEERS, "makeSquare", "()Ldev/ide/jvm/host/Shape;") as Shape
        val expected = Peers.makeSquare()
        assertEquals(expected.describe(), shape.describe(), "describe (override calling super)")
        assertEquals(expected.sides(), shape.sides(), "sides (override)")
        assertEquals(expected.kind(), shape.kind(), "kind (real method calling the override)")
    }

    @Test fun interpretedInterfaceImplPassedToPlatformApi() {
        for (n in listOf(0, 1, 5, 10)) {
            assertEquals(Peers.mapDoubled(n), vm.invokeStatic(PEERS, "mapDoubled", "(I)I", listOf(n)), "mapDoubled($n)")
        }
    }

    @Test fun interfaceOnlyPeerIsAProxyNotAGeneratedClass() {
        val op = vm.invokeStatic(PEERS, "doubler", "()Ljava/util/function/IntUnaryOperator;")
            as java.util.function.IntUnaryOperator
        // An interface-only peer is a JDK Proxy — no class is generated or loaded (on Android, no dex),
        // which is the point of this path for Play dynamic-code compliance.
        assertTrue(java.lang.reflect.Proxy.isProxyClass(op.javaClass), "expected a Proxy, got ${op.javaClass}")
        assertEquals(14, op.applyAsInt(7), "the interpreted override runs through the proxy handler")
        // A non-overridden default method (andThen) runs its real body via invokeDefault, and its self-call
        // back to applyAsInt routes through the handler to the interpreted body: (7*2) then +1 = 15.
        assertEquals(15, op.andThen { it + 1 }.applyAsInt(7), "a non-overridden default composes with the interpreted body")
    }

    @Test fun realSuperConstructorCallsInterpretedOverrideDuringConstruction() {
        // Eager's real constructor calls tag() while the interpreted subclass is still inside super() — its
        // field initializers haven't run, exactly like a real Java subclass at that point.
        assertEquals(Peers.eagerGreeting(), vm.invokeStatic(PEERS, "eagerGreeting", "()Ljava/lang/String;"))
    }

    @Test fun interpretedCtorArgsThreadedToRealSuper() {
        // The real Widget(name, size) constructor has no no-arg form; label() reads the state it set plus the
        // interpreted render() override.
        for (name in listOf("ok", "start")) {
            assertEquals(Peers.makeButtonLabel(name), vm.invokeStatic(PEERS, "makeButtonLabel", "(Ljava/lang/String;)Ljava/lang/String;", listOf(name)), "label($name)")
        }
    }

    @Test fun interpretedArrayPassedToPlatformApi() {
        for (n in listOf(0, 1, 6)) assertEquals(Peers.sumViaStream(n), vm.invokeStatic(PEERS, "sumViaStream", "(I)I", listOf(n)), "sumViaStream($n)")
    }

    @Test fun platformArrayUsedByInterpretedCode() {
        for (s in listOf("a,bb,ccc", "solo", "")) {
            assertEquals(Peers.splitAndMeasure(s), vm.invokeStatic(PEERS, "splitAndMeasure", "(Ljava/lang/String;)I", listOf(s)), "split($s)")
        }
    }

    @Test fun instanceofAgainstRealInterfaceThroughBridgedSuper() {
        // Triangle extends the real Shape, which implements the real Sized interface; the check resolves via the
        // real supertype even though Triangle declares neither.
        assertEquals(Peers.triangleIsSized(), (vm.invokeStatic(PEERS, "triangleIsSized", "()Z") as Int) != 0)
    }

    @Test fun inheritedRealSuperStaticFieldReadThroughSubclass() {
        // Triangle reads STATE_SET (a static int[]) and BASE (a static int) declared on the real Shape super,
        // via getstatics javac emits with Triangle (interpreted) as the owner. The interpreted chain declares
        // neither, so the VM must read them from the real super instead of returning null (the null array is
        // what broke android.view.View.EMPTY_STATE_SET for BottomNavigationView in the layout preview).
        assertEquals(Peers.triangleStateSum(), vm.invokeStatic(PEERS, "triangleStateSum", "()I"))
    }

    @Test fun realSuperProtectedFieldReadAndWritten() {
        // Tally writes and reads Counter.count (a protected real-super field), and real Counter.report() reads it.
        for (start in listOf(0, 3, 10)) {
            assertEquals(Peers.tallyReport(start), vm.invokeStatic(PEERS, "tallyReport", "(I)I", listOf(start)), "tallyReport($start)")
        }
    }
}
