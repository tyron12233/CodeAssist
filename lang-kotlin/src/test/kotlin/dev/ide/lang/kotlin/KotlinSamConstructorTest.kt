package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SAM-constructor lambda-parameter typing against the real JDK/Android functional interfaces (kotlin-stdlib +
 * android.jar): `Comparator<String> { a, b -> … }` binds `a`/`b` to `String` from the interface's single
 * abstract method (`compare(T, T)`) with the explicit type argument substituted. Regression: those params were
 * untyped, so `a.<caret>` completed nothing and diagnostics inside `{ }` backed off. Self-gates on android.jar.
 */
class KotlinSamConstructorTest {

    private fun labels(code: String): List<String> {
        assumeTrue(androidJar != null, "no android.jar on this machine; skipping SAM-constructor completion")
        return runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.label }
    }

    @Test
    fun comparatorSamConstructorTypesLambdaParams() {
        val ls = labels("fun main() {\n  val c = Comparator<String> { a, b -> a.| }\n}")
        assertTrue("length" in ls, "`Comparator<String> { a, b -> a.<caret> }` should type `a` as String; got ${ls.take(30)}")
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
