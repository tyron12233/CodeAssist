package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.ui.backend.DependencyService
import dev.ide.ui.backend.DepsResolveState
import dev.ide.ui.backend.UiAddResult
import dev.ide.ui.backend.UiArtifactHit
import dev.ide.ui.backend.UiDepModule
import dev.ide.ui.backend.UiModuleDeps
import dev.ide.ui.backend.UiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** [DependencyService] over the engine: Maven add/resolve/conflict, local libraries, repositories. Resolution
 *  does blocking HTTP / disk work, so the suspend paths hop to [Dispatchers.IO]. */
internal class DependencyBackend(private val ctx: BackendContext) : DependencyService {
    override val depsState: StateFlow<DepsResolveState> =
        ctx.engineFlow(DepsResolveState()) { it.dependencies.depsState }

    override fun startPendingDependencyResolution() =
        ctx.services.dependencies.startPendingDependencyResolution()

    override suspend fun retryDependencyResolution() =
        withContext(Dispatchers.IO) { ctx.services.dependencies.retryDependencyResolution() }

    override fun dependencyModules(): List<UiDepModule> = ctx.services.dependencies.dependencyModules()

    override suspend fun moduleDependencies(moduleName: String): UiModuleDeps? =
        withContext(Dispatchers.IO) { ctx.services.dependencies.moduleDependencies(moduleName) }

    override suspend fun searchArtifacts(query: String, moduleName: String): List<UiArtifactHit> =
        withContext(Dispatchers.IO) { ctx.services.dependencies.searchArtifacts(query, moduleName) }

    override suspend fun addDependency(
        moduleName: String, coordinate: String, scope: String, exclusions: List<String>, variant: String?
    ): UiAddResult = withContext(Dispatchers.IO) {
        ctx.services.dependencies.addDependency(
            moduleName, coordinate, scope, exclusions, variant
        )
    }

    override suspend fun addPlatform(moduleName: String, coordinate: String, variant: String?): UiAddResult =
        withContext(Dispatchers.IO) { ctx.services.dependencies.addPlatform(moduleName, coordinate, variant) }

    override suspend fun addFirebase(moduleName: String, artifacts: List<String>): UiAddResult =
        withContext(Dispatchers.IO) { ctx.services.dependencies.addFirebase(moduleName, artifacts) }

    override suspend fun addGooglePlayServices(
        moduleName: String, coordinates: List<String>
    ): UiAddResult =
        withContext(Dispatchers.IO) { ctx.services.dependencies.addGooglePlayServices(moduleName, coordinates) }

    override fun removeDependency(moduleName: String, coordinate: String): Boolean =
        ctx.services.dependencies.removeDependency(moduleName, coordinate)

    override suspend fun setDependencyExclusions(
        moduleName: String, coordinate: String, exclusions: List<String>
    ): UiAddResult = withContext(Dispatchers.IO) {
        ctx.services.dependencies.setExclusions(
            moduleName, coordinate, exclusions
        )
    }

    override suspend fun availableVersions(moduleName: String, coordinate: String): List<String> =
        withContext(Dispatchers.IO) { ctx.services.dependencies.availableVersions(moduleName, coordinate) }

    override suspend fun updateDependency(
        moduleName: String, coordinate: String, version: String, scope: String, exclusions: List<String>
    ): UiAddResult = withContext(Dispatchers.IO) {
        ctx.services.dependencies.updateDependency(moduleName, coordinate, version, scope, exclusions)
    }

    override fun moduleDependencyTargets(moduleName: String): List<String> =
        ctx.services.dependencies.moduleDependencyTargets(moduleName)

    override suspend fun addModuleDependency(
        moduleName: String, targetModule: String, scope: String, variant: String?
    ): UiAddResult = withContext(Dispatchers.IO) {
        ctx.services.dependencies.addModuleDependency(
            moduleName, targetModule, scope, variant
        )
    }

    // ---- local libraries ----

    override fun localLibraryDropDir(moduleName: String): String? =
        ctx.services.dependencies.localLibraryDropDir(moduleName)

    override fun localLibraryCandidates(moduleName: String): List<String> =
        ctx.services.dependencies.localLibraryCandidates(moduleName)

    override suspend fun addLocalLibrary(
        moduleName: String, path: String, scope: String
    ): UiAddResult =
        withContext(Dispatchers.IO) { ctx.services.dependencies.addLocalLibrary(moduleName, path, scope) }

    // ---- repositories ----

    override fun repositories(): List<UiRepository> = ctx.services.dependencies.repositories()
    override fun addRepository(name: String, url: String): Boolean =
        ctx.services.dependencies.addRepository(name, url)

    override fun removeRepository(url: String): Boolean = ctx.services.dependencies.removeRepository(url)
}
