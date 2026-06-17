package dev.ide.build.engine

import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import dev.ide.model.ContentRole
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * The Kotlin compile step, injected so build-engine never links the Kotlin compiler (the mirror of
 * [JavaCompile]; `:lang-kotlin` wires the real `K2JVMCompiler`, the host injects it). Kotlin/Java interop:
 * [javaSources] are handed to kotlinc *for symbol resolution only* — Kotlin code may reference Java classes
 * in the same module — but kotlinc emits `.class` only for the [kotlinSources]. The Java compiler then sees
 * the Kotlin output via its classpath (the `kotlin-classes` dir), closing the loop the other way.
 *
 * The bootclasspath (the host JDK on desktop, `android.jar` on ART) is captured by the injected lambda, not
 * passed here — exactly as [JavaCompile] leaves its boot library to the host.
 */
fun interface KotlinCompile {
    fun compile(
        kotlinSources: List<Path>,
        javaSources: List<Path>,
        classpath: List<Path>,
        outputDir: Path,
        jvmTarget: String,
    ): KotlinCompileResult
}

data class KotlinCompileResult(val success: Boolean, val messages: List<String> = emptyList())

/** The `.kt` files in a module's SOURCE/GENERATED roots — kotlinc's program inputs. */
internal fun kotlinSourceFiles(module: Module): List<Path> = sourceRootDirs(module)
    .flatMap { root -> Files.walk(root).use { s -> s.filter { it.toString().endsWith(".kt") }.collect(Collectors.toList()) } }

/** True when a module carries any Kotlin source (so it needs a `compileKotlin` step). */
internal fun hasKotlinSources(module: Module): Boolean = sourceRootDirs(module)
    .any { root -> Files.walk(root).use { s -> s.anyMatch { it.toString().endsWith(".kt") } } }

private fun sourceRootDirs(module: Module): List<Path> = module.sourceSets
    .flatMap { it.contentRoots }
    .filter { ContentRole.SOURCE in it.roles || ContentRole.GENERATED in it.roles }
    .map { Paths.get(it.dir.path) }
    .filter { Files.isDirectory(it) }

/** Where a module's Kotlin `.class` output lands — a sibling of the Java [outputDir] so the two compilers
 *  never share a directory (which would make each see the other's classes as "changed" output every run). */
internal fun kotlinOutputDir(module: Module): Path = outputDir(module).resolveSibling("kotlin-classes")

/** For each upstream Java output dir, the sibling `kotlin-classes` dir an upstream Kotlin module emits into —
 *  added to a depender's compile classpath so it can reference an upstream module's Kotlin types. */
internal fun kotlinSiblings(javaOutputDirs: List<Path>): List<Path> =
    javaOutputDirs.map { it.resolveSibling("kotlin-classes") }

/**
 * `compileKotlin`: compile a module's `.kt` (with its `.java` as resolution-only inputs) to `.class` in the
 * module's `kotlin-classes` dir. Runs before `compileJava`, which then puts that dir on its classpath. The
 * Java sources are declared as inputs so editing Java re-runs the Kotlin compile (interop can change shape).
 */
internal class KotlinCompileTask(
    private val module: Module,
    override val name: TaskName,
    private val compile: KotlinCompile,
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
        val r = compile.compile(kt, sourceFiles(module), classpath(), out, levelOf(module.languageLevel))
        ctx.logger()(":${module.name}:compileKotlin ${if (r.success) "OK" else "FAILED"}")
        return if (r.success) TaskResult.Success
        else TaskResult.Failed(r.messages.joinToString("\n").ifBlank { "kotlin compilation failed" })
    }
}
