package dev.ide.lang.kotlin

import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A static constant inherited through a NESTED-class supertype chain (`FrameLayout.LayoutParams` →
 * `ViewGroup.MarginLayoutParams` → `ViewGroup.LayoutParams`, which declares `MATCH_PARENT`). Regression:
 * with the type-shape INDEX wired (the real IDE), the index is keyed by dot-form FQNs but a binary's
 * supertype arrives in bytecode `$`-nested form (`android.view.ViewGroup$MarginLayoutParams`), so the
 * inherited-member walk missed the index and dropped everything above the first nested supertype —
 * `FrameLayout.LayoutParams.MATCH_PARENT` completed to nothing and was flagged `kt.unresolved`.
 *
 * Exercises the INDEX path (not the live reader, which tolerates `$` via classBytes) and self-gates on a
 * real android.jar, so CI without an SDK skips it.
 */
class KotlinNestedInheritedStaticTest {

    private fun labels(code: String): List<String> {
        assumeTrue(androidJar != null, "no android.jar; skipping nested inherited-static test")
        return runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.label }
    }

    private fun unresolved(code: String): List<String> {
        assumeTrue(androidJar != null, "no android.jar; skipping nested inherited-static test")
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("D.kt")))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.analyze(doc.file).diagnostics }
            .filter { it.code == "kt.unresolved" }.map { it.message }
    }

    @Test
    fun inheritedStaticCompletesThroughNestedSupertypeChain() {
        val ls = labels("fun f() { android.widget.FrameLayout.LayoutParams.| }")
        assertTrue("MATCH_PARENT" in ls, "MATCH_PARENT (inherited from ViewGroup.LayoutParams) must complete; got $ls")
        assertTrue("WRAP_CONTENT" in ls, "WRAP_CONTENT (inherited) must complete; got $ls")
    }

    @Test
    fun inheritedStaticNotFlaggedUnresolved() {
        assertTrue(
            unresolved("import android.widget.FrameLayout\nfun f() { val x = FrameLayout.LayoutParams.MATCH_PARENT }").isEmpty(),
            "FrameLayout.LayoutParams.MATCH_PARENT must resolve (not flagged unresolved)",
        )
    }

    @Test
    fun declaringNestedClassStillResolves() {
        // The class that DECLARES MATCH_PARENT (no supertype walk) — a control that already worked.
        assertTrue("MATCH_PARENT" in labels("fun f() { android.view.ViewGroup.LayoutParams.| }"), "ViewGroup.LayoutParams.MATCH_PARENT")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        private val androidJar: Path? = listOfNotNull(
            System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"),
            System.getProperty("user.home") + "/Library/Android/sdk",
        ).map { Path.of(it) }.filter { Files.isDirectory(it) }
            .map { it.resolve("platforms") }.filter { Files.isDirectory(it) }
            .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
            .map { it.resolve("android.jar") }.filter { Files.isRegularFile(it) }
            .maxByOrNull { it.parent.fileName.toString() }
        private val jars = listOfNotNull(stdlibJarPath(), androidJar)
        // A READY index over android.jar — the real-IDE condition (the live reader tolerates `$`, so the bug
        // only reproduces through the persistent index).
        private val index = IndexServiceImpl(listOf(KotlinTypeShapeIndex, KotlinCallableIndex), Files.createTempDirectory("nested-idx"))
            .also { if (androidJar != null) runBlocking { it.ensureUpToDate(IndexScope(libraryJars = jars)) } }
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = jars)).also { it.indexService = index }
    }
}
