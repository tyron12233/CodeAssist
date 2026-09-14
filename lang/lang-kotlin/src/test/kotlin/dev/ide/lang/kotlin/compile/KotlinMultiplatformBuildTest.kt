package dev.ide.lang.kotlin.compile

import dev.ide.lang.kotlin.parse
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the build's multiplatform support: a module whose sources span a common source set and the platform
 * source set implementing it compiles only when the compile runs in multiplatform mode.
 *
 * This is what an imported Kotlin Multiplatform module looks like once its source sets are collapsed into
 * CodeAssist's single compilation — `expect` in `commonMain`, `actual` in `androidMain` — and without the
 * flag the compiler rejects the `expect` outright. The negative half of the test is the point: it shows the
 * failure the flag prevents, so the flag cannot be quietly dropped without a test going red.
 *
 * Runs the real K2 compiler on desktop.
 */
class KotlinMultiplatformBuildTest {

    // Pin the shared parser-host environment alive across this codegen run (see IncrementalKotlinCompilerTest).
    @BeforeTest
    fun pinParserHost() { parse("package warmup\nfun warmup() {}") }

    private fun write(dir: Path, rel: String, text: String): Path {
        val f = dir.resolve(rel)
        Files.createDirectories(f.parent)
        Files.writeString(f, text.trimIndent())
        return f
    }

    /** The two halves of an `expect`/`actual` pair, as two source sets of one module. */
    private fun sources(dir: Path): List<Path> = listOf(
        write(dir, "commonMain/Platform.kt", """
            package sample

            expect fun platformName(): String

            expect class Clock() {
                fun now(): Long
            }

            fun greeting(): String = "hello from " + platformName()
        """),
        // `.android.kt`, the convention every multiplatform module here follows: two files of the same name
        // in the same package would compile to one JVM facade class and collide.
        write(dir, "androidMain/Platform.android.kt", """
            package sample

            actual fun platformName(): String = "android"

            actual class Clock actual constructor() {
                actual fun now(): Long = 0L
            }
        """),
    )

    private fun compile(dir: Path, multiplatform: Boolean): KotlinCompileResult {
        val out = Files.createDirectories(dir.resolve("out"))
        val sources = sources(dir)
        return KotlinJvmCompiler().compile(
            kotlinSources = sources,
            javaSources = emptyList(),
            classpath = emptyList(),
            outputDir = out,
            commonSources = if (multiplatform) sources.filter { "commonMain" in it.toString() } else emptyList(),
        )
    }

    @Test
    fun compilesExpectAndActualTogetherInMultiplatformMode() {
        withTempDir("kt-mpp") { dir ->
            val result = compile(dir, multiplatform = true)
            assertTrue(result.success, "expected success, got:\n${result.messages.joinToString("\n")}")
            assertTrue(
                Files.exists(dir.resolve("out/sample/PlatformKt.class")),
                "the common + platform sources compiled into one output",
            )
            assertTrue(Files.exists(dir.resolve("out/sample/Clock.class")), "the expected class has its actual")
        }
    }

    @Test
    fun rejectsExpectDeclarationsWithoutIt() {
        withTempDir("kt-mpp-off") { dir ->
            val result = compile(dir, multiplatform = false)
            assertFalse(result.success, "expected the compile to fail without multiplatform mode")
            assertTrue(
                result.messages.any { "multiplatform" in it.lowercase() || "expect" in it.lowercase() },
                "the failure should name the expect/actual problem:\n${result.messages.joinToString("\n")}",
            )
        }
    }
}
