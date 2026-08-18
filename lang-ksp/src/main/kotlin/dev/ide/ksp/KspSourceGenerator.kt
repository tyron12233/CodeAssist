package dev.ide.ksp

import com.google.devtools.ksp.processing.KSPConfig
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSNode
import dev.ide.build.SourceGenRequest
import dev.ide.build.SourceGenResult
import dev.ide.build.SourceGenerator
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * A [SourceGenerator] that runs KSP2 symbol processors (Room, Moshi, Hilt/Dagger, Glide, …) over a module and
 * emits their generated sources into the module's `ContentRole.GENERATED` root — so the build's
 * `generateSources` task compiles + indexes them like hand-written code, with no compile-task change.
 *
 * The KSP2 runner (`symbol-processing-aa-embeddable`, ~78 MB, carrying its own relocated Analysis API) is NOT
 * a static dependency of this module: [runnerClasspath] plus a module's [processorClasspath] are loaded
 * through [loader] (a `URLClassLoader` on desktop, a `DexClassLoader` over bundled dex on ART), and
 * `com.google.devtools.ksp.impl.KotlinSymbolProcessing` is invoked REFLECTIVELY. Only the thin
 * `symbol-processing-api` + `-common-deps` sit on this module's classpath — enough to build a [KSPJvmConfig],
 * a [KSPLogger], and hold the loaded providers, which cross the classloader boundary as the SAME types via
 * parent-first delegation. See docs/kotlin-compiler-plugins-and-codegen.md and the `ksp2-source-generation`
 * memory note (the runner + processors are bundled with the app, never downloaded — Play DCL policy).
 */
class KspSourceGenerator(
    /** The KSP2 runner classpath. Defaults to the bundled THIN runner ([BundledKspThin]) — KSP's own classes
     *  running on OUR Analysis API — so nothing 78 MB or downloaded is involved. A caller may override (e.g.
     *  the self-contained `-aa-embeddable`) for testing. Sourced from the app (bundled), never Maven. */
    private val runnerClasspath: () -> List<Path> = { listOfNotNull(BundledKspThin.jar()) },
    /** Resolves a module's applicable KSP processor jars from its request (typically a [KspProcessorCatalog]
     *  probing `request.classpath` for a runtime marker — add `room-runtime` and Room turns on). Empty → the
     *  generator no-ops for that module. */
    private val processors: (request: SourceGenRequest) -> List<Path>,
    /**
     * Problems with the module's setup, checked BEFORE any processor runs (typically
     * [KspProcessorCatalog.preflight]: a declared runtime too old for the bundled processor's generated code).
     * [KspProcessorCatalog.Preflight.blocking] fails source generation with exactly those messages, so the
     * console names the real cause instead of the unresolved symbols it produces downstream; `warnings` are
     * reported and generation proceeds (what the user gets after accepting a mismatch). No preflight by default.
     */
    private val preflight: (request: SourceGenRequest) -> KspProcessorCatalog.Preflight =
        { KspProcessorCatalog.Preflight() },
    /** Loads [runnerClasspath] + processors: `URLClassLoader` on desktop, `DexClassLoader` (bundled dex) on ART. */
    private val loader: KspProcessorLoader = DefaultKspProcessorLoader,
    /**
     * KSP processor options (`room.generateKotlin`, Hilt's `disableAndroidSuperclassValidation`, …) for the
     * module being generated. Takes the whole request, not just the module name, because the options that
     * matter are per-PROCESSOR (the ones a library's Gradle plugin would contribute), so the resolver has to
     * see the same classpath/declared-dependency signal that decides which processors run (typically
     * [KspProcessorCatalog.optionsFor]).
     */
    private val processorOptions: (request: SourceGenRequest) -> Map<String, String> = { emptyMap() },
    /** JDK home for the KSP frontend's Java resolution (null on ART; the host JDK on desktop). */
    private val jdkHome: Path? = null,
    private val languageVersion: String = DEFAULT_LANGUAGE_VERSION,
    private val apiVersion: String = DEFAULT_LANGUAGE_VERSION,
    private val jvmTarget: String = DEFAULT_JVM_TARGET,
    /** Sink for the run's diagnostics (also returned in the [SourceGenResult]); defaults to no-op. */
    private val log: (String) -> Unit = {},
) : SourceGenerator {

    override val id: String = "ksp"

    override fun appliesTo(request: SourceGenRequest): Boolean =
        runnerClasspath().isNotEmpty() && processors(request).isNotEmpty()

    override fun generate(request: SourceGenRequest): SourceGenResult {
        val processorJars = processors(request).filter { java.nio.file.Files.exists(it) }
        val runner = runnerClasspath().filter { java.nio.file.Files.exists(it) }
        if (processorJars.isEmpty() || runner.isEmpty()) return SourceGenResult.OK

        // A blocking setup problem (a runtime too old for the bundled processor) is reported here and nowhere
        // else: running anyway emits sources that reference symbols the module's runtime lacks, and the build
        // then fails with one unresolved-import error per generated file, pointing at generated code instead of
        // the version skew. Checked after the applicability gates so an inapplicable processor never complains.
        val checks = preflight(request)
        checks.warnings.forEach(log)
        if (checks.blocking.isNotEmpty()) {
            checks.blocking.forEach(log)
            return SourceGenResult(false, checks.blocking)
        }

        // An accepted mismatch still says so on every build (the user was told the compile will fail and chose
        // to proceed), so `messages` starts with the warnings rather than dropping them.
        val messages = checks.warnings.toMutableList()
        val logger = CollectingLogger { messages += it; log(it) }

        // KSP writes into <generated>/{kotlin,java,resources}. The build wires <generated> as ONE
        // ContentRole.GENERATED source root; the compile tasks walk it recursively, so both the kotlin/ and
        // java/ subtrees are compiled. The caches + KSP's class-output dir live OUTSIDE <generated> so they're
        // never picked up as source or fingerprinted by the up-to-date check.
        val genRoot = request.outputDir.toFile()
        val kotlinOut = File(genRoot, "kotlin")
        val javaOut = File(genRoot, "java")
        val resOut = File(genRoot, "resources")
        val sidecar = request.outputDir.resolveSibling("${request.outputDir.fileName}.ksp").toFile()
        val classOut = File(sidecar, "classes")
        val caches = File(sidecar, "caches")

        val cl = loader.load(runner + processorJars)
        // Read each processor's SymbolProcessorProvider from its jar's META-INF/services descriptor, then load
        // the class through `cl`. NOT `ServiceLoader.load(cl)`: on ART `cl` is a DexClassLoader over dex-only
        // jars (D8 drops non-class resources like META-INF/services), so ServiceLoader would find nothing —
        // read the descriptor from the original jars instead (the same split the kotlinc-plugin loader uses).
        val providers = loadProviders(processorJars, cl)
        if (providers.isEmpty()) {
            val m = "ksp: no SymbolProcessorProvider found on the processor classpath for ${request.moduleName}"
            log(m)
            // Carry `messages` (the accepted-mismatch warnings) rather than replacing it: a second problem must
            // not make the first one disappear from the console.
            return SourceGenResult(false, messages + m)
        }

        val config = KSPJvmConfig.Builder().apply {
            moduleName = sanitizeModuleName(request.moduleName)
            sourceRoots = request.sourceRoots.map { it.toFile() }
            javaSourceRoots = request.sourceRoots.map { it.toFile() }
            libraries = request.classpath.map { it.toFile() }.filter { it.exists() }
            projectBaseDir = (genRoot.parentFile ?: genRoot)
            outputBaseDir = genRoot
            cachesDir = caches
            kotlinOutputDir = kotlinOut
            javaOutputDir = javaOut
            classOutputDir = classOut
            resourceOutputDir = resOut
            languageVersion = this@KspSourceGenerator.languageVersion
            apiVersion = this@KspSourceGenerator.apiVersion
            jvmTarget = this@KspSourceGenerator.jvmTarget
            jdkHome = this@KspSourceGenerator.jdkHome?.toFile()
            processorOptions = this@KspSourceGenerator.processorOptions(request)
        }.build()

        return runCatching { runKsp(cl, config, providers, logger) }
            .fold(
                onSuccess = { ok ->
                    // A logged error must FAIL generateSources even when KSP's exit code came back OK: KSP2's
                    // `execute()` does not reliably turn a processor's `logger.error(...)` into a non-OK ExitCode,
                    // so a run can "succeed" while a processor (Room reporting a schema/DAO/query error) actually
                    // aborted its code generation. Continuing then compiles against incomplete generated sources
                    // and surfaces a confusing downstream error (a missing generated class) instead of the real
                    // cause — so hard-stop here on any reported error, with the error lines as the failure reason.
                    if (ok && logger.errorCount > 0) {
                        val summary = "ksp: ${logger.errorCount} error(s) reported; failing source generation for ${request.moduleName}"
                        messages += summary
                        log(summary)
                    }
                    kspOutcome(ok, logger.errorCount, messages, request.moduleName)
                },
                onFailure = { e ->
                    // KSP is invoked reflectively, so a crash arrives wrapped in InvocationTargetException whose
                    // own message is null ("crashed for app: null"). Unwrap to the real cause and report its type
                    // + message (the type alone is informative when the message is null, e.g. a bare NPE).
                    val root = unwrapReflection(e)
                    val detail = root.message?.takeIf { it.isNotBlank() } ?: "(${root.javaClass.name}; no message)"
                    val m = "ksp: crashed for ${request.moduleName}: $detail"
                    log(m)
                    // Dump the FULL cause chain, ONE line per log() call. Two reasons this is not
                    // `log(root.stackTrace…joinToString("\n"))`: (1) the build console renders a newline-carrying
                    // log() call as a single line, so an embedded multi-line trace never shows; (2) some AA
                    // errors are thrown on a leaf exception that carries NO stack trace of its own — the useful
                    // frames live on an intermediate cause. `traceLines(e)` walks the whole chain (ITE → … →
                    // root), emitting each "Caused by" header and every frame separately, so the throw site
                    // survives both. Capped to keep the console readable.
                    val chain = kspCrashTraceLines(e, MAX_TRACE_LINES)
                    chain.forEach { log("ksp:   $it") }
                    // A bundled processor that needs a native library (Room's SQLite query verifier via
                    // sqlite-jdbc) can't load it on ART — there's no `.so` for Android/aarch64 — so the run dies
                    // with an opaque "No native library found". Say so plainly: it's a device limitation, not a
                    // project error, and the (declared) processor's code generation isn't supported on-device yet.
                    val hint = if (needsUnavailableNativeLibrary(root))
                        "ksp: a bundled processor needs a native library not available on this device; its code generation isn't supported on-device yet"
                    else null
                    hint?.let(log)
                    SourceGenResult(false, messages + m + chain.map { "ksp:   $it" } + listOfNotNull(hint))
                },
            )
    }

    /**
     * Instantiate each processor's `SymbolProcessorProvider`: read the class names from the processor jars'
     * `META-INF/services` descriptor (the jars still have it; a dex loader wouldn't expose it as a resource),
     * then load each class through [cl] (dex on ART / URLClassLoader on desktop). Best-effort per provider.
     */
    private fun loadProviders(processorJars: List<Path>, cl: ClassLoader): List<SymbolProcessorProvider> {
        val serviceEntry = "META-INF/services/${SymbolProcessorProvider::class.java.name}"
        val names = LinkedHashSet<String>()
        for (jar in processorJars.filter { Files.isRegularFile(it) }) {
            runCatching {
                ZipFile(jar.toFile()).use { zf ->
                    zf.getEntry(serviceEntry)?.let { e ->
                        zf.getInputStream(e).bufferedReader().readLines()
                            .map { it.substringBefore('#').trim() }
                            .filter { it.isNotEmpty() }
                            .forEach { names.add(it) }
                    }
                }
            }
        }
        return names.mapNotNull { name ->
            runCatching { Class.forName(name, true, cl).getDeclaredConstructor().newInstance() as SymbolProcessorProvider }
                .onFailure { e ->
                    // Don't drop the reason silently: a provider that fails to load (a missing transitive on the
                    // processor classpath, an ART class-init failure) otherwise surfaces only as the opaque
                    // "no SymbolProcessorProvider found". Report the real cause.
                    val root = unwrapReflection(e)
                    log("ksp: failed to load processor provider '$name': ${root.javaClass.name}: ${root.message}")
                }
                .getOrNull()
        }
    }

    /**
     * Reflectively instantiate + run `KotlinSymbolProcessing` from [cl] — the runner is loaded, never a static
     * dependency (so it is not on the IDE's own classloader and cannot be referenced directly). The config,
     * providers and logger are the shipped `symbol-processing-api`/`-common-deps` types, which the runner
     * resolves to the SAME classes via parent-first delegation. Returns true iff the run's `ExitCode` is `OK`.
     */
    private fun runKsp(
        cl: ClassLoader,
        config: KSPConfig,
        providers: List<SymbolProcessorProvider>,
        logger: KSPLogger,
    ): Boolean {
        val kspClass = cl.loadClass("com.google.devtools.ksp.impl.KotlinSymbolProcessing")
        val ctor = kspClass.getConstructor(KSPConfig::class.java, List::class.java, KSPLogger::class.java)
        val instance = ctor.newInstance(config, providers, logger)
        val exit = kspClass.getMethod("execute").invoke(instance)
        return (exit as? Enum<*>)?.name == "OK"
    }

    /** True when [t] is (or its message reports) a missing/failed native library load — the sqlite-jdbc
     *  "No native library found for os.name=…" that Room's verifier hits on ART, or a plain
     *  `UnsatisfiedLinkError`. Used only to attach a clear on-device-limitation hint to the failure. */
    private fun needsUnavailableNativeLibrary(t: Throwable): Boolean =
        t is UnsatisfiedLinkError ||
            (t.message?.let { "No native library found" in it || "UnsatisfiedLinkError" in it } == true)

    /** Peel reflection wrappers (`InvocationTargetException`/`UndeclaredThrowableException`/
     *  `ExceptionInInitializerError`) — whose own message is null — off [e] to reach the real cause. */
    private fun unwrapReflection(e: Throwable): Throwable {
        var t: Throwable = e
        while (t.cause != null && t.cause !== t &&
            (t is java.lang.reflect.InvocationTargetException ||
                t is java.lang.reflect.UndeclaredThrowableException ||
                t is ExceptionInInitializerError)
        ) {
            t = t.cause!!
        }
        return t
    }

    /** KSP rejects a module name with `:` (Kotlin 2.4 default module suffix); KSP 2.3.10 sanitizes internally,
     *  but normalize here too so older runners and cache keys stay clean. */
    private fun sanitizeModuleName(name: String): String = name.replace(':', '_')

    /** Collects KSP diagnostics AND counts errors: a processor error/exception must fail source generation even
     *  when KSP's exit code is OK (see the [generate] `onSuccess` branch). */
    internal class CollectingLogger(private val emit: (String) -> Unit) : KSPLogger {
        /** Number of `error`/`exception` diagnostics reported by the processors so far. */
        var errorCount = 0
            private set

        override fun logging(message: String, symbol: KSNode?) { /* verbose; dropped */ }
        override fun info(message: String, symbol: KSNode?) = emit("ksp: $message")
        override fun warn(message: String, symbol: KSNode?) = emit("ksp warning: $message")
        override fun error(message: String, symbol: KSNode?) { errorCount++; emit("ksp error: $message") }
        override fun exception(e: Throwable) { errorCount++; emit("ksp exception: ${e.message}") }
    }

    private companion object {
        const val DEFAULT_LANGUAGE_VERSION = "2.4"
        const val DEFAULT_JVM_TARGET = "17"
        /** Cap on emitted crash-trace lines (chain headers + frames), to keep the build console readable. */
        const val MAX_TRACE_LINES = 60
    }
}

/**
 * Decide a KSP run's [SourceGenResult] from its reflective exit ([exitOk]) and the number of errors its
 * processors logged ([errorCount]). The run SUCCEEDS only when KSP exited OK AND no error was reported —
 * KSP2 can return OK despite a processor logging an error, and a logged error means the generated sources are
 * incomplete, so it must fail the build. On failure the collected [messages] (which already carry the
 * `ksp error:` lines) are the reason; an empty message list falls back to a generic note for [moduleName].
 */
internal fun kspOutcome(exitOk: Boolean, errorCount: Int, messages: List<String>, moduleName: String): SourceGenResult =
    if (exitOk && errorCount == 0) SourceGenResult(true, messages)
    else SourceGenResult(false, messages.ifEmpty { listOf("ksp: processing failed for $moduleName") })

/**
 * Render [e] and its full cause chain to individual lines — a "Caused by" header per level, then each stack
 * frame as its own line — capped at [max] lines overall and [perLevel] frames per level. Three reasons for this
 * shape:
 *  - **One line per entry**: the build console shows only the first line of a newline-carrying `log()` call, so
 *    an embedded multi-line trace never surfaces; each frame must be its own line.
 *  - **Full chain**: some Analysis-API errors are thrown on a leaf exception that carries NO stack trace of its
 *    own — the useful frames then live on an intermediate cause, so we must walk the whole chain, not just the
 *    leaf (what the old diagnostic did).
 *  - **Per-level frame cap**: the outermost wrapper (a reflective `InvocationTargetException`) has a long,
 *    useless reflection/build trace that would otherwise exhaust [max] before reaching the real cause.
 * Cycle-guarded by identity; never exceeds [max] lines.
 */
internal fun kspCrashTraceLines(e: Throwable, max: Int, perLevel: Int = 20): List<String> {
    val out = ArrayList<String>()
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var t: Throwable? = e
    var first = true
    while (t != null && seen.add(t) && out.size < max) {
        out += (if (first) "" else "Caused by: ") + "${t.javaClass.name}: ${t.message}"
        first = false
        val frames = t.stackTrace
        if (frames.isEmpty()) {
            if (out.size < max) out += "    <no stack trace on this exception>"
        } else {
            for ((i, f) in frames.withIndex()) {
                if (out.size >= max || i >= perLevel) break
                out += "    at $f"
            }
        }
        t = t.cause
    }
    return out
}
