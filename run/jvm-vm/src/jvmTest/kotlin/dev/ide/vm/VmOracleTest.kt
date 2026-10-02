package dev.ide.vm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Each fixture, run for real on this JVM and then through the VM: the two must agree, and the recorded
 * expectation (which the simulator run is held to) must be what the real call returns.
 */
class VmOracleTest {

    private fun real(case: Case): Any? {
        val cls = Class.forName(case.owner.replace('/', '.'))
        val params = parameterDescriptors(case.descriptor).map { d ->
            when (d) {
                "I" -> Int::class.javaPrimitiveType!!
                "J" -> Long::class.javaPrimitiveType!!
                "Z" -> Boolean::class.javaPrimitiveType!!
                "D" -> Double::class.javaPrimitiveType!!
                else -> Class.forName(d.substring(1, d.length - 1).replace('/', '.'))
            }
        }
        return cls.getMethod(case.name, *params.toTypedArray()).invoke(null, *case.args.toTypedArray())
    }

    @Test
    fun everyFixtureMatchesTheRealRun() {
        val failures = ArrayList<String>()
        for (case in vmCases) {
            val expected = real(case)
            if (case.expected != null && case.expected != expected) {
                failures.add("$case: recorded expectation ${case.expected} but the real call returns $expected")
            }
            val vm = newTestVm()
            val actual = try {
                vm.invokeStatic(case.owner, case.name, case.descriptor, *case.args.toTypedArray())
            } catch (e: Throwable) {
                failures.add("$case: VM threw ${e::class.simpleName}: ${e.message}")
                continue
            }
            if (actual != expected) failures.add("$case:\n  real: $expected\n  vm:   $actual")
            else println("OK $case = $actual (${vm.steps} steps, ${vm.loadedClassCount} classes)")
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun realValuesForTheRecord() {
        for (case in vmCases) println("REAL $case = ${real(case)}")
        assertEquals(true, true)
    }
}
