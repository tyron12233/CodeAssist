package dev.ide.lang.kotlin

import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Receivers whose type used to come back empty, so the list after the dot was empty too: a `try` used as a
 * value, a property reference, a value of function type, a constructor call spelled with its package. Plus
 * the two places a list was offered but led with the wrong thing: an `Int` receiver, and a name with many
 * overloads that are all typed the same way.
 */
class KotlinCompletionShapesTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "package demo\n$code") }.items.map { it.label }

    private fun List<String>.offers(name: String) = any { it == name || it.startsWith("$name(") || it.startsWith("$name ") }

    @Test
    fun aTryUsedAsAValueHasTheTypeOfItsBranches() {
        assertTrue(labels("fun g() { val x = try { \"a\" } catch (e: Exception) { \"b\" }; x.| }").offers("length"))
        assertTrue(labels("fun g() {\n    val x = try { \"a\" } finally { }\n    x.|\n}").offers("length"))
    }

    @Test
    fun aPropertyReferenceHasItsReflectionMembers() {
        assertTrue(labels("class A { lateinit var s: String; fun f() { ::s.| } }").offers("isInitialized"))
        assertEquals(listOf("isInitialized"), labels("class A { lateinit var s: String; fun f() { this::s.isIn| } }"))
        assertTrue(labels("class A { val s = \"\" }\nfun f() { A::s.| }").offers("name"))
    }

    @Test
    fun aFunctionValueOffersInvoke() {
        // The invoke operator comes first as IntelliJ's `()` bracket item, then the plain call.
        val onValue = labels("fun g() { val f: (Int) -> String = { it.toString() }; f.| }")
        assertEquals(listOf("()(p1: Int)", "invoke(p1: Int)"), onValue.take(2), "got $onValue")
        assertTrue(labels("typealias Cb = (String) -> Unit\nfun g(cb: Cb?) { cb?.inv| }").offers("invoke"))
    }

    /** `kotlin.math.E` is a top-level property the file never imported; the enum declared right here wins. */
    @Test
    fun aProjectTypeIsNotShadowedByAnUnimportedTopLevelProperty() {
        val ls = labels("enum class E { A, B }\nfun g() { E.| }")
        assertTrue(ls.offers("A") && ls.offers("B") && ls.offers("valueOf") && ls.offers("entries"), "got $ls")
        assertTrue(!ls.offers("NaN") && !ls.offers("MAX_VALUE"), "an enum is not a Double; got $ls")
    }

    @Test
    fun aClassWithNoSupertypeListCanOverrideAnysMembers() {
        assertEquals(listOf("override fun toString(): String {...}"), labels("class A {\n    override fun toS|\n}"))
        val all = labels("class A : Runnable {\n    override fun |\n}")
        assertEquals("override fun run() {...}", all.first(), "the declared supertype's member leads; got ${all.take(5)}")
        assertTrue(all.any { it.startsWith("override fun equals") } && all.any { it.startsWith("override fun hashCode") })
    }

    @Test
    fun aConstructorCallSpelledWithItsPackageIsTyped() {
        assertTrue(labels("fun g() { java.io.File(\"a\").| }").offers("exists"))
        assertTrue(labels("fun g() {\n    val j = java.io.File(\"a\")\n    j.|\n}").offers("name"))
        assertTrue(labels("fun g() { kotlin.text.StringBuilder().| }").offers("append"))
    }

    @Test
    fun aNumberLeadsWithConversionsNotOperatorFunctions() {
        val ls = labels("fun g() { 1.| }")
        assertEquals("toString()", ls.first(), "got ${ls.take(6)}")
        assertTrue(ls.indexOfFirst { it.startsWith("toLong") } < 8 && ls.indexOfFirst { it.startsWith("coerceIn") } < 12, "got ${ls.take(12)}")
    }

    @Test
    fun anEnumValueLeadsWithNameAndOrdinal() {
        val ls = labels("enum class Color { R }\nfun g(c: Color) { c.| }")
        assertEquals(listOf("name", "ordinal"), ls.take(2), "got ${ls.take(6)}")
    }

    /** `sumOf { }` is typed the same way whatever the selector returns, so its overloads share one row. */
    @Test
    fun overloadsThatDifferOnlyInTheirLambdasResultAreOneRow() {
        val ls = labels("fun g(l: List<Int>) { l.su| }")
        assertEquals("sum()", ls.first(), "got ${ls.take(6)}")
        assertEquals(2, ls.count { it.startsWith("sumOf") }, "one overload, in its lambda and its parenthesized form; got $ls")
    }

    // Per test instance, NOT in a companion: an analyzer over the JDK holds its whole index, and one parked in
    // a static for the life of the test JVM is heap every later test class runs without.
    private val srcDir: Path = tempProject(mapOf("Marker.kt" to "package demo\nclass Marker"))
    private val analyzer by lazy {
        KotlinSourceAnalyzer(
            fakeContext(
                srcDir,
                listOfNotNull(stdlibJarPath(), TestJars.jdkBaseJar(Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar"))),
            ),
        )
    }
}
