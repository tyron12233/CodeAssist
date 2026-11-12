package dev.ide.lang.kotlin

import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.index.KotlinBuiltinsIndex
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `use` on an `AutoCloseable` that is not a `Closeable` resolves, whichever `use` the file imports.
 *
 * The receiver here is a project class written `: AutoCloseable`. With the package-declaration index present
 * (as in the IDE, and as in this fixture) that bare name is the stdlib's `kotlin.AutoCloseable` TYPEALIAS, not
 * `java.lang.AutoCloseable`, and a supertype chain that carried only the alias name never reached the extension,
 * which is keyed under the type the alias expands to. Reported from a device as "Unresolved reference: use".
 * The stdlib
 * compiles that overload into `kotlin/jdk7/AutoCloseableKt` under `@JvmPackageName`, but it is `kotlin.use`:
 * reading its package off the class entry made `import kotlin.use` an unresolved reference on the indexed path.
 */
class KotlinAutoCloseableUseImportTest {

    private fun diagnose(code: String): List<Diagnostic> {
        assumeTrue(androidJar != null, "no android.jar on this machine")
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private val body = "class FakeResource : AutoCloseable {\n  override fun close() {}\n}\n" +
        "fun testUse() {\n  FakeResource().use {\n    println(\"Inside use\")\n  }\n}\n"

    private fun assertUseResolves(diags: List<Diagnostic>) {
        assertTrue(diags.none { it.code == "kt.unresolved" }, "got $diags")
    }

    @Test
    fun withTheCloseableImport() {
        assertUseResolves(diagnose("package demo\nimport kotlin.io.use\n$body"))
    }

    @Test
    fun sanityANonCloseableIsFlagged() {
        val d = diagnose("package demo\nclass Plain\nfun t() {\n  Plain().use {\n    println(1)\n  }\n}\n")
        assertTrue(d.any { it.code == "kt.unresolved" && "use" in it.message }, "got $d")
    }

    @Test
    fun withNoImport() {
        assertUseResolves(diagnose("package demo\n$body"))
    }

    @Test
    fun withTheAutoCloseableImport() {
        assertUseResolves(diagnose("package demo\nimport kotlin.use\n$body"))
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Use.kt" to "package demo\n"))
        private val androidJar: Path? = sdkRoots().map { it.resolve("platforms") }.filter { Files.isDirectory(it) }
            .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
            .map { it.resolve("android.jar") }.filter { Files.isRegularFile(it) }
            .maxByOrNull { it.parent.fileName.toString() }
        private val jars = listOfNotNull(stdlibJarPath(), androidJar)
        private val index = IndexServiceImpl(listOf(KotlinTypeShapeIndex, KotlinBuiltinsIndex, KotlinCallableIndex, dev.ide.lang.kotlin.index.KotlinPackageDeclIndex, dev.ide.lang.kotlin.index.KotlinBuiltinCallableIndex), Files.createTempDirectory("use-idx"))
            .also { if (androidJar != null) runBlocking { it.ensureUpToDate(IndexScope(libraryJars = jars)) } }
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = jars)).also { it.indexService = index }

        private fun sdkRoots() = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            System.getProperty("user.home") + "/Library/Android/sdk",
        ).map { Path.of(it) }.filter { Files.isDirectory(it) }
    }
}
