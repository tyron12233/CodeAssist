package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Inference that reads what the context expects of a generic call, the way the compiler's shared constraint
 * system does: an expected type reaching a nested call through `id(…)`, `!!` and a lambda's result, a declared
 * bound that meets an unrelated expected type, and a type variable nothing at all fixes.
 */
class KotlinContextualInferenceTest {

    private fun diagnose(code: String): List<Diagnostic> {
        val srcDir = tempProject(mapOf("A.kt" to code))
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
    fun declaredBoundMeetingAnUnrelatedExpectedTypeIsNotAMismatch() {
        val d = diagnose(
            """
            package p
            open class Foo
            class Bar
            fun <T : Foo> foo(): T? = null
            fun <T : Appendable> wtf(): T = TODO()
            fun main() {
                val a: Bar? = foo()
                val b: Int = wtf()
            }
            """.trimIndent(),
        )
        assertTrue(d.codes("kt.typeMismatch").isEmpty(), "the variable is the intersection, which fits; got $d")
    }

    @Test
    fun expectedTypeReachesANestedCallThroughIdAndNotNullAssertion() {
        val d = diagnose(
            """
            package p
            interface A
            fun <T> id(t: T): T = t
            fun doList(l: List<Int>) = l
            fun <T : A> emptyNullableListOfA(): List<T>? = null
            fun test() {
                doList(emptyNullableListOfA()!!)
                val l: List<Int> = id(emptyNullableListOfA()!!)
            }
            """.trimIndent(),
        )
        assertTrue(d.codes("kt.typeMismatch").isEmpty(), "the inner call is fixed from the outer expectation; got $d")
    }

    @Test
    fun expectedTypeReachesALambdaResult() {
        val d = diagnose(
            """
            package p
            fun <T2 : Any> decodeNonReified(): T2? = null
            fun <R> myRun(x: () -> R): R = TODO()
            sealed interface Base
            sealed interface Derived : Base
            val d: Derived = TODO()
            fun main() {
                val x2: Base = decodeNonReified() ?: d
                val x4: Base = myRun { decodeNonReified() ?: d }
            }
            """.trimIndent(),
        )
        assertTrue(d.codes("kt.typeMismatch").isEmpty(), "the lambda's result is expected to be a Base; got $d")
    }

    @Test
    fun variablesNothingFixesAcrossNestedCallsAreReported() {
        val d = diagnose(
            """
            package p
            fun <T> materialize(): T = null!!
            fun <V> id(value: V) = value
            fun <R> myRun(block: () -> R): R = block()
            interface A { fun fooA(): A }
            fun <T> foo(): T = TODO()
            val viaId = id(materialize())
            val asReceiver = foo<A>().fooA()
            val freeReceiver = foo().hashCode()
            val fromIs = foo() is String
            fun main() {
                if (materialize() == "") return
                val x = myRun { materialize() }
            }
            """.trimIndent(),
        ).codes("kt.cannotInferType")
        val lines = d.map { it.range.start }.toSet()
        assertTrue(d.size == 7, "id and materialize, foo as a receiver, foo under is, the equality's left side, myRun and materialize; got $d")
        assertTrue(lines.size == 7, "each reported once; got $d")
    }

    @Test
    fun variablesSomethingFixesAreNotReported() {
        val d = diagnose(
            """
            package p
            fun <T> materialize(): T = null!!
            fun <V> id(value: V) = value
            fun <R> myRun(block: () -> R): R = block()
            fun <T> foo(): T = TODO()
            fun take(s: String) {}
            val asString = foo() as String
            val insideId = id(foo() as String)
            val typed: String = id(materialize())
            val viaArg = id(id("x"))
            fun main() {
                if ("" == materialize()) return
                take(id(materialize()))
                val y: Int = myRun { materialize() }
                val z = myRun { 1 }
                myRun { materialize<String>() }
            }
            """.trimIndent(),
        ).codes("kt.cannotInferType")
        assertTrue(d.isEmpty(), "every variable here has a type from somewhere; got $d")
    }

    private val pclaDecls = """
        class TypeVariableOwner<T> {
            fun constrain(subtypeValue: T) {}
            fun provide(): T = null!!
        }
        fun <OT> pcla(lambda: (TypeVariableOwner<OT>) -> Unit): OT = null!!
        interface BaseType
        class ScopeOwner : BaseType {
            infix fun infixFunction(other: ScopeOwner) {}
            fun member() {}
        }
        object Interloper : BaseType
    """.trimIndent()

    @Test
    fun builderVariableFixedOnDemandRejectsALaterArgument() {
        val d = diagnose(
            """
            package p
            $pclaDecls
            fun test() {
                val a = pcla { otvOwner ->
                    otvOwner.constrain(ScopeOwner())
                    otvOwner.provide() infixFunction ScopeOwner()
                    otvOwner.constrain(Interloper)
                }
                val b = pcla { otvOwner ->
                    otvOwner.constrain(ScopeOwner())
                    otvOwner.provide().member()
                    otvOwner.constrain(Interloper)
                }
            }
            """.trimIndent(),
        ).codes("kt.typeMismatch")
        assertTrue(d.size == 2, "each Interloper comes after the variable was fixed to ScopeOwner; got $d")
    }

    @Test
    fun builderVariableStillOpenTakesAnyArgument() {
        val d = diagnose(
            """
            package p
            $pclaDecls
            fun test() {
                val a = pcla { otvOwner ->
                    otvOwner.constrain(ScopeOwner())
                    otvOwner.provide() == ScopeOwner()
                    "${'$'}{otvOwner.provide()}"
                    otvOwner.constrain(Interloper)
                }
                val b = pcla { otvOwner ->
                    otvOwner.constrain(Interloper)
                    otvOwner.provide().hashCode()
                    otvOwner.constrain(Interloper)
                }
                val c = buildList { add(1); add("x") }
            }
            """.trimIndent(),
        ).codes("kt.typeMismatch")
        assertTrue(d.isEmpty(), "equality and a template fix nothing, and a fitting argument is fine; got $d")
    }

    @Test
    fun lambdaThroughABareVariableIsAFunctionValue() {
        val d = diagnose(
            """
            package p
            fun foo(a: Any) = a
            fun foo(i: Int, f: (Int) -> Int) = f(i)
            fun foo(s: String, a: Any) = s + a
            fun <T> id(t: T) = t
            fun <S> select(vararg x: S) = x[0]
            fun test() {
                foo(1, id { x1 ->
                    foo(2, id { x2 -> x2 })
                })
                select(id { x, y -> x.inv() + y.toByte() }, { x: Int, y -> y.toByte() }, { x, y: Number -> x.inv() })
            }
            """.trimIndent(),
        )
        assertTrue(d.codes("kt.typeMismatch").isEmpty(), "id { … } is a function value, which the (Int) -> Int overload takes; got $d")
        assertTrue(d.codes("kt.unresolved").isEmpty(), "its untyped parameters type nothing they flow into; got $d")
    }

    @Test
    fun nullAmongTheArgumentsMakesTheVariableNullable() {
        val d = diagnose(
            """
            package p
            fun <T> box(vararg xs: T): MutableList<T> = xs.toMutableList()
            fun main() {
                val a: MutableList<Int?> = box(128, null)
                val b: MutableList<Int?> = box(null, 128)
            }
            """.trimIndent(),
        ).codes("kt.typeMismatch")
        assertTrue(d.isEmpty(), "T is Int?, not Int or Nothing?; got $d")
    }

    @Test
    fun argumentThatContradictsTheExpectedTypeIsStillAMismatch() {
        val d = diagnose(
            """
            package p
            fun <T> id(t: T): T = t
            fun main() {
                val s: String = id(5)
            }
            """.trimIndent(),
        )
        assertTrue(d.codes("kt.typeMismatch").size == 1, "an argument pins T to Int, which is no String; got $d")
    }
}
