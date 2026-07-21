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
    fun typeClassLiteralEvaluatesToTheJvmClass() {
        // `X::class.java` yields the `java.lang.Class` token — the `Intent(context, X::class.java)` argument the
        // Compose preview used to crash on (`KtClassLiteralExpression` was Unsupported). A mapped Kotlin type
        // (`String`) resolves to its JVM class.
        assertEquals(java.lang.String::class.java, runProgram("package demo\nfun f(): Any = String::class.java", "f/0", emptyList()))
    }

    @Test
    fun instanceClassLiteralEvaluatesToTheRuntimeClass() {
        // `value::class.java` — the runtime class of the evaluated receiver.
        assertEquals(java.lang.String::class.java, runProgram("package demo\nfun f(): Any = \"hi\"::class.java", "f/0", emptyList()))
    }

    @Test
    fun bareClassLiteralEvaluatesToAKClass() {
        // `X::class` (no `.java`) yields a KClass whose `.java` is the JVM class.
        val v = runProgram("package demo\nfun f(): Any = String::class", "f/0", emptyList())
        assertTrue(v is kotlin.reflect.KClass<*>, "a bare `::class` must yield a KClass; got ${v?.javaClass}")
        assertEquals(java.lang.String::class.java, (v as kotlin.reflect.KClass<*>).java)
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
    fun omittedTrailingDefaultArgumentIsFilled() {
        // The Compose-template shape: `Greeting("Compose")` calls `fun Greeting(name, modifier = Modifier)` with
        // the trailing defaulted parameter omitted. The call has FEWER args than the declared arity, so it must
        // (a) still find the function — the reported `no source function Greeting/1` — and (b) fill the default.
        val code = "package demo\n" +
            "fun greet(name: String, greeting: String = \"Hi \"): String = greeting + name\n" +
            "fun caller(): String = greet(\"Bob\")"
        // Direct call with the trailing default omitted → the default is substituted at bind time.
        assertEquals("Hi Bob", runProgram(code, "greet/2", listOf("Bob")))
        // Through a caller whose body calls `greet` with one argument → the TOP_LEVEL lookup resolves the
        // 2-parameter declaration by its declared arity rather than missing on `greet/1`.
        assertEquals("Hi Bob", runProgram(code, "caller/0", emptyList()))
    }

    @Test
    fun namedArgumentsBindByNameAndFillOmittedDefaults() {
        // A source top-level call with NAMED arguments must reorder them into the declared parameter order
        // (previously they bound positionally — `greeting` landed in `name`), and an omitted defaulted parameter
        // still takes its default.
        val code = "package demo\n" +
            "fun greet(name: String, greeting: String = \"Hi \"): String = greeting + name\n" +
            "fun reordered(): String = greet(greeting = \"Yo \", name = \"Al\")\n" +
            "fun defaulted(): String = greet(name = \"Al\")"
        assertEquals("Yo Al", runProgram(code, "reordered/0", emptyList()))
        assertEquals("Hi Al", runProgram(code, "defaulted/0", emptyList()))
    }

    @Test
    fun defaultReferencingAnEarlierParameterIsEvaluatedInTheCalleeFrame() {
        // A default expression may read an earlier parameter (`fun f(a, b = a + 1)`); it must evaluate in the
        // callee's frame where `a` is already bound, not blow up or read null.
        val code = "package demo\n" +
            "fun f(a: Int, b: Int = a + 1): Int = a + b\n" +
            "fun g(): Int = f(10)"
        assertEquals(21, runProgram(code, "f/2", listOf(10)))
        assertEquals(21, runProgram(code, "g/0", emptyList()))
    }

    @Test
    fun unsupportedConstructFailsLoudly() {
        // A construct outside the interpreter's subset (an anonymous object literal) makes the function
        // incomplete → the interpreter refuses it rather than producing a wrong result. (Non-Int ranges,
        // indexed assignment `xs[i] = v`, and labeled break/continue are now supported.)
        val code = "package demo\nfun f(): Any = object : Runnable { override fun run() {} }"
        val ex = assertFailsWith<InterpreterException> { runProgram(code, "f/0", emptyList()) }
        assertTrue(ex.message?.contains("unsupported") == true, "message=${ex.message}")
    }
}
