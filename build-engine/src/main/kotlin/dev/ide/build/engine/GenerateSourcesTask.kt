package dev.ide.build.engine

import dev.ide.build.SourceGenRequest
import dev.ide.build.SourceGenerator
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import dev.ide.model.ContentRole
import dev.ide.model.LibraryDependency
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * `generateSources`: runs the applicable [SourceGenerator]s for [module] into [outputDir] (the module's
 * `ContentRole.GENERATED` root), BEFORE `compileKotlin`/`compileJava` — which read that root as source, so
 * the generated files compile and index like hand-written ones (no compile-task change needed).
 *
 * Inputs are the module's **hand-written** sources only (the SOURCE roots, never [outputDir] itself, so the
 * generator never sees its own output and can't ping-pong the up-to-date check) plus the compile classpath;
 * the output is [outputDir]. `JavaPlugin` adds an explicit `compile -> generateSources` edge (the generated
 * dir is empty at graph-build time, so the engine's output/input inference alone wouldn't catch it).
 */
class GenerateSourcesTask(
    private val module: Module,
    override val name: TaskName,
    private val generators: List<SourceGenerator>,
    private val outputDir: Path,
    private val classpath: () -> List<Path>,
) : Task {

    /** The module's `ContentRole.SOURCE` root directories (excludes the generated root, so a generator never
     *  sees its own output as an input and can't ping-pong the up-to-date check). */
    private fun sourceRootDirs(): List<Path> = module.sourceSets
        .flatMap { it.contentRoots }
        .filter { ContentRole.SOURCE in it.roles }
        .map { Paths.get(it.dir.path) }
        .filter { Files.isDirectory(it) }

    /** The module's hand-written source files of [ext] (SOURCE roots only; excludes the generated root). */
    private fun handWritten(ext: String): List<Path> = sourceRootDirs()
        .flatMap { root -> Files.walk(root).use { s -> s.filter { it.toString().endsWith(ext) }.collect(Collectors.toList()) } }

    /** The module's directly-declared library coordinates as `group:name` — the opt-in signal probe-based
     *  generators gate on (a transitive dependency is folded into a declaring library's roots, so it never
     *  appears here). */
    private fun declaredDependencyCoordinates(): List<String> = module.dependencies
        .filterIsInstance<LibraryDependency>()
        .mapNotNull { groupName(it.library.name) }
        .distinct()

    private fun request(): SourceGenRequest = SourceGenRequest(
        moduleName = module.name,
        kotlinSources = handWritten(".kt"),
        javaSources = handWritten(".java"),
        classpath = classpath(),
        outputDir = outputDir,
        sourceRoots = sourceRootDirs(),
        declaredDependencies = declaredDependencyCoordinates(),
    )

    /** `group:name` from a `group:name[:version[:classifier]]` coordinate, or null when it isn't one. */
    private fun groupName(coordinate: String): String? {
        val parts = coordinate.split(':')
        return if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) "${parts[0]}:${parts[1]}" else null
    }

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("kotlinSources", handWritten(".kt"))
            filePaths("javaSources", handWritten(".java"))
            dirPaths("deps", depOutputDirs(module))
            filePaths("libs", libJars(module))
            property("generators", generators.joinToString(",") { it.id })
        }
    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply { dirPath("generated", outputDir) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        Files.createDirectories(outputDir)
        val req = request()
        val applicable = generators.filter { it.appliesTo(req) }
        if (applicable.isEmpty()) return TaskResult.Success
        for (g in applicable) {
            ctx.checkCanceled()
            val r = g.generate(req)
            r.messages.forEach(ctx.logger())
            if (!r.success) {
                return TaskResult.Failed(r.messages.joinToString("\n").ifBlank { "source generation (${g.id}) failed" })
            }
        }
        ctx.logger()(":${module.name}:generateSources OK (${applicable.joinToString(",") { it.id }})")
        return TaskResult.Success
    }
}
