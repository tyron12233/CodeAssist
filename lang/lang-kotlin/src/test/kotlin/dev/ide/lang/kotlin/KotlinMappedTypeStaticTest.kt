package dev.ide.lang.kotlin

import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
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
 * A JVM type NAMED BY ITS JAVA FQN offers its statics: `java.lang.String.<caret>` completes `valueOf`/`format`/
 * `join`, `java.lang.Integer.<caret>` completes `parseInt`. Regression: Kotlin maps `java.lang.String` to the
 * classifier `kotlin.String` whose API omits the JVM statics, and member enumeration walked the mapped Kotlin
 * type — so the FQN reference came up EMPTY (and `java.lang.String.valueOf(...)` was flagged `kt.unresolved`).
 * A bare `String.` (which presents as `kotlin.String`) must STAY static-free — the mapped type's own API.
 *
 * Reads the statics from android.jar's `java.lang.*` stubs, so CI without an SDK skips it.
 */
class KotlinMappedTypeStaticTest {

    /** Member names offered at the caret, stripped of any method signature (`valueOf(p0: int)` → `valueOf`). */
    private fun names(code: String): List<String> {
        assumeTrue(androidJar != null, "no android.jar; skipping mapped-type static test")
        return runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.label.substringBefore('(') }
    }

    private fun unresolved(code: String): List<String> {
        assumeTrue(androidJar != null, "no android.jar; skipping mapped-type static test")
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("D.kt")))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.analyze(doc.file).diagnostics }
            .filter { it.code == "kt.unresolved" }.map { it.message }
    }

    @Test
    fun javaLangStringFqnCompletesStatics() {
        val ns = names("fun f() { java.lang.String.| }")
        assertTrue("valueOf" in ns, "java.lang.String.valueOf must complete; got $ns")
        assertTrue("format" in ns, "java.lang.String.format must complete; got $ns")
        assertTrue("join" in ns, "java.lang.String.join must complete; got $ns")
        assertTrue("CASE_INSENSITIVE_ORDER" in ns, "the static field must complete too; got $ns")
    }

    @Test
    fun javaLangIntegerFqnCompletesStatics() {
        val ns = names("fun f() { java.lang.Integer.| }")
        assertTrue("parseInt" in ns, "java.lang.Integer.parseInt must complete; got $ns")
        assertTrue("valueOf" in ns, "java.lang.Integer.valueOf must complete; got $ns")
    }

    @Test
    fun javaLangStringStaticCallNotFlaggedUnresolved() {
        assertTrue(
            unresolved("fun f() { val x = java.lang.String.valueOf(5) }").none { "valueOf" in it },
            "java.lang.String.valueOf(...) must resolve (not flagged unresolved)",
        )
    }

    @Test
    fun bareStringStaysStaticFree() {
        // `String` maps to `kotlin.String`, whose API intentionally omits the JVM statics — the mapped-type
        // contract. Only the explicit `java.lang.String` FQN surfaces them.
        val ns = names("fun f() { String.| }")
        assertTrue("valueOf" !in ns, "bare String. must NOT surface JVM statics (offered only via the java.lang.String FQN); got $ns")
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
        // The BUILTINS index (the real-IDE condition) makes `kotlin.String`'s shape the minimal `.kotlin_builtins`
        // one — no JVM statics — so the bug reproduces (without it, `builtinShape` falls through to the java.lang
        // bytecode and the statics leak in, masking the fix).
        private val index = IndexServiceImpl(listOf(KotlinTypeShapeIndex, KotlinBuiltinsIndex, KotlinCallableIndex), Files.createTempDirectory("mapped-static-idx"))
            .also { if (androidJar != null) runBlocking { it.ensureUpToDate(IndexScope(libraryJars = jars)) } }
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = jars)).also { it.indexService = index }
    }
}
