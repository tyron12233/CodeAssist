package dev.ide.android.support.tools

import java.nio.file.Path

/**
 * The build-tool ports of the native Android pipeline. Each Android build step talks
 * to one of these interfaces, never to a concrete tool, exactly as the Java pipeline injects a
 * `JavaCompile` port. The desktop/on-device wirings supply subprocess implementations
 * ([Aapt2Subprocess]/[D8Dexer]/[ApkSignerTool]); a test can supply fakes. Calls are blocking; the tasks
 * invoke them on an IO dispatcher.
 */

/** Result of running a tool: success plus the captured (merged stdout/stderr) log lines for the console. */
data class ToolResult(val success: Boolean, val log: List<String> = emptyList()) {
    companion object {
        fun ok(log: List<String> = emptyList()) = ToolResult(true, log)
        fun fail(message: String, log: List<String> = emptyList()) = ToolResult(false, log + message)
    }
}

/**
 * D8 emits a per-method desugaring warning when *library* code uses an API above the build's min-api —
 * e.g. guava's `MethodHandle`/`VarHandle` helpers warn "only supported starting with Android O
 * (--min-api 26)" at min-api < 26. They are benign (guava selects those code paths at runtime only on
 * capable devices) and flood the console, so the dexer suppresses them and surfaces a single count.
 */
internal fun isBenignDexWarning(message: String): Boolean =
    "only supported starting with Android" in message

/** Drop benign desugaring warnings (and their `Warning in …:` headers) from D8's line output, appending a
 *  one-line count when any were suppressed. */
internal fun suppressBenignDexWarnings(lines: List<String>): List<String> {
    val out = ArrayList<String>(lines.size)
    var suppressed = 0
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        // A D8 warning is a "Warning in <file>…:" header followed by the message line; drop both when benign.
        if (line.startsWith("Warning in ") && i + 1 < lines.size && isBenignDexWarning(lines[i + 1])) {
            suppressed++; i += 2; continue
        }
        if (isBenignDexWarning(line)) { suppressed++; i++; continue }
        out.add(line); i++
    }
    if (suppressed > 0) out.add("($suppressed D8 desugaring warning(s) suppressed — library APIs needing a higher min-api; benign)")
    return out
}

/** aapt2: compile resources to an intermediate form, then link them into `resources.ap_` + `R.java`. */
interface Aapt2 {
    /** Compile each res directory into a flat-file archive; returns the produced archives under [outDir]. */
    fun compile(resDirs: List<Path>, outDir: Path): Aapt2CompileResult

    /**
     * Link [compiled] archives with the [manifest] against [androidJar] into [outApk] (`resources.ap_`),
     * emitting `R.java` for [customPackage] — and, for each entry in [extraPackages] (dependency
     * android-lib packages), an additional `R.java` with the same merged IDs — under [genJavaDir].
     */
    fun link(
        compiled: List<Path>,
        manifest: Path,
        androidJar: Path,
        customPackage: String,
        extraPackages: List<String>,
        minSdk: Int,
        targetSdk: Int,
        genJavaDir: Path,
        outApk: Path,
        nonFinalIds: Boolean = false,   // libraries generate a non-final R (IDs not inlined by the compiler)
    ): ToolResult
}

data class Aapt2CompileResult(val archives: List<Path>, val result: ToolResult)

/**
 * D8: turn JVM `.class` files (+ library jars) into Dalvik dex. The build uses D8 in its two AGP roles:
 *  - [dexArchive] — the per-class-file *dex archive* (D8 `OutputMode.DexFilePerClassFile`): every input
 *    class becomes its own `.dex`, so the result is an intermediate archive whose unchanged classes need
 *    not be re-dexed. This is what `dexBuilder` runs over each scope bucket.
 *  - [dex] — the indexed *merge* (D8 `OutputMode.DexIndexed`): combine dex archives (or class jars) into
 *    `classes.dex` (+ `classes2.dex` … past the 64k method limit). D8 is also the dex merger, so feeding
 *    it the `.dex` files of an archive merges them; this is what `mergeProjectDex`/`mergeLibDex`/
 *    `mergeExtDex`/`mergeDex` run. [androidJar] supplies the library (bootclasspath) for desugaring in
 *    both roles — the archive step defers cross-class desugaring as intermediate dex, the merger resolves it.
 */
interface Dexer {
    /**
     * [threads] caps D8's internal worker count for this invocation (0 = D8's default = all cores). The dex
     * pipeline runs many invocations in parallel and sets this so `workers × threads` doesn't oversubscribe
     * cores or multiply peak memory on a phone; see `DexConcurrency`.
     */
    fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int = 0): ToolResult

    /**
     * Archive [inputs] per-class. [classpath] supplies types needed to desugar [inputs] without dexing them
     * (D8 `--classpath`) — used when archiving only a *subset* of a scope's classes incrementally, so a
     * changed class can still see its unchanged siblings; pass empty when archiving a whole jar. [threads] as
     * in [dex].
     */
    fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int = 0): ToolResult
}

/**
 * R8: shrink + optimize + obfuscate and dex every input in one pass (the release path, replacing the
 * dexBuilder→merge pipeline). [keepRules] (e.g. aapt2's manifest-derived rules) decide what survives
 * shrinking; with none, R8 runs pass-through (`-dontshrink`) so the dex is still correct, just not smaller.
 */
fun interface Shrinker {
    /** [threads] caps R8's worker pool (0 = default = all cores). R8 is the heaviest, whole-program step, so
     *  a lower count trades wall-time for a smaller peak working set — the in-process OOM lever on a phone.
     *  (No default: a `fun interface` SAM method can't have one; the sole caller passes it explicitly.) */
    fun shrink(programs: List<Path>, library: Path, keepRules: List<String>, minApi: Int, release: Boolean, outDir: Path, threads: Int): ToolResult
}

/** zipalign + apksigner: produce an aligned, signed APK. */
fun interface ApkSigner {
    fun sign(unsigned: Path, signed: Path, config: SigningConfig): ToolResult
}

/** A keystore + alias/passwords used to sign. A debug build uses [DebugKeystore.getOrCreate]. */
data class SigningConfig(
    val keystore: Path,
    val storePass: String,
    val keyAlias: String,
    val keyPass: String,
)
