package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Full-fidelity built-ins: `List`/`Int`/… now come from the real `.kotlin_builtins` declarations (via
 * [dev.ide.lang.kotlin.symbols.BuiltinsReader]), not the `java.util.List`/`java.lang.Integer` approximation.
 * So a read-only `List` has no mutators, `MutableList` does, `Int.` exposes its companion's `MAX_VALUE`,
 * and a builtin enum (`AnnotationTarget`, `AnnotationRetention`, `DeprecationLevel` — types with no
 * `.class` file at all) carries its constants.
 */
class BuiltinsFidelityTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    private fun diagnose(code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Diag.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun readOnlyListHasRealMembersButNoMutators() {
        assertTrue("size" in labels("fun f() { listOf(\"\").siz| }"), "read-only List has size")
        assertTrue("get" in labels("fun f() { listOf(\"\").ge| }"), "read-only List has get")
        // The whole point: java.util.List's mutators are NOT part of Kotlin's read-only List.
        assertTrue("add" !in labels("fun f() { listOf(\"\").ad| }"), "read-only List must NOT have add")
        assertTrue("set" !in labels("fun f() { listOf(\"\").se| }"), "read-only List must NOT have set")
    }

    @Test
    fun mutableListHasMutators() {
        assertTrue("add" in labels("fun f() { mutableListOf(\"\").ad| }"), "MutableList has add")
    }

    @Test
    fun intCompanionShowsOnTypeAccess() {
        // `Int.` is type access → the companion's MAX_VALUE shows (it didn't with the java.lang.Integer hack).
        assertTrue("MAX_VALUE" in labels("fun f() { val x = Int.MAX| }"), "Int. should show companion MAX_VALUE")
    }

    @Test
    fun builtinEnumEntriesCompleteOnTypeAccess() {
        // A builtin enum lives ONLY in `.kotlin_builtins` (no `.class`), where its entries are their own
        // protobuf list — so they used to decode away entirely and `AnnotationTarget.` completed empty.
        val targets = labels("fun f() { val x = AnnotationTarget.| }")
        assertTrue("CLASS" in targets && "FUNCTION" in targets, "AnnotationTarget. should offer its entries; got $targets")
        assertTrue("WARNING" in labels("fun f() { val x = DeprecationLevel.| }"), "DeprecationLevel. should offer WARNING")
    }

    @Test
    fun builtinEnumEntryIsNotUnresolved() {
        val d = diagnose(
            "package demo\n" +
                "@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)\n" +
                "@Retention(AnnotationRetention.SOURCE)\n" +
                "annotation class Ann\n" +
                "val level = DeprecationLevel.WARNING\n"
        )
        assertTrue(d.none { it.code == KotlinDiagnosticCodes.UNRESOLVED },
            "builtin enum constants resolve — none of these is an unresolved reference; got $d")
    }

    @Test
    fun listTypeAccessIsEmpty() {
        // `List.` — a Kotlin interface with no companion → nothing (matches IntelliJ).
        assertTrue(labels("fun f() { List.| }").isEmpty(), "List. (no companion) should be empty; got ${labels("fun f() { List.| }").take(10)}")
    }

    companion object {
        val srcDir: Path = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
