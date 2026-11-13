package dev.ide.jvm

import dev.ide.jvm.fixtures.Semantics
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/**
 * Runs every [Semantics] fixture natively and in the VM and expects the same answer. Each is a point of the
 * language the interpreter once diverged on: IEEE comparison of `-0.0`, the identity of an array platform code
 * keeps, array type checks and store checks, errors the program must be able to catch, string-constant
 * identity, shadowed fields, and the class name a default `toString()` prints.
 */
class VmSemanticsTest {

    @TestFactory fun matchesTheRealRuntime(): List<DynamicTest> =
        Semantics::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && it.parameterCount == 0 }
            .sortedBy { it.name }
            .map { m ->
                DynamicTest.dynamicTest(m.name) {
                    val expected = onDeepStack { m.invoke(null) }
                    val actual = onDeepStack {
                        Vm().invokeStatic("dev/ide/jvm/fixtures/Semantics", m.name, "()Ljava/lang/String;", emptyList())
                    }
                    assertEquals(expected, actual)
                }
            }

    /** The runaway-recursion fixture needs the same generous stack on both sides, so that the VM's own frames
     *  (several host frames per interpreted call) decide nothing. */
    private fun onDeepStack(body: () -> Any?): Any? {
        var result: Result<Any?>? = null
        val t = Thread(null, { result = runCatching(body) }, "semantics", 256L * 1024 * 1024)
        t.start()
        t.join()
        return result!!.getOrThrow()
    }
}
