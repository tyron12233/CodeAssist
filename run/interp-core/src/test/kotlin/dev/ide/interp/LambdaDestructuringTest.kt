package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A destructuring lambda parameter (`forEach { (_, name, _) -> }`, `map { (k, v) -> }`) must bind its entries
 * to `componentN()` reads of the argument. Regression (the reported Compose-preview failure): the lowerer
 * bound only a single nameless parameter, so a use of a destructured entry (`name`) fell back to a bare object
 * reference and the interpreter failed at render with
 * "cannot load `name` (a project-source object isn't available to the interpreter)". The equivalent
 * property-access form (`coffees.forEach { c -> c.second }`) worked, matching the report.
 */
class LambdaDestructuringTest {

    @Test fun forEachDestructuresTripleMiddleEntry() {
        // The reported shape via the inline `forEach` intrinsic: destructure a Triple and use the middle entry.
        val code = """
            package demo
            fun f(): Int {
                var sum = 0
                listOf(Triple(1, 10, 100), Triple(2, 20, 200)).forEach { (_, count, _) -> sum = sum + count }
                return sum
            }
        """.trimIndent()
        assertEquals(30, runProgram(code, "f/0", emptyList()))
    }

    @Test fun destructuringReadsEntryByPositionNotByIgnoredCount() {
        // `(_, _, third)` must read component3 — the ignored leading entries must not shift the index.
        val code = """
            package demo
            fun f(): Int {
                var acc = 0
                listOf(Triple(1, 2, 3)).forEach { (_, _, third) -> acc = third }
                return acc
            }
        """.trimIndent()
        assertEquals(3, runProgram(code, "f/0", emptyList()))
    }

    @Test fun reportedTripleNameDestructuringYieldsTheRenderedValues() {
        // The exact reported code, reduced to the values a `Text(text = name)` would render (via `map`, which
        // runs through the reflective higher-order-function path — proving the reads fire there too).
        val code = """
            package demo
            fun f(): List<String> {
                val coffees = listOf(Triple("1", "Espresso", 3.50), Triple("2", "Cappuccino", 4.50))
                return coffees.map { (_, name, _) -> name }
            }
        """.trimIndent()
        assertEquals(listOf("Espresso", "Cappuccino"), runProgram(code, "f/0", emptyList()))
    }

    @Test fun propertyAccessFormStillWorks() {
        // The form the report said already worked — a guard that the non-destructuring path is unaffected.
        val code = """
            package demo
            fun f(): List<String> =
                listOf(Triple("1", "Espresso", 3.50), Triple("2", "Cappuccino", 4.50)).map { c -> c.second }
        """.trimIndent()
        assertEquals(listOf("Espresso", "Cappuccino"), runProgram(code, "f/0", emptyList()))
    }
}
