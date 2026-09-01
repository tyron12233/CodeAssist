package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Local function declarations (`fun helper() { … }` inside a block) are lowered as a slot-bound closure flagged
 * as a local function, so the interpreter treats a `return` in the body as a LOCAL return (unlike a lambda's
 * non-local return). Covers parameters, self-recursion, closure capture of an enclosing `var`, and an early
 * `return` from a block body — plus the call-shape parity a local function shares with a top-level one:
 * declared defaults, `vararg`, named arguments, an extension receiver, and `::` references.
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

    @Test fun omittedArgumentTakesTheDeclaredDefault() {
        // Regression: the closure bound arguments positionally and never consulted the parameter's default, so
        // an omitted defaulted argument silently arrived as null instead of the declared value.
        assertEquals(3, eval("fun g(a: Int = 3) = a\nreturn g()"))
        assertEquals(7, eval("fun g(a: Int = 3) = a\nreturn g(7)"))
        assertEquals(5, eval("fun g(a: Int, b: Int = 4) = a + b\nreturn g(1)"))
    }

    @Test fun namedArgumentsBindByName() {
        // Regression: the invoke path never reordered named arguments, so `g(b = 1, a = 5)` bound positionally
        // and quietly computed 1 - 5.
        assertEquals(4, eval("fun g(a: Int, b: Int) = a - b\nreturn g(b = 1, a = 5)"))
        assertEquals(4, eval("fun g(a: Int = 1, b: Int = 2) = a - b\nreturn g(b = 2, a = 6)"))
    }

    @Test fun varargParameterPacksTrailingArguments() {
        // A `vararg` slot used to take a single value, so `xs.size` failed on a bare Integer.
        assertEquals(3, eval("fun g(vararg xs: Int) = xs.size\nreturn g(1, 2, 3)"))
    }

    @Test fun localExtensionResolvesOnItsReceiver() {
        assertEquals("abab", eval("fun String.twice() = this + this\nreturn \"ab\".twice()"))
        assertEquals("xy!", eval("fun String.tag(s: String) = this + s\nreturn \"x\".tag(\"y\") + \"!\""))
    }

    @Test fun callableReferenceToALocalFunction() {
        // `::g` on a local function IS the closure the slot already holds — no forwarding lambda to synthesize.
        assertEquals(2, eval("fun g(a: Int) = a + 1\nval h: (Int) -> Int = ::g\nreturn h(1)"))
    }

    @Test fun localShadowsASameNamedTopLevelFunction() {
        assertEquals(
            1,
            runProgram("package demo\nfun g(a: Int) = a * 100\nfun f(): Any? {\nfun g() = 1\nreturn g()\n}", "f/0", emptyList()),
        )
    }
}
