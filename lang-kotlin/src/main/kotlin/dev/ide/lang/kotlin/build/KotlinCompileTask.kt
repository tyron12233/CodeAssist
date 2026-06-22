package dev.ide.lang.kotlin.build

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
import dev.ide.build.engine.kotlinSourceFiles
import dev.ide.build.engine.levelOf
import dev.ide.build.engine.libJars
import dev.ide.build.engine.reportToolDiagnostics
import dev.ide.build.engine.sourceFiles
import dev.ide.lang.kotlin.compile.ComposeCompilerPlugin
import dev.ide.lang.kotlin.compile.IncrementalKotlinCompiler
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `compileKotlin` build task for the Kotlin backend: K2 codegen of a module's `.kt` (with its `.java`
 * as resolution-only inputs) into the module's `kotlin-classes` dir, run ahead of `compileJava`, which then
 * puts that dir on its classpath (the Kotlin -> Java and Java -> Kotlin interop loop).
 *
 * It lives in lang-kotlin, next to [IncrementalKotlinCompiler], and drives it directly: the build graph no
 * longer routes Kotlin compilation through an injected port. The Compose compiler plugin is applied
 * automatically when the module depends on the Compose runtime (otherwise `@Composable` functions emit
 * unusable bytecode). [compiler] is the workspace's incremental compiler (it wraps the warm, app-scoped
 * `KotlinJvmCompiler`); [bootClasspath] (host JDK on the desktop, `android.jar` on ART) is host-specific and
 * passed in. The Java sources are declared inputs so a Java edit re-runs the Kotlin compile (interop shape).
 */
class KotlinCompileTask(
    private val module: Module,
    override val name: TaskName,
    private val bootClasspath: List<Path>,
    private val compiler: IncrementalKotlinCompiler,
) : Task {
    private fun upstreamKotlin(): List<Path> = kotlinSiblings(depOutputDirs(module)).filter { Files.isDirectory(it) }
    private fun classpath(): List<Path> = depOutputDirs(module) + upstreamKotlin() + libJars(module)

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("kotlinSources", kotlinSourceFiles(module))
            filePaths("javaSources", sourceFiles(module))      // interop: a Java edit can change what Kotlin resolves
            dirPaths("deps", depOutputDirs(module) + upstreamKotlin())
            filePaths("libs", libJars(module))
            property("level", levelOf(module.languageLevel))
        }
    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply { dirPath("classes", kotlinOutputDir(module)) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val out = kotlinOutputDir(module)
        Files.createDirectories(out)
        val kt = kotlinSourceFiles(module)
        if (kt.isEmpty()) return TaskResult.Success
        val classpath = classpath()
        val composePlugin = if (ComposeCompilerPlugin.isComposeModule(classpath + bootClasspath))
            listOfNotNull(ComposeCompilerPlugin.jar()) else emptyList()
        val r = compiler.compile(
            kt, sourceFiles(module), classpath, out, levelOf(module.languageLevel),
            bootClasspath = bootClasspath, compilerPlugins = composePlugin,
        )
        ctx.reportToolDiagnostics("kotlin", r.messages)
        ctx.logger()(":${module.name}:compileKotlin ${if (r.success) "OK" else "FAILED"}")
        return if (r.success) TaskResult.Success
        else TaskResult.Failed(r.messages.joinToString("\n").ifBlank { "kotlin compilation failed" })
    }
}
