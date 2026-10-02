package dev.ide.vm

import kotlin.test.Test
import kotlin.test.fail

/** The fixtures through the VM, held to the values the JVM oracle confirmed. Runs on every platform. */
class VmCasesTest {
    @Test
    fun everyFixtureReturnsWhatTheJvmReturned() {
        val failures = ArrayList<String>()
        for (case in vmCases) {
            val vm = newTestVm()
            val actual = try {
                vm.invokeStatic(case.owner, case.name, case.descriptor, *case.args.toTypedArray())
            } catch (e: Throwable) {
                failures.add("$case: VM threw ${e::class.simpleName}: ${e.message}")
                continue
            }
            if (actual != case.expected) failures.add("$case:\n  expected: ${case.expected}\n  vm:       $actual")
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }
}
