package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Callable references (`::foo`, `obj::method`) — desugared to a lambda that forwards its arguments to the
 * target — together with invoking a function value (`fn(x)` where `fn` is a function-typed local/param,
 * `DispatchKind.INVOKE`). A reference passed to a higher-order function (source OR library) is then invoked.
 */
class FunctionReferenceTest {

    @Test fun topLevelFunctionReferenceInvokedByASourceHigherOrderFunction() {
        val code = """
            package demo
            fun double(n: Int): Int = n * 2
            fun apply2(fn: (Int) -> Int, x: Int): Int = fn(x)
            fun f(): Int = apply2(::double, 5)
        """.trimIndent()
        assertEquals(10, runProgram(code, "f/0", emptyList()))
    }

    @Test fun boundMemberReferenceOnASourceObject() {
        val code = """
            package demo
            class Calc { fun add(n: Int): Int = n + 10 }
            fun apply2(fn: (Int) -> Int, x: Int): Int = fn(x)
            fun f(): Int {
                val c = Calc()
                return apply2(c::add, 5)
            }
        """.trimIndent()
        assertEquals(15, runProgram(code, "f/0", emptyList()))
    }

    @Test fun nullaryReferenceIsInvokable() {
        val code = """
            package demo
            fun greeting(): String = "hi"
            fun callIt(producer: () -> String): String = producer()
            fun f(): String = callIt(::greeting)
        """.trimIndent()
        assertEquals("hi", runProgram(code, "f/0", emptyList()))
    }

    @Test fun referencePassedToALibraryHigherOrderFunction() {
        // The common callback path: a reference handed to a library HOF, invoked through the lambda proxy.
        val code = """
            package demo
            fun double(n: Int): Int = n * 2
            fun f(): List<Int> = listOf(1, 2, 3).map(::double)
        """.trimIndent()
        assertEquals(listOf(2, 4, 6), runProgram(code, "f/0", emptyList()))
    }

    @Test fun invokingAFunctionTypedParameterDirectly() {
        // Not a reference — a plain lambda invoked via `fn(x)` (DispatchKind.INVOKE), which references need too.
        val code = """
            package demo
            fun apply2(fn: (Int) -> Int, x: Int): Int = fn(x)
            fun f(): Int = apply2({ n -> n * 2 }, 5)
        """.trimIndent()
        assertEquals(10, runProgram(code, "f/0", emptyList()))
    }
}
