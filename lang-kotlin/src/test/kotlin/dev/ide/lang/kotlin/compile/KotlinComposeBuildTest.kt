package dev.ide.lang.kotlin.compile

import dev.ide.lang.kotlin.parse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves the build's Compose support: feeding [KotlinJvmCompiler] the bundled Compose compiler plugin makes
 * it transform `@Composable` functions — the emitted method gains the synthetic `Composer` + `$changed`
 * parameters the Compose runtime relies on. Without the plugin the same source compiles to a plain `()V`,
 * so the test also serves as a negative control: the transform is the plugin's doing, not the source's.
 *
 * Runs the real K2 compiler on desktop. Self-gates (assumeTrue) when the Compose runtime jar or the bundled
 * plugin jar isn't on the test classpath, so a stripped CI classpath skips rather than fails.
 */
class KotlinComposeBuildTest {

    // Pin the shared parser-host environment alive across this codegen run (see IncrementalKotlinCompilerTest).
    @BeforeTest
    fun pinParserHost() { parse("package warmup\nfun warmup() {}") }

    /** The Compose runtime jar(s) on the test classpath — detected the same way the build detects a Compose module. */
    private fun composeRuntimeJars(): List<Path> =
        System.getProperty("java.class.path").split(java.io.File.pathSeparator)
            .map { Path.of(it) }
            .filter { Files.exists(it) }
            .let { entries -> entries.filter { ComposeCompilerPlugin.isComposeModule(listOf(it)) } }

    @Test
    fun composablePluginThreadsComposerParameter() {
        val pluginJar = ComposeCompilerPlugin.jar()
        assumeTrue(pluginJar != null, "Compose plugin jar not bundled on the test classpath")
        val runtime = composeRuntimeJars()
        assumeTrue(runtime.isNotEmpty(), "Compose runtime jar not on the test classpath")

        val dir = Files.createTempDirectory("kt-compose")
        try {
            val src = dir.resolve("src")
            val source = write(
                src, "demo/Screen.kt",
                """
                package demo
                import androidx.compose.runtime.Composable
                @Composable fun Greeting() {}
                """,
            )
            val compiler = KotlinJvmCompiler()

            // With the plugin: Greeting() must become Greeting(Composer, int).
            val withPlugin = dir.resolve("with")
            val r1 = compiler.compile(
                listOf(source), emptyList(), runtime, withPlugin, jvmTarget = "1.8",
                compilerPlugins = listOf(pluginJar!!),
            )
            assertTrue(r1.success, "compile with Compose plugin failed: ${r1.messages}")
            val transformed = greetingDescriptor(withPlugin)
            assertNotNull(transformed, "no Greeting method emitted")
            assertTrue(
                transformed.contains("Landroidx/compose/runtime/Composer;"),
                "Compose plugin did not thread the Composer parameter — descriptor was: $transformed",
            )

            // Without the plugin: the same @Composable source compiles to a plain ()V (negative control).
            val noPlugin = dir.resolve("without")
            val r2 = compiler.compile(listOf(source), emptyList(), runtime, noPlugin, jvmTarget = "1.8")
            assertTrue(r2.success, "compile without plugin failed: ${r2.messages}")
            val plain = greetingDescriptor(noPlugin)
            assertNotNull(plain, "no Greeting method emitted (no-plugin build)")
            assertFalse(
                plain.contains("Landroidx/compose/runtime/Composer;"),
                "Greeting gained a Composer param WITHOUT the plugin — descriptor was: $plain",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** The JVM descriptor of the (first) `Greeting` method in the compiled `ScreenKt.class` under [outDir]. */
    private fun greetingDescriptor(outDir: Path): String? {
        val classFile = Files.walk(outDir).use { s ->
            s.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.toList()
        }.firstOrNull { it.fileName.toString().contains("Screen") } ?: return null
        var descriptor: String? = null
        ClassReader(Files.readAllBytes(classFile)).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, name: String, descr: String, signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor? {
                if (name == "Greeting" && descriptor == null) descriptor = descr
                return null
            }
        }, ClassReader.SKIP_CODE)
        return descriptor
    }

    private fun write(root: Path, rel: String, content: String): Path {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent())
        return f
    }
}
