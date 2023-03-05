package com.tyron.builder.gradle.internal.ide.v2
import com.tyron.builder.gradle.internal.ide.dependencies.LocalJarCache
import com.tyron.builder.gradle.internal.ide.dependencies.LocalJarCacheImpl
import com.tyron.builder.gradle.internal.ide.dependencies.MavenCoordinatesCacheBuildService
import com.tyron.builder.gradle.internal.ide.dependencies.StringCache
import com.tyron.builder.gradle.internal.ide.dependencies.StringCacheImpl
import com.tyron.builder.gradle.internal.services.ServiceRegistrationAction
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Build Service used to cache objects used across sync of several sub-projects.
 *
 * Right now this caches:
 * - string instances
 * - content of local jar folders so that we only need to do IO once per folder.
 */
@Suppress("UnstableApiUsage")
abstract class GlobalSyncService : BuildService<GlobalSyncService.Parameters>,
    AutoCloseable {

    interface Parameters: BuildServiceParameters {
        val mavenCoordinatesCache: Property<MavenCoordinatesCacheBuildService>
    }

    class RegistrationAction(
        project: Project,
        private val mavenCoordinatesCache: Provider<MavenCoordinatesCacheBuildService>
    ) : ServiceRegistrationAction<GlobalSyncService, Parameters>(
        project,
        GlobalSyncService::class.java
    ) {
        override fun configure(parameters: Parameters) {
            parameters.mavenCoordinatesCache.set(mavenCoordinatesCache)
        }
    }

    val stringCache: StringCache
        get() = _stringCache

    val localJarCache: LocalJarCache
        get() = _localJarCache

    private val _stringCache = StringCacheImpl()
    private val _localJarCache = LocalJarCacheImpl()

    override fun close() {
        _stringCache.clear()
        _localJarCache.clear()
    }
}

