package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Secondary constructors on PROJECT-SOURCE classes. The resolver lowers each `constructor(…) : this(…)/super(…)`
 * into a [dev.ide.lang.kotlin.interp.SecondaryCtor]; the interpreter selects one by arity when the call can't
 * fit the primary, binds its parameters, runs the delegation (a `this(…)` runs the primary — params + init
 * steps), then the constructor body. Same-arity overloads collide on the declId key, so selection is arity-
 * based and best-effort; the primary is preferred whenever the arguments fit it.
 */
class SecondaryConstructorTest {

    @Test fun delegatesToPrimaryWithComputedArgs() {
        val code = """
            package demo
            class Point(val x: Int, val y: Int) { constructor(v: Int) : this(v, v) }
            fun f(): Int { val p = Point(5); return p.x + p.y }
        """.trimIndent()
        assertEquals(10, runProgram(code, "f/0", emptyList()))
    }

    @Test fun secondaryBodyRunsAfterDelegation() {
        val code = """
            package demo
            class Box(val a: Int) {
                var tag: String = "primary"
                constructor(a: Int, label: String) : this(a) { tag = label }
            }
            fun f(): String { val b = Box(1, "secondary"); return b.tag }
        """.trimIndent()
        assertEquals("secondary", runProgram(code, "f/0", emptyList()))
    }

    @Test fun primaryStillChosenWhenArgsFit() {
        val code = """
            package demo
            class Point(val x: Int, val y: Int) { constructor(v: Int) : this(v, v) }
            fun f(): Int { val p = Point(3, 7); return p.x * 10 + p.y }
        """.trimIndent()
        assertEquals(37, runProgram(code, "f/0", emptyList()))
    }

    @Test fun delegationArgsReferenceSecondaryParams() {
        val code = """
            package demo
            class Rect(val w: Int, val h: Int) {
                val area: Int get() = w * h
                constructor(side: Int) : this(side * 2, side + 1)
            }
            fun f(): Int = Rect(4).area
        """.trimIndent()
        // w = 4*2 = 8, h = 4+1 = 5, area = 40
        assertEquals(40, runProgram(code, "f/0", emptyList()))
    }
}
