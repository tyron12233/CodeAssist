package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.parse.KotlinParserHost
import org.jetbrains.kotlin.psi.KtFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [IncrementalDecls.plan] must invalidate a declaration's cached diagnostics when an added/removed import can
 * change resolution of a CONVENTION-invoked symbol it uses (an operator `+`, a `by` delegate's
 * getValue/setValue, destructuring componentN, a for-loop iterator). Those symbols carry NO name reference the
 * name-scoping heuristic could match, so auto-importing e.g. `androidx.compose.runtime.getValue` for
 * `var x by mutableStateOf(0)` used to leave the stale "no getValue operator" error until the next keystroke.
 * A plain class/function import must still stay narrowly scoped (perf).
 */
class IncrementalDeclsImportPlanTest {

    private fun parse(text: String): KtFile = KotlinParserHost.parse("A.kt", text)

    private fun planFor(v1: String, v2: String): IncrementalDecls.Plan {
        val kt1 = parse(v1)
        val kt2 = parse(v2)
        val prev = kt1.declarations.map { IncrementalDecls.factsOf(it) }
        return IncrementalDecls.plan(
            prev, IncrementalDecls.importsOf(kt1), v1,
            kt2.declarations, IncrementalDecls.importsOf(kt2), v2,
        )
    }

    @Test
    fun addingAConventionOperatorImportRecomputesTheFile() {
        // `var x by mutableStateOf(0)` uses the `by` convention → getValue/setValue, invoked with no name ref.
        val v1 = "package demo\nfun f() { var x by mutableStateOf(0) }\n"
        val v2 = "package demo\nimport androidx.compose.runtime.getValue\nfun f() { var x by mutableStateOf(0) }\n"
        assertTrue(
            planFor(v1, v2) is IncrementalDecls.Plan.Full,
            "importing a convention operator (getValue) must recompute — the delegate uses it by convention, so name-scoping can't see it",
        )
    }

    @Test
    fun addingAnOperatorFunctionImportRecomputesTheFile() {
        // `a + b` resolves `plus` by the `+` symbol — no `plus` name reference for scoping to match.
        val v1 = "package demo\nval z = a + b\n"
        val v2 = "package demo\nimport ext.plus\nval z = a + b\n"
        assertTrue(planFor(v1, v2) is IncrementalDecls.Plan.Full, "importing an operator function (plus) must recompute")
    }

    @Test
    fun addingAComponentNImportRecomputesTheFile() {
        val v1 = "package demo\nval z = run { val (a, b) = pair; a }\n"
        val v2 = "package demo\nimport ext.component1\nval z = run { val (a, b) = pair; a }\n"
        assertTrue(planFor(v1, v2) is IncrementalDecls.Plan.Full, "importing componentN must recompute (destructuring is convention-invoked)")
    }

    @Test
    fun addingAPlainClassImportStaysNarrowlyScoped() {
        // A non-operator import keeps the fast scoped path: only declarations referencing the name recompute.
        val v1 = "package demo\nfun f(): Random? = null\nfun g() {}\n"
        val v2 = "package demo\nimport kotlin.random.Random\nfun f(): Random? = null\nfun g() {}\n"
        val plan = planFor(v1, v2)
        assertTrue(plan is IncrementalDecls.Plan.Partial, "a plain class import must stay scoped, not fall back to Full")
        val p = plan as IncrementalDecls.Plan.Partial
        assertTrue(0 in p.recompute, "the declaration referencing Random must recompute")
        assertFalse(1 in p.recompute, "an unrelated declaration must be reused")
    }
}
