package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `Throwable`/`Exception` member completion. `kotlin.Throwable` is a mapped built-in whose `.kotlin_builtins`
 * shape is intentionally minimal (`message`, `cause`); its JVM-only API (`stackTrace`, `printStackTrace`,
 * `localizedMessage`, `addSuppressed`, …) lives on `java.lang.Throwable` and Kotlin exposes it through the
 * mapping. Regression: a caught `Exception` (`java.lang.Exception`, inheriting Throwable) used to complete
 * only its `Any`-level extensions — `e.stackTrace` was missing — because the inherited `java.lang.Throwable`
 * was enumerated from the incomplete built-in stub instead of the JVM bytecode.
 *
 * Needs a jar carrying `java.lang.Throwable` (the kotlin-stdlib does not); self-gates on a real android.jar
 * (`assumeTrue`), so CI without an SDK skips it.
 */
class KotlinThrowableMemberCompletionTest {

    private fun labels(code: String): List<String> {
        assumeTrue(androidJar != null, "no android.jar on this machine; skipping Throwable member completion")
        return runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.label }
    }

    @Test
    fun caughtExceptionCompletesThrowableMembers() {
        val ls = labels("fun main() {\n  try { println() } catch (e: Exception) {\n    e.|\n  }\n}")
        assertTrue("stackTrace" in ls, "e.stackTrace must complete on a caught Exception; got ${ls.take(20)}")
        assertTrue("message" in ls, "e.message must complete; got ${ls.take(20)}")
        assertTrue(ls.any { it.startsWith("printStackTrace") }, "e.printStackTrace() must complete; got ${ls.take(20)}")
    }

    @Test
    fun exceptionMemberByPrefix() {
        val ls = labels("fun main() {\n  try { println() } catch (e: Exception) {\n    e.st|\n  }\n}")
        assertTrue("stackTrace" in ls, "e.st| must offer stackTrace; got $ls")
    }

    @Test
    fun runtimeExceptionAlsoCompletesThrowableMembers() {
        val ls = labels("fun main() {\n  val e: RuntimeException = RuntimeException()\n  e.|\n}")
        assertTrue("stackTrace" in ls, "RuntimeException.stackTrace must complete; got ${ls.take(20)}")
    }

    @Test
    fun throwableCompletesJvmMembers() {
        val ls = labels("fun main() {\n  val e: Throwable = Throwable()\n  e.|\n}")
        assertTrue("stackTrace" in ls && "message" in ls, "Throwable JVM members must complete; got ${ls.take(20)}")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        private val androidJar: Path? = sdkRoots().map { it.resolve("platforms") }.filter { Files.isDirectory(it) }
            .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
            .map { it.resolve("android.jar") }.filter { Files.isRegularFile(it) }
            .maxByOrNull { it.parent.fileName.toString() }
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOfNotNull(stdlibJarPath(), androidJar)))

        private fun sdkRoots() = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            System.getProperty("user.home") + "/Library/Android/sdk",
        ).map { Path.of(it) }.filter { Files.isDirectory(it) }
    }
}
