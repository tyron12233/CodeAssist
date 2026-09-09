package dev.ide.android.spike

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.R8ForkSupport
import dev.ide.lang.kotlin.compile.KotlinJvmCompiler
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Discovery spike (not a regression test) for running the Kotlin compiler in a FORKED command-line VM, the
 * way [dev.ide.android.ForkedR8Shrinker] already runs R8.
 *
 * R8 forks against a dedicated 3.5MB `r8.dex.zip` asset. kotlinc cannot: the merged compiler jar is ~65MB of
 * `.class`, so a dexed asset would add tens of MB to the APK and a second copy on disk. The only affordable
 * classpath is the app's OWN dex, which already holds the compiler, the IntelliJ platform, and the ART shims
 * the `dev.ide.kotlinc-art` AGP instrumentation writes into it (`PathUtilSelfLocatePass`,
 * `ClassValueArtPass`, `VarHandleArtPass`, ...). This spike measures the two candidates side by side: the
 * installed APKs directly, and their `classes*.dex` entries extracted to `cacheDir`.
 *
 * `org.jetbrains.kotlin.cli.jvm.K2JVMCompiler` carries a `public static final void main(String[])`, so no
 * worker entry point has to be added to the app to answer the question. A compile that succeeds in the fork
 * also proves the patched classes loaded: an unpatched `PathUtil` fails at extension-point loading with
 * "Unable to find extension point configuration .../compiler-cli-root.xml".
 *
 * That CLI entry point does need [COLORS_OFF]. `CLICompiler.doMain` builds a `PlainTextMessageRenderer`,
 * whose static initializer calls `org.fusesource.jansi.internal.CLibrary.isatty` for TTY detection unless
 * `kotlin.colors.enabled` is off; jansi is not bundled, so without the flag the fork dies with
 * `NoClassDefFoundError: Lorg/fusesource/jansi/internal/CLibrary;` before compiling anything. The in-process
 * path never reaches it, because [dev.ide.lang.kotlin.compile.KotlinJvmCompiler] calls
 * `K2JVMCompiler.exec(collector, ...)` with its own message collector rather than going through `main`.
 *
 * The four questions, one test each:
 *  1. [appClassesLoadInAForkedVm] - does either classpath candidate let a forked VM load the compiler at all.
 *  2. [forkedKotlincCompilesASample] - does K2 then produce `.class` output in the fork.
 *  3. [forkedVsInProcessCompileCost] - what a fork costs versus the warm in-process compiler, which is the
 *     number that decides whether a fork can be per-invocation or has to be a long-lived daemon.
 *  4. [forkedKotlincHeapCeiling] - how much heap a fork holding the compiler is actually granted, versus the
 *     app's own `largeHeap` cap.
 *
 * Run on a connected device:
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.ForkedKotlincArtSpike
 *     adb logcat -s ForkedKotlincSpike
 */
@RunWith(AndroidJUnit4::class)
class ForkedKotlincArtSpike {

    /**
     * The classpath question, and the one that gates everything else. Launches
     * `<launcher> -Xmx768m -cp <candidate> K2JVMCompiler` with [PROBE_ARGS] for each candidate and reports
     * which ones start.
     */
    @Test
    fun appClassesLoadInAForkedVm() {
        val ctx = targetContext()
        val launcher = launcherOrSkip()

        val results = classpathCandidates(ctx).map { candidate ->
            val outcome = fork(launcher, listOf("-Xmx${PROBE_XMX_MB}m"), candidate.entries, COMPILER_MAIN, PROBE_ARGS)
            Log.i(TAG, "candidate '${candidate.name}' (${candidate.entries.size} entry, ${candidate.sizeMb}MB): $outcome")
            dumpOutput("candidate ${candidate.name}", outcome)
            candidate to outcome
        }

        val working = results.filter { it.second.ok }
        Log.i(
            TAG,
            "CLASSPATH SUMMARY: " + results.joinToString("  ") { (c, o) ->
                "${c.name}=${if (o.ok) "OK ${o.ms}ms" else "FAIL exit=${o.exit}"}"
            },
        )
        assertTrue(
            "No forked-VM classpath candidate loaded the app's compiler classes. A forked kotlinc would need " +
                "its own dexed compiler asset instead. Details:\n" +
                results.joinToString("\n") { (c, o) -> "${c.name}: exit=${o.exit}\n${o.output.take(40).joinToString("\n")}" },
            working.isNotEmpty(),
        )
    }

    /**
     * Compiles a real source file through the forked compiler, with the same argument shape
     * [dev.ide.lang.kotlin.compile.KotlinJvmCompiler] builds in-process: `-no-jdk` with `android.jar` folded
     * into the classpath, the bundled stdlib supplied explicitly, PSI parsing rather than LightTree (the
     * `-for-ide` publication excludes `light-tree2fir`), and `-Xreport-output-files` so the source to `.class`
     * mapping a worker protocol would have to carry is visible in the output.
     */
    @Test
    fun forkedKotlincCompilesASample() {
        val ctx = targetContext()
        val launcher = launcherOrSkip()
        val work = workDir(ctx, "forked-kotlinc-compile")
        val fixture = Fixture.provision(ctx, work)
        val candidate = firstWorkingCandidate(ctx, launcher)

        val srcDir = File(work, "src").apply { mkdirs() }
        File(srcDir, "Sample.kt").writeText(SAMPLE_SOURCE)
        val outDir = File(work, "out").apply { mkdirs() }

        val outcome = fork(
            launcher,
            vmArgs = listOf("-Xmx${COMPILE_XMX_MB}m", "-Dkotlinc.art.home=${fixture.kotlincHome.absolutePath}"),
            classpath = candidate.entries,
            mainClass = COMPILER_MAIN,
            args = compilerArgs(srcDir, outDir, fixture),
            timeoutSec = COMPILE_TIMEOUT_SEC,
        )
        dumpOutput("forked compile", outcome)

        val produced = outDir.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
        Log.i(TAG, "forked compile: exit=${outcome.exit} ${outcome.ms}ms classes=${produced.map { it.name }}")
        assertTrue(
            "Forked kotlinc did not produce .class output (exit=${outcome.exit}). Output:\n" +
                outcome.output.joinToString("\n"),
            outcome.ok && produced.isNotEmpty(),
        )
    }

    /**
     * The cost question. A forked VM starts empty every time: it class-loads the compiler, stands up the
     * IntelliJ application environment, and re-reads `android.jar` plus the stdlib, which is exactly the work
     * [dev.ide.lang.kotlin.compile.KotlinEnvironmentKeepAlive] exists to avoid across in-process compiles.
     *
     * Times three separate forks (every one of them cold) against the in-process compiler's cold and warm
     * numbers on the same source, on the same device. If the forked figure lands near the in-process COLD
     * figure, a per-invocation fork pays that gap on every module of every build and only a long-lived
     * compiler VM can be worth it; if it lands near WARM, a per-invocation fork is viable.
     */
    @Test
    fun forkedVsInProcessCompileCost() {
        val ctx = targetContext()
        val launcher = launcherOrSkip()
        val work = workDir(ctx, "forked-kotlinc-cost")
        val fixture = Fixture.provision(ctx, work)
        val candidate = firstWorkingCandidate(ctx, launcher)

        val srcDir = File(work, "src").apply { mkdirs() }
        File(srcDir, "Sample.kt").writeText(SAMPLE_SOURCE)

        val forked = (0 until FORK_RUNS).map { i ->
            val outDir = File(work, "fork-out$i").apply { deleteRecursively(); mkdirs() }
            val outcome = fork(
                launcher,
                vmArgs = listOf("-Xmx${COMPILE_XMX_MB}m", "-Dkotlinc.art.home=${fixture.kotlincHome.absolutePath}"),
                classpath = candidate.entries,
                mainClass = COMPILER_MAIN,
                args = compilerArgs(srcDir, outDir, fixture),
                timeoutSec = COMPILE_TIMEOUT_SEC,
            )
            val classes = outDir.walkTopDown().count { it.isFile && it.extension == "class" }
            Log.i(TAG, "TIMING forked compile #${i + 1}: ${outcome.ms}ms exit=${outcome.exit} classes=$classes")
            if (!outcome.ok) dumpOutput("forked compile #${i + 1}", outcome)
            assertTrue("forked compile #${i + 1} failed (exit=${outcome.exit})", outcome.ok && classes > 0)
            outcome.ms
        }

        // The in-process baseline, driven through the production compiler so the comparison is against what
        // the build actually runs today (keepalive on, same boot classpath, same stdlib).
        System.setProperty("kotlinc.art.home", fixture.kotlincHome.absolutePath)
        val compiler = KotlinJvmCompiler()
        val inProcess = (0 until IN_PROCESS_RUNS).map { i ->
            val outDir = File(work, "inproc-out$i").apply { deleteRecursively(); mkdirs() }
            val start = System.nanoTime()
            val result = compiler.compile(
                kotlinSources = listOf(File(srcDir, "Sample.kt").toPath()),
                javaSources = emptyList(),
                classpath = listOf(fixture.stdlibJar.toPath()),
                outputDir = outDir.toPath(),
                bootClasspath = listOf(fixture.androidJar.toPath()),
            )
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.i(TAG, "TIMING in-process compile #${i + 1} (${if (i == 0) "COLD" else "warm"}): ${ms}ms ok=${result.success}")
            assertTrue("in-process compile #${i + 1} failed: ${result.messages}", result.success)
            ms
        }

        val appHeapMb = Runtime.getRuntime().maxMemory() / MB
        Log.i(
            TAG,
            "COST SUMMARY (classpath=${candidate.name}, fork -Xmx${COMPILE_XMX_MB}m, app cap ${appHeapMb}MB): " +
                "forked=${forked.joinToString("/") { "${it}ms" }} avg=${forked.average().toLong()}ms  " +
                "inProcess cold=${inProcess.first()}ms warm=${inProcess.drop(1).joinToString("/") { "${it}ms" }}",
        )
    }

    /**
     * The heap question: the whole point of forking is an `-Xmx` above the app's `dalvik.vm.heapsize` cap.
     * Walks the affordable ladder ([R8ForkSupport.affordableHeaps] trims the rungs this device's RAM cannot
     * back, so no rung ends in an ART startup abort) and reports the largest at which a fork boots with the
     * compiler loaded, next to the app's own ceiling.
     */
    @Test
    fun forkedKotlincHeapCeiling() {
        val ctx = targetContext()
        val launcher = launcherOrSkip()
        val candidate = firstWorkingCandidate(ctx, launcher)

        val ladder = R8ForkSupport.affordableHeaps(ctx, HEAP_LADDER)
        Log.i(TAG, "heap ladder for ${R8ForkSupport.totalMemMb(ctx)}MB of RAM: $ladder")
        var ceiling: Int? = null
        for (mb in ladder) {
            val outcome = fork(launcher, listOf("-Xmx${mb}m"), candidate.entries, COMPILER_MAIN, PROBE_ARGS)
            Log.i(TAG, "heap probe -Xmx${mb}m: ${if (outcome.ok) "granted" else "refused (exit=${outcome.exit})"} ${outcome.ms}ms")
            if (outcome.ok) ceiling = mb else break
        }
        Log.i(
            TAG,
            "HEAP SUMMARY: forked kotlinc ceiling=${ceiling ?: "none"}MB  " +
                "app cap=${Runtime.getRuntime().maxMemory() / MB}MB  device RAM=${R8ForkSupport.totalMemMb(ctx)}MB",
        )
        assertTrue("No affordable heap started a forked VM holding the compiler", ceiling != null)
    }

    // --- Forked-VM classpath candidates ------------------------------------------------------------------

    /** One way of pointing a forked VM at the app's own dexed classes. */
    private class Candidate(val name: String, val entries: List<String>) {
        val sizeMb: Long get() = entries.sumOf { runCatching { File(it).length() }.getOrDefault(0L) } / MB
    }

    /**
     * The two ways to hand a forked VM the app's classes, cheapest first:
     *  - `apk`: the installed APKs as they sit on disk. Costs nothing, but relies on the VM's classloader
     *    reading every `classes*.dex` inside them, which is what `R8ForkSupport` reports did not work.
     *  - `extracted-dex`: each `classes*.dex` unpacked to `cacheDir` and made read-only. Read-only is
     *    mandatory: ART refuses a writable dex on a command-line VM's classpath. Costs a full copy of the
     *    app's dex on disk, and carries classes only: a classloader built over bare dex has no resources, so
     *    `getResourceAsStream` returns null and the compiler fails in `KotlinCompilerVersion.<clinit>` reading
     *    its own `compiler.version`. Anything loaded by resource (`META-INF/services`, extension descriptors)
     *    has the same problem, which is why `R8ForkSupport` puts the tool's zip on the classpath rather than
     *    the dex files inside it.
     */
    private fun classpathCandidates(ctx: Context): List<Candidate> {
        val apks = appApks(ctx)
        Log.i(TAG, "app APKs: ${apks.map { "${it.name} ${it.length() / MB}MB" }}")
        return listOf(
            Candidate("apk", apks.map { it.absolutePath }),
            Candidate("extracted-dex", extractAppDexes(ctx, apks).map { it.absolutePath }),
        ).filter { it.entries.isNotEmpty() }
    }

    private fun appApks(ctx: Context): List<File> {
        val info = ctx.applicationInfo
        return (listOfNotNull(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList()))
            .map(::File)
            .filter { it.isFile }
    }

    /** Unpack every `classes*.dex` from [apks] into `cacheDir`, read-only, and return them in load order. */
    private fun extractAppDexes(ctx: Context, apks: List<File>): List<File> {
        val dir = File(ctx.cacheDir, "app-dex-fork")
        dir.listFiles()?.forEach { it.setWritable(true); it.delete() }
        dir.mkdirs()
        val start = System.nanoTime()
        val out = ArrayList<File>()
        apks.forEachIndexed { apkIndex, apk ->
            runCatching {
                ZipFile(apk).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.matches(DEX_ENTRY) }
                        .sortedBy { it.name }
                        .forEach { entry ->
                            val dest = File(dir, "$apkIndex-${entry.name}")
                            zip.getInputStream(entry).use { ins -> dest.outputStream().use { ins.copyTo(it) } }
                            dest.setReadOnly() // ART rejects a writable dex on a VM classpath
                            out += dest
                        }
                }
            }.onFailure { Log.w(TAG, "could not read dex out of ${apk.name}: ${it.message}") }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        Log.i(TAG, "extracted ${out.size} dex (${out.sumOf { it.length() } / MB}MB) from ${apks.size} APK(s) in ${ms}ms")
        return out
    }

    /** The first candidate that boots a VM with the compiler loadable, or skip the test if none does. */
    private fun firstWorkingCandidate(ctx: Context, launcher: String): Candidate {
        val candidate = classpathCandidates(ctx).firstOrNull {
            fork(launcher, listOf("-Xmx${PROBE_XMX_MB}m"), it.entries, COMPILER_MAIN, PROBE_ARGS).ok
        }
        assumeTrue("no forked-VM classpath loads the app's compiler classes on this device", candidate != null)
        Log.i(TAG, "using classpath candidate '${candidate!!.name}'")
        return candidate
    }

    // --- Forking ------------------------------------------------------------------------------------------

    private class Outcome(val exit: Int, val ms: Long, val output: List<String>) {
        val ok: Boolean get() = exit == 0
        override fun toString(): String = "exit=$exit ${ms}ms (${output.size} output line(s))"
    }

    private fun fork(
        launcher: String,
        vmArgs: List<String>,
        classpath: List<String>,
        mainClass: String,
        args: List<String>,
        timeoutSec: Long = PROBE_TIMEOUT_SEC,
    ): Outcome {
        val cmd = buildList {
            add(launcher); add(COLORS_OFF); addAll(vmArgs)
            add("-cp"); add(classpath.joinToString(File.pathSeparator))
            add(mainClass); addAll(args)
        }
        val start = System.nanoTime()
        val proc = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            return Outcome(-1, 0, listOf("could not launch $launcher: ${t.message}"))
        }
        // Drain to EOF before waiting, so a chatty compile cannot fill the pipe buffer and deadlock.
        val output = proc.inputStream.bufferedReader().readLines()
        val exited = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!exited) {
            proc.destroyForcibly()
            return Outcome(-2, (System.nanoTime() - start) / 1_000_000, output + "timed out after ${timeoutSec}s")
        }
        return Outcome(proc.exitValue(), (System.nanoTime() - start) / 1_000_000, output)
    }

    private fun launcherOrSkip(): String {
        val launcher = R8ForkSupport.launcher()
        assumeTrue("no command-line VM launcher on this device", launcher != null)
        Log.i(TAG, "launcher = $launcher")
        return launcher!!
    }

    private fun dumpOutput(label: String, outcome: Outcome) {
        if (outcome.output.isEmpty()) return
        Log.i(TAG, "--- $label output (${outcome.output.size} lines) ---")
        outcome.output.take(MAX_LOGGED_LINES).forEach { Log.i(TAG, it) }
        if (outcome.output.size > MAX_LOGGED_LINES) Log.i(TAG, "... ${outcome.output.size - MAX_LOGGED_LINES} more line(s)")
    }

    // --- Compile fixture ----------------------------------------------------------------------------------

    /** The on-disk inputs a compile needs, whichever process runs it. */
    private class Fixture(val androidJar: File, val stdlibJar: File, val kotlincHome: File) {
        companion object {
            fun provision(ctx: Context, work: File): Fixture = Fixture(
                androidJar = copyAsset(ctx, "android.jar", File(work, "android.jar")),
                stdlibJar = copyAsset(ctx, "kotlin-stdlib.jar", File(work, "kotlin-stdlib.jar")),
                kotlincHome = provisionKotlincHome(ctx, File(work, "kotlinc-home")),
            )

            private fun copyAsset(ctx: Context, name: String, dest: File): File {
                ctx.assets.open(name).use { input -> dest.outputStream().use { input.copyTo(it) } }
                return dest
            }

            /**
             * Extract `kotlinc-resources.zip` (the compiler jar minus its `.class` entries) so IntelliJ-core
             * can read its extension-point descriptors off a real filesystem path. The patched `PathUtil`
             * finds it through the `kotlinc.art.home` property, which the fork receives as a `-D` VM arg.
             */
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
                                if (entry.isDirectory) {
                                    out.mkdirs()
                                } else {
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
        }
    }

    /**
     * The CLI form of the arguments [dev.ide.lang.kotlin.compile.KotlinJvmCompiler] builds programmatically.
     * `-Xuse-fir-lt=false` is required, not a preference: the `-for-ide` compiler publication excludes
     * `light-tree2fir`, so the CLI default would fail with `NoClassDefFoundError fir/lightTree/LightTree`.
     */
    private fun compilerArgs(srcDir: File, outDir: File, fixture: Fixture): List<String> = listOf(
        srcDir.absolutePath,
        "-d", outDir.absolutePath,
        "-classpath", listOf(fixture.androidJar, fixture.stdlibJar).joinToString(File.pathSeparator) { it.absolutePath },
        "-no-jdk", "-no-stdlib", "-no-reflect",
        "-jvm-target", "1.8",
        "-Xuse-fir-lt=false",
        "-Xreport-output-files",
    )

    private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun workDir(ctx: Context, name: String): File =
        File(ctx.filesDir, name).apply { deleteRecursively(); mkdirs() }

    private companion object {
        const val TAG = "ForkedKotlincSpike"
        const val MB = 1024L * 1024L
        const val COMPILER_MAIN = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

        /** Keeps `PlainTextMessageRenderer`'s static initializer off jansi, which is not bundled. */
        const val COLORS_OFF = "-Dkotlin.colors.enabled=false"

        /**
         * Cheapest proof that a fork booted, built a classloader over the candidate, and resolved a compiler
         * class out of it. The `-no-*` flags keep `-version` from failing on the absent JDK and Kotlin home,
         * so a non-zero exit means the classpath, not the arguments.
         */
        val PROBE_ARGS = listOf("-version", "-no-jdk", "-no-stdlib", "-no-reflect")

        /** Small enough that every device affords it, so a failed probe means the classpath, not the heap. */
        const val PROBE_XMX_MB = 768
        const val COMPILE_XMX_MB = 1536
        val HEAP_LADDER = listOf(768, 1024, 1536, 2048, 3072, 4096)

        /** A cold fork over the app's whole dex has no warm oat to reuse, so both budgets are generous. */
        const val PROBE_TIMEOUT_SEC = 180L
        const val COMPILE_TIMEOUT_SEC = 300L

        const val FORK_RUNS = 3
        const val IN_PROCESS_RUNS = 3
        const val MAX_LOGGED_LINES = 60

        val DEX_ENTRY = Regex("""classes\d*\.dex""")

        val SAMPLE_SOURCE = """
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
        """.trimIndent()
    }
}
