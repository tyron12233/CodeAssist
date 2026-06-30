package dev.ide.android.support

import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.R8InProcessShrinker
import dev.ide.android.support.tools.ShrinkRequest
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A minify (R8) build must not fail just because a class is referenced but absent from the program +
 * classpath. Real apps hit this constantly — e.g. Play Services' `DeviceProperties` is annotated with
 * `@com.google.android.apps.common.proguard.SideEffectFree`, an annotation Google never publishes as a class,
 * so R8 reports `Error: Missing class …SideEffectFree` and (by default) fails the whole build. A tolerant
 * on-device IDE keeps building: the shrinkers add `-ignorewarnings` on the minify path, which downgrades the
 * missing-class error to a still-logged warning (see [dev.ide.android.support.tools.R8_IGNORE_WARNINGS]).
 *
 * This exercises the shrinker directly (no aapt2/model machinery): a class `A` annotated by `Anno`, with
 * `Anno.class` deleted before R8 runs, plus a keep rule (so the run takes the minify path, NOT the
 * already-tolerant pass-through fallback). Without the fix R8 fails here; with it the dex is produced and the
 * missing class is surfaced as a warning.
 */
class R8MissingClassToleranceTest {

    @Test
    fun minifyToleratesAClassReferencedButMissingFromTheClasspath() {
        val sdk = AndroidSdk.findSdkRoot()?.let { AndroidSdk.detect(it) }
        assumeTrue(sdk != null && sdk.isComplete(), "Android SDK not installed; skipping")
        sdk!!
        val javac = ToolProvider.getSystemJavaCompiler()
        assumeTrue(javac != null, "no system javac (test not run on a JDK); skipping")

        val dir = Files.createTempDirectory("r8-missing")
        try {
            val src = Files.createDirectories(dir.resolve("src"))
            val anno = src.resolve("Anno.java").also { Files.writeString(it, "public @interface Anno {}") }
            val a = src.resolve("A.java").also { Files.writeString(it, "@Anno public class A { public void m() {} }") }
            val classes = Files.createDirectories(dir.resolve("classes"))
            check(javac!!.run(null, null, null, "-d", classes.toString(), anno.toString(), a.toString()) == 0) {
                "fixture compile failed"
            }
            // Drop the annotation class so A now references a type absent from the program + classpath.
            Files.delete(classes.resolve("Anno.class"))
            val programJar = dir.resolve("program.jar")
            ZipOutputStream(Files.newOutputStream(programJar)).use { z ->
                z.putNextEntry(ZipEntry("A.class")); Files.copy(classes.resolve("A.class"), z); z.closeEntry()
            }

            val keep = dir.resolve("keep.pro").also { Files.writeString(it, "-keep class A { *; }") }
            val outDir = dir.resolve("dex")
            val result = R8InProcessShrinker().shrink(
                ShrinkRequest(
                    programs = listOf(programJar),
                    library = sdk.androidJar,
                    keepRuleFiles = listOf(keep),
                    minApi = 24,
                    release = true,
                    outDir = outDir,
                ),
            )

            assertTrue(result.success, "minify must tolerate the missing class:\n${result.log.joinToString("\n")}")
            assertTrue(Files.exists(outDir.resolve("classes.dex")), "a dex must be produced despite the missing class")
            assertTrue(
                result.log.any { it.startsWith("warning:") && it.contains("Missing class", ignoreCase = true) },
                "the missing class must still surface as a warning (not silently dropped):\n${result.log.joinToString("\n")}",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
