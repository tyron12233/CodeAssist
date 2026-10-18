package dev.ide.build.jvm.buildconfig

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
import dev.ide.build.engine.moduleClosure
import dev.ide.build.engine.moduleDir
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Generates a module's Kotlin `BuildConfig` object ([KotlinBuildConfigFacet]) ahead of its compile, on whichever
 * pipeline is building it (the JVM one, or the Android one for a KMP module imported as an Android library), the
 * way [dev.ide.build.jvm.compose.ComposeResourcesPlugin] hangs its generator off the shared lifecycle names.
 */
class KotlinBuildConfigPlugin : BuildPlugin {

    override val id: String = "kotlin-build-config"

    override fun appliesTo(config: BuildConfiguration): Boolean =
        config.project.modules.any { it.facets.get(KotlinBuildConfigFacet.KEY) != null }

    override fun apply(config: BuildConfiguration) {
        val byId = config.project.modules.associateBy { it.id }
        for (module in moduleClosure(config.request.targets, byId)) {
            val facet = module.facets.get(KotlinBuildConfigFacet.KEY) ?: continue
            val name = TaskName(":${module.name}:generateKotlinBuildConfig")
            config.tasks.register(name) { GenerateKotlinBuildConfigTask(name, module, facet) }
            config.tasks.named(Lifecycle.compileKotlin(module.name)).configure { dependsOn(name) }
            config.tasks.named(Lifecycle.compileJava(module.name)).configure { dependsOn(name) }
        }
    }
}

class GenerateKotlinBuildConfigTask(
    override val name: TaskName,
    private val module: Module,
    private val facet: KotlinBuildConfigFacet,
) : Task {
    private fun outDir(): Path = moduleDir(module).resolve(KotlinBuildConfigFacet.GENERATED_KOTLIN_ROOT)
    private fun source(): String = KotlinBuildConfigSource.render(facet)

    override val inputs: TaskInputs get() = TaskInputsImpl().apply { property("source", source()) }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { dirPath("kotlin", outDir()) }

    override suspend fun execute(ctx: TaskContext): TaskResult = runCatching {
        val out = outDir()
        if (Files.isDirectory(out)) Files.walk(out).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        val dir = if (facet.packageName.isBlank()) out else out.resolve(facet.packageName.replace('.', '/'))
        Files.createDirectories(dir)
        dir.resolve("${facet.className}.kt").writeText(source())
        TaskResult.Success as TaskResult
    }.getOrElse { TaskResult.Failed("buildConfig: ${it.message}", it) }
}
