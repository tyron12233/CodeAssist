package dev.ide.lang.kotlin.compile

import dev.ide.lang.kotlin.parse
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Incremental Kotlin compilation: editing a method body recompiles only the changed file and leaves every
 * other `.class` byte-for-byte (the fast path), while a change to a public signature falls back to a full
 * module recompile. Runs the real K2 compiler on desktop.
 */
class IncrementalKotlinCompilerTest {

    private val stdlib: Path = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())

    /**
     * Mirror production: the editor parse-host keeps the shared Kotlin/IntelliJ application environment
     * alive, so the K2 codegen run below never tears it down. Without this pin, when this codegen test
     * shares a JVM with the parser-host tests the compiler's environment-dispose can race the shared
     * singleton and surface as an intermittent `NoClassDefFoundError`.
     */
    @BeforeTest
    fun pinParserHost() { parse("package warmup\nfun warmup() {}") }

    private fun write(root: Path, rel: String, content: String): Path {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
        return f
    }

    private fun bytes(p: Path): ByteArray = Files.readAllBytes(p)

    @Test
    fun bodyEditRecompilesOnlyTheChangedFile() {
        val dir = Files.createTempDirectory("kt-ic")
        try {
            val src = dir.resolve("src"); val out = dir.resolve("out")
            // B depends on A: B.compute() calls A().value(). A body edit must not recompile B.
            val a = write(src, "demo/A.kt", "package demo\nclass A {\n  fun value(): Int = 41\n  fun helper(): Int = 1\n}")
            write(src, "demo/B.kt", "package demo\nclass B {\n  fun compute(): Int = A().value() + 1\n}")

            val ic = IncrementalKotlinCompiler()
            val cp = listOf(stdlib)
            val first = ic.compile(listOf(a, src.resolve("demo/B.kt")), emptyList(), cp, out)
            assertTrue(first.success, "initial compile failed: ${first.messages}")
            assertEquals(IncrementalKotlinCompiler.Mode.FULL, first.mode)
            assertEquals(42, invoke(out, "demo.B", "compute"), "B().compute() should be A.value()+1 = 42")

            val aClass = out.resolve("demo/A.class"); val bClass = out.resolve("demo/B.class")
            val bBefore = bytes(bClass)
            val aBefore = bytes(aClass)

            // Body-only edit: same signature, different return value.
            write(src, "demo/A.kt", "package demo\nclass A {\n  fun value(): Int = 42\n  fun helper(): Int = 1\n}")
            val second = ic.compile(listOf(a, src.resolve("demo/B.kt")), emptyList(), cp, out)
            assertTrue(second.success, "incremental compile failed: ${second.messages}")
            assertEquals(IncrementalKotlinCompiler.Mode.INCREMENTAL, second.mode, "a body edit must take the fast path")
            assertContentEquals(listOf(a), second.recompiledSources.map { it.toAbsolutePath().normalize() })

            assertContentEquals(bBefore, bytes(bClass), "B.class must be untouched (not recompiled)")
            assertFalse(aBefore.contentEquals(bytes(aClass)), "A.class must have been recompiled")
            // Runtime is still correct: B (unchanged bytecode) calls the rebuilt A → 42 + 1 = 43.
            assertEquals(43, invoke(out, "demo.B", "compute"), "B().compute() should reflect the new A.value() = 42")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun signatureChangeFallsBackToFullRecompile() {
        val dir = Files.createTempDirectory("kt-ic-abi")
        try {
            val src = dir.resolve("src"); val out = dir.resolve("out")
            val a = write(src, "demo/A.kt", "package demo\nclass A {\n  fun value(): Int = 1\n}")
            val b = write(src, "demo/B.kt", "package demo\nclass B {\n  fun compute(): Int = A().value()\n}")
            val ic = IncrementalKotlinCompiler()
            val cp = listOf(stdlib)

            assertEquals(IncrementalKotlinCompiler.Mode.FULL, ic.compile(listOf(a, b), emptyList(), cp, out).mode)
            // Re-running with no change is a no-op.
            assertEquals(IncrementalKotlinCompiler.Mode.NOOP, ic.compile(listOf(a, b), emptyList(), cp, out).mode)

            // Add a public method → A's ABI changes → must rebuild the module (conservative dependent handling).
            write(src, "demo/A.kt", "package demo\nclass A {\n  fun value(): Int = 1\n  fun added(): Int = 2\n}")
            val r = ic.compile(listOf(a, b), emptyList(), cp, out)
            assertTrue(r.success, "rebuild failed: ${r.messages}")
            assertEquals(IncrementalKotlinCompiler.Mode.FULL, r.mode, "a public-signature change must force a full recompile")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Load [fqcn] from [outDir] (+ the stdlib) and invoke its no-arg [method], returning the result. */
    private fun invoke(outDir: Path, fqcn: String, method: String): Any? {
        val loader = URLClassLoader(arrayOf(outDir.toUri().toURL(), stdlib.toUri().toURL()), javaClass.classLoader)
        loader.use {
            val cls = it.loadClass(fqcn)
            val instance = cls.getDeclaredConstructor().newInstance()
            return cls.getMethod(method).invoke(instance)
        }
    }
}
