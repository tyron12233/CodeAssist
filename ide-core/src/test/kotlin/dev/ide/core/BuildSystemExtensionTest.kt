package dev.ide.core

import dev.ide.build.BUILD_SYSTEM_EP
import dev.ide.build.BuildRequest
import dev.ide.build.BuildSystem
import dev.ide.build.RUN_TASK_PROVIDER_EP
import dev.ide.build.RunTaskProvider
import dev.ide.build.RunTaskSpec
import dev.ide.build.SyncResult
import dev.ide.build.TaskDescriptor
import dev.ide.build.TaskGraph
import dev.ide.model.BuildSystemId
import dev.ide.model.Module
import dev.ide.model.ModuleType
import dev.ide.model.Project
import dev.ide.model.impl.UnknownModuleType
import dev.ide.platform.PluginId
import dev.ide.platform.ProgressReporter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 2f / 2h: a plugin contributes a [BuildSystem] (selected by [BuildSystem.supports]) and extra Run-picker
 * options ([RunTaskProvider]) through the build extension points, and the engine's own built-ins still win
 * for the module types they support. Registered on the engine's per-project registry (the same one the build
 * service queries).
 */
class BuildSystemExtensionTest {

    /** Only [supports] and identity matter for the selection test; the graph methods are never reached. */
    private class FakeBuildSystem(private val type: String) : BuildSystem {
        override val id = BuildSystemId.NATIVE
        override suspend fun sync(project: Project, progress: ProgressReporter) = SyncResult(true, emptyList())
        override fun supports(moduleType: ModuleType) = moduleType.id == type
        override fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph =
            throw UnsupportedOperationException("not exercised by the selection test")
        override fun tasks(project: Project): List<TaskDescriptor> = emptyList()
    }

    private class FakeRunTaskProvider : RunTaskProvider {
        override fun tasksFor(module: Module) =
            listOf(RunTaskSpec("lint:${module.name}", "Lint ${module.name}", "build"))
    }

    @Test
    fun pluginBuildSystemSelectedForItsTypeButBuiltinsWin() {
        val dir = Files.createTempDirectory("build-system-ep")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val foo = FakeBuildSystem("custom-foo")
            ide.platform.extensions.register(BUILD_SYSTEM_EP, foo, PluginId("test-plugin"))

            // A novel module type the built-ins reject → the plugin build system is selected.
            assertSame(foo, ide.build.buildSystemFor(UnknownModuleType("custom-foo")))
            // A novel type nobody claims → null (no built-in, no plugin supports it).
            assertNull(ide.build.buildSystemFor(UnknownModuleType("custom-bar")))
            // A real Java module type → the built-in JavaBuildSystem wins, never the plugin.
            val javaType = ide.modules().first().type
            val chosen = ide.build.buildSystemFor(javaType)
            assertTrue(chosen != null && chosen !== foo, "the built-in must win for a Java module type")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun runTaskProviderOptionsMergedIntoRunTasks() {
        val dir = Files.createTempDirectory("run-task-ep")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            assertTrue(ide.build.runTasks().none { it.id.startsWith("lint:") }, "no lint tasks before the provider")
            ide.platform.extensions.register(RUN_TASK_PROVIDER_EP, FakeRunTaskProvider(), PluginId("test-plugin"))

            val after = ide.build.runTasks()
            for (name in ide.modules().map { it.name }) {
                assertTrue(
                    after.any { it.id == "lint:$name" && it.label == "Lint $name" },
                    "the provider's option for $name should appear: ${after.map { it.id }}",
                )
            }
            // The built-in enumeration is untouched (a runnable main still yields a run: task).
            assertTrue(after.any { it.id.startsWith("run:") }, "built-in run tasks still present")
        }
        dir.toFile().deleteRecursively()
    }
}
