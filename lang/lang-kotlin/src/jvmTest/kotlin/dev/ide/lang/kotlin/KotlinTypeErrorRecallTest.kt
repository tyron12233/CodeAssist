package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Type errors the compiler reports that the editor used to let through, each next to the valid code that
 * looks like it: a generic call nothing can infer, a number expression that cannot take the expected number
 * type, a lambda where no function value fits, and an argument for a parameter typed by an explicit type
 * argument.
 */
class KotlinTypeErrorRecallTest {

    private fun diagnose(code: String): List<Diagnostic> {
        val srcDir = tempProject(mapOf("A.kt" to code))
        // The stdlib is on the classpath, as in a real project: `Int` and `String` are then known types.
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, listOf(stdlibJarPath())))
        try {
            val doc = SnippetDoc(code, DiskFile(srcDir.resolve("A.kt")))
            return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
        } finally {
            runCatching { analyzer.dispose() }
        }
    }

    private fun List<Diagnostic>.codes(code: String) = filter { it.code == code }

    @Test
    fun genericCallWithNothingToInferFromIsReported() {
        val d = diagnose(
            """
            package p
            fun <T> materialize(): T = null!!
            fun <T> unused() {}
            fun f() {
                materialize()
                unused()
                val x = materialize()
            }
            fun g() = materialize()
            """.trimIndent(),
        ).codes("kt.cannotInferType")
        assertTrue(d.size == 4, "each uninferable call is reported; got $d")
    }

    @Test
    fun genericCallInferredFromItsSurroundingsIsNotReported() {
        val d = diagnose(
            """
            package p
            fun <T> materialize(): T = null!!
            fun take(s: String) {}
            fun <T> id(t: T): T = t
            class Box<T>(val t: T) { fun <R : T> get(): R = null!! }
            fun h(): String = materialize()
            fun f(b: Box<String>): String {
                val s: String = materialize()
                take(materialize())
                val y = id("a")
                b.get()
                return materialize()
            }
            """.trimIndent(),
        ).codes("kt.cannotInferType")
        assertTrue(d.isEmpty(), "every call here has its type parameter inferred or bounded by another; got $d")
    }

    @Test
    fun numberExpressionThatCannotTakeTheExpectedTypeIsReported() {
        val d = diagnose(
            """
            package p
            fun takeByte(b: Byte) {}
            val top = 1 - 1
            fun f() {
                takeByte(1000)
                takeByte(1 + 1)
                takeByte(top)
                val l: Int = 1 - 1.toLong()
                val b: Long = -1.toByte()
                val s: Short = 1 + 2
            }
            """.trimIndent(),
        ).codes("kt.typeMismatch")
        assertTrue(d.size == 6, "each number that cannot take the expected type is reported; got $d")
    }

    @Test
    fun numberExpressionThatAdaptsIsNotReported() {
        val d = diagnose(
            """
            package p
            fun takeByte(b: Byte) {}
            fun takeLong(l: Long) {}
            fun f(x: Int) {
                takeByte(127)
                takeByte(-128)
                takeLong(2 + 1)
                takeLong(1)
                val m: Long = 1 + 1
                val i: Int = x and 0xFF
                val d: Double = 1.0 + 1
                val n: Long = 1L + x
            }
            """.trimIndent(),
        ).codes("kt.typeMismatch")
        assertTrue(d.isEmpty(), "every number here takes the expected type; got $d")
    }

    @Test
    fun compoundAssignmentThatWidensANumberIsReported() {
        val d = diagnose(
            """
            package p
            fun f() {
                var x = 0
                x += 1L
                x += 1.toShort()
                x *= 2
                var s: Short = 0
                s += 1
                var d = 1.0
                d += 1
                var l: Long = 1
                l = 2
                l = x
            }
            """.trimIndent(),
        ).codes("kt.typeMismatch")
        assertTrue(d.size == 3, "x += 1L, s += 1 and l = x widen or narrow a number; got $d")
    }

    @Test
    fun annotationNumberArgumentsAreChecked() {
        val d = diagnose(
            """
            package p
            annotation class Ann(val b: Byte, val s: Short, val i: Int, val l: Long)
            @Ann(128, 1 / 1, 1 / 1, 1 / 1) class A
            @Ann(1, 1.toShort(), 2, l = 3) class B
            """.trimIndent(),
        ).codes("kt.typeMismatch")
        assertTrue(d.size == 2, "only 128 for the Byte and 1 / 1 for the Short are wrong; got $d")
    }

    @Test
    fun everyMismatchedArgumentOfASingleCandidateIsReported() {
        val d = diagnose(
            """
            package p
            fun varargFloat(vararg v: Float) = v
            fun two(a: Byte, b: Short) {}
            fun f() {
                varargFloat(1, 1.0f, 2)
                two(1000, 100000)
            }
            """.trimIndent(),
        ).codes("kt.typeMismatch")
        assertTrue(d.size == 4, "each wrong argument is reported; got $d")
    }

    @Test
    fun lambdaWhereNoFunctionFitsIsReported() {
        val d = diagnose(
            """
            package p
            fun test(): Int = { 1 }
            val s: String = { }
            """.trimIndent(),
        ).codes("kt.typeMismatch")
        assertTrue(d.size == 2, "a lambda can never be an Int or a String; got $d")
    }

    @Test
    fun lambdaWhereAFunctionFitsIsNotReported() {
        val d = diagnose(
            """
            package p
            fun interface Action { fun run() }
            typealias Handler = (Int) -> Unit
            val h: Handler = { }
            val r: Runnable = Runnable { }
            val a: Any = { }
            val b: () -> Int = { 1 }
            val c: Action = Action { }
            val u: () -> Unit = { { } }
            """.trimIndent(),
        ).codes("kt.typeMismatch")
        assertTrue(d.isEmpty(), "each lambda here fits its expected type; got $d")
    }

    @Test
    fun argumentAgainstAnExplicitTypeArgumentIsChecked() {
        val d = diagnose(
            """
            package p
            fun <T> checkSubtype(t: T): T = t
            fun f(s: String) {
                checkSubtype<Int>(s)
                checkSubtype<String>(s)
            }
            """.trimIndent(),
        ).codes("kt.typeMismatch")
        assertTrue(d.size == 1 && d[0].message.contains("String"), "only the Int-typed call is wrong; got $d")
    }
}
