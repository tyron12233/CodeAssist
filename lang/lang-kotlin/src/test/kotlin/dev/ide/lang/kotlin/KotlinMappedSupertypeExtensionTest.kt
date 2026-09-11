package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An extension declared on a MAPPED built-in must be found through a JVM receiver's SUPERTYPE chain, not just
 * on the receiver itself. `@Metadata` always spells an extension's receiver with the Kotlin name
 * (`kotlin.Throwable.stackTraceToString`), while a chain read from bytecode names only the JVM half
 * (`java.lang.Exception` → `java.lang.Throwable`). Regression: `catch (e: Exception) { e.stackTraceToString() }`
 * read as `Unresolved reference: stackTraceToString` — with or without an import, since the extension is in
 * `kotlin`, a default-imported package — because `kotlin.Throwable` was never among the lookup's receiver
 * targets. `catch (e: Throwable)` escaped it: the receiver ITSELF is mapped before the lookup.
 *
 * Needs a jar carrying `java.lang.Exception` (the kotlin-stdlib does not); self-gates on a real android.jar
 * (`assumeTrue`), so CI without an SDK skips it.
 */
class KotlinMappedSupertypeExtensionTest {

    private fun diagnose(code: String): List<Diagnostic> {
        assumeTrue(androidJar != null, "no android.jar on this machine; skipping mapped-supertype extensions")
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun throwableExtensionResolvesOnACaughtException() {
        val diags = diagnose(
            "package demo\nfun main() {\n  try { println() } catch (e: Exception) {\n" +
                "    println(e.stackTraceToString())\n  }\n}"
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" && "stackTraceToString" in it.message },
            "Throwable.stackTraceToString must resolve on a caught Exception; got $diags",
        )
    }

    @Test
    fun throwableExtensionResolvesWithAnExplicitImport() {
        val diags = diagnose(
            "package demo\nimport kotlin.stackTraceToString\nfun main() {\n" +
                "  try { println() } catch (e: Exception) {\n    println(e.stackTraceToString())\n  }\n}"
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" && "stackTraceToString" in it.message },
            "an explicit import of the same extension must resolve too; got $diags",
        )
    }

    @Test
    fun throwableExtensionResolvesOnADeeperJvmSubclass() {
        val diags = diagnose(
            "package demo\nimport java.io.IOException\nfun f(e: IOException) { println(e.stackTraceToString()) }"
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" && "stackTraceToString" in it.message },
            "the mapped supertype must be reached from deeper in the chain; got $diags",
        )
    }

    @Test
    fun throwableExtensionCompletesOnACaughtException() {
        assumeTrue(androidJar != null, "no android.jar on this machine; skipping mapped-supertype extensions")
        val ls = runBlocking {
            analyzer.completeAtCaret(
                srcDir, "Use.kt",
                "package demo\nfun main() {\n  try { println() } catch (e: Exception) {\n    e.stackTr|\n  }\n}",
            )
        }.items.map { it.label }
        assertTrue(ls.any { it.startsWith("stackTraceToString") }, "e.stackTr| must offer it; got $ls")
    }

    @Test
    fun supertypeChainNamesTheKotlinClassifierToo() {
        assumeTrue(androidJar != null, "no android.jar on this machine; skipping mapped-supertype extensions")
        val service = KotlinSymbolService(
            sourceRoots = emptyList(),
            classpathJars = listOfNotNull(stdlibJarPath(), androidJar),
        )
        val chain = service.supertypesOf("java.lang.Exception").map { it.qualifiedName }
        // BOTH names, JVM first: the Kotlin one is what extension lookup keys on, the JVM one is what
        // bytecode-facing consumers (and "nearest supertype" ordering) already rely on.
        assertTrue("java.lang.Throwable" in chain, "the JVM supertype must stay in the chain; got $chain")
        assertTrue("kotlin.Throwable" in chain, "the mapped Kotlin classifier must be in the chain; got $chain")
        assertTrue(
            chain.indexOf("java.lang.Throwable") < chain.indexOf("kotlin.Throwable"),
            "the JVM name must come first so supertype distance is unchanged; got $chain",
        )
        // The same gap on a CharSequence chain: a `java.lang.StringBuilder`-typed value gets `CharSequence`'s
        // Kotlin extensions (`trim`, `isNotBlank`, …) only if the chain names `kotlin.CharSequence`.
        assertTrue(
            "kotlin.CharSequence" in service.supertypesOf("java.lang.StringBuilder").map { it.qualifiedName },
            "a JVM CharSequence chain must name the Kotlin classifier too",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Use.kt" to "package demo\n"))
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
