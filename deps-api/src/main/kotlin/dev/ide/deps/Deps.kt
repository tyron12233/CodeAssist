package dev.ide.deps

import dev.ide.model.Coordinate
import dev.ide.platform.ProgressReporter
import dev.ide.vfs.VirtualFile

/**
 * deps-api — resolve `group:name:version` coordinates into concrete jars/aars (with transitives and
 * conflict resolution). Output feeds the model's LibraryTable; the resolved classpath then flows
 * into ClasspathSnapshot and on into both the build and the language backends.
 *
 * On-device realities: read Maven .pom metadata to walk transitives; newest-wins by default; extract
 * .aar (classes.jar + res + manifest); cache under .platform/caches/resolved-deps with an LRU bound;
 * resolve offline from cache when possible.
 */
interface DependencyResolver {
    suspend fun resolve(
        coordinates: List<Coordinate>,
        repositories: List<Repository>,
        conflict: ConflictPolicy = ConflictPolicy.NEWEST,
        progress: ProgressReporter,
    ): ResolutionResult
}

data class Repository(val name: String, val url: String)

enum class ConflictPolicy { NEWEST, FAIL_ON_CONFLICT, PINNED }

data class ResolutionResult(
    val resolved: List<ResolvedArtifact>,
    val unresolved: List<Coordinate>,
    val conflicts: List<VersionConflict>,
)

data class ResolvedArtifact(
    val coordinate: Coordinate,
    val kind: ArtifactKind,
    val classesRoot: VirtualFile,            // jar, or classes.jar extracted from an aar
    val sourcesRoot: VirtualFile? = null,
    val dependsOn: List<Coordinate> = emptyList(),
)

enum class ArtifactKind { JAR, AAR }

data class VersionConflict(val coordinate: String, val requested: List<String>, val chosen: String)
