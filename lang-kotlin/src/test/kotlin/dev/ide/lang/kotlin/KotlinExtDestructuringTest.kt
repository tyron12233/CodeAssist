package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression coverage for user-created (member) extension functions and destructuring typing — the gaps
 * reported on a `Toolbox` map example: a member-extension `Map<…>.printMap()` declared inside a class wasn't
 * completed/resolved (and was false-flagged unresolved), and destructured names (`{ (k, v) -> }`,
 * `val (a, b) = …`, `for ((k, v) in …)`) had no type so members off them yielded nothing.
 */
class KotlinExtDestructuringTest {

    private fun labels(file: String, code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, file, code) }.items.map { it.label }

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    // --- member extensions declared inside a class ---

    @Test
    fun memberExtensionCompletesOnReceiverInScope() {
        val ls = labels(
            "Toolbox.kt",
            """
            package demo
            class Toolbox {
                fun use(m: Map<String, String>) { m.print| }
                fun Map<String, String>.printMap() {}
            }
            """.trimIndent(),
        )
        assertTrue("printMap" in ls || ls.any { it.startsWith("printMap") },
            "member extension printMap should complete on a Map receiver inside its class; got ${ls.take(30)}")
    }

    @Test
    fun memberExtensionCallIsNotUnresolved() {
        val diags = diagnose(
            "Toolbox.kt",
            """
            package demo
            class Toolbox {
                fun use(m: Map<String, String>) { m.printMap() }
                fun Map<String, String>.printMap() {}
            }
            """.trimIndent(),
        )
        assertTrue(diags.none { it.code == "kt.unresolved" && it.message.contains("printMap") },
            "member extension call must not be flagged unresolved; got $diags")
    }

    // --- destructuring of lambda parameters ---

    @Test
    fun lambdaDestructuringEntriesAreTyped() {
        // forEach on a Map → entry is Map.Entry<String,String>; destructured (k, v) are both String.
        val ls = labels(
            "Use.kt",
            "package demo\nfun f(m: Map<String, String>) { m.forEach { (k, v) -> k.len| } }",
        )
        assertTrue("length" in ls, "destructured key `k` should be typed String (have `length`); got ${ls.take(30)}")
    }

    @Test
    fun lambdaDestructuringSecondEntryTyped() {
        val ls = labels(
            "Use.kt",
            "package demo\nfun f(m: Map<String, Int>) { m.forEach { (k, v) -> v.toLo| } }",
        )
        assertTrue(ls.any { it.startsWith("toLong") }, "destructured value `v` should be typed Int; got ${ls.take(30)}")
    }

    // --- the non-destructuring counterpart: entry.value.<caret> ---

    @Test
    fun mapEntryValueMembersComplete() {
        val ls = labels(
            "Use.kt",
            "package demo\nfun f(m: Map<String, String>) { m.forEach { entry -> entry.value.len| } }",
        )
        assertTrue("length" in ls, "entry.value should be String (have `length`); got ${ls.take(30)}")
    }

    // --- destructuring declarations + for-loop destructuring ---

    @Test
    fun destructuringDeclarationEntriesAreTyped() {
        val ls = labels("Use.kt", "package demo\nfun f() { val (a, b) = \"x\" to 1; a.len| }")
        assertTrue("length" in ls, "destructured `a` from a Pair should be String; got ${ls.take(30)}")
    }

    @Test
    fun forLoopDestructuringEntriesAreTyped() {
        val ls = labels(
            "Use.kt",
            "package demo\nfun f(m: Map<String, String>) { for ((k, v) in m) { k.len| } }",
        )
        assertTrue("length" in ls, "for-loop destructured `k` should be String; got ${ls.take(30)}")
    }

    // --- new destructuring diagnostics ---

    @Test
    fun tooManyDestructuringEntriesFlagged() {
        val diags = diagnose("Use.kt", "package demo\nfun f() { val (a, b, c) = \"x\" to 1 }")
        assertTrue(diags.any { it.code == "kt.destructuring" && it.message.contains("component3") },
            "a 3rd entry on a Pair should be flagged (no component3); got $diags")
    }

    @Test
    fun validPairDestructuringNotFlagged() {
        val diags = diagnose("Use.kt", "package demo\nfun f() { val (a, b) = \"x\" to 1 }")
        assertTrue(diags.none { it.code == "kt.destructuring" },
            "a valid 2-entry Pair destructuring must not be flagged; got $diags")
    }

    @Test
    fun validMapForDestructuringNotFlagged() {
        val diags = diagnose("Use.kt", "package demo\nfun f(m: Map<String, String>) { for ((k, v) in m) { println(k + v) } }")
        assertTrue(diags.none { it.code == "kt.destructuring" },
            "a valid Map for-loop destructuring must not be flagged; got $diags")
    }

    @Test
    fun explicitlyTypedDestructuringEntryMismatchFlagged() {
        val diags = diagnose("Use.kt", "package demo\nfun f() { val (a: Int, b) = \"x\" to 1 }")
        assertTrue(diags.any { it.code == "kt.typeMismatch" },
            "an Int-typed entry over a String component should be a type mismatch; got $diags")
    }

    @Test
    fun ignoredUnderscoreEntryNotFlagged() {
        // `_` calls no componentN, so `val (a, _) = single` is fine even if there were no component2.
        val diags = diagnose("Use.kt", "package demo\nfun f() { val (a, _) = \"x\" to 1 }")
        assertTrue(diags.none { it.code == "kt.destructuring" },
            "an underscore entry must not require a component; got $diags")
    }

    companion object {
        val srcDir: Path = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
