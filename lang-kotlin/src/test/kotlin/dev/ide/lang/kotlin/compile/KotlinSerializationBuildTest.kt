package dev.ide.lang.kotlin.compile

import dev.ide.lang.kotlin.parse
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the build's kotlinx.serialization support: feeding [KotlinJvmCompiler] the bundled serialization
 * compiler plugin makes it generate each `@Serializable` class's serializer — a nested `Model$serializer`
 * class appears in the output. Without the plugin the same source compiles to a plain data class with no
 * serializer, so the test doubles as a negative control: the generated serializer is the plugin's doing.
 *
 * Runs the real K2 compiler on desktop. Self-gates (assumeTrue) when the serialization runtime jar or the
 * bundled plugin jar isn't on the test classpath, so a stripped CI classpath skips rather than fails.
 */
class KotlinSerializationBuildTest {

    // Pin the shared parser-host environment alive across this codegen run (see IncrementalKotlinCompilerTest).
    @BeforeTest
    fun pinParserHost() { parse("package warmup\nfun warmup() {}") }

    /** The serialization runtime jar(s) on the test classpath — detected the same way the build detects a
     *  serialization module (the `@Serializable` annotation present). */
    private fun serializationRuntimeJars(): List<Path> =
        System.getProperty("java.class.path").split(java.io.File.pathSeparator)
            .map { Path.of(it) }
            .filter { Files.exists(it) }
            .let { entries -> entries.filter { SerializationCompilerPlugin.usesSerialization(listOf(it)) } }

    @Test
    fun serializationPluginGeneratesSerializer() {
        val pluginJar = SerializationCompilerPlugin.jar()
        assumeTrue(pluginJar != null, "serialization plugin jar not bundled on the test classpath")
        val runtime = serializationRuntimeJars()
        assumeTrue(runtime.isNotEmpty(), "serialization runtime jar not on the test classpath")

        val dir = Files.createTempDirectory("kt-serialization")
        try {
            val src = dir.resolve("src")
            val source = write(
                src, "demo/Model.kt",
                """
                package demo
                import kotlinx.serialization.Serializable
                @Serializable data class Model(val id: Int, val name: String)
                """,
            )
            val compiler = KotlinJvmCompiler()

            // With the plugin: a `Model${'$'}serializer` class must be generated.
            val withPlugin = dir.resolve("with")
            val r1 = compiler.compile(
                listOf(source), emptyList(), runtime, withPlugin, jvmTarget = "1.8",
                compilerPlugins = listOf(pluginJar!!),
            )
            assertTrue(r1.success, "compile with serialization plugin failed: ${r1.messages}")
            assertTrue(
                hasGeneratedSerializer(withPlugin),
                "serialization plugin did not generate a \$serializer class",
            )

            // Without the plugin: the same source compiles, but no serializer is generated (negative control).
            val noPlugin = dir.resolve("without")
            val r2 = compiler.compile(listOf(source), emptyList(), runtime, noPlugin, jvmTarget = "1.8")
            assertTrue(r2.success, "compile without plugin failed: ${r2.messages}")
            assertFalse(
                hasGeneratedSerializer(noPlugin),
                "a \$serializer class was generated WITHOUT the plugin",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** True when a plugin-generated serializer class (`…$serializer.class`) exists anywhere under [outDir]. */
    private fun hasGeneratedSerializer(outDir: Path): Boolean =
        Files.exists(outDir) && Files.walk(outDir).use { s ->
            s.anyMatch { Files.isRegularFile(it) && it.fileName.toString().endsWith("\$serializer.class") }
        }

    private fun write(root: Path, rel: String, content: String): Path {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
        return f
    }
}
