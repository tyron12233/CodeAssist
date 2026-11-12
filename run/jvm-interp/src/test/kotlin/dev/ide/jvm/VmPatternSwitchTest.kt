package dev.ide.jvm

import dev.ide.jvm.fixtures.PatternSwitch
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A pattern `switch` dispatches through a `SwitchBootstraps` call site, which the VM has to answer itself.
 * Every case interprets the fixture and compares with running it for real.
 */
class VmPatternSwitchTest {

    private val vm = Vm()
    private val owner = "dev/ide/jvm/fixtures/PatternSwitch"

    private fun call(name: String, arg: Int): Any? = vm.invokeStatic(owner, name, "(I)Ljava/lang/String;", listOf(arg))

    @Test fun typePatternsNullAndDefault() {
        for (which in 0..10) assertEquals(PatternSwitch.byType(which), call("byType", which), "byType($which)")
    }

    @Test fun guardsRestartAtTheNextLabel() {
        for (which in listOf(0, 1, 3, 4, 5, 6, 7, 8, 9, 10)) {
            assertEquals(PatternSwitch.guarded(which), call("guarded", which), "guarded($which)")
        }
    }

    @Test fun enumSelectorWithConstantsAndPatterns() {
        for (ordinal in -1..2) assertEquals(PatternSwitch.enumMixed(ordinal), call("enumMixed", ordinal), "enumMixed($ordinal)")
    }

    @Test fun qualifiedEnumConstantLabels() {
        for (which in listOf(0, 1, 6, 10)) {
            assertEquals(PatternSwitch.qualifiedConstant(which), call("qualifiedConstant", which), "qualifiedConstant($which)")
        }
    }

    @Test fun stringAndIntegerConstantLabels() {
        for (which in listOf(0, 1, 7, 8, 10)) {
            assertEquals(
                PatternSwitch.stringAndIntConstants(which), call("stringAndIntConstants", which),
                "stringAndIntConstants($which)",
            )
        }
    }
}
