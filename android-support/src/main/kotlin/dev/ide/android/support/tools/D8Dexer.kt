package dev.ide.android.support.tools

import java.nio.file.Files
import java.nio.file.Path

/**
 * Dexes JVM `.class` files into Dalvik `classes.dex` with D8. D8 is pure Java, so it
 * runs unchanged on a desktop JVM and on ART; here it is launched as `java -cp d8.jar
 * com.android.tools.r8.D8 …` using the supplied [javaLauncher] (the running JVM by default, so the build
 * needs no `java` on `PATH`). `--lib android.jar` supplies the bootclasspath for core-library desugaring.
 * Inputs may be class directories and/or jars; output is written to [outDir] as `classes.dex`.
 */
class D8Dexer(
    private val d8Jar: Path,
    private val javaLauncher: Path,
) : Dexer {

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
        val cmd = buildList {
            add(javaLauncher.toString());
            add("-cp");
            add(d8Jar.toString());
            add("com.android.tools.r8.D8")
            add(if (release) "--release" else "--debug")
            // Archive mode = one intermediate .dex per input class file (D8 OutputMode.DexFilePerClassFile),
            // the per-class dexing dexBuilder runs; merge resolves the intermediates.
            if (archive) {
                add("--file-per-class-file");
                add("--intermediate")
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
        val r = Subprocess.run(cmd)
        return r.copy(log = suppressBenignDexWarnings(r.log))
    }
}
