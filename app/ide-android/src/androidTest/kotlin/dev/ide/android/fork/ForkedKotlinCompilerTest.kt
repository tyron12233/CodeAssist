package dev.ide.android.fork

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.R8ForkSupport
import dev.ide.lang.kotlin.compile.KotlinCompileRequest
import dev.ide.lang.kotlin.compile.KotlinCompileResult
import dev.ide.lang.kotlin.compile.KotlinCompilerBackend
import dev.ide.lang.kotlin.compile.KotlinJvmCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * On-device coverage for the persistent forked Kotlin compiler VM ([ForkedKotlinCompiler]).
 *
 * The three properties the design rests on, each checked against a real forked VM on real hardware:
 *  - it compiles at all, off the app's own APK, producing the same `.class` output and source-to-class
 *    mapping the in-process compiler produces ([compilesInAForkedVm]);
 *  - the VM is REUSED, so the second compile is far cheaper than the first ([reusesTheWorkerAcrossCompiles]) —
 *    this is the whole reason the VM is persistent rather than per invocation;
 *  - it never fails a build it could have completed: In-process mode does not fork
 *    ([fallsBackWhenForkingIsOff]), an unbackable heap request steps down to one the device can grant
 *    ([stepsDownToAHeapTheDeviceCanBack]), and a worker that dies is replaced
 *    ([survivesAWorkerKilledMidSession]).
 *
 * Run on a connected device:
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.fork.ForkedKotlinCompilerTest
 *     adb logcat -s ForkedKotlincTest
 */
@RunWith(AndroidJUnit4::class)
class ForkedKotlinCompilerTest {

    @Test
    fun compilesInAForkedVm() {
        val fixture = fixtureOrSkip("forked-compile")
        val compiler = forked(fixture, CountingFallback())

        val result = compiler.compile(fixture.request("Sample.kt", SAMPLE_SOURCE, "out"))
        compiler.close()

        Log.i(TAG, "forked compile: ok=${result.success} messages=${result.messages}")
        assertTrue("forked compile failed: ${result.messages}", result.success)
        val produced = fixture.work.resolve("out").walkTopDown().filter { it.extension == "class" }.map { it.name }.toSet()
        assertEquals(setOf("Counter.class", "SampleKt.class"), produced)
        // The source-to-class mapping is what the incremental layer prunes and ABI-diffs against, so it has to
        // survive the process hop, not just the .class files.
        assertEquals(1, result.outputs.size)
        assertTrue(
            "output mapping did not name the compiled source: ${result.outputs.keys}",
            result.outputs.keys.single().toString().endsWith("Sample.kt"),
        )
        assertEquals(2, result.outputs.values.single().size)
    }

    /**
     * The persistence claim. Compile twice through one [ForkedKotlinCompiler]: the first pays the VM boot and
     * the compiler's cold start, the second reuses the same worker and its warm environment. A per-invocation
     * fork could not show this gap, which is why the VM is kept alive.
     */
    @Test
    fun reusesTheWorkerAcrossCompiles() {
        val fixture = fixtureOrSkip("forked-reuse")
        val fallback = CountingFallback()
        val compiler = forked(fixture, fallback)

        val timings = (0 until COMPILES).map { i ->
            val start = System.nanoTime()
            val result = compiler.compile(fixture.request("Sample$i.kt", sampleSource(i), "out$i"))
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue("compile #${i + 1} failed: ${result.messages}", result.success)
            Log.i(TAG, "TIMING forked compile #${i + 1} (${if (i == 0) "cold worker" else "reused worker"}): ${ms}ms")
            ms
        }
        compiler.close()

        val cold = timings.first()
        val warm = timings.drop(1)
        Log.i(TAG, "REUSE SUMMARY: cold=${cold}ms warm=${warm.joinToString("/") { "${it}ms" }} avg=${warm.average().toLong()}ms")
        assertEquals("a healthy forked compile must never reach the fallback", 0, fallback.compiles)
        assertTrue(
            "the worker was not reused: warm compiles (${warm.joinToString()}) are not faster than the cold one ($cold)",
            warm.min() < cold,
        )
    }

    /** In-process mode never forks, and produces the same result through the fallback. */
    @Test
    fun fallsBackWhenForkingIsOff() {
        val fixture = fixtureOrSkip("forked-off")
        val fallback = CountingFallback()
        val compiler = forked(fixture, fallback, mode = "inprocess")

        val result = compiler.compile(fixture.request("Sample.kt", SAMPLE_SOURCE, "out"))
        compiler.close()

        assertTrue("in-process compile failed: ${result.messages}", result.success)
        assertEquals("In-process mode must not fork", 1, fallback.compiles)
    }

    /**
     * A heap the device cannot back must not sink the compile. `affordableHeaps` drops the rungs the RAM
     * cannot reserve (a VM that cannot reserve its region space ABORTS rather than failing politely), and the
     * worker starts at the largest one left. Asks for a heap no phone has and expects a normal compile.
     */
    @Test
    fun stepsDownToAHeapTheDeviceCanBack() {
        val fixture = fixtureOrSkip("forked-ladder")
        val fallback = CountingFallback()
        val compiler = forked(fixture, fallback, heapMb = UNBACKABLE_XMX_MB)

        val result = compiler.compile(fixture.request("Sample.kt", SAMPLE_SOURCE, "out"))
        compiler.close()

        Log.i(TAG, "ladder: ok=${result.success} fallbackCompiles=${fallback.compiles}")
        assertTrue("compile at an unbackable requested heap failed: ${result.messages}", result.success)
        val produced = fixture.work.resolve("out").walkTopDown().count { it.extension == "class" }
        assertTrue("compile produced no classes", produced > 0)
    }

    /**
     * A worker killed between compiles (the OS reclaiming it, an idle exit, a crash) must be noticed and
     * replaced rather than wedging the build. Kills every forked VM this app owns after a successful compile,
     * then compiles again through the same object.
     */
    @Test
    fun survivesAWorkerKilledMidSession() {
        val fixture = fixtureOrSkip("forked-kill")
        val fallback = CountingFallback()
        val compiler = forked(fixture, fallback)

        assertTrue(compiler.compile(fixture.request("A.kt", sampleSource(1), "outA")).success)
        val killed = killWorkers()
        Log.i(TAG, "killed $killed worker process(es)")
        assumeTrue("could not identify a worker process to kill on this device", killed > 0)

        val second = compiler.compile(fixture.request("B.kt", sampleSource(2), "outB"))
        compiler.close()

        Log.i(TAG, "after kill: ok=${second.success} fallbackCompiles=${fallback.compiles}")
        assertTrue("compile after a killed worker failed: ${second.messages}", second.success)
        val produced = fixture.work.resolve("outB").walkTopDown().count { it.extension == "class" }
        assertTrue("compile after a killed worker produced nothing", produced > 0)
        // A lost worker is replaced, not routed around: whether the death is noticed before the send (the
        // pooled worker is already reaped) or during it (the retry), a fresh VM serves the compile.
        assertEquals("a lost worker must be replaced, not fallen back on", 0, fallback.compiles)
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private fun forked(
        fixture: Fixture,
        fallback: KotlinCompilerBackend,
        mode: String? = null,
        heapMb: Int? = null,
    ) = ForkedKotlinCompiler(
        fixture.ctx,
        modeProvider = { mode },
        maxHeapMbProvider = { heapMb },
        workerCountProvider = { 1 },
        hostsBuilds = { true },
        androidJar = fixture.androidJar.toPath(),
        minApi = 26,
        fallback = fallback,
    )

    /** Counts what the in-process path was asked to do, so a test can assert the fork was (not) used. */
    private class CountingFallback(
        private val delegate: KotlinJvmCompiler = KotlinJvmCompiler(),
    ) : KotlinCompilerBackend {
        @Volatile
        var compiles = 0

        override fun compile(request: KotlinCompileRequest): KotlinCompileResult {
            compiles++
            return delegate.compile(request)
        }
    }

    private class Fixture(val ctx: Context, val work: File, val androidJar: File, val stdlibJar: File) {

        fun request(fileName: String, source: String, outDirName: String): KotlinCompileRequest {
            val srcDir = File(work, "src").apply { mkdirs() }
            val src = File(srcDir, fileName).apply { writeText(source) }
            val outDir = File(work, outDirName).apply { deleteRecursively(); mkdirs() }
            return KotlinCompileRequest(
                kotlinSources = listOf(src.toPath()),
                classpath = listOf<Path>(stdlibJar.toPath()),
                outputDir = outDir.toPath(),
                jvmTarget = "1.8",
                bootClasspath = listOf(androidJar.toPath()),
            )
        }
    }

    private fun fixtureOrSkip(name: String): Fixture {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("no command-line VM launcher on this device", R8ForkSupport.launcher() != null)
        val work = File(ctx.filesDir, name).apply { deleteRecursively(); mkdirs() }
        // The worker resolves the extension-point descriptors through `kotlinc.art.home`, which the app
        // normally publishes at startup; an instrumentation run may not have gone through that path.
        if (System.getProperty("kotlinc.art.home").isNullOrEmpty()) {
            System.setProperty("kotlinc.art.home", provisionKotlincHome(ctx, File(work, "kotlinc-home")).absolutePath)
        }
        return Fixture(
            ctx,
            work,
            copyAsset(ctx, "android.jar", File(work, "android.jar")),
            copyAsset(ctx, "kotlin-stdlib.jar", File(work, "kotlin-stdlib.jar")),
        )
    }

    /** Kill every forked worker VM this app owns; returns how many were signalled. */
    private fun killWorkers(): Int {
        val marker = KotlincWorkerMain::class.java.name
        val pids = runCatching {
            val proc = ProcessBuilder("sh", "-c", "ps -A -o PID,ARGS 2>/dev/null || ps -A").redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().readLines()
                .filter { marker in it }
                .mapNotNull { it.trim().substringBefore(' ').toIntOrNull() }
        }.getOrDefault(emptyList())
        pids.forEach { runCatching { android.os.Process.killProcess(it) } }
        return pids.size
    }

    private fun copyAsset(ctx: Context, assetName: String, dest: File): File {
        ctx.assets.open(assetName).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }

    private fun provisionKotlincHome(ctx: Context, home: File): File {
        home.deleteRecursively()
        home.mkdirs()
        val canonical = home.canonicalPath + File.separator
        ctx.assets.open("kotlinc-resources.zip").use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(home, entry.name)
                    if (out.canonicalPath.startsWith(canonical)) {
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return home
    }

    /** The sample with its class renamed, so sibling compiles in one session cannot collide on output. */
    private fun sampleSource(i: Int): String = SAMPLE_SOURCE.replace("Counter", "Counter$i")

    private companion object {
        const val TAG = "ForkedKotlincTest"
        const val COMPILES = 3

        /** Above any plausible device budget, so the ladder must step down to a rung that can be reserved. */
        const val UNBACKABLE_XMX_MB = 65536

        val SAMPLE_SOURCE = """
            package spike

            class Counter(var value: Int = 0) {
                fun increment(): Int { value += 1; return value }
            }

            fun main() {
                val c = Counter()
                repeat(3) { c.increment() }
                println("count=" + c.value)
            }
        """.trimIndent()
    }
}
