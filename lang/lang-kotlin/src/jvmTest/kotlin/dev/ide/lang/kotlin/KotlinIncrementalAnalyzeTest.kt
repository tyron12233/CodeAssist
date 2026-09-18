package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The incremental (scoped) analyze path must be INDISTINGUISHABLE from a full re-analyze. A body-only edit of
 * one function re-analyzes just that function and reuses every other declaration's diagnostics, re-anchored to
 * its shifted offset — so this compares scoped output (analyzer that saw v1 then v2) against full output (a
 * fresh analyzer that sees only v2), for several edit shapes, asserting the diagnostic sets are identical.
 */
class KotlinIncrementalAnalyzeTest {

    private fun key(d: Diagnostic) = "${d.range.start}-${d.range.end}:${d.severity}:${d.code}:${d.message}"

    private fun diagsOf(srcDir: Path, file: String, text: String, analyzer: KotlinSourceAnalyzer): List<String> {
        val doc = SnippetDoc(text, DiskFile(srcDir.resolve(file)))
        return runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.analyze(doc.file).diagnostics
        }.map(::key).sorted()
    }

    /** v1 → v2 on one analyzer (scoped) must equal v2 on a fresh analyzer (full). */
    private fun assertScopedEqualsFull(v1: String, v2: String) {
        val srcDir = tempProject(mapOf("Big.kt" to v2)) // disk = final version (the model resolves against it)
        val incremental = KotlinSourceAnalyzer(fakeContext(srcDir))
        diagsOf(srcDir, "Big.kt", v1, incremental)            // seed the cache with v1
        val scoped = diagsOf(srcDir, "Big.kt", v2, incremental) // v2 → exercises the scoped/reuse path

        val full = diagsOf(srcDir, "Big.kt", v2, KotlinSourceAnalyzer(fakeContext(srcDir))) // fresh → full walk
        assertEquals(full, scoped, "scoped analyze diverged from full analyze")
    }

    @Test
    fun bodyEditThatRemovesADiagnosticMatchesFull() {
        val v1 = file(bodyA = "val x = bogusUnresolved", bodyB = "val unusedB = 1")
        val v2 = file(bodyA = "val x = 1", bodyB = "val unusedB = 1") // fixed the unresolved ref in A
        assertScopedEqualsFull(v1, v2)
    }

    @Test
    fun bodyEditThatAddsADiagnosticMatchesFull() {
        val v1 = file(bodyA = "val x = 1", bodyB = "val unusedB = 1")
        val v2 = file(bodyA = "val x = stillMissing", bodyB = "val unusedB = 1") // introduced an unresolved ref
        assertScopedEqualsFull(v1, v2)
    }

    @Test
    fun bodyEditThatShiftsLaterDeclarationsMatchesFull() {
        // Growing A's body shifts B and C downward — the reused diagnostics for B/C must be re-anchored.
        val v1 = file(bodyA = "val x = 1", bodyB = "var couldBeVal = 2")
        val v2 = file(bodyA = "val x = 1\n    val y = 2\n    val z = nopeMissing\n    println(y)", bodyB = "var couldBeVal = 2")
        assertScopedEqualsFull(v1, v2)
    }

    @Test
    fun crossFileDependencyEditInvalidatesDependentCache() {
        // Editing a class's property type in ANOTHER (open) file must refresh a dependent file's diagnostics,
        // even though the dependent file's OWN text is unchanged (its analyze cache would otherwise be reused).
        // `Main.kt` does `with(Test()) { s = null }`; flipping Test.s String? → String must make it flag.
        val dir = tempProject(mapOf(
            "Test.kt" to "package demo\nclass Test { var s: String? = null }",
            "Main.kt" to "package demo\nfun use() { with(Test()) { s = null } }",
        ))
        val analyzer = KotlinSourceAnalyzer(fakeContext(dir))
        val testPath = dir.resolve("Test.kt").toString()
        fun analyzeMainCodes(): List<String?> {
            val doc = SnippetDoc("package demo\nfun use() { with(Test()) { s = null } }", DiskFile(dir.resolve("Main.kt")))
            return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }.map { it.code }
        }
        analyzer.liveOverlayProvider = { mapOf(testPath to "package demo\nclass Test { var s: String? = null }") }
        assertEquals(false, "kt.typeMismatch" in analyzeMainCodes(), "s: String? accepts null")
        // Edit Test.kt (the dependency) in the overlay: s becomes non-null. Main.kt's text is unchanged.
        analyzer.liveOverlayProvider = { mapOf(testPath to "package demo\nclass Test { var s: String = \"\" }") }
        assertEquals(true, "kt.typeMismatch" in analyzeMainCodes(), "after the dependency's type changed, the dependent's cached diagnostics must refresh")
    }

    @Test
    fun bodyEditChangingCrossDeclarationUsageMatchesFull() {
        // screenB's body starts referencing the private helper → helper's "unused" warning must clear, even
        // though helper's own text is unchanged (a whole-file check that the scoped path must not stale-reuse).
        val withHelper = { bodyB: String ->
            "package demo\nprivate fun helper() {}\nfun screenA() {}\nfun screenB() {\n    $bodyB\n}\n"
        }
        assertScopedEqualsFull(withHelper("val x = 1"), withHelper("helper()"))
        assertScopedEqualsFull(withHelper("helper()"), withHelper("val x = 1"))
    }

    @Test
    fun signatureEditFallsBackToFull() {
        // Changing A's parameter list is a header change → must NOT scope; still must equal full.
        val v1 = file(sigA = "fun screenA(seed: Int)", bodyA = "val x = 1", bodyB = "val q = 1")
        val v2 = file(sigA = "fun screenA(seed: Int, extra: String)", bodyA = "val x = 1", bodyB = "val q = 1")
        assertScopedEqualsFull(v1, v2)
    }

    // ---- intra-function statement reuse (one big body; a keystroke re-checks only the touched statement) ----

    /** A single function with many body statements, so an edit exercises the per-STATEMENT reuse path. */
    private fun bigFn(vararg stmts: String): String = buildString {
        appendLine("package demo")
        appendLine("fun screen() {")
        stmts.forEach { appendLine("    $it") }
        appendLine("}")
    }

    @Test
    fun statementEditDeepInBodyMatchesFull() {
        val v1 = bigFn("val a = 1", "val b = 2", "val c = bogusRef", "println(a + b)")
        val v2 = bigFn("val a = 1", "val b = 2", "val c = 3", "println(a + b)") // fixed only statement 3
        assertScopedEqualsFull(v1, v2)
    }

    @Test
    fun laterStatementUsingAnEarlierUnusedLocalClearsTheWarning() {
        // `helper` is unused in v1 (warning); v2 adds a LATER use → the warning on the UNCHANGED earlier `val`
        // must clear. unused-local reads sibling statements, so this proves it is NOT stale-reused per statement.
        val v1 = bigFn("val helper = 41", "println(\"hi\")")
        val v2 = bigFn("val helper = 41", "println(helper)")
        assertScopedEqualsFull(v1, v2)
        assertScopedEqualsFull(v2, v1) // and back: the use is removed → warning must reappear
    }

    @Test
    fun laterReassignmentFlipsVarCouldBeValOnEarlierDecl() {
        // var-could-be-val also reads later statements: adding a reassignment must clear the hint on the
        // unchanged `var` declaration.
        val v1 = bigFn("var count = 0", "println(count)")
        val v2 = bigFn("var count = 0", "count = 5", "println(count)")
        assertScopedEqualsFull(v1, v2)
        assertScopedEqualsFull(v2, v1)
    }

    @Test
    fun earlyDeclTypeChangePropagatesToLaterStatements() {
        // Changing the type of an early local must re-resolve LATER statements' member access against it
        // (scopeDirty propagation) — `s.length` is valid on String, unresolved on Int.
        val v1 = bigFn("val s = \"x\"", "println(s.length)")
        val v2 = bigFn("val s = 1", "println(s.length)") // now s: Int — but Int has no `length`? (still must match full)
        assertScopedEqualsFull(v1, v2)
    }

    @Test
    fun importEditFallsBackToFull() {
        val v1 = "package demo\nimport kotlin.math.PI\n" + body()
        val v2 = "package demo\n" + body() // removed the import → file-level + every decl's resolution may change
        assertScopedEqualsFull(v1, v2)
    }

    @Test
    fun signatureChangeRechecksCallerInAnotherDeclaration() {
        // `caller` calls `helper()`; changing helper's signature to require an argument makes caller's call
        // a missing-argument error. caller's own text is unchanged, so it is reused unless the dependency plan
        // re-checks it as a dependent of helper. `unrelated` must stay reused (it references neither).
        fun src(helperSig: String) =
            "package demo\n$helperSig {}\nfun caller() { helper() }\nfun unrelated() { println(\"hi\") }\n"
        val v1 = src("fun helper()")
        val v2 = src("fun helper(x: Int)")
        assertScopedEqualsFull(v1, v2)
        assertScopedEqualsFull(v2, v1)
    }

    // ---- intra-CLASS member reuse (one top-level class; an edit re-checks only the touched member) ----
    //
    // This is the shape of most real Kotlin files: ONE top-level class. Before per-member reuse, a class's
    // change key was its whole text, so every keystroke anywhere inside it read as a signature change and the
    // entire class — i.e. the entire file — was re-checked. Each case below edits one spot and asserts the
    // scoped result still equals a full walk.

    /** A class with several members, so an edit exercises the per-MEMBER reuse path. */
    private fun bigClass(
        header: String = "class View : Any()",
        prop: String = "private val label: String = \"hi\"",
        initBlock: String = "init { println(label) }",
        bodyA: String = "val x = 1",
        bodyB: String = "val y = 2",
        sigB: String = "fun beta(): Int",
        extra: String = "",
    ): String = buildString {
        appendLine("package demo")
        appendLine()
        appendLine("$header {")
        appendLine("    $prop")
        appendLine("    $initBlock")
        appendLine("    fun alpha() {")
        appendLine("        $bodyA")
        appendLine("    }")
        appendLine("    $sigB {")
        appendLine("        $bodyB")
        appendLine("        return 0")
        appendLine("    }")
        appendLine("    private class Nested(val n: Int)")
        if (extra.isNotEmpty()) appendLine("    $extra")
        appendLine("}")
    }

    @Test
    fun methodBodyEditInsideAClassMatchesFull() {
        assertScopedEqualsFull(bigClass(bodyA = "val x = bogusInMethod"), bigClass(bodyA = "val x = 1"))
        assertScopedEqualsFull(bigClass(bodyA = "val x = 1"), bigClass(bodyA = "val x = alsoMissing"))
    }

    @Test
    fun methodBodyEditShiftingLaterMembersMatchesFull() {
        // Growing alpha's body shifts the property/init/beta/Nested below it — every reused member's
        // diagnostics must be re-anchored to its new offset.
        val v1 = bigClass(bodyA = "val x = 1")
        val v2 = bigClass(bodyA = "val x = 1\n        val y2 = 2\n        val z = missingHere\n        println(y2)")
        assertScopedEqualsFull(v1, v2)
        assertScopedEqualsFull(v2, v1)
    }

    @Test
    fun memberSignatureEditInsideAClassMatchesFull() {
        // beta's parameter list changes — a header change, so the class re-checks fully. Still must equal full.
        assertScopedEqualsFull(bigClass(sigB = "fun beta(): Int"), bigClass(sigB = "fun beta(seed: Int): Int"))
    }

    @Test
    fun propertyInitializerEditInsideAClassMatchesFull() {
        // A property initializer is NOT a body: it types the property, so it stays in the class's header and
        // an edit to it invalidates the members that resolve against it. `label.length` is valid on String,
        // not on Int.
        val v1 = bigClass(prop = "private val label: String = \"hi\"", bodyA = "val x = label.length")
        val v2 = bigClass(prop = "private val label = 7", bodyA = "val x = label.length")
        assertScopedEqualsFull(v1, v2)
        assertScopedEqualsFull(v2, v1)
    }

    @Test
    fun initBlockEditInsideAClassMatchesFull() {
        assertScopedEqualsFull(bigClass(initBlock = "init { println(label) }"), bigClass(initBlock = "init { println(nopeMissing) }"))
    }

    @Test
    fun addingAndRemovingAMemberMatchesFull() {
        // A structural change to the member list: the per-member cache can't align, so the class recomputes.
        assertScopedEqualsFull(bigClass(), bigClass(extra = "fun added(): Int = 3"))
        assertScopedEqualsFull(bigClass(extra = "fun added(): Int = 3"), bigClass())
    }

    @Test
    fun aMemberBecomingUsedClearsTheUnusedWarningOnAnUnchangedMember() {
        // `Nested` is unused in v1 (a private declaration → warning); v2 references it from a method body.
        // The warning sits on Nested, whose OWN text never changes — so this only passes if the whole-file
        // checks are recomputed rather than reused per member.
        val v1 = bigClass(bodyA = "val x = 1")
        val v2 = bigClass(bodyA = "val x = Nested(1)")
        assertScopedEqualsFull(v1, v2)
        assertScopedEqualsFull(v2, v1)
    }

    @Test
    fun supertypeEditInsideAClassMatchesFull() {
        assertScopedEqualsFull(bigClass(header = "class View : Any()"), bigClass(header = "class View"))
    }

    @Test
    fun repeatedMethodBodyEditsStayEqualToFull() {
        // Several keystrokes in a row through the SAME analyzer: each reuses the previous pass's member cache,
        // so a re-anchoring or alignment bug compounds rather than cancelling out.
        val srcDir = tempProject(mapOf("Big.kt" to bigClass(bodyA = "val x = 4")))
        val incremental = KotlinSourceAnalyzer(fakeContext(srcDir))
        for (v in listOf("val x = 1", "val x = 12", "val x = 123", "val x = missing1", "val x = 4")) {
            diagsOf(srcDir, "Big.kt", bigClass(bodyA = v), incremental)
        }
        val scoped = diagsOf(srcDir, "Big.kt", bigClass(bodyA = "val x = 4"), incremental)
        val full = diagsOf(srcDir, "Big.kt", bigClass(bodyA = "val x = 4"), KotlinSourceAnalyzer(fakeContext(srcDir)))
        assertEquals(full, scoped, "scoped analyze diverged from full analyze after repeated member edits")
    }

    companion object {
        private fun body() = KotlinIncrementalAnalyzeTest().file()
    }

    /** A small multi-declaration file with pluggable pieces, so each test perturbs exactly one spot. */
    private fun file(
        sigA: String = "fun screenA(seed: Int)",
        bodyA: String = "val x = 1",
        bodyB: String = "val y = 1",
    ): String = buildString {
        appendLine("package demo")
        appendLine()
        appendLine("data class Model(val id: String, val value: Int)")
        appendLine()
        appendLine("$sigA {")
        appendLine("    $bodyA")
        appendLine("}")
        appendLine()
        appendLine("fun screenB() {")
        appendLine("    $bodyB")
        appendLine("}")
        appendLine()
        appendLine("fun screenC(): Int {")
        appendLine("    return 42")
        appendLine("}")
    }
}
