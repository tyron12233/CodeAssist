package dev.ide.core

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleType
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The persistent "couldn't be resolved" banner must appear the instant a project with a known-bad dependency
 * closure opens, BEFORE any (re)resolve runs. The in-memory unresolved verdict starts empty each session, so
 * the engine mirrors it to a sidecar (`.platform/.deps-unresolved`) after every resolve and seeds it back on
 * the next open ([DependencyService.loadPersistedUnresolved] via its `init`). The first dependency-health
 * publish then reflects it immediately.
 *
 * Verified WITHOUT touching the network: a matching reconcile marker (`.platform/.deps-reconciled`) gates the
 * background reconcile so this open does zero resolution, leaving only the seeded-from-disk verdict to drive
 * the banner.
 */
class UnresolvedDepsPersistenceTest {

    private class JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    private val fakeCoord = "com.example.totally:nonexistent:1.0.0"

    /**
     * Build a one-module workspace on disk that DECLARES an unresolvable Maven dependency (it is never
     * resolved, so `libraries.json` holds no entry for it). Returns the module's id value so the test can write
     * a reconcile-marker fingerprint that matches what the engine computes.
     */
    private fun createWorkspaceWithUnresolvableDep(dir: Path): String {
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
                m.addDependency(LibraryDependency(LibraryRef(fakeCoord), DependencyScope.IMPLEMENTATION))
                commit()
            }
            store.save()
            return store.workspace.projects.single().modules.single().id.value
        } finally {
            platform.dispose()
        }
    }

    @Test
    fun unresolvedDepBannerShowsOnOpenBeforeAnyResolve() {
        val dir = Files.createTempDirectory("ide-unresolved-persist")
        val moduleId = createWorkspaceWithUnresolvableDep(dir)

        // The state a prior session that couldn't resolve this dep leaves behind: the sidecar carries the
        // verdict, and a MATCHING reconcile marker (same fingerprint the engine computes: "<moduleId>|<coord>"
        // for the single Maven dep) gates the background reconcile so this open performs no network resolution.
        val platformDir = dir.resolve(".platform").also { Files.createDirectories(it) }
        Files.writeString(
            platformDir.resolve(".deps-unresolved"),
            "$fakeCoord\tCouldn't reach the repositories. Check your internet connection, then tap Retry.",
        )
        Files.writeString(platformDir.resolve(".deps-reconciled"), "$moduleId|$fakeCoord")

        IdeServices.open(dir).use { ide ->
            // The editor's mount effect drives this; it publishes dependency-health synchronously (reflecting
            // the seeded verdict) and then gates the reconcile, so no resolve runs.
            ide.dependencies.startPendingDependencyResolution()

            val unresolved = ide.dependencies.depsState.value.unresolved
            assertTrue(
                unresolved.any { it.coordinate == fakeCoord },
                "the persisted unresolved dependency should surface immediately on open: $unresolved",
            )
        }
        dir.toFile().deleteRecursively()
    }
}
