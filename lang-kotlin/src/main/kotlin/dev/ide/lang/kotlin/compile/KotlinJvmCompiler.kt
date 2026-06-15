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
 * in-process: the same compiler the editor parse-host loads, and one that runs on ART. `:build-engine` never
 * links it; it injects a [dev.ide.build.engine.KotlinCompile] lambda that calls this (the mirror of the JDT
 * `JavaCompile` wiring).
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
    fun compile(
        kotlinSources: List<Path>,
        javaSources: List<Path>,
        classpath: List<Path>,
        outputDir: Path,
        jvmTarget: String = "17",
        bootClasspath: List<Path> = emptyList(),
        friendPaths: List<Path> = emptyList(),
    ): Result {
        if (kotlinSources.isEmpty()) return Result(true, emptyList())
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
            val where = location?.let { " (${it.path}:${it.line}:${it.column})" } ?: ""
            messages += "[$severity] $message$where"
        }

        /** Source file → the `.class` files it produced (normalized), from the OUTPUT messages. */
        fun outputs(): Map<Path, List<Path>> =
            sourceToOut.entries.associate { (src, outs) -> src.normalize() to outs.map { it.normalize() } }
    }
}
