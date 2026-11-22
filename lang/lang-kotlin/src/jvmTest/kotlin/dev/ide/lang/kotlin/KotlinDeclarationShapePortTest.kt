package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * More rules ported from K2's declaration checkers: inherited implementations that must be overridden, implicit
 * `Nothing` types, reified parameters outside inline functions, class-body and accessor shapes, and constructors
 * of a class with no primary one. Each reports on K2's line; each look-alike stays clean.
 */
class KotlinDeclarationShapePortTest {

    private val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
    private val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))

    @AfterTest
    fun dispose() {
        runCatching { analyzer.dispose() }
    }

    private fun lines(code: String, wanted: String): List<Int> {
        val src = "package demo\n${code.trimIndent()}\n"
        val doc = SnippetDoc(src, DiskFile(srcDir.resolve("S.kt")))
        val diags: List<Diagnostic> = runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
        // The "must implement" family is one judgment split by K2 into several names.
        val family = if (wanted in KotlinDiagnosticCodes.IMPLEMENT_MEMBERS) KotlinDiagnosticCodes.IMPLEMENT_MEMBERS else setOf(wanted)
        return diags.filter { it.code in family }.map { d -> src.take(d.range.start).count { it == '\n' } }.sorted()
    }

    @Test
    fun inheritedImplementationsThatDisagreeMustBeOverridden() {
        val code = """
            interface One { fun foo(): Int }
            interface Two { fun foo(): Int }
            interface OneImpl : One { override fun foo() = 1 }
            interface TwoImpl : Two { override fun foo() = 2 }
            class Test1 : TwoImpl, OneImpl
            class Test2(a: One) : One by a, Two
            class Test3(a: One, b: Two) : Two by b, One by a
            class Fine : TwoImpl, OneImpl { override fun foo() = 3 }
        """
        assertEquals(listOf(5, 7), lines(code, KotlinDiagnosticCodes.ABSTRACT_NOT_IMPLEMENTED))
    }

    @Test
    fun aSharedBaseIsNotAConflict() {
        val code = """
            interface Base { fun f() = 1 }
            interface A : Base
            interface B : Base
            class C : A, B
            interface D : Base { override fun f() = 2 }
            class E : D, Base
        """
        assertEquals(emptyList(), lines(code, KotlinDiagnosticCodes.ABSTRACT_NOT_IMPLEMENTED))
    }

    @Test
    fun implicitNothingTypes() {
        val code = """
            fun foo() = throw IllegalStateException()
            fun bar() = null!!
            fun baz() = bar()
            val x = null!!
            fun fine(): Nothing = throw IllegalStateException()
            fun fine2() = 1
        """
        assertEquals(listOf(1, 2, 3, 4), lines(code, KotlinDiagnosticCodes.FUNCTION_SHAPE))
    }

    @Test
    fun objectLiteralWithTwoSupertypesNeedsATypeWhenNotPrivate() {
        val code = """
            interface I1
            interface I2
            class K {
                val a = object : I1, I2 {}
                fun b() = object : I1, I2 {}
                private val fine = object : I1, I2 {}
                val fine2 = object : I1 {}
                val fine3: I1 = object : I1, I2 {}
            }
        """
        assertEquals(listOf(4, 5), lines(code, KotlinDiagnosticCodes.FUNCTION_SHAPE))
    }

    @Test
    fun typeParameterBoundShapes() {
        val code = """
            interface I1
            fun <T : Int.() -> String> a() {}
            fun <K, V, W> b() where W : K, W : V {}
            fun <K, T> c() where T : K, T : I1 {}
            fun <K, T : K> fine() {}
            fun <T : () -> String> fine2() {}
        """
        assertEquals(listOf(2, 3, 4), lines(code, KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE))
    }

    @Test
    fun reifiedOnlyOnInlineFunctions() {
        val code = """
            fun <reified T> a() {}
            inline fun <reified T> fine() {}
            val <reified T> List<T>.b: Int get() = 0
            inline val <reified T> List<T>.fine2: Int get() = 0
        """
        assertEquals(listOf(1, 3), lines(code, KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE))
    }

    @Test
    fun classBodyAndAccessorShapes() {
        val code = """
            class C {
                companion object A
                companion object B
            }
            interface I {
                init {}
                private val p: Int
                private val fine: Int get() = 1
            }
            abstract class D {
                abstract val q: Int get() = 1
                val fine2: Int get() = 1
            }
        """
        assertEquals(listOf(3, 6), lines(code, KotlinDiagnosticCodes.DECLARATION_FORM))
        assertEquals(listOf(7, 11), lines(code, KotlinDiagnosticCodes.PROPERTY_SHAPE))
    }

    @Test
    fun constructorsOfAClassWithoutAPrimary() {
        val code = """
            open class Base(x: Int)
            class A : Base(1) { constructor() }
            class B : Base { constructor() }
            class Fine : Base { constructor() : super(1) }
            open class NoArgs
            class Fine2 : NoArgs { constructor() }
        """
        assertEquals(listOf(2, 3), lines(code, KotlinDiagnosticCodes.CONSTRUCTOR_SHAPE))
    }
}
