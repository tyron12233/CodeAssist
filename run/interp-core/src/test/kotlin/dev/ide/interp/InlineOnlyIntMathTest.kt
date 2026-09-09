package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/** `Int.floorDiv`/`mod` are `@InlineOnly` stdlib functions (no JVM method to reflect on); JetNews's
 *  `InterestsAdaptiveContentLayout` computes `index.floorDiv(columns)` inside its measure lambda. */
class InlineOnlyIntMathTest {
    @Test
    fun floorDivAndModOnInts() {
        assertEquals(3, runProgram("fun use(): Int = 7.floorDiv(2)", "use/0", emptyList()))
        assertEquals(-4, runProgram("fun use(): Int = (-7).floorDiv(2)", "use/0", emptyList()))
        assertEquals(1, runProgram("fun use(): Int = 7.mod(2)", "use/0", emptyList()))
        assertEquals(1, runProgram("fun use(): Int = (-7).mod(2)", "use/0", emptyList()))
    }
}
