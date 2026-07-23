package dev.ide.lang.kotlin.compile

import dev.ide.lang.kotlin.parse
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Does the runtime-plugin (registrar) compile path report a genuine compile ERROR? The CLI path
 * (`K2JVMCompiler.exec`) does; the manual K2-phase pipeline in [KotlinJvmCompiler.compileViaRegistrars]
 * (used on ART for Compose) must too, or a broken source "compiles" with no output and no diagnostics —
 * which strands a wiped output dir exactly like the incremental stale-manifest bug.
 */
class RegistrarPathErrorReportingTest {

    @BeforeTest
    fun pinParserHost() { parse("package warmup\nfun warmup() {}") }

    private val stdlib: Path = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())

    private fun composeRuntimeJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator)
            .map { Path.of(it) }.filter { Files.exists(it) }
            .filter { ComposeCompilerPlugin.isComposeModule(listOf(it)) }

    private fun write(root: Path, rel: String, content: String): Path {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content.trimIndent()); return f
    }

    @Test
    fun brokenSourceIsReportedAsFailureOnBothPaths() {
        val pluginJar = ComposeCompilerPlugin.jar()
        assumeTrue(pluginJar != null, "Compose plugin jar not bundled on the test classpath")
        val runtime = composeRuntimeJars()
        assumeTrue(runtime.isNotEmpty(), "Compose runtime jar not on the test classpath")

        val dir = Files.createTempDirectory("kt-registrar-err")
        try {
            val src = dir.resolve("src")
            val broken = write(src, "demo/Broken.kt", "package demo\nfun broken(): Int = notARealSymbolAnywhere()\n")
            val cp = listOf(stdlib) + runtime

            // CLI path (no runtime plugins) — the control: this must report failure.
            val cli = KotlinJvmCompiler().compile(listOf(broken), emptyList(), cp, dir.resolve("out-cli"))
            println("CLI       success=${cli.success} msgs=${cli.messages}")
            assertFalse(cli.success, "CLI path should reject a broken file")

            // Registrar path (Compose plugin loaded programmatically) — the on-device path.
            val reg = KotlinJvmCompiler().compile(
                listOf(broken), emptyList(), cp, dir.resolve("out-reg"),
                runtimePluginClasspaths = listOf(listOf(pluginJar!!)),
            )
            println("REGISTRAR success=${reg.success} msgs=${reg.messages}")
            assertFalse(reg.success, "registrar path must also reject a broken file (else it swallows compile errors)")
            assertTrue(reg.messages.isNotEmpty(), "registrar path must surface the diagnostic, not swallow it")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
