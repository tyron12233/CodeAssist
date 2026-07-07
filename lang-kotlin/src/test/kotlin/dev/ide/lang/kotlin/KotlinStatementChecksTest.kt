package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Statement-level gap-fill checks (2026-07-07): assignment to a non-variable (VARIABLE_EXPECTED), an unused
 * pure statement, a duplicate `when` branch, and the qualified-receiver smart-cast that prevents a false
 * unsafe-call. Each error case is flagged and each valid counterpart left alone.
 */
class KotlinStatementChecksTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }
    private fun b(fn: String) = "package demo\n$fn\n"

    // --- variable expected (assignment to a non-lvalue) ---

    @Test
    fun assignToCallOrLiteralIsFlagged() {
        assertTrue(diagnose("V1.kt", b("fun g(): Int = 1\nfun f() { g() = 5 }")).any { it.code == "kt.variableExpected" }, "assign to a call")
        assertTrue(diagnose("V2.kt", b("fun f() { 5 = 3 }")).any { it.code == "kt.variableExpected" }, "assign to a literal")
    }

    @Test
    fun validAssignmentTargetsAreClean() {
        val ok = listOf(
            "fun f() { var x = 1\n x = 2 }",
            "fun f(a: IntArray) { a[0] = 1 }",
            "class B(var s: Int)\nfun f(b: B) { b.s = 1 }",
        )
        for (o in ok) assertTrue(diagnose("V3.kt", b(o)).none { it.code == "kt.variableExpected" }, "`$o` must be clean; got ${diagnose("V3.kt", b(o))}")
    }

    // --- unused expression ---

    @Test
    fun unusedPureStatementIsFlagged() {
        assertTrue(diagnose("U1.kt", b("fun f(a: Int, c: Int) { a == c\n println(a) }")).any { it.code == "kt.unusedExpression" }, "a comparison statement")
        assertTrue(diagnose("U2.kt", b("fun f(a: Int) { a\n println(a) }")).any { it.code == "kt.unusedExpression" }, "a bare reference")
    }

    @Test
    fun sideEffectingOrTrailingStatementsAreClean() {
        // A call (side effect), and a trailing value expression, are not flagged.
        assertTrue(diagnose("U3.kt", b("fun f() { println(\"x\")\n println(\"y\") }")).none { it.code == "kt.unusedExpression" }, "calls are not unused")
        assertTrue(diagnose("U4.kt", b("fun f(a: Int): Boolean { return a == 1 }")).none { it.code == "kt.unusedExpression" }, "a returned comparison is used")
        assertTrue(diagnose("U5.kt", b("fun f(a: Int, c: Int): Boolean { a == c }")).none { it.code == "kt.unusedExpression" }, "the last (value) statement is not flagged")
    }

    // --- duplicate when branch ---

    @Test
    fun duplicateWhenBranchIsFlagged() {
        assertTrue(diagnose("W1.kt", b("fun f(x: Int) { when (x) { 1 -> {}\n 1 -> {} } }")).any { it.code == "kt.duplicateWhenBranch" }, "duplicate literal branch")
        assertTrue(diagnose("W2.kt", b("fun f(x: Int) { when (x) { 1 -> {}\n 2 -> {} } }")).none { it.code == "kt.duplicateWhenBranch" }, "distinct branches are clean")
    }

    // --- qualified-receiver smart-cast (the false-positive fix) ---

    @Test
    fun guardedQualifiedReceiverIsNotUnsafe() {
        val d = diagnose("Q1.kt", b("class B(val s: String?)\nfun f(b: B) { if (b.s != null) b.s.length }"))
        assertTrue(d.none { it.code == "kt.unsafeNullable" }, "a guarded member deref must not be flagged unsafe; got $d")
    }

    @Test
    fun unguardedQualifiedReceiverIsUnsafe() {
        val d = diagnose("Q2.kt", b("class B(val s: String?)\nfun f(b: B) { b.s.length }"))
        assertTrue(d.any { it.code == "kt.unsafeNullable" }, "an unguarded member deref is unsafe; got $d")
    }

    @Test
    fun earlyExitGuardedQualifiedReceiverIsNotUnsafe() {
        val d = diagnose("Q3.kt", b("class B(val s: String?)\nfun f(b: B) {\n  if (b.s == null) return\n  b.s.length\n}"))
        assertTrue(d.none { it.code == "kt.unsafeNullable" }, "an early-exit guarded member deref is safe; got $d")
    }

    // --- const misuse (2026-07-07) ---

    @Test
    fun constMisuseIsFlagged() {
        assertTrue(diagnose("C1.kt", b("fun f() { const val x = 5 }")).any { it.code == "kt.constMisuse" }, "const on a local")
        assertTrue(diagnose("C2.kt", b("const var x = 5")).any { it.code == "kt.constMisuse" }, "const on a var")
        assertTrue(diagnose("C3.kt", b("fun foo() = 5\nconst val x = foo()")).any { it.code == "kt.constMisuse" }, "const with a call initializer")
        assertTrue(diagnose("C4.kt", b("const val x: Int get() = 5")).any { it.code == "kt.constMisuse" }, "const with a getter")
    }

    @Test
    fun validConstIsClean() {
        val ok = listOf("const val x = 5", "const val s = \"hi\"", "const val x = 1 + 2")
        for (o in ok) assertTrue(diagnose("Cok.kt", b(o)).none { it.code == "kt.constMisuse" }, "`$o` must be clean; got ${diagnose("Cok.kt", b(o))}")
    }

    // --- name shadowing (2026-07-07) ---

    @Test
    fun localShadowingIsFlagged() {
        assertTrue(diagnose("S1.kt", b("fun f(x: Int) { val x = 5\n println(x) }")).any { it.code == "kt.nameShadowing" }, "val shadowing a param")
        assertTrue(diagnose("S2.kt", b("fun f() { val a = 1\n run { val a = 2\n println(a) } }")).any { it.code == "kt.nameShadowing" }, "val shadowing an outer val")
    }

    @Test
    fun nonShadowingIsClean() {
        assertTrue(diagnose("S3.kt", b("fun f(x: Int) { val y = 5\n println(x + y) }")).none { it.code == "kt.nameShadowing" }, "distinct names")
        assertTrue(diagnose("S4.kt", b("fun f(x: Int) { fun g(x: Int) = x }")).none { it.code == "kt.nameShadowing" }, "nested-function param reuse (not a local val)")
    }

    // --- assignment in expression context (2026-07-07) ---

    @Test
    fun assignmentInExpressionIsFlagged() {
        assertTrue(diagnose("A5.kt", b("fun f() { var x = 0\n val b = (x = 5)\n println(b) }")).any { it.code == "kt.assignmentInExpression" }, "assign as initializer")
        assertTrue(diagnose("A6.kt", b("fun g(y: Boolean) {}\nfun f() { var x = 0\n g((x = 5)) }")).any { it.code == "kt.assignmentInExpression" }, "assign as argument")
    }

    @Test
    fun statementAssignmentIsClean() {
        assertTrue(diagnose("A7.kt", b("fun f() { var x = 0\n x = 5\n println(x) }")).none { it.code == "kt.assignmentInExpression" }, "a plain statement assignment")
        assertTrue(diagnose("A8.kt", b("fun f(c: Boolean) { var x = 0\n if (c) x = 5\n println(x) }")).none { it.code == "kt.assignmentInExpression" }, "an if-body assignment is a statement")
    }

    // --- increment of a non-variable + unreachable catch ---

    @Test
    fun incrementOfNonVariableIsFlagged() {
        assertTrue(diagnose("I9.kt", b("fun g(): Int = 1\nfun f() { g()++ }")).any { it.code == "kt.variableExpected" }, "g()++ increments a non-variable")
        assertTrue(diagnose("I10.kt", b("fun f() { var x = 0\n x++ }")).none { it.code == "kt.variableExpected" }, "x++ on a var is fine")
    }

    @Test
    fun unreachableCatchIsFlagged() {
        val d = diagnose("Cat1.kt", b("open class E\nclass F : E()\nfun f() { try {} catch (e: E) {} catch (e: F) {} }"))
        assertTrue(d.any { it.code == "kt.unreachableCatch" }, "F after its supertype E is unreachable; got $d")
        val ok = diagnose("Cat2.kt", b("open class E\nclass F : E()\nfun f() { try {} catch (e: F) {} catch (e: E) {} }"))
        assertTrue(ok.none { it.code == "kt.unreachableCatch" }, "subtype-first is fine; got $ok")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
