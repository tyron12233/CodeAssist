package dev.ide.lang.kotlin.compile

import dev.ide.lang.kotlin.parse
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reproduction of the reported "unresolved reference: <cross-file @Composable>" that only bites the
 * incremental (debug) Kotlin build. Mirrors the user's project: a @Composable provider in one file, a
 * consumer in another that references it fully-qualified, then a BODY-ONLY edit to the CONSUMER.
 */
class IncrementalConsumerEditReproTest {

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
    fun consumerBodyEditKeepsCrossFileComposableResolvable() {
        val pluginJar = ComposeCompilerPlugin.jar()
        assumeTrue(pluginJar != null, "Compose plugin jar not bundled on the test classpath")
        val runtime = composeRuntimeJars()
        assumeTrue(runtime.isNotEmpty(), "Compose runtime jar not on the test classpath")

        val dir = Files.createTempDirectory("kt-ic-consumer")
        try {
            val src = dir.resolve("src"); val out = dir.resolve("out")
            // Provider: a top-level @Composable in its own file (compiles to ThemeKt.class).
            write(
                src, "demo/Theme.kt",
                """
                package demo
                import androidx.compose.runtime.Composable
                @Composable fun MyApplicationTheme(content: @Composable () -> Unit) { content() }
                """,
            )
            // Consumer: references the provider FULLY-QUALIFIED, with a body-editable @Composable of its own.
            val main = write(
                src, "demo/Main.kt",
                """
                package demo
                import androidx.compose.runtime.Composable
                @Composable fun Greeting(name: String) {}
                @Composable fun Screen() {
                    demo.MyApplicationTheme { Greeting("Hello World!") }
                }
                """,
            )

            val ic = IncrementalKotlinCompiler()
            val cp = listOf(stdlib) + runtime
            val plugins = listOf(listOf(pluginJar!!))

            val first = ic.compile(
                listOf(main, src.resolve("demo/Theme.kt")), emptyList(), cp, out,
                runtimePluginClasspaths = plugins,
            )
            assertTrue(first.success, "initial full compile failed: ${first.messages}")
            assertEquals(IncrementalKotlinCompiler.Mode.FULL, first.mode)

            // Body-only edit to the CONSUMER (change the greeting string), then recompile.
            write(
                src, "demo/Main.kt",
                """
                package demo
                import androidx.compose.runtime.Composable
                @Composable fun Greeting(name: String) {}
                @Composable fun Screen() {
                    demo.MyApplicationTheme { Greeting("Goodbye World!") }
                }
                """,
            )
            val second = ic.compile(
                listOf(main, src.resolve("demo/Theme.kt")), emptyList(), cp, out,
                runtimePluginClasspaths = plugins,
            )
            assertTrue(
                second.success,
                "consumer body edit failed to compile (mode=${second.mode}): ${second.messages}",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * The actual reported failure (reduced to the plain path so a failed compile is reported deterministically):
     * a FULL compile that CLEARS the output dir then FAILS leaves the output wiped while the manifest still
     * describes the old full state. The next build, driven by a body edit to the CONSUMER, takes the incremental
     * path against the wiped output dir — so the cross-file symbol is spuriously "unresolved reference". A later
     * full rebuild "fixes" it. Mirrors debug (incremental) failing while release (full) is fine.
     */
    @Test
    fun failedFullCompileDoesNotStrandStaleManifest() {
        val dir = Files.createTempDirectory("kt-ic-stale")
        try {
            val src = dir.resolve("src"); val out = dir.resolve("out")
            val provider = "package demo\nfun myTheme(): Int = 41\n"
            write(src, "demo/Theme.kt", provider)
            val main = write(src, "demo/Main.kt", "package demo\nfun screen(): Int = demo.myTheme() + 1\n")
            val theme = src.resolve("demo/Theme.kt")
            val ic = IncrementalKotlinCompiler()
            val cp = listOf(stdlib)

            // 1) Good full compile → output holds ThemeKt.class + MainKt.class.
            assertTrue(ic.compile(listOf(main, theme), emptyList(), cp, out).success)

            // 2) Force full() (context changed via an extra classpath entry) with a BROKEN provider edit →
            //    full() clears the output dir and the compile fails, leaving the output wiped.
            val extraCp = Files.createDirectories(dir.resolve("extra"))
            write(src, "demo/Theme.kt", "package demo\nfun myTheme(): Int = notARealSymbolAnywhere()\n")
            assertTrue(!ic.compile(listOf(main, theme), emptyList(), cp + extraCp, out).success, "step 2 must fail the full compile")

            // 3) Fix the provider and do a body edit to the CONSUMER, back on the original context. Before the
            //    fix this took the incremental path against the wiped output dir and failed with
            //    "Unresolved reference 'myTheme'"; now the output/manifest inconsistency forces a full rebuild.
            write(src, "demo/Theme.kt", provider)
            write(src, "demo/Main.kt", "package demo\nfun screen(): Int = demo.myTheme() + 2\n")
            val third = ic.compile(listOf(main, theme), emptyList(), cp, out)
            assertTrue(
                third.success,
                "post-recovery build failed (mode=${third.mode}) — stale manifest drove a bad incremental: ${third.messages}",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
