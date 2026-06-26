package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Step 3: the tree-walking interpreter executes a plain-Kotlin subset over the lowered [ResolvedTree] — no
 * Compose yet. Proves the value/environment/control-flow core, the primitive-operator intrinsics, and
 * cross-function (source) calls, plus that an unsupported construct fails loudly rather than guessing.
 */
class InterpreterTest {

    @Test
    fun expressionBodyArithmetic() {
        assertEquals(5, runProgram("package demo\nfun add(a: Int, b: Int): Int = a + b", "add/2", listOf(2, 3)))
    }

    @Test
    fun localsAndIntermediateValues() {
        val code = "package demo\nfun f(): Int { val x = 2\n  val y = x + 3\n  return y }"
        assertEquals(5, runProgram(code, "f/0", emptyList()))
    }

    @Test
    fun varReassignment() {
        val code = "package demo\nfun f(): Int { var x = 1\n  x = x + 4\n  return x }"
        assertEquals(5, runProgram(code, "f/0", emptyList()))
    }

    @Test
    fun ifBranchesOnBooleanParam() {
        val code = "package demo\nfun pick(b: Boolean): Int { if (b) return 10\n  return 20 }"
        assertEquals(10, runProgram(code, "pick/1", listOf(true)))
        assertEquals(20, runProgram(code, "pick/1", listOf(false)))
    }

    @Test
    fun crossFunctionSourceCalls() {
        // quad calls dbl twice; dbl is another source function interpreted recursively.
        val code = "package demo\nfun dbl(x: Int): Int = x + x\nfun quad(x: Int): Int = dbl(dbl(x))"
        assertEquals(12, runProgram(code, "quad/1", listOf(3)))
    }

    @Test
    fun mixedNumericPromotion() {
        // Int + Double promotes to Double (the intrinsic operator path).
        val code = "package demo\nfun f(): Double { val a = 2\n  return a * 1.5 }"
        assertEquals(3.0, runProgram(code, "f/0", emptyList()))
    }

    @Test
    fun endToEndReflectiveStdlibCall() {
        // Full pipeline: resolve a stdlib top-level call to its precise `…Kt` facade (a multi-file class —
        // the descriptor work maps it to the public facade `kotlin.collections.CollectionsKt`), then the
        // default ReflectiveDispatcher invokes the real static method.
        val result = runProgram("package demo\nfun f(): List<Int> = emptyList<Int>()", "f/0", emptyList())
        assertTrue(result is List<*> && result.isEmpty(), "expected an empty List; got $result")
    }

    @Test
    fun whileLoopWithComparison() {
        // Exercises `<` (comparison operator) + a while loop + var reassignment.
        val code = "package demo\nfun count(n: Int): Int { var i = 0\n  var c = 0\n  while (i < n) { c = c + 1\n    i = i + 1 }\n  return c }"
        assertEquals(5, runProgram(code, "count/1", listOf(5)))
        assertEquals(0, runProgram(code, "count/1", listOf(0)))
    }

    @Test
    fun forEachOverIterable() {
        // Exercises `for (x in xs)` — the interpreter reflects iterator()/hasNext()/next() on the value.
        val code = "package demo\nfun sum(xs: List<Int>): Int { var s = 0\n  for (x in xs) { s = s + x }\n  return s }"
        assertEquals(6, runProgram(code, "sum/1", listOf(listOf(1, 2, 3))))
    }

    @Test
    fun capturingLambdaPassedToLibraryFunction() {
        // `xs.forEach { s = s + it }` — the lambda is wrapped as a Function1 proxy the stdlib invokes per
        // element; it captures and mutates `s`, and `it` is the implicit parameter.
        val code = "package demo\nfun sum(xs: List<Int>): Int { var s = 0\n  xs.forEach { s = s + it }\n  return s }"
        assertEquals(6, runProgram(code, "sum/1", listOf(listOf(1, 2, 3))))
    }

    @Test
    fun outerVarMutationFromLoopBodyIsVisible() {
        // Per-iteration scoping must NOT break writes to an enclosing `var`: `s = s + x` inside the loop body
        // resolves outward to the function-scope `s` (scope-chained assign), so accumulation still works.
        val code = "package demo\nfun sum(xs: List<Int>): Int { var s = 0\n  for (x in xs) { s = s + x }\n  return s }"
        assertEquals(6, runProgram(code, "sum/1", listOf(listOf(1, 2, 3))))
    }

    @Test
    fun equalityOperator() {
        val code = "package demo\nfun eq(a: Int, b: Int): Boolean = a == b"
        assertEquals(true, runProgram(code, "eq/2", listOf(2, 2)))
        assertEquals(false, runProgram(code, "eq/2", listOf(2, 3)))
    }

    @Test
    fun stringInterpolation() {
        // `"…$x…${e}…"` desugars to a concat; each part is stringified.
        val code = "package demo\nfun greet(name: String, n: Int): String = \"Hi \$name, you have \${n + 1} msgs\""
        assertEquals("Hi Ann, you have 4 msgs", runProgram(code, "greet/2", listOf("Ann", 3)))
    }

    @Test
    fun stringConcatenationWithPlus() {
        // The Jetpack Compose template's `Greeting` body: `"Hello, " + name + "!"`. String `+` lowers to a
        // synthetic `plus` the interpreter does intrinsically (left.plus(other.toString())).
        val code = "package demo\nfun greet(name: String): String = \"Hello, \" + name + \"!\""
        assertEquals("Hello, World!", runProgram(code, "greet/1", listOf("World")))
        // A non-String `+` still goes through numeric arithmetic.
        assertEquals(5, runProgram("package demo\nfun add(a: Int, b: Int): Int = a + b", "add/2", listOf(2, 3)))
    }

    @Test
    fun whenExpressionWithSubject() {
        // `when (x) { 1 -> ; 2 -> ; else -> }` → if/else chain comparing the subject (evaluated once).
        val code = "package demo\nfun label(x: Int): String = when (x) { 1 -> \"one\"\n  2 -> \"two\"\n  else -> \"many\" }"
        assertEquals("one", runProgram(code, "label/1", listOf(1)))
        assertEquals("two", runProgram(code, "label/1", listOf(2)))
        assertEquals("many", runProgram(code, "label/1", listOf(7)))
    }

    @Test
    fun subjectlessWhen() {
        val code = "package demo\nfun sign(n: Int): String = when { n < 0 -> \"neg\"\n  n > 0 -> \"pos\"\n  else -> \"zero\" }"
        assertEquals("neg", runProgram(code, "sign/1", listOf(-3)))
        assertEquals("pos", runProgram(code, "sign/1", listOf(5)))
        assertEquals("zero", runProgram(code, "sign/1", listOf(0)))
    }

    @Test
    fun unsupportedConstructFailsLoudly() {
        // Indexed assignment (`xs[i] = v`, the `set` operator) is still outside the subset → the function isn't
        // complete → the interpreter refuses it rather than producing a wrong result.
        val code = "package demo\nfun f(xs: MutableList<Int>) { xs[0] = 1 }"
        val ex = assertFailsWith<InterpreterException> { runProgram(code, "f/1", listOf(mutableListOf(0))) }
        assertTrue(ex.message?.contains("unsupported") == true, "message=${ex.message}")
    }
}
