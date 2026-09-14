package dev.ide.build.jvm.compose

import dev.ide.build.BuildConfiguration
import dev.ide.build.BuildPlugin
import dev.ide.build.Lifecycle
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.build.engine.debug
import dev.ide.build.engine.moduleClosure
import dev.ide.build.engine.moduleDir
import dev.ide.build.engine.warn
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates a module's Compose resources before it compiles: the `Res` object its sources import, and the
 * staged resource tree the runtime reads (see [ComposeResourcesGenerator]).
 *
 * Contributed on `platform.buildPlugin`, so it applies to whichever pipeline is running (the JVM one for a
 * `java-lib`, the Android one for a library or an application) by hanging off the lifecycle task names both
 * register. A module without a [ComposeResourcesFacet] gets no task at all.
 */
class ComposeResourcesPlugin : BuildPlugin {

    override val id: String = "compose-resources"

    override fun appliesTo(config: BuildConfiguration): Boolean =
        config.project.modules.any { it.facets.get(ComposeResourcesFacet.KEY) != null }

    override fun apply(config: BuildConfiguration) {
        // Only the modules this build actually reaches. A task registered for one outside the closure has
        // nothing to attach to (the pipeline registered no lifecycle tasks for it) and the engine would run
        // it as a root of its own, generating resources for a module nobody asked to build.
        val byId = config.project.modules.associateBy { it.id }
        for (module in moduleClosure(config.request.targets, byId)) {
            val facet = module.facets.get(ComposeResourcesFacet.KEY) ?: continue
            val name = TaskName(":${module.name}:generateComposeResources")
            config.tasks.register(name) { GenerateComposeResourcesTask(name, module, facet) }
            // Ahead of everything that reads either output: the compilers that see the accessors as source,
            // and `processResources`, which copies the staged tree into the packaged output.
            config.tasks.named(Lifecycle.compileKotlin(module.name)).configure { dependsOn(name) }
            config.tasks.named(Lifecycle.compileJava(module.name)).configure { dependsOn(name) }
            config.tasks.named(Lifecycle.processResources(module.name)).configure { dependsOn(name) }
        }
    }
}

/**
 * One module's Compose resource generation.
 *
 * Inputs are the `composeResources` roots plus the package/visibility the generated code is shaped by;
 * outputs are the generated Kotlin directory and the staged resource directory. Declaring both means the
 * engine skips the task when nothing changed and re-runs it when a string is edited, like any other.
 */
class GenerateComposeResourcesTask(
    override val name: TaskName,
    private val module: Module,
    private val facet: ComposeResourcesFacet,
) : Task {

    private fun kotlinDir(): Path =
        moduleDir(module).resolve(ComposeResourcesFacet.GENERATED_KOTLIN_ROOT)

    private fun roots(): List<Path> =
        facet.roots.map { moduleDir(module).resolve(it) }.filter { Files.isDirectory(it) }

    private fun resourcesDir(): Path =
        moduleDir(module).resolve(ComposeResourcesFacet.GENERATED_RESOURCES_ROOT)

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            val roots = roots()
            if (roots.isNotEmpty()) dirPaths("composeResources", roots)
            property("package", facet.packageName)
            property("public", facet.publicResClass.toString())
        }

    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply {
            dirPath("kotlin", kotlinDir())
            dirPath("resources", resourcesDir())
        }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        val roots = roots()
        if (roots.isEmpty()) return TaskResult.Success
        if (facet.packageName.isBlank()) {
            return TaskResult.Failed("compose resources: ${module.name} declares no package for its Res class")
        }
        return runCatching {
            // A stale accessor for a deleted resource would still compile and then fail at runtime, so the
            // previous generation is cleared rather than written over.
            clear(kotlinDir())
            clear(resourcesDir())
            val result = ComposeResourcesGenerator(facet.packageName, facet.publicResClass)
                .generate(roots, kotlinDir(), resourcesDir())
            result.warnings.forEach { ctx.warn(it) }
            ctx.debug(
                "${name.value}: ${result.resourceCount} resource(s), " +
                    "${result.generatedFiles} generated file(s), ${result.stagedFiles} staged"
            )
            TaskResult.Success as TaskResult
        }.getOrElse { TaskResult.Failed("compose resources: ${it.message}", it) }
    }

    private fun clear(dir: Path) {
        if (!Files.isDirectory(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
