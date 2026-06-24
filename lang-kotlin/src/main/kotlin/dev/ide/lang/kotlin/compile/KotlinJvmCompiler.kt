package dev.ide.lang.kotlin.compile

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.OutputMessageUtil
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.nio.file.Path

/**
 * Kotlin to `.class` codegen for the build. A thin wrapper over the embeddable K2 `K2JVMCompiler`, run
 * in-process: the same compiler the editor parse-host loads, and one that runs on ART. The build's
 * `compileKotlin` task ([dev.ide.lang.kotlin.build.KotlinCompileTask], via [IncrementalKotlinCompiler])
 * drives it directly; `:build-engine` names no Kotlin compiler.
 *
 * Java interop: kotlinc is handed the module's `.java` sources alongside its `.kt`: it parses the Java for
 * symbol resolution (so Kotlin can reference same-module Java types) but emits `.class` only for Kotlin.
 * The Java compiler then closes the loop by putting this compiler's output dir on its classpath.
 *
 * Platform library: desktop has a real JDK, so [K2JVMCompilerArguments.jdkHome] points at it. ART has none
 * and its platform is `android.jar`, so the caller passes that in [bootClasspath]; this then sets `-no-jdk`
 * and folds it into the classpath. The Kotlin stdlib is never auto-discovered (which needs a `kotlin-home`
 * that may be absent in-process); it normally arrives on [classpath] as the module's `kotlin-stdlib` library
 * dependency, and the bundled stdlib jar ([BundledKotlinStdlib]) is also folded in as a fallback so a
 * compile still has a stdlib even when no such dependency is declared. Never the host runtime's jar (absent
 * on ART).
 */
class KotlinJvmCompiler {

    /**
     * [outputs] maps each compiled source file to the `.class` files it produced (populated via
     * `-Xreport-output-files`; empty when the compile threw before reporting). [IncrementalKotlinCompiler]
     * uses it to know which outputs a changed source owns, so a class a source no longer produces can be
     * detected and pruned, and per-class ABI diffed.
     */
    data class Result(
        val success: Boolean,
        val messages: List<String>,
        val outputs: Map<Path, List<Path>> = emptyMap(),
    )

    /** True on Android's runtime (ART/Dalvik), where there is no host JDK for the compiler to borrow. */
    private val isAndroidRuntime: Boolean =
        System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true) ||
            System.getProperty("java.vendor").orEmpty().contains("Android", ignoreCase = true)

    /**
     * Compile [kotlinSources] (resolving [javaSources] for interop) into [outputDir].
     *
     * [friendPaths] grants same-module `internal` visibility across the binary boundary: when
     * [IncrementalKotlinCompiler] recompiles only the changed `.kt` files, the unchanged ones arrive as
     * already-compiled `.class` on [classpath]; pointing `-Xfriend-paths` at that output dir lets a changed
     * file still see its `internal` siblings. (Empty for a whole-module compile, where every file is source.)
     */
    /**
     * [compilerPlugins] are kotlinc compiler-plugin jars (each carrying a `META-INF/services`
     * `CompilerPluginRegistrar`) and [pluginOptions] their `plugin:<id>:<key>=<value>` strings — fed to
     * `-Xplugin`/`-P`. This is a generic capability; the Compose compiler plugin is the first consumer (the
     * host decides per-module whether to apply it — see `ComposeCompilerPlugin`). On ART the plugin's
     * registrar must also be dexed into the app: kotlinc reads the service *descriptor* from the jar but
     * resolves the registrar *class* through parent delegation to the app classloader (a jar's `.class`
     * bytes can't be defined at runtime on ART), exactly as the bundled compiler's own classes are.
     */
    fun compile(
        kotlinSources: List<Path>,
        javaSources: List<Path>,
        classpath: List<Path>,
        outputDir: Path,
        jvmTarget: String = "17",
        bootClasspath: List<Path> = emptyList(),
        friendPaths: List<Path> = emptyList(),
        compilerPlugins: List<Path> = emptyList(),
        pluginOptions: List<String> = emptyList(),
    ): Result {
        if (kotlinSources.isEmpty()) return Result(true, emptyList())
        // Keep the compiler's application environment (and its warm jar FS) alive across builds. Must be set
        // before the first KotlinCoreEnvironment is created, so do it before exec touches one.
        KotlinEnvironmentKeepAlive.ensure()
        runCatching { java.nio.file.Files.createDirectories(outputDir) }

        // The platform library: an explicit boot library when given (android.jar on ART); otherwise on ART
        // fall back to whatever the classpath carries. On desktop it stays empty (jdkHome points at the JDK).
        val boot = bootClasspath.ifEmpty { if (isAndroidRuntime) classpath else emptyList() }
        val onArt = isAndroidRuntime || bootClasspath.isNotEmpty()

        // kotlinc resolves Java sources for interop but only emits Kotlin classes; both are free args.
        val sourcePaths = (kotlinSources + javaSources).map { it.toString() }
        val fullClasspath = (classpath + boot + listOfNotNull(stdlibJar())).distinct()

        val args = K2JVMCompilerArguments().apply {
            freeArgs = sourcePaths
            destination = outputDir.toString()
            if (fullClasspath.isNotEmpty()) this.classpath = fullClasspath.joinToString(File.pathSeparator) { it.toString() }
            this.jvmTarget = jvmTargetOf(jvmTarget)
            noStdlib = true     // supplied explicitly above (auto-discovery needs an on-disk kotlin-home)
            noReflect = true
            reportOutputFiles = true   // emit OUTPUT messages → source→.class mapping recovered below
            if (friendPaths.isNotEmpty()) this.friendPaths = friendPaths.map { it.toString() }.toTypedArray()
            // Compiler plugins (e.g. Compose). pluginClasspaths is the `-Xplugin` set; pluginOptions the `-P`
            // `plugin:<id>:<k>=<v>` strings. The plugin jars must exist on disk for kotlinc to read their
            // service descriptors; the host supplies them (bundled assets on ART, resolved jars on desktop).
            val plugins = compilerPlugins.filter { java.nio.file.Files.isRegularFile(it) }
            if (plugins.isNotEmpty()) this.pluginClasspaths = plugins.map { it.toString() }.toTypedArray()
            if (pluginOptions.isNotEmpty()) this.pluginOptions = pluginOptions.toTypedArray()
            if (onArt) {
                noJdk = true    // ART has no JDK; the platform is android.jar, folded into the classpath above
            } else {
                jdkHome = System.getProperty("java.home")
            }
        }

        val collector = RecordingMessageCollector()
        val exit = runCatching { K2JVMCompiler().exec(collector, Services.EMPTY, args) }
            .getOrElse {
                return Result(false, collector.messages + "kotlinc threw: ${it.javaClass.name}: ${it.message}")
            }
        return Result(exit == ExitCode.OK && !collector.hasErrors(), collector.messages, collector.outputs())
    }

    /**
     * Pay the compiler's one-time cold-start cost — class-loading the embeddable compiler and standing up its
     * application environment — NOW, off the interaction path, so the user's first real build compile is warm.
     * On ART the first in-process Kotlin compile measures ~1s (see `KotlinCompilerArtSpikeTest`) versus ~135ms
     * warm; this front-loads that ~1s, and [KotlinEnvironmentKeepAlive] keeps the environment hot for every
     * later compile. A single throwaway compile of a trivial source is what loads the frontend + JVM-backend
     * classes that the editor's parse-only host never touches.
     *
     * Idempotent and best-effort: any failure is swallowed (this is an optimization, not a build step) and not
     * retried. [bootClasspath] should be the platform library the real build uses (android.jar on ART, empty on
     * desktop) so the warm-up exercises the same `-no-jdk`/boot path. Call from a background thread at project open.
     */
    fun warmUp(bootClasspath: List<Path> = emptyList()) {
        if (warmedUp) return
        synchronized(warmLock) {
            if (warmedUp) return
            warmedUp = true   // set first: a failed warm-up must not re-fire its cost on every project open
            runCatching {
                val dir = java.nio.file.Files.createTempDirectory("kotlinc-warmup")
                try {
                    val src = dir.resolve("Warmup.kt")
                    java.nio.file.Files.write(src, "package warmup\nfun warmup() {}\n".toByteArray(Charsets.UTF_8))
                    val out = java.nio.file.Files.createDirectories(dir.resolve("out"))
                    compile(listOf(src), emptyList(), emptyList(), out, bootClasspath = bootClasspath)
                } finally {
                    runCatching { dir.toFile().deleteRecursively() }
                }
            }
        }
    }

    @Volatile private var warmedUp = false
    private val warmLock = Any()

    /** The bundled kotlin-stdlib jar, passed to kotlinc explicitly (host-independent; see [BundledKotlinStdlib]). */
    private fun stdlibJar(): Path? = BundledKotlinStdlib.jar()

    private fun jvmTargetOf(level: String): String = when (level) {
        "8" -> "1.8"
        else -> level
    }

    private class RecordingMessageCollector : MessageCollector {
        val messages = ArrayList<String>()
        private val sourceToOut = LinkedHashMap<Path, MutableList<Path>>()
        private var errors = false
        override fun clear() { messages.clear(); sourceToOut.clear(); errors = false }
        override fun hasErrors(): Boolean = errors
        override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
            // -Xreport-output-files arrives as OUTPUT messages mapping an output .class to its sources.
            if (severity == CompilerMessageSeverity.OUTPUT) {
                val out = runCatching { OutputMessageUtil.parseOutputMessage(message) }.getOrNull()
                val classFile = out?.outputFile?.takeIf { it.path.endsWith(".class") } ?: return
                out.sourceFiles.forEach { src ->
                    sourceToOut.getOrPut(src.toPath()) { ArrayList() }.add(classFile.toPath())
                }
                return
            }
            if (severity.isError) errors = true
            // Drop LOGGING/INFO chatter from the build console: the scripting-plugin ClassNotFoundException
            // (we ship no scripting jars), "Using Kotlin home directory", "Configuring the compilation
            // environment", etc. They are diagnostics, not build output; warnings and errors are kept.
            if (severity == CompilerMessageSeverity.LOGGING || severity == CompilerMessageSeverity.INFO) return
            val where = location?.let { " (${it.path}:${it.line}:${it.column})" } ?: ""
            messages += "[$severity] $message$where"
        }

        /** Source file → the `.class` files it produced (normalized), from the OUTPUT messages. */
        fun outputs(): Map<Path, List<Path>> =
            sourceToOut.entries.associate { (src, outs) -> src.normalize() to outs.map { it.normalize() } }
    }
}
