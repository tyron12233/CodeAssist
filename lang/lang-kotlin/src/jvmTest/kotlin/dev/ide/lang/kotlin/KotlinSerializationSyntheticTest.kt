package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The editor view onto kotlinx.serialization's compiler-plugin-generated members: a `@Serializable` class gets a
 * companion `serializer(): KSerializer<T>` the parse-only model never sees. [SerializationSyntheticMembers]
 * (contributed on `platform.kotlinSyntheticMember`, the default in direct wiring) makes `Foo.serializer()`
 * complete on `Foo.`, resolve (its `KSerializer<Foo>` chain enumerates), and NOT false-flag `kt.unresolved` —
 * while a module WITHOUT the serialization runtime synthesizes nothing (the runtime gate).
 *
 * Self-gates (assumeTrue) when the serialization runtime jar isn't on the test classpath, so a stripped CI
 * classpath skips rather than fails (like [dev.ide.lang.kotlin.compile.KotlinSerializationBuildTest]).
 */
class KotlinSerializationSyntheticTest {

    /** The serialization runtime jar(s) on the test classpath — the ones carrying `@Serializable` (core). */
    private fun serializationRuntimeJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator)
            .map { Path.of(it) }
            .filter { it.toString().endsWith(".jar") && Files.exists(it) }
            .filter { runCatching { ZipFile(it.toFile()).use { z -> z.getEntry("kotlinx/serialization/Serializable.class") != null } }.getOrDefault(false) }

    private fun analyzer(withRuntime: Boolean): Pair<KotlinSourceAnalyzer, Path> {
        val runtime = serializationRuntimeJars()
        if (withRuntime) assumeTrue(runtime.isNotEmpty(), "serialization runtime jar not on the test classpath")
        val srcDir = tempProject(emptyMap())
        val libs = listOf(stdlibJarPath()) + if (withRuntime) runtime else emptyList()
        return KotlinSourceAnalyzer(fakeContext(srcDir, libs)) to srcDir
    }

    private val model = """
        package demo
        import kotlinx.serialization.Serializable
        @Serializable
        class Foo(val x: Int)
    """.trimIndent() + "\n"

    private fun completionNames(withRuntime: Boolean, body: String): List<String> {
        val (a, srcDir) = analyzer(withRuntime)
        val code = model + "fun use() { $body }\n"
        return runBlocking { a.completeAtCaret(srcDir, "Foo.kt", code) }.items.mapNotNull { it.symbol?.name }
    }

    @Test
    fun serializerCompletesOnType() {
        val items = completionNames(withRuntime = true, body = "Foo.|")
        assertTrue("serializer" in items, "`Foo.` should offer the synthesized `serializer()`; got $items")
    }

    @Test
    fun serializerChainResolves() {
        // `Foo.serializer()` is typed `KSerializer<Foo>` — a chain off it enumerates the interface's members.
        val items = completionNames(withRuntime = true, body = "Foo.serializer().|")
        assertTrue("descriptor" in items, "`Foo.serializer().` should offer `KSerializer.descriptor`; got $items")
    }

    @Test
    fun serializerCallIsNotUnresolved() {
        val (a, srcDir) = analyzer(withRuntime = true)
        val code = model + "fun use() { val s = Foo.serializer() }\n"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Foo.kt")))
        val diags: List<Diagnostic> = runBlocking { a.incrementalParser.parseFull(doc); a.analyze(doc.file).diagnostics }
        val unresolvedSerializer = diags.filter { it.code == KotlinDiagnosticCodes.UNRESOLVED && it.message.contains("serializer") }
        assertTrue(unresolvedSerializer.isEmpty(), "`Foo.serializer()` must not be flagged unresolved; got $unresolvedSerializer")
    }

    @Test
    fun serializableObjectHasSerializerOnItself() {
        // JetNews: `@Serializable data object HomeKey : NavKey` + `HomeKey.serializer()` (navigation3 deep links).
        // An object has no companion (the plugin generates `serializer()` on the object itself), so it must
        // complete on `HomeKey.` and not be flagged unresolved (it refused the whole file's @Previews).
        val (a, srcDir) = analyzer(withRuntime = true)
        val objectModel = """
            package demo
            import kotlinx.serialization.Serializable
            @Serializable
            data object HomeKey
        """.trimIndent() + "\n"
        val items = runBlocking { a.completeAtCaret(srcDir, "HomeKey.kt", objectModel + "fun use() { HomeKey.| }\n") }.items.mapNotNull { it.symbol?.name }
        assertTrue("serializer" in items, "`HomeKey.` should offer the object's synthesized `serializer()`; got $items")
        val code = objectModel + "val s = HomeKey.serializer()\n"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("HomeKey.kt")))
        val diags: List<Diagnostic> = runBlocking { a.incrementalParser.parseFull(doc); a.analyze(doc.file).diagnostics }
        val unresolved = diags.filter { it.code == KotlinDiagnosticCodes.UNRESOLVED }
        assertTrue(unresolved.isEmpty(), "`HomeKey.serializer()` on a @Serializable object must not be unresolved; got ${'$'}{unresolved.map { it.message }}")
    }

    @Test
    fun notSynthesizedWithoutRuntime() {
        // No serialization runtime on the classpath ⇒ the provider's gate fails ⇒ nothing synthesized.
        val items = completionNames(withRuntime = false, body = "Foo.|")
        assertFalse("serializer" in items, "without the serialization runtime, `serializer()` must NOT be synthesized; got $items")
    }
}
