package dev.ide.lang.kotlin

import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Reproduction/regression against the REAL `androidx.compose.foundation:foundation-android:1.7.5` classes
 * (extracted from the AAR by the build; self-gates when absent). `LazyColumn { items(sItem) { } }` where
 * `sItem: MutableList<String>` and `items` is NOT imported: `items(count: Int, ...)` is a MEMBER of
 * `LazyListScope` (in scope), while `items(List<T>, ...)` is a top-level EXTENSION (needs importing). kotlinc
 * binds the Int member and reports "Argument type mismatch: MutableList<String> but Int expected"; the editor
 * must match (and offer the import).
 */
class KotlinRealFoundationItemsTest {

    private val SRC =
        """
        package demo
        import androidx.compose.foundation.lazy.LazyColumn
        import androidx.compose.runtime.Composable
        @Composable fun c(sItem: MutableList<String>) {
            LazyColumn { items(sItem) { } }
        }
        """.trimIndent()

    @Test
    fun itemsListArgInLazyColumnMatchesCompiler() {
        assumeTrue(analyzer != null, "real compose-foundation jar not on the test classpath")
        val a = analyzer!!
        val doc = SnippetDoc(SRC, DiskFile(srcDir.resolve("Use.kt")))
        val diags = runBlocking { a.incrementalParser.parseFull(doc); a.analyze(doc.file).diagnostics }
        val rendered = diags.joinToString("\n") { "  ${it.code}: ${it.message}" }.ifEmpty { "  (no diagnostics)" }
        // `items` is NOT imported: only the LazyListScope.items(Int, …) MEMBER is in scope, so the List arg
        // mismatches it (the List extension needs importing) — exactly what kotlinc reports.
        assertTrue(
            diags.any { it.code == "kt.typeMismatch" && it.message.contains("MutableList") },
            "editor must report the arg-type mismatch the compiler does (MutableList<String> vs Int); got:\n$rendered",
        )
    }

    @Test
    fun bareLazyColumnReferenceIsFlagged() {
        // `LazyColumn` (an imported top-level @Composable function) referenced WITHOUT a call is not a value;
        // kotlinc reports "Function invocation 'LazyColumn(...)' expected". The editor must match.
        assumeTrue(analyzer != null, "real compose-foundation jar not on the test classpath")
        val a = analyzer!!
        val src =
            """
            package demo
            import androidx.compose.foundation.lazy.LazyColumn
            import androidx.compose.runtime.Composable
            @Composable fun c() {
                LazyColumn
            }
            """.trimIndent()
        val doc = SnippetDoc(src, DiskFile(srcDir.resolve("Bare.kt")))
        val diags = runBlocking { a.incrementalParser.parseFull(doc); a.analyze(doc.file).diagnostics }
        val rendered = diags.joinToString("\n") { "  ${it.code}: ${it.message}" }.ifEmpty { "  (no diagnostics)" }
        assertTrue(
            diags.any { it.code == "kt.functionCallExpected" && it.message.contains("LazyColumn") },
            "a bare `LazyColumn` reference must be flagged (Function invocation expected); got:\n$rendered",
        )
    }

    @Test
    fun itemsListArgOffersTheExtensionImport() {
        assumeTrue(analyzer != null, "real compose-foundation jar not on the test classpath")
        val a = analyzer!!
        val doc = SnippetDoc(SRC, DiskFile(srcDir.resolve("Use.kt")))
        runBlocking { a.incrementalParser.parseFull(doc); a.analyze(doc.file) }
        val caret = SRC.indexOf("sItem)") // on the mismatched argument of `items(sItem)`
        val fixes = a.importFixesAt(doc.file, caret)
        assertTrue(
            fixes.any { it.title == "Import androidx.compose.foundation.lazy.items" },
            "should offer to import the matching `items` extension overload; got ${fixes.map { it.title }}",
        )
    }

    /**
     * The caret on the CALLEE is a fix site too. The mismatch is reported on the ARGUMENT, but `items` is the
     * name that needs importing, so that is where the caret sits when the user asks for the fix; requiring the
     * argument made the lightbulb look absent on the very call it was written for.
     */
    @Test
    fun theImportIsOfferedWithTheCaretOnTheCalleeName() {
        assumeTrue(analyzer != null, "real compose-foundation jar not on the test classpath")
        assertEquals(
            listOf("Import androidx.compose.foundation.lazy.items"),
            fixTitlesAt(SRC, "items(sItem", "Callee.kt"),
            "the caret on `items` must offer the same import the caret on the argument does",
        )
    }

    /**
     * Only the `items` an implicit `this` can reach is offered. `LazyColumn`'s content lambda has a
     * `LazyListScope` receiver, so the `grid` / `staggeredgrid` namesakes are unreachable from this call and
     * importing one leaves the error in place.
     */
    @Test
    fun onlyTheOverloadTheReceiverInScopeCanReachIsOffered() {
        assumeTrue(analyzer != null, "real compose-foundation jar not on the test classpath")
        assertEquals(
            listOf("Import androidx.compose.foundation.lazy.items"),
            fixTitlesAt(SRC, "sItem)", "Reachable.kt"),
            "the lazy-grid namesakes cannot apply inside a LazyColumn and must not be offered",
        )
    }

    /**
     * An argument that is itself a CALL. The mismatch sits inside `notes()`, whose own import ("Import
     * demo.notes") fixes nothing: the fix belongs to the call the argument was passed to.
     */
    @Test
    fun anArgumentThatIsItselfACallOffersTheOuterCalleesImport() {
        assumeTrue(analyzer != null, "real compose-foundation jar not on the test classpath")
        val src =
            """
            package demo
            import androidx.compose.foundation.lazy.LazyColumn
            import androidx.compose.runtime.Composable
            data class Note(val id: Int)
            fun notes(): List<Note> = emptyList()
            fun row(n: Note) {}
            @Composable fun c() {
                LazyColumn { items(notes()) { n -> row(n) } }
            }
            """.trimIndent()
        assertEquals(
            listOf("Import androidx.compose.foundation.lazy.items"),
            fixTitlesAt(src, "notes())", "InnerCall.kt"),
            "the import must be the outer callee's, not the inner call's (which is same-package anyway)",
        )
    }

    /**
     * `items(items = list)`: the parameter name exists only on the unimported overload, so the call is flagged
     * for an unknown named argument rather than a type mismatch, and importing that overload is the fix.
     */
    @Test
    fun anUnknownNamedArgumentOffersTheOverloadThatDeclaresIt() {
        assumeTrue(analyzer != null, "real compose-foundation jar not on the test classpath")
        val src =
            """
            package demo
            import androidx.compose.foundation.lazy.LazyColumn
            import androidx.compose.runtime.Composable
            @Composable fun c(list: List<String>) {
                LazyColumn { items(items = list) { } }
            }
            """.trimIndent()
        val diags = diagnosticsOf(src, "Named.kt")
        assertTrue(
            diags.any { it.code == KotlinDiagnosticCodes.NAMED_ARGUMENT },
            "fixture check: the named argument must be flagged, else the fix list is trivially empty",
        )
        assertEquals(
            listOf("Import androidx.compose.foundation.lazy.items"),
            fixTitlesAt(src, "items = list", "Named.kt"),
            "the overload declaring an `items` parameter is the one to import",
        )
    }

    /**
     * `itemsIndexed` has no `LazyListScope` member to bind, so it is unresolved. ONE error: the unimported
     * extension of that name is not a value, so it must not also be reported as an expression of type Unit
     * that cannot be invoked. And one import, the reachable one.
     */
    @Test
    fun unresolvedItemsIndexedReportsOneErrorAndOffersTheLazyImport() {
        assumeTrue(analyzer != null, "real compose-foundation jar not on the test classpath")
        val src =
            """
            package demo
            import androidx.compose.foundation.lazy.LazyColumn
            import androidx.compose.runtime.Composable
            @Composable fun c(list: List<String>) {
                LazyColumn { itemsIndexed(list) { i, s -> } }
            }
            """.trimIndent()
        val diags = diagnosticsOf(src, "Indexed.kt")
        val rendered = diags.joinToString("\n") { "  ${it.code}: ${it.message}" }.ifEmpty { "  (no diagnostics)" }
        assertTrue(diags.any { it.code == KotlinDiagnosticCodes.UNRESOLVED }, "the reference is unresolved:\n$rendered")
        assertTrue(
            diags.none { it.code == KotlinDiagnosticCodes.NOT_CALLABLE },
            "an unimported extension namesake is not a callable VALUE of its return type; got:\n$rendered",
        )
        assertEquals(
            listOf("Import androidx.compose.foundation.lazy.itemsIndexed"),
            fixTitlesAt(src, "itemsIndexed", "Indexed.kt"),
        )
    }

    /**
     * The counterweight to the narrowing: with no receiver in scope to judge them by, every namesake stays on
     * offer. The narrowing fires on the evidence that one candidate matches, never by dropping candidates it
     * could not judge.
     */
    @Test
    fun withNoReceiverInScopeEveryNamesakeIsStillOffered() {
        assumeTrue(analyzer != null, "real compose-foundation jar not on the test classpath")
        val src =
            """
            package demo
            fun f(list: List<String>) {
                itemsIndexed(list) { i, s -> }
            }
            """.trimIndent()
        val titles = fixTitlesAt(src, "itemsIndexed", "Free.kt")
        assertTrue(
            titles.containsAll(
                listOf(
                    "Import androidx.compose.foundation.lazy.itemsIndexed",
                    "Import androidx.compose.foundation.lazy.grid.itemsIndexed",
                ),
            ),
            "no implicit receiver pins the call, so nothing may be ruled out; got $titles",
        )
    }

    /** The "Import …" titles offered with the caret just inside [needle]'s first occurrence in [src]. */
    private fun fixTitlesAt(src: String, needle: String, fileName: String): List<String> {
        val a = analyzer!!
        val doc = SnippetDoc(src, DiskFile(srcDir.resolve(fileName)))
        runBlocking { a.incrementalParser.parseFull(doc); a.analyze(doc.file) }
        return a.importFixesAt(doc.file, src.indexOf(needle) + 1).map { it.title }
    }

    private fun diagnosticsOf(src: String, fileName: String): List<Diagnostic> {
        val a = analyzer!!
        val doc = SnippetDoc(src, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { a.incrementalParser.parseFull(doc); a.analyze(doc.file).diagnostics }
    }

    companion object {
        private val foundationJar: Path? = System.getProperty("compose.foundation.classes.jar")
            ?.let { Path.of(it) }?.takeIf { Files.exists(it) }
        private val runtimeJar: Path? =
            runCatching { TestJars.onClasspath("androidx/compose/runtime/Composable.class") }.getOrNull()
        private val srcDir: Path = Files.createTempDirectory("real-foundation-src")
        private val libJars: List<Path> = listOfNotNull(foundationJar, runtimeJar, stdlibJarPath())

        private val index =
            if (foundationJar != null && runtimeJar != null)
                IndexServiceImpl(
                    listOf(KotlinTypeShapeIndex, KotlinCallableIndex),
                    cacheRoot = Files.createTempDirectory("idx"),
                ).also { runBlocking { it.ensureUpToDate(IndexScope(libraryJars = libJars)) } }
            else null
        private val analyzer =
            if (index != null) KotlinSourceAnalyzer(fakeContext(srcDir, libJars = libJars)).also { it.indexService = index }
            else null
    }
}
