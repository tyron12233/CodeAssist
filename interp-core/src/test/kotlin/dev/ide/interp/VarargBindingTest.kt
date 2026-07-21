package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A source function's `vararg` parameter must pack the trailing arguments into an array-like value (the
 * interpreter models it as a List). Regression (the reported preview crash
 * "IllegalArgumentException: Not an array: class dev.ide.interp.SourceObject"): `bindParams` bound the vararg
 * slot to a single argument, so inside the body `xs` was one element — a `SourceObject` for a source-class
 * argument — and any array use of it (`xs.size`, `xs[i]`, `for (x in xs)`, passing it on) failed; on ART a
 * real array op reported "Not an array: …SourceObject".
 */
class VarargBindingTest {

    private fun run(code: String) = runProgram(code, "f/0", emptyList())

    @Test fun varargSizeCountsAllTrailingArgs() {
        val code = "package demo\nfun many(vararg xs: Int): Int = xs.size\nfun f(): Int = many(1, 2, 3)"
        assertEquals(3, run(code))
    }

    @Test fun varargWithNoArgumentsIsEmpty() {
        val code = "package demo\nfun many(vararg xs: Int): Int = xs.size\nfun f(): Int = many()"
        assertEquals(0, run(code))
    }

    @Test fun varargIsIndexable() {
        val code = "package demo\nfun firstOf(vararg xs: Int): Int = xs[0]\nfun f(): Int = firstOf(10, 20, 30)"
        assertEquals(10, run(code))
    }

    @Test fun varargIsIterableWithForIn() {
        val code = """
            package demo
            fun sumAll(vararg xs: Int): Int { var s = 0; for (x in xs) s = s + x; return s }
            fun f(): Int = sumAll(1, 2, 3, 4)
        """.trimIndent()
        assertEquals(10, run(code))
    }

    @Test fun varargSupportsForEach() {
        val code = """
            package demo
            fun sumAll(vararg xs: Int): Int { var s = 0; xs.forEach { s = s + it }; return s }
            fun f(): Int = sumAll(2, 3, 4)
        """.trimIndent()
        assertEquals(9, run(code))
    }

    @Test fun varargOfSourceObjectsIsIterable() {
        // The exact reported shape: source-class instances (`SourceObject`s) passed as a vararg and iterated.
        val code = """
            package demo
            class Item(val name: String)
            fun names(vararg items: Item): String { var s = ""; for (i in items) s = s + i.name; return s }
            fun f(): String = names(Item("Espresso"), Item("Cappuccino"))
        """.trimIndent()
        assertEquals("EspressoCappuccino", run(code))
    }

    @Test fun fixedParameterBeforeVarargStillBinds() {
        val code = """
            package demo
            fun tag(prefix: String, vararg xs: Int): String { var s = prefix; for (x in xs) s = s + x; return s }
            fun f(): String = tag("n=", 1, 2, 3)
        """.trimIndent()
        assertEquals("n=123", run(code))
    }
}
