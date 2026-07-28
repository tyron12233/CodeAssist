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
    /** Loads [runnerClasspath] + processors: `URLClassLoader` on desktop, `DexClassLoader` (bundled dex) on ART. */
    private val loader: KspProcessorLoader = DefaultKspProcessorLoader,
    /** Per-module KSP processor options (`room.generateKotlin`, `room.schemaLocation`, …). */
    private val processorOptions: (moduleName: String) -> Map<String, String> = { emptyMap() },
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

        val messages = mutableListOf<String>()
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
            return SourceGenResult(false, listOf(m))
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
            processorOptions = this@KspSourceGenerator.processorOptions(request.moduleName)
        }.build()

        return runCatching { runKsp(cl, config, providers, logger) }
            .fold(
                onSuccess = { ok ->
                    if (ok) SourceGenResult(true, messages)
                    else SourceGenResult(false, messages.ifEmpty { listOf("ksp: processing failed for ${request.moduleName}") })
                },
                onFailure = { e ->
                    val m = "ksp: crashed for ${request.moduleName}: ${e.message}"
                    log(m)
                    SourceGenResult(false, messages + m)
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
            runCatching { Class.forName(name, true, cl).getDeclaredConstructor().newInstance() as SymbolProcessorProvider }.getOrNull()
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

    /** KSP rejects a module name with `:` (Kotlin 2.4 default module suffix); KSP 2.3.10 sanitizes internally,
     *  but normalize here too so older runners and cache keys stay clean. */
    private fun sanitizeModuleName(name: String): String = name.replace(':', '_')

    private class CollectingLogger(private val emit: (String) -> Unit) : KSPLogger {
        override fun logging(message: String, symbol: KSNode?) { /* verbose; dropped */ }
        override fun info(message: String, symbol: KSNode?) = emit("ksp: $message")
        override fun warn(message: String, symbol: KSNode?) = emit("ksp warning: $message")
        override fun error(message: String, symbol: KSNode?) = emit("ksp error: $message")
        override fun exception(e: Throwable) = emit("ksp exception: ${e.message}")
    }

    private companion object {
        const val DEFAULT_LANGUAGE_VERSION = "2.4"
        const val DEFAULT_JVM_TARGET = "17"
    }
}
