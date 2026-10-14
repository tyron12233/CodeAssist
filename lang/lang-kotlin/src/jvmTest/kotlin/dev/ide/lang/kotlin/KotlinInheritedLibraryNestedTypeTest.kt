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
 * A nested type declared by a LIBRARY supertype is in scope by its simple name inside a subclass, and it
 * shadows the default imports. `Result` inside a `Worker` subclass is `ListenableWorker.Result`, not
 * `kotlin.Result`: `override fun doWork(): Result = Result.success()` compiles with no import. The editor
 * only walked SOURCE supertypes for nested types, so the name fell through to `kotlin.Result` and every
 * `return Result.success(...)` read as a type mismatch.
 *
 * `MediaBrowserService.Result` (android.jar) has the same shape as the WorkManager case: a Java nested class
 * named `Result`, reached from a subclass. Self-gates on a real android.jar, so CI without an SDK skips it.
 */
class KotlinInheritedLibraryNestedTypeTest {

    private fun diagnostics(code: String): List<String> {
        assumeTrue(androidJar != null, "no android.jar; skipping inherited library nested-type test")
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("D.kt")))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.analyze(doc.file).diagnostics }
            .filter { it.code == "kt.unresolved" || it.code == "kt.typeMismatch" }
            .map { "${it.code}: ${it.message}" }
    }

    @Test
    fun inheritedNestedTypeShadowsKotlinResult() {
        val ds = diagnostics(
            """
            package demo
            import android.service.media.MediaBrowserService
            abstract class Svc : MediaBrowserService() {
                fun send(r: Result<String>) { r.sendResult("x") }
                fun same(r: Result<String>): MediaBrowserService.Result<String> = r
            }
            """.trimIndent(),
        )
        assertTrue(ds.isEmpty(), "Result inside a MediaBrowserService subclass is MediaBrowserService.Result; got $ds")
    }

    @Test
    fun outsideTheSubclassResultIsStillKotlinResult() {
        val ds = diagnostics(
            """
            package demo
            import android.service.media.MediaBrowserService
            abstract class Svc : MediaBrowserService()
            fun ok(): Result<Int> = Result.success(1)
            """.trimIndent(),
        )
        assertTrue(ds.isEmpty(), "a top-level Result is kotlin.Result; got $ds")
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
        // A READY index over android.jar, as in the real IDE.
        private val index = IndexServiceImpl(listOf(KotlinTypeShapeIndex, KotlinCallableIndex), Files.createTempDirectory("inh-nested-idx"))
            .also { if (androidJar != null) runBlocking { it.ensureUpToDate(IndexScope(libraryJars = jars)) } }
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = jars)).also { it.indexService = index }
    }
}
