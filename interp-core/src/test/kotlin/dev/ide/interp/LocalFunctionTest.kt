package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Local function declarations (`fun helper() { … }` inside a block) are lowered as a slot-bound closure flagged
 * as a local function, so the interpreter treats a `return` in the body as a LOCAL return (unlike a lambda's
 * non-local return). Covers parameters, self-recursion, closure capture of an enclosing `var`, and an early
 * `return` from a block body.
 */
class LocalFunctionTest {

    private fun eval(body: String): Any? =
        runProgram("package demo\nfun f(): Any? {\n$body\n}", "f/0", emptyList())

    @Test fun expressionBodyWithParams() {
        assertEquals(25, eval("fun square(x: Int) = x * x\nreturn square(5)"))
        assertEquals(7, eval("fun add(a: Int, b: Int) = a + b\nreturn add(3, 4)"))
    }

    @Test fun selfRecursion() {
        assertEquals(55, eval("fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)\nreturn fib(10)"))
    }

    @Test fun capturesEnclosingVar() {
        assertEquals(7, eval("var total = 0\nfun acc(x: Int) { total += x }\nacc(3)\nacc(4)\nreturn total"))
    }

    @Test fun localReturnFromBlockBody() {
        val prog = """
            fun classify(n: Int): String {
                if (n < 0) return "neg"
                if (n == 0) return "zero"
                return "pos"
            }
            return classify(-5) + classify(0) + classify(7)
        """.trimIndent()
        assertEquals("negzeropos", eval(prog))
    }

    @Test fun localReturnDoesNotEscapeEnclosingFunction() {
        // A `return` inside the local function must NOT return from `f` — the value after the call proves `f`
        // kept running past `helper()`.
        assertEquals(99, eval("fun helper(): Int { return 1 }\nhelper()\nreturn 99"))
    }
}
