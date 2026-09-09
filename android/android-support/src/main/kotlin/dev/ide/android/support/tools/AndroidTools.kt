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

/**
 * Make D8/R8 output readable for the build console + Problems list. The raw output of a failed dex/merge/shrink
 * — especially from the forked VM, which dumps the process's stderr — is a wall of Java stack frames and
 * obfuscated R8 internal symbols around a one-line cause. [humanize]:
 *  - suppresses the benign desugaring warnings ([suppressBenignDexWarnings]);
 *  - drops the stack-trace noise (`at …`, `… N more`, `Exception in thread …`, the bare `Error in <path>:`
 *    headers);
 *  - shortens the internal per-class dex-archive paths (`…/ext/<hash>/kotlin/ArrayIntrinsicsKt.dex`) to the
 *    class name (`kotlin.ArrayIntrinsicsKt`);
 *  - rewrites the failure signatures it recognizes into a single `error: <plain explanation + fix>` line (so
 *    the existing [CompilerOutputParser] surfaces ONE actionable Problem), collapsing the duplicate copies D8
 *    prints (the diagnostic, then the thrown exception's cause).
 *
 * Applied to every dexer/shrinker's [ToolResult.log], so the console, the Problems list, and a task's failure
 * summary ([firstError]) all read cleanly without each call site re-parsing.
 */
internal object DexDiagnostics {
    private val STACK_AT = Regex("""^at\s+\S.*""")
    private val MORE = Regex("""^\.\.\.\s+\d+\s+more$""")
    private val ERROR_IN_HEADER = Regex("""^Error in\s+.*:$""")
    // An internal per-class dex-archive path: <…>/<24-hex bucket>/<class path>.dex
    private val DEX_BUCKET_PATH = Regex("""\S*/[0-9a-f]{24}/(\S+?)\.dex""")
    private val DUP_TYPE = Regex("""Type (\S+) is defined multiple times""")
    private val ALREADY_PRESENT = Regex("""(?:Program|Classpath) type already present:\s*([^\s,]+)""")
    private val UNSUPPORTED_VERSION = Regex("""[Uu]nsupported class file (?:major )?version|bytecode (?:that is )?newer""")
    private const val TOO_MANY = "Cannot fit requested classes in a single dex file"

    fun humanize(log: List<String>): List<String> {
        val base = suppressBenignDexWarnings(log)
        val out = ArrayList<String>(base.size)
        val seen = HashSet<String>()   // collapse the same recognized error echoed by diagnostic + exception cause
        for (raw in base) {
            val t = raw.trim()
            if (STACK_AT.matches(t) || MORE.matches(t) || t.startsWith("Exception in thread ") || ERROR_IN_HEADER.matches(t)) continue
            val friendly = recognize(raw)
            if (friendly != null) {
                if (seen.add(friendly)) out.add(friendly)
                continue
            }
            out.add(shortenPaths(raw))
        }
        return out
    }

    /** The first recognized error message in [log] (already-humanized), for a task's `TaskResult.Failed`
     *  summary; null when nothing error-like is present (caller keeps its generic message). */
    fun firstError(log: List<String>): String? =
        log.firstOrNull { it.startsWith("error:", ignoreCase = true) }?.substringAfter(':')?.trim()

    private fun recognize(line: String): String? {
        DUP_TYPE.find(line)?.let { return duplicate(it.groupValues[1]) }
        ALREADY_PRESENT.find(line)?.let { return duplicate(it.groupValues[1]) }
        if (line.contains(TOO_MANY))
            return "error: Too many methods or fields to fit in a single dex file (the 64K limit). Enable multidex (automatic at minSdk 21+) or remove unused dependencies."
        if (line.contains("OutOfMemoryError"))
            return "error: The dexer ran out of memory. Raise the \"R8 forked-VM heap\" setting in Build Runtime settings, or reduce the build's dependencies."
        if (UNSUPPORTED_VERSION.containsMatchIn(line))
            return "error: A class was compiled for a newer Java bytecode version than the dexer supports. Lower the module's language level to Java 17 or below."
        return null
    }

    private fun duplicate(type: String): String =
        "error: Duplicate class '${asClassName(type)}' — more than one library on the classpath defines it (often a shaded or duplicated dependency). Remove or exclude one copy so a single version remains."

    /** Normalize a class reference (a `path/Foo.dex`, a `La/B;` descriptor, or a dotted name) to a dotted FQN. */
    private fun asClassName(raw: String): String =
        raw.trim().trimEnd(',', ':').removeSuffix(".dex").removeSuffix(".class")
            .removePrefix("L").removeSuffix(";").replace('/', '.')

    /** Replace internal per-class dex-archive paths with the class name, so a retained line reads in user terms. */
    private fun shortenPaths(line: String): String =
        DEX_BUCKET_PATH.replace(line) { it.groupValues[1].replace('/', '.') }
}

/** aapt2: compile resources to an intermediate form, then link them into `resources.ap_` + `R.java`. */
interface Aapt2 {
    /** Compile each res directory into a flat-file archive; returns the produced archives under [outDir]. */
    fun compile(resDirs: List<Path>, outDir: Path): Aapt2CompileResult

    /**
     * Link [compiled] archives with the [manifest] against [androidJar] into [outApk] (`resources.ap_`),
     * emitting `R.java` for [customPackage] — and, for each entry in [extraPackages] (dependency
     * android-lib packages), an additional `R.java` with the same merged IDs — under [genJavaDir].
     *
     * [overlays] are linked as aapt2 `-R` overlays (on top of [compiled]): each overrides a resource the base
     * also defines and, with `--auto-add-overlay`, may add new ones — used by the layout preview to relink the
     * live editor buffer over the build's compiled resources without recompiling the whole project.
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
        versionCode: Int? = null,       // injected into the manifest when it declares none (AGP defaultConfig)
        versionName: String? = null,
        nonFinalIds: Boolean = false,   // libraries generate a non-final R (IDs not inlined by the compiler)
        proguardRules: Path? = null,    // aapt2 `--proguard`: keep rules for manifest/layout-referenced classes (for R8)
        protoFormat: Boolean = false,   // aapt2 `--proto-format`: emit proto resources (R8 resource shrinking input)
        overlays: List<Path> = emptyList(), // aapt2 `-R` overlays applied on top of [compiled]
        rTxt: Path? = null,             // aapt2 `--output-text-symbols`: the R symbol table (`R.txt`) for an AAR
    ): ToolResult

    /**
     * Convert a linked resources archive between aapt2's binary and proto formats (`aapt2 convert`).
     * R8's integrated resource shrinking reads/writes proto resources, so a `shrinkResources` build
     * links to proto, shrinks, then converts the result back to binary for packaging.
     */
    fun convert(input: Path, output: Path, toProto: Boolean): ToolResult =
        ToolResult.fail("aapt2 convert not supported by this implementation")
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
    fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int = 0, desugaredLibConfig: Path? = null): ToolResult

    /**
     * Archive [inputs] per-class. [classpath] supplies types needed to desugar [inputs] without dexing them
     * (D8 `--classpath`) — used when archiving only a *subset* of a scope's classes incrementally, so a
     * changed class can still see its unchanged siblings; pass empty when archiving a whole jar. [threads] as
     * in [dex]. [desugaredLibConfig] (when set) is the core-library-desugaring config (`desugar.json`): D8
     * rewrites `java.*` backport call sites per it (the L8 step then dexes the runtime).
     */
    fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int = 0, desugaredLibConfig: Path? = null): ToolResult

    /**
     * How a dex *merge* of [inputCount] independent inputs (per-library buckets or chunks) should be split into
     * [dex] invocations and run. Returning null means "plan it for an in-process dexer" — the merge task then
     * bounds concurrency by the *app* heap (`DexConcurrency`), one group per input.
     *
     * A dexer that runs the merge in a FORKED VM overrides this: a fork has its own large `-Xmx` and costs ~0
     * app heap, so the app-heap bound is the wrong constraint. It instead batches many libraries per forked VM
     * (so N per-library merges collapse to a handful of forked invocations) and reports how many forks the
     * *device's available RAM* allows to run at once — see [MergePlan].
     */
    fun mergePlan(inputCount: Int): MergePlan? = null

    /**
     * Whether this dexer runs a whole-classpath [dex] pass OFF the app heap — a forked VM or a real-JVM
     * subprocess, each with its own large `-Xmx`. When true (the default, and correct for a desktop subprocess
     * D8), one monolithic pass over a big library classpath is cheap. When false — an in-process dexer bounded
     * by ART's ~576MB app heap, e.g. a forked dexer that fell back to in-process on a low-memory device — a
     * single giant pass is GC-bound (measured in the hundreds of seconds on a small emulator) and can be killed
     * by the low-memory killer before it finishes or caches. The Android build consults this to decide between
     * the fast one-pass external dexing (off-heap) and bounded per-library archiving (in-process): the latter
     * caps each D8 invocation's working set and banks each library to the shared cache as it completes, so a
     * killed build keeps its progress.
     */
    fun runsOffHeap(): Boolean = true
}

/**
 * A plan for a dex *merge* over a set of independent inputs (see [Dexer.mergePlan]). The merge task coalesces
 * the inputs into at most [maxInvocations] groups (each its own `classes*.dex` group), and runs [concurrency]
 * of them at a time with [threadsPerInvocation] D8 threads each.
 */
data class MergePlan(
    /** Coalesce the independent inputs into at most this many [Dexer.dex] invocations. A forking dexer sets
     *  this low (batch many libraries per forked VM); an unbounded value keeps one group per input. */
    val maxInvocations: Int,
    /** How many invocations to run concurrently. */
    val concurrency: Int,
    /** D8 `--thread-count` per invocation (so `concurrency × threads` doesn't oversubscribe cores). */
    val threadsPerInvocation: Int,
)

/**
 * Optional capability of a [Dexer]: run the per-jar library *archive* OFF the app heap (in a forked VM), so
 * `dexBuilder` can archive several cold libraries at once instead of one-at-a-time in-process. The in-process
 * dexer does not implement it — its archives hold `android.jar` in the app heap, so concurrency must stay
 * bounded by `DexConcurrency`; a forked archive runs off that heap and is bounded by device RAM instead.
 *
 * Each jar is archived into its own per-class bucket (so the content-hash incremental reuse `dexBuilder`
 * depends on is preserved — no batching, unlike the merge). Only the library scope routes through this; the
 * project scope keeps the size-thresholded [Dexer.dexArchive] (a small incremental edit must not fork).
 */
interface OffHeapArchiveDexer {
    /** Plan for archiving [jarCount] library jars off-heap. `concurrency` (forks at once) and
     *  `threadsPerInvocation` are used; `maxInvocations` is not (each jar keeps its own bucket). Concurrency
     *  is 1 when forking is unavailable here, so the caller falls back to serial in-process archiving. */
    fun offHeapArchivePlan(jarCount: Int): MergePlan

    /** Archive [jar] into [outDir] in a forked VM (one jar → its own per-class bucket), with [classpath] as the
     *  desugaring classpath; falls back to in-process when forking isn't available. */
    fun dexArchiveOffHeap(
        jar: Path, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path,
        threads: Int, desugaredLibConfig: Path?,
    ): ToolResult
}

/** Pass-through R8 config: no shrink/optimize/obfuscate, so R8 behaves as a plain dexer. Used as a fallback
 *  when a minify build supplies no keep rules at all (an over-shrunk APK would crash; a correct dex is safe). */
internal val R8_PASS_THROUGH = listOf("-dontshrink", "-dontoptimize", "-dontobfuscate", "-ignorewarnings")

/**
 * Make R8 tolerate classes referenced but absent from the program + classpath. By default R8 *fails* the
 * build on them (`Error: Missing class …`), then AGP writes a `missing_rules.txt` and asks the user to add a
 * `-dontwarn` for each. Such references are routine in real apps — e.g. Play Services' `DeviceProperties`
 * carries `@com.google.android.apps.common.proguard.SideEffectFree`, an annotation Google never publishes as a
 * class. A tolerant on-device IDE keeps building instead (it can't sensibly make a phone user hand-edit
 * ProGuard files): `-ignorewarnings` downgrades the missing-class *error* to a *warning* that is still printed,
 * so the references surface as Problems in the build console while the APK is produced. (Already part of
 * [R8_PASS_THROUGH] for the no-rules path; applied to the minify path too — see the shrinkers.) */
internal const val R8_IGNORE_WARNINGS = "-ignorewarnings"

/**
 * Integrated resource shrinking: R8 reads the linked, proto-format resources from [inputAp], drops the
 * entries unreachable from the shrunken code, and writes the shrunk proto-format resources to [outputAp]
 * during the same run. The build then converts [outputAp] back to binary for packaging (see [Aapt2.convert]).
 */
data class ResourceShrink(val inputAp: Path, val outputAp: Path)

/**
 * Core-library desugaring inputs (AGP's `coreLibraryDesugaringEnabled`). [configJson] is the desugared
 * library configuration (`desugar.json`); R8 rewrites `java.*` backport call sites per it and writes the
 * keep rules the runtime library needs to [keepRulesOutput], which the separate L8 step ([Shrinker.l8])
 * consumes to dex the runtime into the APK.
 */
data class DesugaredLibrary(val configJson: Path, val keepRulesOutput: Path)

/**
 * A whole-program R8 run: shrink + optimize + obfuscate and dex [programs] in one pass (the release path,
 * replacing the dexBuilder->merge pipeline). [keepRuleFiles] (aapt2 manifest rules + the build type's
 * proguardFiles + AAR consumer rules) and [inlineRules] (the build type's raw `proguardRules`) decide what
 * survives shrinking; with neither, R8 runs pass-through ([R8_PASS_THROUGH]) so the dex is still correct.
 */
data class ShrinkRequest(
    val programs: List<Path>,
    /** android.jar (the bootclasspath); kept out of the output. */
    val library: Path,
    /** Types needed to resolve [programs] but not shrunk/emitted (e.g. desugar stubs). */
    val classpath: List<Path> = emptyList(),
    val keepRuleFiles: List<Path> = emptyList(),
    val inlineRules: List<String> = emptyList(),
    val minApi: Int,
    val release: Boolean = true,
    /** Full mode (true) vs ProGuard-compatibility mode (false); see [AndroidFacet.r8FullMode]. */
    val fullMode: Boolean = true,
    val outDir: Path,
    /** When set, R8 writes the obfuscation mapping (`mapping.txt`) here for stack-trace de-obfuscation. */
    val mappingOutput: Path? = null,
    /** When set, R8 shrinks resources too (Phase: resource shrinking). */
    val resources: ResourceShrink? = null,
    /** When set, R8 applies core-library desugaring and emits the runtime keep rules for L8. */
    val desugaredLibrary: DesugaredLibrary? = null,
    /** [threads] caps R8's worker pool (0 = all cores). R8 is the heaviest step, so fewer threads trades
     *  wall-time for a smaller peak working set: the in-process OOM lever on a phone. */
    val threads: Int = 0,
)

/** A request to L8 (the desugared-library compiler): dex the core-library runtime for packaging. */
data class L8Request(
    /** The desugar runtime jar (`desugar_jdk_libs.jar`). */
    val desugarJdkLibs: Path,
    val configJson: Path,
    /** The keep rules R8 emitted for the runtime ([DesugaredLibrary.keepRulesOutput]). */
    val keepRules: Path,
    val library: Path,            // android.jar
    val minApi: Int,
    val release: Boolean,
    val outDir: Path,
)

/** R8 (shrink/optimize/obfuscate/dex) plus its L8 sidekick (core-library-desugaring runtime). */
interface Shrinker {
    fun shrink(request: ShrinkRequest): ToolResult

    /** Compile the core-library-desugaring runtime to dex (L8), packaged alongside the R8/D8 output. */
    fun l8(request: L8Request): ToolResult =
        ToolResult.fail("core-library desugaring (L8) not supported by this implementation")
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
