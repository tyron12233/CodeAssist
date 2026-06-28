package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Resolution/completion of user-declared operators and infix functions: the result type of `a + b`, `a combine
 * b`, `a[i]`, `a()`, `-a`, `a..b`, etc. must resolve so chained member access (`(a + b).member`) completes.
 * `Money` and its operators live on disk so the symbol model carries them while the analyzed buffer is Main.kt.
 */
class KotlinOperatorResolutionTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Main.kt", code) }.items.map { it.label }

    private fun diagnose(code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Main.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private val preamble = "package demo\nfun main() { val a = Money(1); val b = Money(2)\n"

    @Test fun memberPlusOperatorResolves() {
        assertTrue("cents" in labels(preamble + "(a + b).ce| }"), "member `plus` result should be Money")
    }

    @Test fun memberInfixResolves() {
        assertTrue("cents" in labels(preamble + "(a combine b).ce| }"), "member infix `combine` result should be Money")
    }

    @Test fun extensionInfixResolves() {
        assertTrue("cents" in labels(preamble + "(a scale 2).ce| }"), "extension infix `scale` result should be Money")
    }

    @Test fun extensionOperatorResolves() {
        assertTrue("cents" in labels(preamble + "(a * 2).ce| }"), "extension operator `times` result should be Money")
    }

    @Test fun getOperatorResolves() {
        assertTrue(labels(preamble + "a[0].toLo| }").any { it.startsWith("toLong") }, "`a[0]` should be Int (get operator)")
    }

    @Test fun invokeOperatorResolves() {
        assertTrue("cents" in labels(preamble + "a().ce| }"), "`a()` should be Money (invoke operator)")
    }

    @Test fun unaryMinusResolves() {
        assertTrue("cents" in labels(preamble + "(-a).ce| }"), "`-a` should be Money (unaryMinus operator)")
    }

    @Test fun rangeToResolves() {
        // `(1..10)` is `Int.rangeTo` → IntRange; chaining a member off it should resolve.
        assertTrue(labels(preamble + "(1..10).firs| }").any { it.startsWith("first") }, "`1..10` should be an IntRange")
    }

    @Test fun customOperatorsNotFalseFlagged() {
        val diags = diagnose(preamble + "val c = a + b; val d = a combine b; val e = a * 2; val f = -a; val g = a scale 2 }")
        assertTrue(diags.none { it.code == "kt.unresolved" || it.code == "kt.notCallable" || it.code == "kt.typeMismatch" },
            "custom operators/infix must not be false-flagged; got ${diags.filter { it.severity.name == "ERROR" }}")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Money.kt" to """
                    package demo
                    class Money(val cents: Int) {
                        operator fun plus(o: Money): Money = Money(cents + o.cents)
                        operator fun get(i: Int): Int = cents
                        operator fun invoke(): Money = this
                        operator fun unaryMinus(): Money = Money(-cents)
                        infix fun combine(o: Money): Money = Money(cents + o.cents)
                    }
                    operator fun Money.times(n: Int): Money = Money(cents * n)
                    infix fun Money.scale(n: Int): Money = Money(cents * n)
                """.trimIndent(),
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
