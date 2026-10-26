package dev.ide.lang.kotlin.compile

import dev.ide.testkit.withTempDir
import dev.ide.testkit.writeSource
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
 * A compile with a bundled compiler plugin applied (Compose, through `-Xplugin`) reports a genuine compile
 * ERROR just like a plain one, or a broken source "compiles" with no output and no diagnostics, which strands a
 * wiped output dir exactly like the incremental stale-manifest bug.
 */
class CompilerPluginErrorReportingTest {

    @BeforeTest
    fun pinParserHost() { parse("package warmup\nfun warmup() {}") }

    private val stdlib: Path = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())

    private fun composeRuntimeJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator)
            .map { Path.of(it) }.filter { Files.exists(it) }
            .filter { ComposeCompilerPlugin.isComposeModule(listOf(it)) }

    private fun write(root: Path, rel: String, content: String): Path = root.writeSource(rel, content)

    @Test
    fun brokenSourceIsReportedAsFailureWithAndWithoutAPlugin() {
        val pluginJar = ComposeCompilerPlugin.jar()
        assumeTrue(pluginJar != null, "Compose plugin jar not bundled on the test classpath")
        val runtime = composeRuntimeJars()
        assumeTrue(runtime.isNotEmpty(), "Compose runtime jar not on the test classpath")

        withTempDir("kt-registrar-err") { dir ->
            val src = dir.resolve("src")
            val broken = write(src, "demo/Broken.kt", "package demo\nfun broken(): Int = notARealSymbolAnywhere()\n")
            val cp = listOf(stdlib) + runtime

            // No plugin, the control: this must report failure.
            val cli = KotlinJvmCompiler().compile(listOf(broken), emptyList(), cp, dir.resolve("out-cli"))
            assertFalse(cli.success, "CLI path should reject a broken file")

            // With the bundled Compose plugin applied.
            val withPlugin = KotlinJvmCompiler().compile(
                listOf(broken), emptyList(), cp, dir.resolve("out-plugin"),
                compilerPlugins = listOf(pluginJar!!),
            )
            assertFalse(withPlugin.success, "a compile with a plugin must also reject a broken file")
            assertTrue(withPlugin.messages.isNotEmpty(), "a compile with a plugin must surface the diagnostic")
        }
    }
}
