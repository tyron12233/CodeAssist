package dev.ide.lang.jdt

import dev.ide.lang.jdt.compile.JdtBatchCompiler
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Android/ART build path: [JdtBatchCompiler] compiles **Java 17** to `.class` against `android.jar` +
 * `core-lambda-stubs.jar` with NO JRT image, by routing compliance >= 9 + an android boot library through the
 * internal ecj compiler over a classic name environment (see `ImageFreeJavaCompiler`). This is the build-side
 * counterpart to the editor fix — same platform jars, no `-bootclasspath`, no level cap. Skips when no SDK.
 */
class JdtImageFreeCompileTest {

    @Test
    fun compilesJava17AgainstAndroidJarWithoutJrtImage() {
        val androidJar = androidJar() ?: run { println("[ImageFreeCompile] no Android SDK — skipping"); return }
        val stubs = coreLambdaStubs() ?: run { println("[ImageFreeCompile] no core-lambda-stubs — skipping"); return }
        val dir = Files.createTempDirectory("imagefree")
        try {
            val src = dir.resolve("src").also { Files.createDirectories(it.resolve("p")) }
            // Two units with a cross-file reference + Java 17 features (records, sealed, switch-expr, var, text
            // block, string concat) + android types.
            Files.write(src.resolve("p/Helper.java"),
                "package p; public record Helper(int x){ public String tag(){ return \"x=\"+x; } }".toByteArray())
            Files.write(src.resolve("p/Demo.java"), """
                package p;
                import android.app.Activity;
                import android.os.Bundle;
                import java.util.ArrayList;
                public class Demo extends Activity {
                    sealed interface S permits C, D {}
                    record C() implements S {}
                    record D() implements S {}
                    protected void onCreate(Bundle b) {
                        super.onCreate(b);
                        var xs = new ArrayList<String>(); xs.add("h");
                        var h = new Helper(xs.size());
                        String t = ""${'"'}
                            hi""${'"'} + h.tag();
                        int n = switch (0) { default -> 1; };
                        System.out.println(t + n);
                    }
                }
            """.trimIndent().toByteArray())

            val out = dir.resolve("out")
            val sources = listOf(src.resolve("p/Helper.java"), src.resolve("p/Demo.java"))
            val result = JdtBatchCompiler.compile(sources, classpath = emptyList(), outputDir = out,
                sourceLevel = "17", bootClasspath = listOf(androidJar, stubs))

            assertTrue(result.success, "Java 17 should compile against android.jar+stubs with no jimage: ${result.messages}")
            val demo = out.resolve("p/Demo.class")
            val helper = out.resolve("p/Helper.class")
            assertTrue(Files.exists(demo) && Files.exists(helper), "class files emitted: ${out.toFile().walkTopDown().toList()}")
            // Demo.class must be Java 17 bytecode (major version 61) — the cap was NOT applied.
            val bytes = Files.readAllBytes(demo)
            val major = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
            assertEquals(61, major, "Demo.class should be Java 17 (major 61), proving no level-8 clamp")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun sdkRoots() = listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        System.getProperty("user.home") + "/Library/Android/sdk",
    ).map { Path.of(it) }.filter { Files.isDirectory(it) }

    private fun androidJar(): Path? = sdkRoots().map { it.resolve("platforms") }.filter { Files.isDirectory(it) }
        .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
        .map { it.resolve("android.jar") }.filter { Files.isRegularFile(it) }
        .maxByOrNull { it.parent.fileName.toString() }

    private fun coreLambdaStubs(): Path? = sdkRoots().map { it.resolve("build-tools") }.filter { Files.isDirectory(it) }
        .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
        .map { it.resolve("core-lambda-stubs.jar") }.filter { Files.isRegularFile(it) }
        .maxByOrNull { it.parent.fileName.toString() }
}
