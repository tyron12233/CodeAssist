package dev.ide.lang.kotlin.compile

import dev.ide.lang.kotlin.parse
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the runtime-loaded compiler-plugin path (step 2 of docs/kotlin-compiler-plugins-and-codegen.md):
 * a plugin's `CompilerPluginRegistrar` loaded from a [KotlinPluginLoader] over its jar and registered
 * programmatically (not via kotlinc's `-Xplugin` CLI scan), proving the on-device mechanism on the desktop.
 *
 * Uses the real Compose plugin so the assertion is a real codegen transform (the `@Composable` gains its
 * synthetic `Composer` parameter), loaded through the [DefaultKotlinPluginLoader] `URLClassLoader` (the
 * desktop stand-in for ART's D8-dexed `DexClassLoader`). Self-gates when the Compose runtime/plugin jars
 * aren't on the test classpath.
 */
@OptIn(ExperimentalCompilerApi::class)
class ProgrammaticPluginRegistrationTest {

    @BeforeTest
    fun pinParserHost() { parse("package warmup\nfun warmup() {}") }

    private fun composeRuntimeJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator)
            .map { Path.of(it) }
            .filter { Files.exists(it) }
            .let { entries -> entries.filter { ComposeCompilerPlugin.isComposeModule(listOf(it)) } }

    @Test
    fun discoversRegistrarFromJarViaLoader() {
        val pluginJar = ComposeCompilerPlugin.jar()
        assumeTrue(pluginJar != null, "Compose plugin jar not bundled on the test classpath")

        val registrars = loadCompilerPluginRegistrars(listOf(pluginJar!!), DefaultKotlinPluginLoader)
        assertTrue(registrars.isNotEmpty(), "no CompilerPluginRegistrar discovered/loaded from the plugin jar")
        assertTrue(
            registrars.any { it.javaClass.name == "androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar" },
            "expected the Compose registrar, got: ${registrars.map { it.javaClass.name }}",
        )
    }

    @Test
    fun runtimeRegistrarPathAppliesComposeTransform() {
        val pluginJar = ComposeCompilerPlugin.jar()
        assumeTrue(pluginJar != null, "Compose plugin jar not bundled on the test classpath")
        val runtime = composeRuntimeJars()
        assumeTrue(runtime.isNotEmpty(), "Compose runtime jar not on the test classpath")

        val dir = Files.createTempDirectory("kt-runtime-plugin")
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
            val out = dir.resolve("out")

            // The production path: runtimePluginClasspaths routes through the manual bootstrap, loading the
            // registrar via DefaultKotlinPluginLoader (URLClassLoader) and registering it programmatically.
            val r = KotlinJvmCompiler().compile(
                listOf(source), emptyList(), runtime, out, jvmTarget = "1.8",
                runtimePluginClasspaths = listOf(listOf(pluginJar!!)),
            )
            assertTrue(r.success, "runtime-registrar compile failed: ${r.messages}")

            val descriptor = greetingDescriptor(out)
            assertNotNull(descriptor, "no Greeting method emitted")
            assertTrue(
                descriptor.contains("Landroidx/compose/runtime/Composer;"),
                "the runtime-registrar path did not apply the Compose transform — descriptor was: $descriptor",
            )
            // The source -> .class mapping must still be captured on this path (incremental compile needs it).
            assertTrue(r.outputs.keys.any { it.toString().endsWith("Screen.kt") }, "no output mapping captured: ${r.outputs}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

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
