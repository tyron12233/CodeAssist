package dev.ide.build.jvm

import dev.ide.build.BuildConfiguration
import dev.ide.build.BuildContext
import dev.ide.build.BuildEnv
import dev.ide.build.BuildGoal
import dev.ide.build.BuildPlugin
import dev.ide.build.BuildRequest
import dev.ide.build.Lifecycle
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.build.engine.TaskInputsImpl
import dev.ide.build.engine.TaskOutputsImpl
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.Project
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import dev.ide.testkit.testEnv
import dev.ide.testkit.writeSource
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A plugin contributes build logic to the native Java graph through [BuildContext.plugins]: its task is
 * registered after the build system's own, wires itself to them by name in both directions (a compile task
 * depends on it, the module's `assemble` aggregate pulls it in), and runs as part of the build.
 */
class BuildPluginContributionTest {

    private class JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    /** Writes a generated source into the module's generated dir; stands in for a real codegen step. */
    private class WriteFileTask(
        override val name: TaskName,
        private val target: Path,
        private val text: String,
    ) : Task {
        override val inputs: TaskInputs get() = TaskInputsImpl().apply { property("text", text) }
        override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("out", target) }
        override suspend fun execute(ctx: TaskContext): TaskResult {
            Files.createDirectories(target.parent)
            Files.writeString(target, text)
            return TaskResult.Success
        }
    }

    /** The plugin under test: one task per module, hooked before `compileJava` and into `assemble`. */
    private class StampPlugin(private val stamps: MutableList<String>) : BuildPlugin {
        override val id = "stamp"
        override fun appliesTo(config: BuildConfiguration) = config.request.goal != BuildGoal.CLEAN

        override fun apply(config: BuildConfiguration) {
            for (module in config.project.modules) {
                val stamp = TaskName(":${module.name}:stamp")
                val target = config.env.generatedDir(module, id).resolve("stamp.txt")
                config.tasks.register(stamp) { WriteFileTask(stamp, target, "stamped ${module.name}") }
                // Both directions of the seam: an existing task depends on the new one, and the module's
                // terminal aggregate pulls it in.
                config.tasks.named(Lifecycle.compileJava(module.name)).configure { dependsOn(stamp) }
                config.tasks.named(Lifecycle.assemble(module.name)).configure { dependsOn(stamp) }
                stamps += target.toString()
            }
        }
    }

    private fun buildEnv(root: Path) = object : BuildEnv {
        override val workspaceRoot: Path = root
    }

    private fun workspace(dir: Path, platform: PlatformCore): Project {
        ModuleTypeRegistry(platform.extensions).register(JavaLib(), PluginId("java-support"))
        val store = ProjectModel.open(dir, platform, FacetCodecRegistry())
        val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
        store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
        store.workspace.projects.single().beginModification().apply {
            addModule("app", javaLib).addSourceSet(
                SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/java" to setOf(ContentRole.SOURCE)))
            )
            commit()
        }
        dir.writeSource(
            "app/src/main/java/com/example/app/Main.java",
            "package com.example.app; public class Main { public static void main(String[] a) {} }",
        )
        return store.workspace.projects.single()
    }

    @Test
    fun contributedTaskIsWiredIntoTheGraphAndRuns() {
        testEnv("build-plugin") { env ->
            val project = workspace(env.dir, env.platform)
            val stamps = ArrayList<String>()
            val graph = JavaBuildSystem().createBuildGraph(
                project,
                BuildRequest(listOf(ModuleId("app")), VariantSelector("main"), BuildGoal.ASSEMBLE),
                BuildContext(plugins = listOf(StampPlugin(stamps)), env = buildEnv(env.dir)),
            )

            val names = graph.tasks.map { it.name.value }
            assertTrue(":app:stamp" in names, "the contributed task is in the graph: $names")
            assertTrue(":app:assemble" in names, "the assemble aggregate anchor exists: $names")

            // Declared by name against a task the build system registered: the compile task now depends on it.
            val compile = graph.tasks.single { it.name == Lifecycle.compileJava("app") }
            assertTrue(
                graph.dependencies(compile).any { it.name.value == ":app:stamp" },
                "compileJava must depend on the contributed task",
            )
            val levels = graph.topologicalLevels().map { level -> level.map { it.name.value } }
            val stampLevel = levels.indexOfFirst { ":app:stamp" in it }
            val compileLevel = levels.indexOfFirst { ":app:compileJava" in it }
            assertTrue(stampLevel in 0 until compileLevel, "stamp must be scheduled before compileJava: $levels")

            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(env.dir.resolve(".caches/build")))
                    .execute(graph, SimpleTaskContext(), 2)
            }
            assertTrue(outcome.succeeded)
            assertEquals("stamped app", Path.of(stamps.single()).readText())
        }
    }

    @Test
    fun aPluginThatDoesNotApplyContributesNothing() {
        testEnv("build-plugin-gate") { env ->
            val project = workspace(env.dir, env.platform)
            val graph = JavaBuildSystem().createBuildGraph(
                project,
                BuildRequest(listOf(ModuleId("app")), VariantSelector("main"), BuildGoal.CLEAN),
                BuildContext(plugins = listOf(StampPlugin(ArrayList())), env = buildEnv(env.dir)),
            )
            assertTrue(graph.tasks.none { it.name.value == ":app:stamp" }, "appliesTo gates the contribution")
        }
    }

    @Test
    fun aThrowingPluginDoesNotBreakTheBuild() {
        testEnv("build-plugin-throws") { env ->
            val project = workspace(env.dir, env.platform)
            val broken = object : BuildPlugin {
                override val id = "broken"
                override fun apply(config: BuildConfiguration) = error("boom")
            }
            val graph = JavaBuildSystem().createBuildGraph(
                project,
                BuildRequest(listOf(ModuleId("app")), VariantSelector("main"), BuildGoal.ASSEMBLE),
                BuildContext(plugins = listOf(broken), env = buildEnv(env.dir)),
            )
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(env.dir.resolve(".caches/build")))
                    .execute(graph, SimpleTaskContext(), 2)
            }
            assertTrue(outcome.succeeded, "a broken extension must not make the project unbuildable")
        }
    }
}
