package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.parse.KotlinParserHost
import org.jetbrains.kotlin.psi.KtFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Directly exercises [IncrementalDecls.plan] to prove the dependency scoping narrows the recompute set (the
 * end-to-end correctness is covered by KotlinIncrementalAnalyzeTest's scoped-equals-full assertions; these
 * assert that an unrelated declaration is genuinely NOT recomputed).
 */
class IncrementalDeclsPlanTest {

    private fun parse(text: String): KtFile = KotlinParserHost.parse("Plan.kt", text)

    private fun plan(v1: String, v2: String): IncrementalDecls.Plan {
        val f1 = parse(v1)
        val f2 = parse(v2)
        return IncrementalDecls.plan(
            prev = f1.declarations.map { IncrementalDecls.factsOf(it) },
            prevImports = IncrementalDecls.importsOf(f1),
            prevFileText = v1,
            topDecls = f2.declarations,
            curImports = IncrementalDecls.importsOf(f2),
            curFileText = v2,
        )
    }

    @Test
    fun signatureChangeScopesToDependentsOnly() {
        val v1 = "package demo\nfun helper() {}\nfun caller() { helper() }\nfun unrelated() { println(\"x\") }\n"
        val v2 = v1.replace("fun helper() {}", "fun helper(x: Int) {}")
        val p = plan(v1, v2)
        assertTrue(p is IncrementalDecls.Plan.Partial, "a scoped plan, not a full recompute")
        // helper (0) changed; caller (1) references helper → dependent; unrelated (2) reused.
        assertEquals(setOf(0, 1), p.recompute)
    }

    @Test
    fun bodyOnlyEditScopesToItselfWithFineReuse() {
        val v1 = "package demo\nfun a() { val x = 1 }\nfun b() { val y = 2 }\n"
        val v2 = v1.replace("val x = 1", "val x = 99999")
        val p = plan(v1, v2) as IncrementalDecls.Plan.Partial
        assertEquals(setOf(0), p.recompute) // only a() recomputes
        assertEquals(0, p.fineReuse)        // and is eligible for intra-function statement reuse
    }

    @Test
    fun deletionInsideADeclarationIsDetected() {
        val v1 = "package demo\nfun a() {\n    val x = 1\n    x.hashCode()\n}\nfun b() { println(1) }\n"
        val v2 = v1.replace("    x.hashCode()\n", "") // pure deletion collapses the new-text span to a point
        val p = plan(v1, v2) as IncrementalDecls.Plan.Partial
        assertEquals(setOf(0), p.recompute) // a() (which lost a statement) is recomputed, not reused stale
    }

    @Test
    fun operatorSignatureChangeFallsBackToFull() {
        val v1 = "package demo\nclass V { operator fun plus(o: V): V = this }\nfun use(a: V, b: V) = a + b\n"
        val v2 = v1.replace("operator fun plus(o: V): V", "operator fun plus(o: Int): V")
        // A symbolic-operator caller (`a + b`) carries no `plus` name reference, so the change can't be scoped.
        assertTrue(plan(v1, v2) is IncrementalDecls.Plan.Full)
    }

    @Test
    fun noTextChangeReusesEverything() {
        val v1 = "package demo\nfun a() { val x = 1 }\nfun b() { val y = 2 }\n"
        val p = plan(v1, v1) as IncrementalDecls.Plan.Partial
        assertTrue(p.recompute.isEmpty(), "an unchanged re-run reuses every declaration")
    }
}
