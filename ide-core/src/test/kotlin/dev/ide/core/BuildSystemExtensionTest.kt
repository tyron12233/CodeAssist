package dev.ide.core

import dev.ide.testkit.withTempDir
import dev.ide.build.BUILD_SYSTEM_EP
import dev.ide.build.BuildContext
import dev.ide.build.BuildRequest
import dev.ide.build.BuildSystem
import dev.ide.build.RUN_TASK_PROVIDER_EP
import dev.ide.build.RunAction
import dev.ide.build.RunTaskProvider
import dev.ide.build.RunTaskSpec
import dev.ide.build.TaskDescriptor
import dev.ide.build.TaskGraph
import dev.ide.build.TaskName
import dev.ide.build.engine.DefaultTaskContainer
import dev.ide.build.engine.LifecycleTask
import dev.ide.model.BuildSystemId
import dev.ide.model.Module
import dev.ide.model.ModuleType
import dev.ide.model.Project
import dev.ide.model.impl.UnknownModuleType
import dev.ide.platform.PluginId
import dev.ide.ui.backend.RunStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
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
        override fun supports(moduleType: ModuleType) = moduleType.id == type
        override fun createBuildGraph(project: Project, request: BuildRequest): TaskGraph =
            throw UnsupportedOperationException("not exercised by the selection test")
        override fun tasks(project: Project): List<TaskDescriptor> = emptyList()
    }

    /**
     * Contributes a row AND executes it: `lint:<module>` carries no built-in id prefix, so the host dispatches
     * it back here through [RunTaskProvider.actionFor].
     */
    private class FakeRunTaskProvider : RunTaskProvider {
        override fun tasksFor(module: Module) =
            listOf(RunTaskSpec("lint:${module.name}", "Lint ${module.name}", "build"))

        override fun actionFor(
            spec: RunTaskSpec,
            project: Project,
            module: Module,
            ctx: BuildContext,
        ): RunAction? {
            if (!spec.id.startsWith("lint:")) return null
            val name = TaskName(":${module.name}:lint")
            val tasks = DefaultTaskContainer()
            tasks.register(name) { LifecycleTask(name) }
            return RunAction("> lint ${module.name}", tasks.build())
        }
    }

    @Test
    fun pluginBuildSystemSelectedForItsTypeButBuiltinsWin() {
        withTempDir("build-system-ep") { dir ->
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
        }
    }

    @Test
    fun runTaskProviderOptionsMergedIntoRunTasks() {
        withTempDir("run-task-ep") { dir ->
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
        }
    }

    /** A contributed row with no built-in id prefix is dispatched back to its provider and its graph runs. */
    @Test
    fun contributedRunActionIsExecuted() {
        withTempDir("run-action-ep") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                ide.platform.extensions.register(RUN_TASK_PROVIDER_EP, FakeRunTaskProvider(), PluginId("test-plugin"))
                val module = ide.modules().first().name

                ide.build.runTask("lint:$module")

                val state = runBlocking {
                    withTimeout(30_000) {
                        ide.build.buildState.first { it.status != RunStatus.Running && it.log.isNotEmpty() }
                    }
            }
            assertEquals(RunStatus.Succeeded, state.status, "log:\n${state.log.joinToString("\n") { it.message }}")
            assertTrue(
                state.log.any { "> lint $module" in it.message },
                "the action's header reached the console: ${state.log.map { it.message }}",
            )
            assertTrue(
                state.steps.any { it.name == ":$module:lint" },
                "the action's graph drove the step list: ${state.steps.map { it.name }}",
            )
            }
        }
    }

    /** An id nobody claims still fails cleanly rather than throwing or hanging. */
    @Test
    fun unknownTaskIdFailsWithAMessage() {
        withTempDir("run-action-unknown") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                ide.build.runTask("nonsense:whatever")
                val state = ide.build.buildState.value
                assertEquals(RunStatus.Failed, state.status)
                assertTrue(state.log.any { "Unknown task" in it.message }, state.log.map { it.message }.toString())
            }
        }
    }
}
