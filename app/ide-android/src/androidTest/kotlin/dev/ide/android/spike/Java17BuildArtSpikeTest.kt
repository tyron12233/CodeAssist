package dev.ide.android.spike

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.lang.jdt.compile.JdtBatchCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device proof that the IDE compiles **Java 17** to `.class` on ART with no JRT image: the real
 * [JdtBatchCompiler] (→ `ImageFreeJavaCompiler`: the internal ecj compiler over a classic name environment)
 * running against the bundled `android.jar` + `core-lambda-stubs.jar`, using the app's dexed **ecj-art** jar.
 * This is the device counterpart to the desktop `JdtImageFreeCompileTest` — it closes the one gap that couldn't
 * be checked on the host (does ecj's internal compiler + classic env produce Java-17 bytecode on Dalvik?).
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.Java17BuildArtSpikeTest
 *     adb logcat -s Java17BuildArt
 */
@RunWith(AndroidJUnit4::class)
class Java17BuildArtSpikeTest {

    private val TAG = "Java17BuildArt"

    @Test
    fun compilesJava17AgainstAndroidJarOnArt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(ctx.filesDir, "java17-build-spike").apply { deleteRecursively(); mkdirs() }
        val androidJar = copyAsset(ctx, "android.jar", File(work, "android.jar"))
        val stubs = copyAsset(ctx, "core-lambda-stubs.jar", File(work, "core-lambda-stubs.jar"))

        val src = File(work, "src/p").apply { mkdirs() }
        File(src, "Helper.java").writeText(
            "package p; public record Helper(int x){ public String tag(){ return \"x=\"+x; } }")
        File(src, "Demo.java").writeText(
            """
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
                    String t = "tag=" + h.tag() + " n=" + xs.size();
                    int n = switch (0) { default -> 1; };
                    System.out.println(t + n);
                }
            }
            """.trimIndent())

        val out = File(work, "out")
        val result = try {
            JdtBatchCompiler.compile(
                sources = listOf(src.resolve("Helper.java").toPath(), src.resolve("Demo.java").toPath()),
                classpath = emptyList(),
                outputDir = out.toPath(),
                sourceLevel = "17",
                bootClasspath = listOf(androidJar.toPath(), stubs.toPath()),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "compile threw on ART", t)
            throw AssertionError("JdtBatchCompiler threw on ART: ${t}", t)
        }

        Log.i(TAG, "success=${result.success} messages=${result.messages}")
        assertTrue("Java 17 must compile on ART against android.jar+stubs: ${result.messages}", result.success)

        val demo = File(out, "p/Demo.class")
        val helper = File(out, "p/Helper.class")
        assertTrue("class files emitted: ${out.walkTopDown().toList()}", demo.exists() && helper.exists())
        val bytes = demo.readBytes()
        val major = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
        Log.i(TAG, "Demo.class major version = $major (61 = Java 17)")
        assertEquals("Demo.class should be Java 17 bytecode (major 61) — no level-8 clamp on ART", 61, major)
    }

    private fun copyAsset(ctx: Context, assetName: String, dest: File): File {
        ctx.assets.open(assetName).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }
}
