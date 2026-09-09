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
 * A `.apply { }` block whose receiver is a NESTED-class constructor call — `FrameLayout.LayoutParams(w, h)`.
 * Regression: `Owner.Nested(args)` was mis-read as a member-function call on `Owner` (none exists), so the
 * whole expression typed to nothing, the `apply` block's implicit `this` was untyped, and bare members inside
 * it (`gravity`, the inherited `setMargins(...)`) both completed to nothing and were flagged `kt.unresolved`.
 */
class KotlinNestedCtorScopeTest {

    private fun labels(code: String): List<String> {
        assumeTrue(androidJar != null, "no android.jar; skipping nested-ctor scope test")
        return runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.label }
    }

    private fun unresolved(code: String): List<String> {
        assumeTrue(androidJar != null, "no android.jar; skipping nested-ctor scope test")
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("D.kt")))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.analyze(doc.file).diagnostics }
            .filter { it.code == "kt.unresolved" }.map { it.message }
    }

    @Test
    fun applyBlockMembersCompleteOnNestedCtorReceiver() {
        val ls = labels(
            "import android.widget.FrameLayout\n" +
                "fun f() { FrameLayout.LayoutParams(1, 1).apply { | } }",
        )
        assertTrue("gravity" in ls, "gravity (declared field of FrameLayout.LayoutParams) must complete; got $ls")
        assertTrue(ls.any { it.startsWith("setMargins(") }, "setMargins (inherited from ViewGroup.MarginLayoutParams) must complete; got $ls")
    }

    @Test
    fun applyBlockMembersNotFlaggedUnresolved() {
        val diags = unresolved(
            "import android.widget.FrameLayout\n" +
                "import android.view.Gravity\n" +
                "fun f(margin: Int) {\n" +
                "  val p = FrameLayout.LayoutParams(1, 1).apply {\n" +
                "    gravity = Gravity.BOTTOM or Gravity.END\n" +
                "    setMargins(margin, margin, margin, margin)\n" +
                "  }\n" +
                "}",
        )
        assertTrue(diags.none { "gravity" in it }, "gravity must resolve inside apply { }; got $diags")
        assertTrue(diags.none { "setMargins" in it }, "setMargins must resolve inside apply { }; got $diags")
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
        private val index = IndexServiceImpl(listOf(KotlinTypeShapeIndex, KotlinCallableIndex), Files.createTempDirectory("nested-ctor-idx"))
            .also { if (androidJar != null) runBlocking { it.ensureUpToDate(IndexScope(libraryJars = jars)) } }
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = jars)).also { it.indexService = index }
    }
}
