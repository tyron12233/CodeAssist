package dev.ide.android.spike

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Discovery spike (not a regression test). Runs the bundled K2 `kotlin-compiler-embeddable` on a real
 * device to find out what breaks on ART when compiling Kotlin to `.class`. It is the empirical input to
 * the ASM patch passes in `buildSrc` (`dev.ide.build.kotlinc.ArtPatchPasses`): each failure this surfaces
 * becomes one targeted pass, after which this spike is re-run until it goes green.
 *
 * It swallows nothing in the interesting path: the moment the compiler throws (a `LinkageError`,
 * `VerifyError`, `NoClassDefFoundError`, `ExceptionInInitializerError`, missing NIO `jar:` provider, ...)
 * the throwable (class + cause + full stack) is logged to logcat under tag [TAG] and the test fails with
 * that detail, so the offending class is obvious from `adb logcat` / the test report.
 *
 * Run on a connected device (an arm64 / 16 KB-page device used for the on-device APK builds):
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest
 *     adb logcat -s KotlincArtSpike
 *
 * Because [dev.ide.build.kotlinc.ArtPatchPasses] starts empty, the first run is expected to fail; that
 * failure is the deliverable. As passes are added it should progress to a clean compile producing `.class`
 * output. The compiler runs in-process here, exactly as it would for an on-device Kotlin build (and as the
 * editor's PSI parse-host already does).
 */
@RunWith(AndroidJUnit4::class)
class KotlinCompilerArtSpikeTest {

    @Test
    fun k2jvmCompilerRunsOnArt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(ctx.filesDir, "kotlinc-art-spike").apply { deleteRecursively(); mkdirs() }

        // IntelliJ-core reads its extension-point descriptors (META-INF/extensions/*.xml) from a real
        // filesystem path. Extract the compiler's resources (the kotlinc-resources.zip asset = jar minus
        // .class) to a home dir and publish it via `kotlinc.art.home`, which the ASM PathUtil pass reads.
        val home = provisionKotlincHome(ctx, File(work, "kotlinc-home"))
        System.setProperty("kotlinc.art.home", home.absolutePath)
        Log.i(TAG, "kotlinc.art.home = $home")

        // Compile classpath: android.jar (the java.* / android.* shape, with -no-jdk) + the Kotlin
        // stdlib JAR. Both ship as assets (android.jar in src/main/assets; kotlin-stdlib.jar staged by the
        // bundleKotlinStdlibAsset Gradle task). The app's own dexed stdlib can't serve as a -classpath input.
        val androidJar = copyAsset(ctx, "android.jar", File(work, "android.jar"))
        val stdlibJar = copyAsset(ctx, "kotlin-stdlib.jar", File(work, "kotlin-stdlib.jar"))

        val srcDir = File(work, "src").apply { mkdirs() }
        File(srcDir, "Sample.kt").writeText(
            """
            package spike

            fun greeting(name: String): String = "Hello, ${'$'}name!"

            class Counter(var value: Int = 0) {
                fun increment(): Int { value += 1; return value }
            }

            fun main() {
                val c = Counter()
                repeat(3) { c.increment() }
                println(greeting("ART") + " count=" + c.value)
            }
            """.trimIndent(),
        )
        val outDir = File(work, "out").apply { mkdirs() }

        val args = K2JVMCompilerArguments().apply {
            freeArgs = listOf(srcDir.absolutePath)
            destination = outDir.absolutePath
            classpath = listOf(androidJar, stdlibJar).joinToString(File.pathSeparator) { it.absolutePath }
            // ART has no JDK and the app's stdlib/reflect are dexed, so feed everything explicitly and skip
            // the compiler's JDK/stdlib auto-discovery (which would look for a JAVA_HOME that isn't there).
            noJdk = true
            noStdlib = true
            noReflect = true
            // Keep emitted bytecode conservative; codegen target is independent of whether the compiler runs.
            jvmTarget = "1.8"
        }

        val messages = RecordingMessageCollector()
        val exit: ExitCode = try {
            K2JVMCompiler().exec(messages, Services.EMPTY, args)
        } catch (t: Throwable) {
            // The discovery payload: the class/cause that ART can't run. Log it and fail.
            Log.e(TAG, "K2JVMCompiler threw while RUNNING on ART — add an ArtPatchPass for this:", t)
            Log.e(TAG, "compiler messages so far:\n${messages.dump()}")
            fail(
                "Kotlin compiler failed to run on ART: ${t.javaClass.name}: ${t.message}\n" +
                    "Add a pass to dev.ide.build.kotlinc.ArtPatchPasses targeting the class in this trace, " +
                    "then re-run.\n${t.stackTraceToString()}",
            )
            return // unreachable; keeps the compiler happy about `exit` being assigned
        }

        Log.i(TAG, "K2JVMCompiler exit=$exit")
        Log.i(TAG, "compiler messages:\n${messages.dump()}")

        val produced = outDir.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
        Log.i(TAG, "produced ${produced.size} .class file(s): ${produced.map { it.name }}")

        assertTrue(
            "Compiler ran without throwing but reported errors / produced no .class output. " +
                "Messages:\n${messages.dump()}",
            exit == ExitCode.OK && produced.isNotEmpty(),
        )
    }

    private fun copyAsset(ctx: Context, assetName: String, dest: File): File {
        ctx.assets.open(assetName).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }

    /** Extract the kotlinc-resources.zip asset (the compiler's non-class resources) into [home]. */
    private fun provisionKotlincHome(ctx: Context, home: File): File {
        home.deleteRecursively()
        home.mkdirs()
        val canonicalHome = home.canonicalPath + File.separator
        ctx.assets.open("kotlinc-resources.zip").use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(home, entry.name)
                    // Zip-slip guard (a controlled archive, but cheap to be safe).
                    if (outFile.canonicalPath.startsWith(canonicalHome)) {
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { zis.copyTo(it) }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return home
    }

    /** Collects every compiler diagnostic so failures and warnings are visible in the test report / logcat. */
    private class RecordingMessageCollector : MessageCollector {
        private val lines = mutableListOf<String>()
        private var errors = false

        override fun clear() {
            lines.clear()
            errors = false
        }

        override fun hasErrors(): Boolean = errors

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            if (severity.isError) errors = true
            val where = location?.let { " (${it.path}:${it.line}:${it.column})" } ?: ""
            lines += "[$severity] $message$where"
        }

        fun dump(): String = if (lines.isEmpty()) "(no messages)" else lines.joinToString("\n")
    }

    private companion object {
        const val TAG = "KotlincArtSpike"
    }
}
