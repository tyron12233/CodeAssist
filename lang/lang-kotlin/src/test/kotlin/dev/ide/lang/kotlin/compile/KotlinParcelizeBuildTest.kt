package dev.ide.lang.kotlin.compile

import dev.ide.lang.kotlin.parse
import dev.ide.testkit.withTempDir
import dev.ide.testkit.writeSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Proves the build's kotlin-parcelize support end-to-end on the real K2 compiler. WITH the bundled parcelize
 * plugin, a `@Parcelize` class that only declares `: Parcelable` compiles — the plugin generates its
 * `writeToParcel()`/`describeContents()`/`CREATOR`. WITHOUT the plugin the SAME source fails with kotlinc's
 * "is not abstract and does not implement abstract member" (the exact user-reported error), so this doubles as
 * a negative control pinning the failure to a missing plugin, not the source.
 *
 * Needs android.jar (for `android.os.Parcelable`) + the parcelize runtime (the `@Parcelize` annotation);
 * self-gates (assumeTrue) when either is absent, so a stripped CI classpath skips rather than fails.
 */
class KotlinParcelizeBuildTest {

    @BeforeTest
    fun pinParserHost() { parse("package warmup\nfun warmup() {}") }

    @Test
    fun parcelizePluginGeneratesParcelableImpl() {
        val pluginJar = ParcelizeCompilerPlugin.jar()
        assumeTrue(pluginJar != null, "parcelize plugin jar not bundled on the test classpath")
        val runtime = parcelizeRuntimeJars()
        assumeTrue(runtime.isNotEmpty(), "parcelize runtime jar not on the test classpath")
        val androidJar = androidJar()
        assumeTrue(androidJar != null, "no android.jar on this machine; skipping")

        withTempDir("kt-parcelize") { dir ->
            val src = dir.resolve("src")
            val source = src.writeSource(
                "demo/AssetSource.kt",
                """
                package demo
                import android.os.Parcelable
                import kotlinx.parcelize.Parcelize
                @Parcelize data class AssetSource(val paths: List<String>) : Parcelable
                """,
            )
            val compiler = KotlinJvmCompiler()
            val cp = runtime + listOfNotNull(androidJar)

            // With the plugin: the class compiles and gets its generated Parcelable implementation.
            val withPlugin = dir.resolve("with")
            val r1 = compiler.compile(
                listOf(source), emptyList(), cp, withPlugin, jvmTarget = "1.8",
                compilerPlugins = listOf(pluginJar!!),
            )
            assertTrue(r1.success, "compile WITH the parcelize plugin failed: ${r1.messages}")
            assertTrue(
                generatedParcelableImpl(withPlugin),
                "the parcelize plugin did not generate writeToParcel()/CREATOR into AssetSource",
            )

            // Without the plugin: the SAME source fails exactly as the user reported (negative control).
            val noPlugin = dir.resolve("without")
            val r2 = compiler.compile(listOf(source), emptyList(), cp, noPlugin, jvmTarget = "1.8")
            assertFalse(r2.success, "compile WITHOUT the plugin unexpectedly succeeded (nothing to fix?)")
            val msg = r2.messages.joinToString("\n")
            assertTrue(
                "does not implement abstract member" in msg || "writeToParcel" in msg,
                "the negative control should reproduce the Parcelable error; got:\n$msg",
            )
        }
    }

    /** The parcelize runtime jar(s) on the test classpath — detected exactly as the build detects a parcelize
     *  module (the `kotlinx.parcelize.Parcelize` class present). */
    private fun parcelizeRuntimeJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator).map { Path.of(it) }
            .filter { Files.exists(it) }
            .filter { ParcelizeCompilerPlugin.usesParcelize(listOf(it)) }

    /** The compiled `AssetSource.class` carries the plugin-generated `writeToParcel` + `CREATOR` (constant-pool scan). */
    private fun generatedParcelableImpl(outDir: Path): Boolean {
        if (!Files.exists(outDir)) return false
        val cls = Files.walk(outDir).use { s ->
            s.filter { it.fileName.toString() == "AssetSource.class" }.findFirst().orElse(null)
        } ?: return false
        val text = String(Files.readAllBytes(cls), Charsets.ISO_8859_1)
        return "writeToParcel" in text && "CREATOR" in text
    }

    private fun androidJar(): Path? = sdkRoots().map { it.resolve("platforms") }.filter { Files.isDirectory(it) }
        .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
        .map { it.resolve("android.jar") }.filter { Files.isRegularFile(it) }
        .maxByOrNull { it.parent.fileName.toString() }

    private fun sdkRoots(): List<Path> = listOfNotNull(
        System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"),
        System.getProperty("user.home") + "/Library/Android/sdk",
    ).map { Path.of(it) }.filter { Files.isDirectory(it) }
}
