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
