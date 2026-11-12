package dev.ide.jvm

import dev.ide.jvm.fixtures.Java21
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Java 17 to 21 features a program can use ([Java21], one method each): records and their generated
 * methods, sealed types, pattern matching, the newer library calls. Every method is interpreted and compared
 * with running it for real.
 *
 * Each also runs with the platform stand-ins forced on ([Vm.installPlatformStandIns]), which is the state of a
 * device older than `java.lang.Record` and `java.lang.MatchException`: there the VM interprets those two itself.
 * What reflects over a record's components is left out of that run, since such a runtime has no
 * `RecordComponent` to answer with.
 *
 * And each runs once more with the library backports preferred, which checks every stand-in against the real
 * method it replaces.
 */
class VmJava21Test {

    private val owner = "dev/ide/jvm/fixtures/Java21"

    private val features: List<Method> = Java21::class.java.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && it.parameterCount == 0 && it.returnType == String::class.java }
        .sortedBy { it.name }

    private fun interpreted(vm: Vm, m: Method): Any? = vm.invokeStatic(owner, m.name, "()Ljava/lang/String;", emptyList<Any?>())

    @TestFactory
    fun everyFeatureMatchesTheRealJvm(): List<DynamicTest> = features.map { m ->
        DynamicTest.dynamicTest(m.name) { assertEquals(m.invoke(null), interpreted(Vm(), m), m.name) }
    }

    @TestFactory
    fun everyFeatureRunsWhereThePlatformHasNoRecordOrMatchException(): List<DynamicTest> =
        features.filter { it.name !in NEEDS_RECORD_COMPONENTS }.map { m ->
            DynamicTest.dynamicTest(m.name) {
                val vm = Vm().also { it.installPlatformStandIns(force = true) }
                assertEquals(m.invoke(null), interpreted(vm, m), m.name)
            }
        }

    /** The same programs with every [HostBackports] entry preferred over the host's own method: the state of a
     *  runtime that lacks them, where the stand-in is all there is. */
    @TestFactory
    fun everyFeatureMatchesWithTheLibraryBackportsInPlace(): List<DynamicTest> = features.map { m ->
        DynamicTest.dynamicTest(m.name) {
            HostBackports.preferBackports = true
            try {
                assertEquals(m.invoke(null), interpreted(Vm(), m), m.name)
            } finally {
                HostBackports.preferBackports = false
            }
        }
    }

    /** Past the cap an emulated virtual thread refuses to start, and a finished one gives its slot back. */
    @Test
    fun emulatedVirtualThreadsAreCapped() {
        val before = EmulatedThreads.maxLive
        EmulatedThreads.maxLive = 2
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val held = List(2) { EmulatedThreads.newVirtual({ release.await() }, "held-$it").also { t -> t.start() } }
            val refused = assertFailsWith<UnsupportedOperationException> { EmulatedThreads.newVirtual({}, null).start() }
            assertTrue("emulated" in refused.message!!, refused.message)
            release.countDown()
            held.forEach { it.join() }
            EmulatedThreads.newVirtual({}, null).also { it.start() }.join()
        } finally {
            release.countDown()
            EmulatedThreads.maxLive = before
        }
    }

    private companion object {
        val NEEDS_RECORD_COMPONENTS = setOf("recordReflection", "recordAccessorReflection")
    }
}
