package dev.ide.core

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.FacetTemplate
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleType
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiModuleDeps
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The Dependencies pane loads its graph from a `LaunchedEffect`, so its resolve is cancelled every time the
 * pane leaves composition or reloads, which enabling a bundled processor (Hilt/Room) does, since that adds the
 * processor's runtime dependency. That cancellation used to be swallowed inside `moduleDependencies`, which
 * then finished building a model from an EMPTY resolved graph: every declared coordinate had no node, so every
 * one of them was listed as unresolved. On a project with 14 declared dependencies the pane showed a red
 * "14 unresolved" banner with nothing actually wrong.
 *
 * A cancelled resolve must instead propagate, leaving the last good model on screen. Verified offline: the
 * calling coroutine is cancelled before the resolve reaches its first suspension point, so the graph walk never
 * fetches anything.
 */
class CancelledDepsResolveTest {

    private class JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    private val declared = listOf(
        "com.squareup.okhttp3:okhttp:4.12.0",
        "com.google.code.gson:gson:2.11.0",
        "org.jetbrains:annotations:24.1.0",
    )

    private fun createWorkspace(dir: Path) {
        val platform = PlatformCore()
        try {
            ModuleTypeRegistry(platform.extensions).register(JavaLib(), PluginId("java-support"))
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry())
            val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
            store.workspace.beginModification().apply { addProject("app", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            val mainSet = SourceSetTemplate(
                "main", DependencyScope.IMPLEMENTATION, mapOf("src/main/java" to setOf(ContentRole.SOURCE)),
            )
            store.workspace.projects.single().beginModification().apply {
                val m = addModule("app", javaLib)
                m.addSourceSet(mainSet)
                declared.forEach { m.addDependency(LibraryDependency(LibraryRef(it), DependencyScope.IMPLEMENTATION)) }
                commit()
            }
            store.save()
        } finally {
            platform.dispose()
        }
    }

    @Test
    fun cancelledScreenResolveDoesNotReportEveryDeclaredDepUnresolved() = withTempDir("ide-deps-cancel") { dir ->
        createWorkspace(dir)
        IdeServices.open(dir).use { ide ->
            var returned: UiModuleDeps? = null
            var thrown: Throwable? = null
            runBlocking {
                // UNDISPATCHED so the body runs on this thread: cancel first (the pane reloading), then call:
                // the resolve throws at its first suspension point, before any coordinate is fetched.
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    cancel()
                    try {
                        returned = ide.dependencies.moduleDependencies("app")
                    } catch (t: Throwable) {
                        thrown = t
                    }
                }
                job.join()
            }

            assertTrue(
                thrown is CancellationException,
                "a cancelled screen resolve must propagate, not return a model built from an empty graph " +
                    "(threw ${thrown?.javaClass?.name}, returned ${returned?.unresolved})",
            )
            assertNull(returned, "no model may be published for a cancelled resolve: ${returned?.unresolved}")
        }
    }
}
