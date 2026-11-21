package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The EXPOSED_* family ported from K2's FirExposedVisibilityDeclarationChecker over its effective visibility: a
 * signature may not name a class less visible than the declaration, and one that is at least as visible is clean.
 */
class KotlinExposedVisibilityPortTest {

    private val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
    private val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))

    @AfterTest
    fun dispose() {
        runCatching { analyzer.dispose() }
    }

    private fun lines(code: String): List<Int> {
        val src = "package demo\n${code.trimIndent()}\n"
        val doc = SnippetDoc(src, DiskFile(srcDir.resolve("E.kt")))
        val diags: List<Diagnostic> = runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
        return diags.filter { it.code == KotlinDiagnosticCodes.EXPOSED_VISIBILITY }.map { d -> src.take(d.range.start).count { it == '\n' } }.sorted()
    }

    @Test
    fun membersExposingPrivateNestedClassesAreFlagged() {
        val code = """
            class A {
                private class P
                fun f(): P = P()
                fun g(p: P) {}
                val v: List<P> = emptyList()
                private fun fine(p: P): P = p
            }
        """
        assertEquals(listOf(3, 4, 5), lines(code))
    }

    @Test
    fun internalAndProtectedTypesAreFlaggedInWiderSignatures() {
        val code = """
            internal class I
            fun f(i: I) {}
            internal fun fine(i: I) {}
            open class B {
                protected class Q
                fun g(): Q? = null
                protected fun fine2(): Q? = null
            }
            typealias T = I
            internal typealias Fine3 = I
        """
        assertEquals(listOf(2, 6, 9), lines(code))
    }

    @Test
    fun supertypesAndConstructorProperties() {
        val code = """
            internal open class Base
            internal interface Iface
            class Sub : Base()
            interface SubI : Iface
            class Holder(val i: Iface)
            internal class FineSub : Base(), Iface
            class FineHolder internal constructor(i: Iface?) { internal val j: Iface? = i }
        """
        assertEquals(listOf(3, 4, 5), lines(code))
    }
}
