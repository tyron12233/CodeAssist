package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smart-casts: after `if (x is T)` / `when (x) { is T -> }` the lowerer narrows `x` to `T` so its `T`-only
 * members RESOLVE (without narrowing, `x.memberOfT` would be unresolved → the whole function rejected). The
 * interpreter then dispatches on the runtime class as usual.
 */
class SmartCastTest {

    @Test fun thenBranchNarrowsToTheCheckedType() {
        // `x.length` only resolves if `x` is narrowed from `Any` to `String` inside the `is` branch.
        val code = "package demo\nfun f(x: Any): Int = if (x is String) x.length else -1"
        assertEquals(5, runProgram(code, "f/1", listOf("hello")))
        assertEquals(-1, runProgram(code, "f/1", listOf(42)))
    }

    @Test fun negatedIsNarrowsTheElseBranch() {
        // `if (x !is String) … else x.length` — the narrowing holds in the ELSE branch.
        val code = "package demo\nfun f(x: Any): Int = if (x !is String) -1 else x.length"
        assertEquals(3, runProgram(code, "f/1", listOf("abc")))
        assertEquals(-1, runProgram(code, "f/1", listOf(7)))
    }

    @Test fun whenIsBranchNarrowsTheSubject() {
        val code = """
            package demo
            fun f(x: Any): Int = when (x) {
                is String -> x.length
                else -> -1
            }
        """.trimIndent()
        assertEquals(4, runProgram(code, "f/1", listOf("four")))
        assertEquals(-1, runProgram(code, "f/1", listOf(true)))
    }

    @Test fun andChainNarrowsAcrossConjuncts() {
        // The then-branch narrows `x` to String (the `&&` RHS `x.length > 0` also needs it, but we only assert
        // the body here): `x.length` resolves in the body because `x is String` is a true-conjunct.
        val code = "package demo\nfun f(x: Any): Int = if (x is String && x.isNotEmpty()) x.length else -1"
        assertEquals(2, runProgram(code, "f/1", listOf("hi")))
        assertEquals(-1, runProgram(code, "f/1", listOf("")))
        assertEquals(-1, runProgram(code, "f/1", listOf(0)))
    }

    @Test fun memberCallOnANarrowedSourceClass() {
        // A member CALL (resolved through the resolver's callee selection, not just property inference) on a
        // narrowed SOURCE class: `a.bark()` resolves only when `a` is narrowed from `Animal` to `Dog`.
        val code = """
            package demo
            open class Animal
            class Dog : Animal() { fun bark(): String = "woof" }
            fun f(): String {
                val a: Animal = Dog()
                return if (a is Dog) a.bark() else "?"
            }
        """.trimIndent()
        assertEquals("woof", runProgram(code, "f/0", emptyList()))
    }
}
