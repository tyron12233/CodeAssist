package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The declaration rules ported from K2's declaration checkers (KotlinDeclarationPortedChecks): each reports on the
 * line K2 does, and a look-alike the compiler accepts stays clean.
 */
class KotlinDeclarationPortedChecksTest {

    private val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
    // `java.base` too: `Exception` is a typealias of `java.lang.Exception`, whose supertypes the Throwable rule walks.
    private val analyzer = KotlinSourceAnalyzer(
        fakeContext(srcDir, listOfNotNull(stdlibJarPath(), TestJars.jdkBaseJar(Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar")))),
    )

    @AfterTest
    fun dispose() {
        runCatching { analyzer.dispose() }
    }

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    /** The 1-based lines of [code] (after a `package demo` line) carrying [wanted]. */
    private fun lines(code: String, wanted: String, file: String = "T.kt"): List<Int> {
        val src = "package demo\n${code.trimIndent()}\n"
        return diagnose(file, src).filter { it.code == wanted }.map { d -> src.take(d.range.start).count { it == '\n' } }.sorted()
    }

    @Test
    fun parameterWithoutTypeIsFlaggedWhereNothingSuppliesOne() {
        val code = """
            fun test(a, b: Int) {}
            class C(x)
            val la = { a -> }
            val typed: (Int) -> Unit = { a -> }
            fun ok(f: (Int) -> Unit) = ok { a -> }
        """
        assertEquals(listOf(1, 2, 3), lines(code, KotlinDiagnosticCodes.PARAMETER_TYPE_REQUIRED))
    }

    @Test
    fun interfaceSupertypeListRules() {
        val code = """
            open class C
            interface I
            interface A : C
            interface B : I, Any
            interface D : C()
            interface Fine : I
            class E : C(), I
        """
        assertEquals(listOf(3, 4, 5, 5), lines(code, KotlinDiagnosticCodes.SUPERTYPE_LIST))
    }

    @Test
    fun classSupertypeListRules() {
        val code = """
            open class C
            open class D
            interface I
            class E : C(), D()
            class F : I, I
            interface G<T>
            class H : G<out Int>
            class J : Int.() -> Unit
            class Fine : C(), I, G<Int>
        """
        assertEquals(listOf(4, 5, 7, 8), lines(code, KotlinDiagnosticCodes.SUPERTYPE_LIST))
    }

    @Test
    fun publishedApiMustBeInternal() {
        val code = """
            @PublishedApi fun a() = 1
            @PublishedApi internal fun b() = 1
            class K { @PublishedApi private fun c() = 1 }
        """
        assertEquals(listOf(1, 3), lines(code, KotlinDiagnosticCodes.PUBLISHED_API))
    }

    @Test
    fun virtualInlineMembersAreFlagged() {
        val code = """
            open class A {
                open inline fun a() {}
                inline fun b() {}
                private inline fun c() {}
            }
            interface I { inline fun d() {} }
            class F { open inline fun e() {} }
        """
        assertEquals(listOf(2, 6), lines(code, KotlinDiagnosticCodes.INLINE_DECLARATION))
    }

    @Test
    fun localDeclarationsInInlineBodiesAreFlagged() {
        val code = """
            inline fun f() {
                fun local() {}
                class L
                object : Any() { fun fine() {} }
            }
            fun g() { fun fine() {} }
        """
        assertEquals(listOf(2, 3), lines(code, KotlinDiagnosticCodes.INLINE_DECLARATION))
    }

    @Test
    fun propertyTypeParametersAndBounds() {
        val code = """
            val <T> x: Int get() = 0
            val <T> List<T>.y: Int get() = 0
            interface I
            open class C
            open class D
            fun <T> a() where T : I, T : I {}
            fun <T> b() where T : C, T : D {}
            fun <T> fine() where T : C, T : I {}
        """
        assertEquals(listOf(1, 6, 7), lines(code, KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE))
    }

    @Test
    fun propertyInitializersWithNowhereToGo() {
        val code = """
            interface I { val a: Int = 1 }
            val b: Int = 0
                get() = 1
            val Int.c: Int = 1
            val Int.d: Int
            val e: Int = 0
                get() = field + 1
            val Int.f: Int get() = this
        """
        assertEquals(listOf(1, 2, 4, 5), lines(code, KotlinDiagnosticCodes.PROPERTY_SHAPE))
    }

    @Test
    fun constructorShapes() {
        val code = """
            class A(val x: Int) {
                constructor(s: String) : this(s.length)
                constructor(b: Boolean) {}
            }
            class B {
                constructor(a: Int) : this("x")
                constructor(a: String) : this(1)
            }
            object O { constructor() }
            interface I { constructor() }
            class Fine { constructor(a: Int) }
        """
        assertEquals(listOf(3, 6, 7, 9, 10), lines(code, KotlinDiagnosticCodes.CONSTRUCTOR_SHAPE))
    }

    @Test
    fun annotationClassShape() {
        val code = """
            open class C
            annotation class A(x: Int, val y: C) : Any() {
                fun f() {}
            }
            enum class E { X }
            annotation class Fine(val a: Int, val b: String, val c: E, val d: Array<String>, val e: IntArray)
        """
        assertEquals(listOf(2, 2, 2, 3), lines(code, KotlinDiagnosticCodes.ANNOTATION_CLASS_SHAPE))
    }

    @Test
    fun functionShapes() {
        val code = """
            interface I {
                override fun toString(): String = "I"
                fun other(): String = "I"
            }
            val f = fun(p: Int = 3) {}
            val g = fun <T> (t: T) {}
            val fine = fun(p: Int) {}
        """
        assertEquals(listOf(2, 5, 6), lines(code, KotlinDiagnosticCodes.FUNCTION_SHAPE))
    }

    @Test
    fun throwableSubclassesMayNotCaptureTypeParameters() {
        val code = """
            class Outer<T> {
                inner class E1 : Exception()
                class Fine : Exception()
                fun f() { class E2 : Exception() }
            }
            fun <T> g() { class E3 : Exception() }
            fun h() { class Fine2 : Exception() }
        """
        assertEquals(listOf(2, 4, 6), lines(code, KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE))
    }

    @Test
    fun sealedSupertypeInLocalClass() {
        val code = """
            sealed class S
            open class O
            fun f() {
                class L : S()
                val o = object : S() {}
                class Fine : O()
            }
        """
        assertEquals(listOf(4, 5), lines(code, KotlinDiagnosticCodes.SUPERTYPE_LIST))
    }

    @Test
    fun innerClassTypeArguments() {
        val code = """
            class A<T> {
                inner class B
                class N { val b: B? = null }
                fun ok(b: B) {}
            }
            class Outer<E> { class Nested }
            val n: Outer<Int>.Nested? = null
            val fine: Outer.Nested? = null
        """
        assertEquals(listOf(3, 7), lines(code, KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE))
    }

    @Test
    fun recursiveTypeAliases() {
        val code = """
            typealias R = R
            typealias L = List<L>
            typealias A = B
            typealias B = A
            typealias Fine = List<Int>
        """
        assertEquals(listOf(1, 2, 3, 4), lines(code, KotlinDiagnosticCodes.TYPE_PARAMETER_SHAPE))
    }
}
