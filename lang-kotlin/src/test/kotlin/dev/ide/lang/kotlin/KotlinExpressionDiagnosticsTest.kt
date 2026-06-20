package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Expression-level semantic diagnostics that consult the resolver: a useless cast (`x as String` where `x` is
 * already `String`), a useless elvis (`x!! ?: y`), and a value returned from a `Unit`-returning function
 * (`fun f() { return 5 }`). Each error/warning case must be flagged and each valid counterpart left alone,
 * without false positives over the parse-only model.
 */
class KotlinExpressionDiagnosticsTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun uselessCastToSameTypeIsFlagged() {
        val diags = diagnose("UselessCast.kt", "package demo\nfun f(s: String) { val x = s as String }")
        assertTrue(
            diags.any { it.code == "kt.uselessCast" },
            "casting a String to String should be flagged useless; got $diags",
        )
    }

    @Test
    fun narrowingCastIsNotFlagged() {
        val diags = diagnose("RealCast.kt", "package demo\nfun f(a: Any) { val x = a as String }")
        assertTrue(
            diags.none { it.code == "kt.uselessCast" },
            "a narrowing `Any as String` cast must not be flagged; got $diags",
        )
    }

    @Test
    fun safeCastIsNotFlagged() {
        val diags = diagnose("SafeCast.kt", "package demo\nfun f(s: String) { val x = s as? String }")
        assertTrue(
            diags.none { it.code == "kt.uselessCast" },
            "a safe `as?` cast must not be flagged as a useless cast; got $diags",
        )
    }

    @Test
    fun elvisOnNotNullAssertedOperandIsFlagged() {
        val diags = diagnose("UselessElvis.kt", "package demo\nfun f(s: String?) { val x = s!! ?: \"\" }")
        assertTrue(
            diags.any { it.code == "kt.uselessElvis" },
            "`s!! ?: \"\"` should flag a useless elvis; got $diags",
        )
    }

    @Test
    fun elvisOnNullableOperandIsNotFlagged() {
        val diags = diagnose("OkElvis.kt", "package demo\nfun f(s: String?) { val x = s ?: \"\" }")
        assertTrue(
            diags.none { it.code == "kt.uselessElvis" },
            "`s ?: \"\"` on a genuinely nullable value must not be flagged; got $diags",
        )
    }

    @Test
    fun returningValueFromImplicitUnitFunctionIsFlagged() {
        val diags = diagnose("UnitReturn.kt", "package demo\nfun f() { return 5 }")
        assertTrue(
            diags.any { it.code == "kt.typeMismatch" && it.message.contains("Unit") },
            "returning a value from a Unit function should be flagged; got $diags",
        )
    }

    @Test
    fun returningValueFromExplicitUnitFunctionIsFlagged() {
        val diags = diagnose("UnitReturn2.kt", "package demo\nfun f(): Unit { return 5 }")
        assertTrue(
            diags.any { it.code == "kt.typeMismatch" && it.message.contains("Unit") },
            "returning a value from an explicit-Unit function should be flagged; got $diags",
        )
    }

    @Test
    fun matchingReturnTypeIsNotFlagged() {
        val diags = diagnose("IntReturn.kt", "package demo\nfun f(): Int { return 5 }")
        assertTrue(
            diags.none { it.code == "kt.typeMismatch" },
            "a correctly-typed return must not be flagged; got $diags",
        )
    }

    @Test
    fun valuelessReturnFromUnitFunctionIsNotFlagged() {
        val diags = diagnose("BareReturn.kt", "package demo\nfun f() { if (true) return\n  println(1) }")
        assertTrue(
            diags.none { it.code == "kt.typeMismatch" },
            "a bare `return` in a Unit function must not be flagged; got $diags",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
