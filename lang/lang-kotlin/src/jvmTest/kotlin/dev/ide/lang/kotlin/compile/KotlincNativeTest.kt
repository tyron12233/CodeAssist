package dev.ide.lang.kotlin.compile

import dev.ide.lang.kotlin.parse
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A compile through a compiler the host has linked in ([KotlincNative]) crosses as two files and comes back
 * whole. The linked-in compiler here is [KotlinCompileWorker] called directly, which is the code an image
 * compiled ahead of time runs on its side.
 */
class KotlincNativeTest {

    private val stdlib: Path = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())

    @BeforeTest
    fun pinParserHost() { parse("package warmup\nfun warmup() {}") }

    @AfterTest
    fun reset() { KotlincNative.runner = null }

    private fun source(dir: Path, text: String): Path {
        val file = dir.resolve("src/demo/Greeting.kt")
        Files.createDirectories(file.parent)
        return Files.writeString(file, text)
    }

    @Test
    fun aHostWithoutOneHasNoSuchBackend() {
        assertNull(KotlincNative.backend())
    }

    @Test
    fun aCompileCrossesAsFilesAndComesBackWithItsOutputs() {
        withTempDir("kotlinc-native") { dir ->
            val src = source(dir, "package demo\n\nclass Greeting(val name: String) { fun text() = \"Hello, \$name\" }\n")
            val out = dir.resolve("build/classes")
            var crossed = 0
            KotlincNative.runner = { request, result -> crossed++; KotlinCompileWorker.run(request, result) }

            val result = KotlincNative.backend()!!.compile(KotlinCompileRequest(listOf(src), classpath = listOf(stdlib), outputDir = out))

            assertTrue(result.success, result.messages.toString())
            assertEquals(1, crossed)
            assertTrue(Files.isRegularFile(out.resolve("demo/Greeting.class")))
            assertEquals(listOf(out.resolve("demo/Greeting.class")), result.outputs[src.normalize()])
            assertEquals(emptyList(), Files.list(out.parent).use { s -> s.filter { Files.isRegularFile(it) }.toList() }, "the two files do not outlive the compile")
        }
    }

    @Test
    fun aCompileErrorComesBackAsItsDiagnostic() {
        withTempDir("kotlinc-native") { dir ->
            val src = source(dir, "package demo\n\nfun broken(): Int = \"text\"\n")
            KotlincNative.runner = { request, result -> KotlinCompileWorker.run(request, result) }

            val result = KotlincNative.backend()!!.compile(KotlinCompileRequest(listOf(src), classpath = listOf(stdlib), outputDir = dir.resolve("out")))

            assertFalse(result.success)
            val error = result.diagnostics.single { it.severity == KotlinDiagnosticSeverity.ERROR }
            assertEquals(src.toString() to 3, error.path to error.line)
        }
    }

    @Test
    fun aCompilerThatBreaksHandsTheCompileToTheOneInThisJvm() {
        withTempDir("kotlinc-native") { dir ->
            val src = source(dir, "package demo\n\nclass Greeting\n")
            val out = dir.resolve("build/classes")
            val request = KotlinCompileRequest(listOf(src), classpath = listOf(stdlib), outputDir = out)
            val said = ArrayList<String>()

            // Answers nothing at all.
            KotlincNative.runner = { _, _ -> 7 }
            val silent = KotlincNative.backend()!!.compile(request) { said += it }
            assertTrue(silent.success, silent.messages.toString())
            assertTrue(Files.isRegularFile(out.resolve("demo/Greeting.class")))
            assertTrue(said.single().contains("code 7"), said.toString())

            // Cannot be called.
            KotlincNative.runner = { _, _ -> throw UnsatisfiedLinkError("no kotlinc") }
            assertTrue(KotlincNative.backend()!!.compile(request).success)

            // Throws inside, as a path it was not built for does.
            KotlincNative.runner = { _, result ->
                KotlinCompileWire.writeResult(KotlinCompileResult(false, listOf("error: kotlinc threw: MissingReflectionRegistrationError")), result)
                0
            }
            said.clear()
            assertTrue(KotlincNative.backend()!!.compile(request) { said += it }.success)
            assertTrue(said.single().contains("MissingReflectionRegistrationError"))
        }
    }

    @Test
    fun anOrdinaryCompileErrorIsNotCompiledTwice() {
        withTempDir("kotlinc-native") { dir ->
            val src = source(dir, "package demo\n\nfun broken(): Int = \"text\"\n")
            var crossed = 0
            KotlincNative.runner = { request, result -> crossed++; KotlinCompileWorker.run(request, result) }
            val said = ArrayList<String>()
            val result = KotlincNative.backend()!!.compile(KotlinCompileRequest(listOf(src), classpath = listOf(stdlib), outputDir = dir.resolve("out"))) { said += it }
            assertFalse(result.success)
            assertEquals(1 to emptyList<String>(), crossed to said)
        }
    }
}
