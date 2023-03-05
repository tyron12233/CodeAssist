@file:JvmName("MavenCoordinatesUtils")
package com.tyron.builder.gradle.internal.ide.dependencies



import com.tyron.builder.gradle.internal.services.ServiceRegistrationAction
import com.tyron.builder.gradle.internal.services.StringCachingBuildService
import com.tyron.builder.dependency.MavenCoordinatesImpl
import com.tyron.builder.internal.StringCachingService
import com.tyron.builder.model.MavenCoordinates
import com.android.ide.common.caching.CreatingCache
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File

const val LOCAL_AAR_GROUPID = "__local_aars__"
const val WRAPPED_AAR_GROUPID = "__wrapped_aars__"

/** Build service used to cache maven coordinates for libraries. */
abstract class MavenCoordinatesCacheBuildService :
    BuildService<MavenCoordinatesCacheBuildService.Parameters>, AutoCloseable {

    interface Parameters: BuildServiceParameters {
        val stringCache: Property<StringCachingBuildService>
    }

    val cache =
        CreatingCache(
            CreatingCache.ValueFactory<ResolvedArtifact, MavenCoordinates> {
                it.computeMavenCoordinates(parameters.stringCache.get())
            })

    companion object {
        @JvmStatic
        @JvmOverloads
        fun getMavenCoordForLocalFile(artifactFile: File, stringCache: StringCachingService? = null): MavenCoordinatesImpl {
            return MavenCoordinatesImpl.create(
                stringCache,
                LOCAL_AAR_GROUPID, artifactFile.path,
                "unspecified"
            )
        }
    }


    fun getMavenCoordinates(resolvedArtifact: ResolvedArtifact): MavenCoordinates {
        return cache.get(resolvedArtifact)
            ?: throw RuntimeException("Failed to compute maven coordinates for $this")
    }

    override fun close() {
        cache.clear()
    }

    class RegistrationAction(
        project: Project,
        private val stringCache: Provider<StringCachingBuildService>
    ) : ServiceRegistrationAction<MavenCoordinatesCacheBuildService, Parameters>(
        project,
        MavenCoordinatesCacheBuildService::class.java
    ) {
        override fun configure(parameters: Parameters) {
            parameters.stringCache.set(stringCache)
        }
    }
}
