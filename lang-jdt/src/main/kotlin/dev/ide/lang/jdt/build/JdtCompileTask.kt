package dev.ide.lang.jdt.build

import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import dev.ide.build.engine.TaskInputsImpl
import dev.ide.build.engine.TaskOutputsImpl
import dev.ide.build.engine.depOutputDirs
import dev.ide.build.engine.kotlinOutputDir
import dev.ide.build.engine.kotlinSiblings
import dev.ide.build.engine.levelOf
import dev.ide.build.engine.libJars
import dev.ide.build.engine.outputDir
import dev.ide.build.engine.reportToolDiagnostics
import dev.ide.build.engine.sourceFiles
import dev.ide.lang.jdt.compile.JdtBatchCompiler
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `compileJava` build task for the JDT backend: an ecj batch compile of a module's `.java` to `.class`.
 *
 * It lives in lang-jdt, next to [JdtBatchCompiler], and calls the compiler directly: the build graph no
 * longer routes Java compilation through an injected port, so build-engine stays compiler-free and the JDT
 * compile step is owned by the JDT module. The graph builder (jvm-build for plain modules, android-support
 * for Android modules) constructs this task and depends on it by name.
 *
 * [bootClasspath] is the ecj `-bootclasspath`: empty on the desktop (ecj uses the host JRE) and `android.jar`
 * plus the desugar stubs on ART. It is genuinely host-specific, so it is passed in rather than derived from
 * [module]. [ownKotlinOut] puts this module's own `kotlin-classes` on the Java compile classpath when the
 * module also has Kotlin, so Java sees the module's Kotlin types (the Java -> Kotlin interop direction).
 */
class JdtCompileTask(
    private val module: Module,
    override val name: TaskName,
    private val bootClasspath: List<Path>,
    private val ownKotlinOut: Boolean = false,
) : Task {
    /** Upstream Kotlin output dirs + (when the module compiles Kotlin) its own — so Java sees Kotlin types. */
    private fun kotlinClasspath(): List<Path> =
        (kotlinSiblings(depOutputDirs(module)) + if (ownKotlinOut) listOf(kotlinOutputDir(module)) else emptyList())
            .filter { Files.isDirectory(it) }

    private fun classpath(): List<Path> = depOutputDirs(module) + kotlinClasspath() + libJars(module)

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("sources", sourceFiles(module))
            dirPaths("deps", depOutputDirs(module) + kotlinClasspath()) // content of upstream/own outputs → re-run propagates
            filePaths("libs", libJars(module))
            property("level", levelOf(module.languageLevel))
        }
    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply { dirPath("classes", outputDir(module)) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val sources = sourceFiles(module)
        if (sources.isEmpty()) { Files.createDirectories(outputDir(module)); return TaskResult.Success }
        val r = JdtBatchCompiler.compile(
            sources, classpath(), outputDir(module), levelOf(module.languageLevel), bootClasspath = bootClasspath,
        )
        ctx.reportToolDiagnostics("java", r.messages)
        ctx.logger()(":${module.name}:compileJava ${if (r.success) "OK" else "FAILED"}")
        return if (r.success) TaskResult.Success
        else TaskResult.Failed(r.messages.joinToString("\n").ifBlank { "compilation failed" })
    }
}
