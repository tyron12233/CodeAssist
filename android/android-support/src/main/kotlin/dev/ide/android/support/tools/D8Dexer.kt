package dev.ide.android.support.tools

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Dexes JVM `.class` files into Dalvik `classes.dex` with D8. D8 is pure Java, so it
 * runs unchanged on a desktop JVM and on ART; here it is launched as `<launcher> [vmArgs] -cp <tool cp>
 * com.android.tools.r8.D8 …`. `--lib android.jar` supplies the bootclasspath for core-library desugaring.
 * Inputs may be class directories and/or jars; output is written to [outDir] as `classes.dex`.
 *
 * Two wirings: **desktop** = `launcher=java`, `toolClasspath=[d8.jar]`, no [vmArgs]; **on-device forked
 * merge** = `launcher=dalvikvm64`, `toolClasspath=`R8's dexes, `vmArgs=["-Xmx<n>m"]` so the dex merge gets a
 * heap above the app cap (the merge is the debug-path memory peak). See ForkedD8Dexer in :ide-android.
 */
class D8Dexer(
    private val toolClasspath: List<Path>,
    private val javaLauncher: Path,
    private val vmArgs: List<String> = emptyList(),
    /**
     * Whether this D8 understands the global-synthetics options (see [DexGlobalSynthetics]). Null probes
     * [toolClasspath] for its R8 version, which is what the desktop wiring wants: `d8.jar` comes from whichever
     * build-tools the machine has. The on-device wiring passes `true` instead, its tool classpath being dex
     * extracted from the bundled R8 rather than a jar carrying a version marker.
     */
    supportsGlobalSynthetics: Boolean? = null,
    /**
     * D8 itself, when it is not a program to launch: given the file holding its arguments (one per line) and a
     * file to write what it reports to, it answers D8's exit code. See [D8Native].
     */
    private val tool: ((argFile: Path, log: Path) -> Int)? = null,
) : Dexer {
    // A linked-in D8 dexes one library at a time, as the in-process dexer does: each is then taken from, and
    // kept in, the caches that are keyed by library.
    override fun runsOffHeap(): Boolean = tool == null

    private val toolCp: String get() = toolClasspath.joinToString(File.pathSeparator) { it.toString() }

    private val globalSynthetics: Boolean by lazy {
        supportsGlobalSynthetics ?: DexGlobalSynthetics.supportedBy(toolClasspath)
    }

    override fun dex(
        inputs: List<Path>,
        androidJar: Path,
        minApi: Int,
        release: Boolean,
        outDir: Path,
        threads: Int,
        desugaredLibConfig: Path?
    ): ToolResult = run(
        inputs, emptyList(), androidJar, minApi, release, outDir, archive = false, threads = threads, desugaredLibConfig = desugaredLibConfig
    )

    override fun dexArchive(
        inputs: List<Path>,
        classpath: List<Path>,
        androidJar: Path,
        minApi: Int,
        release: Boolean,
        outDir: Path,
        threads: Int,
        desugaredLibConfig: Path?
    ): ToolResult = run(
        inputs, classpath, androidJar, minApi, release, outDir, archive = true, threads = threads, desugaredLibConfig = desugaredLibConfig
    )

    private fun run(
        inputs: List<Path>,
        classpath: List<Path>,
        androidJar: Path,
        minApi: Int,
        release: Boolean,
        outDir: Path,
        archive: Boolean,
        threads: Int,
        desugaredLibConfig: Path?
    ): ToolResult {
        Files.createDirectories(outDir)
        val existing = inputs.filter { Files.exists(it) }
        if (existing.isEmpty()) return ToolResult.fail("no class inputs to dex")
        // Everything D8 itself parses (NOT the VM's `-cp`/vmArgs) goes through an argument file ([ToolArgFile]):
        // the merge can have hundreds of input dex archives (+ a long desugaring classpath), and passing them
        // inline overflows the OS argv limit when launching the forked VM ("error=7, Argument list too long").
        val d8Args = buildList {
            add(if (release) "--release" else "--debug")
            // Archive mode = one intermediate .dex per input class file (D8 OutputMode.DexFilePerClassFile),
            // the per-class dexing dexBuilder runs; merge resolves the intermediates.
            if (archive) {
                add("--file-per-class-file");
                add("--intermediate")
                // Somewhere to park the desugaring helpers that belong to the whole program rather than to one
                // input class (a Java record's stand-in for java.lang.Record below API 34): D8 writes them as
                // `<class>.globals` beside that class's `.dex`, and the merge below takes them back. Without it
                // D8 fails the build on such a class. See [DexGlobalSynthetics].
                if (globalSynthetics) { add("--globals-output"); add(outDir.toString()) }
            } else if (globalSynthetics) {
                // Merging intermediates: return the global synthetics the archive step parked next to them.
                DexGlobalSynthetics.accompanying(existing).forEach { add("--globals"); add(it.toString()) }
            }
            // Desugaring classpath for an incremental subset (types not (re-)dexed but needed to desugar).
            classpath.filter { Files.exists(it) }.forEach {
                add("--classpath");
                add(it.toString())
            }
            add("--min-api");
            add(minApi.toString())
            if (threads > 0) {
                add("--thread-count"); add(threads.toString())
            }
            add("--lib"); add(androidJar.toString())
            // Core-library desugaring config (rewrite java.* backports; the L8 step dexes the runtime).
            if (desugaredLibConfig != null && Files.exists(desugaredLibConfig)) {
                add("--desugared-lib"); add(desugaredLibConfig.toString())
            }
            add("--output"); add(outDir.toString())
            addAll(existing.map { it.toString() })
        }
        if (tool != null) {
            val argFile = ToolArgFile.write(outDir, d8Args)
            val log = Files.createTempFile(outDir, "d8", ".log")
            return try {
                val code = tool.invoke(argFile, log)
                val lines = runCatching { Files.readAllLines(log) }.getOrDefault(emptyList())
                ToolResult(code == 0, DexDiagnostics.humanize(if (code == 0) lines else lines + "D8 exited with code $code"))
            } catch (t: Throwable) {
                ToolResult(false, listOf("D8 could not be called: $t"), processFailed = true)
            } finally {
                runCatching { Files.deleteIfExists(argFile) }
                runCatching { Files.deleteIfExists(log) }
            }
        }
        return ToolArgFile.withArgFile(outDir, d8Args) { argFile ->
            val cmd = listOf(javaLauncher.toString()) + vmArgs + listOf("-cp", toolCp, "com.android.tools.r8.D8", argFile)
            val r = Subprocess.run(cmd)
            // A failed forked D8 dumps the process stderr (a Java stack trace); humanize it to the actual cause.
            r.copy(log = DexDiagnostics.humanize(r.log))
        }
    }
}

/**
 * D8 compiled into the host, for a host whose JVM only interprets.
 *
 * D8 is Java, and the JVM an iOS app embeds runs it a few dozen times slower than a compiling one: a library
 * took most of a second to dex, and an app's libraries most of a minute. Such a host carries D8 compiled ahead
 * of time and sets [runner] to it; the build then drives it with the same arguments it gives a launched D8.
 */
object D8Native {
    /** D8 with the arguments in a file (one per line), what it reports written to a second file; its exit code. */
    @Volatile
    var runner: ((argFile: Path, log: Path) -> Int)? = null

    /** A dexer over [runner], or null when the host has none. */
    fun dexer(): Dexer? = runner?.let { D8Dexer(emptyList(), java.nio.file.Paths.get("d8"), supportsGlobalSynthetics = true, tool = it) }
}
