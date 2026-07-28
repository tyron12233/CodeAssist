package dev.ide.ksp.thinspike

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSNode
import dev.ide.ksp.spike.ListClassesProcessorProvider
import java.io.File
import java.util.ServiceLoader

/**
 * A tiny KSP driver, compiled against the KSP2 API but **loaded by the spike's isolated classloader** (whose
 * KSP `impl` classes are the thin ones extracted from `symbol-processing-aa`, backed by OUR bundled Analysis
 * API rather than KSP's own). All KSP references here are static, so once this class is loaded by that
 * classloader every `com.google.devtools.ksp.impl.*` call resolves to the thin classes running on our AA —
 * which is exactly what the spike measures. Only [run] is invoked reflectively across the classloader
 * boundary (its args/return are JDK types, so no KSP type crosses).
 */
object ThinKspRunner {

    /** Runs the trivial in-process processor over [srcDir] into [outBase]. */
    @JvmStatic
    fun run(srcDir: File, outBase: File): String =
        runWith(srcDir, outBase, libraries = emptyList(), providers = listOf(ListClassesProcessorProvider()), options = emptyMap())

    /**
     * Runs whatever processors ServiceLoader finds on THIS class's loader (the spike's isolated loader — so it
     * discovers e.g. Room when Room's jars are on that loader), over [srcDir] with [libraries] on KSP's library
     * classpath. Proves a REAL processor runs on our AA, not just the trivial one.
     */
    @JvmStatic
    fun runServiceLoaded(srcDir: File, outBase: File, libraries: List<File>, options: Map<String, String>): String {
        val providers = ServiceLoader.load(SymbolProcessorProvider::class.java, ThinKspRunner::class.java.classLoader).toList()
        if (providers.isEmpty()) return "NO_PROVIDERS\n(ServiceLoader found no SymbolProcessorProvider on the isolated loader)"
        return runWith(srcDir, outBase, libraries, providers, options)
    }

    private fun runWith(
        srcDir: File,
        outBase: File,
        libraries: List<File>,
        providers: List<SymbolProcessorProvider>,
        options: Map<String, String>,
    ): String {
        val diagnostics = StringBuilder()
        diagnostics.appendLine("providers: ${providers.map { it.javaClass.name }}")
        val logger = object : KSPLogger {
            override fun logging(message: String, symbol: KSNode?) {}
            override fun info(message: String, symbol: KSNode?) { diagnostics.appendLine("INFO: $message") }
            override fun warn(message: String, symbol: KSNode?) { diagnostics.appendLine("WARN: $message") }
            override fun error(message: String, symbol: KSNode?) { diagnostics.appendLine("ERR:  $message") }
            override fun exception(e: Throwable) { diagnostics.appendLine("EXC:  ${e.stackTraceToString()}") }
        }
        val config = KSPJvmConfig.Builder().apply {
            moduleName = "thinspike"
            sourceRoots = listOf(srcDir)
            javaSourceRoots = emptyList()
            this.libraries = libraries
            projectBaseDir = outBase
            outputBaseDir = outBase
            cachesDir = File(outBase, "caches")
            kotlinOutputDir = File(outBase, "kotlin")
            javaOutputDir = File(outBase, "java")
            classOutputDir = File(outBase, "classes")
            resourceOutputDir = File(outBase, "resources")
            languageVersion = "2.4"
            apiVersion = "2.4"
            jvmTarget = "17"
            // The AA needs a JDK home to resolve JDK/mapped types (List -> java.util.List, etc.) so a real
            // processor like Room can resolve method return/param types. On ART this is android.jar instead.
            jdkHome = File(System.getProperty("java.home"))
            processorOptions = options
        }.build()
        val exit = KotlinSymbolProcessing(config, providers, logger).execute()
        return exit.name + "\n" + diagnostics.toString()
    }
}
